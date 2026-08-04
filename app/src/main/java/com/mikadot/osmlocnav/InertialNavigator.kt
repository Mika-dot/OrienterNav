package com.mikadot.osmlocnav

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class PositionSource {
    GPS,
    VISION,
    FUSED,
    INERTIAL,
}

data class MotionSnapshot(
    val position: GeoPoint,
    val progressMeters: Double,
    val speedMps: Double,
    val headingDeg: Double,
    val routeBearingDeg: Double,
    val yawDeltaDeg: Double,
    val forwardAccelMps2: Double,
    val stationary: Boolean,
    val nearestRouteIndex: Int,
    val distanceFromRouteMeters: Double,
    val accuracyMeters: Double,
    val source: PositionSource,
    val lastAbsoluteFixAgeMillis: Long,
    /** Unconstrained estimate, used only after a confirmed off-route event. */
    val rawPosition: GeoPoint = position,
    /** True while the position shown/sent to vision is constrained to the car route. */
    val roadLocked: Boolean = false,
    val motionConfidence: Double = 0.0,
    val absoluteFixSequence: Long = 0L,
)

/**
 * Short-gap 2D dead reckoning for a road vehicle.
 *
 * The raw state remains free so a real detour can be detected, while the
 * navigation position is road-constrained until several reliable absolute
 * fixes prove that the vehicle actually left the route. This prevents phone
 * IMU bias from sending the car across pavements, yards and parallel streets.
 */
class InertialNavigator(context: Context) : SensorEventListener {
    private data class VisualCandidate(
        val point: GeoPoint,
        val headingDeg: Double,
        val confidence: Double,
        val sigmaMeters: Double,
        val timeNs: Long,
    )

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val absoluteRotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val relativeRotation = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rotation = absoluteRotation ?: relativeRotation
    private val linear = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val projectorLock = Any()
    private var projector: RouteProjector? = null
    private var nearestRouteIndex = 0

    private var position: GeoPoint? = null
    private var speed = 0.0
    private var heading = 0.0
    private var accuracy = 60.0
    private var filteredAccel = 0.0
    private var source = PositionSource.INERTIAL
    private var lastPredictNs = 0L
    private var lastAbsoluteFixNs = 0L
    private var lastGpsNs = 0L
    private var lastGpsPoint: GeoPoint? = null
    private var lastGpsSpeed: Double? = null
    private var stationaryAnchor: GeoPoint? = null
    private var stationaryEvidence = 0
    private var stationaryLatched = true
    private var movingEvidenceUntilNs = 0L
    private var lastVisualMotionNs = 0L
    private var visualMotionConfidence = 0.0
    private var accelBias = 0.0
    private var accelMotionSamples = 0
    private var absoluteFixSequence = 0L

    private val rotationMatrix = FloatArray(9)
    private var rotationReady = false
    private var sensorYaw = 0.0
    private var sensorYawAtAnchor = Double.NaN
    private var headingAtAnchor = Double.NaN
    private var previousHeading = 0.0
    private var lastHeadingAtNs = 0L
    private var yawDelta = 0.0
    private val visualCandidates = ArrayDeque<VisualCandidate>()

    @Volatile private var running = false

    @Synchronized
    fun setRoute(route: List<GeoPoint>, start: GeoPoint, preservePose: Boolean = false) {
        projector = RouteProjector(route)
        if (!preservePose || position == null) {
            position = start
            speed = 0.0
            accuracy = 45.0
            heading = if (route.size >= 2) Geo.bearing(route[0], route[1]) else heading
            stationaryAnchor = start
            stationaryLatched = true
            stationaryEvidence = 0
            anchorHeadingToSensor()
        }
        val projection = projector?.project(position ?: start)
        nearestRouteIndex = projection?.nearestIndex ?: 0
        lastPredictNs = SystemClock.elapsedRealtimeNanos()
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        rotation?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linear?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        lastPredictNs = SystemClock.elapsedRealtimeNanos()
    }

    fun stop() {
        running = false
        sensors.unregisterListener(this)
    }

    /** Applies a trusted GPS measurement. Returns false for a physically impossible jump. */
    @Synchronized
    fun supplementGps(
        measured: GeoPoint,
        reportedSpeed: Double?,
        reportedBearing: Double?,
        reportedAccuracy: Double,
        trusted: Boolean,
    ): Boolean {
        if (!trusted) return false
        val now = SystemClock.elapsedRealtimeNanos()
        predictLocked(now)
        val current = position
        val dt = if (lastGpsNs == 0L) Double.POSITIVE_INFINITY else (now - lastGpsNs) / 1e9
        if (lastGpsPoint != null && dt < 4.0) {
            val physicallyReachable = max(45.0, speed * dt + reportedAccuracy * 3.0)
            if (Geo.distance(lastGpsPoint!!, measured) > physicallyReachable) return false
        }
        val validSpeed = reportedSpeed?.takeIf { it.isFinite() && it in 0.0..65.0 }
        val gpsTravel = lastGpsPoint?.let { Geo.distance(it, measured) } ?: 0.0
        val stoppedByGps = validSpeed != null && validSpeed <= 0.8 &&
            (lastGpsPoint == null || gpsTravel <= max(5.0, reportedAccuracy * 0.75))
        val movingByGps = validSpeed != null && validSpeed >= 1.5 ||
            (dt.isFinite() && dt in 0.4..4.0 && gpsTravel > max(7.0, reportedAccuracy * 0.9))

        if (movingByGps) {
            stationaryEvidence = 0
            stationaryLatched = false
            stationaryAnchor = null
            movingEvidenceUntilNs = now + 5_000_000_000L
        } else if (stoppedByGps) {
            stationaryEvidence++
            if (stationaryEvidence >= 2) {
                stationaryLatched = true
                speed = 0.0
                filteredAccel = 0.0
                stationaryAnchor = stationaryAnchor?.let { anchor ->
                    if (Geo.distance(anchor, measured) <= max(10.0, reportedAccuracy * 1.5)) {
                        Geo.interpolate(anchor, measured, 0.12)
                    } else anchor
                } ?: measured
            }
        }

        val gain = when {
            stationaryLatched -> 0.10
            current == null -> 1.0
            reportedAccuracy <= 6.0 -> 0.62
            reportedAccuracy <= 15.0 -> 0.45
            reportedAccuracy <= 35.0 -> 0.28
            else -> 0.14
        }
        val gpsTarget = if (stationaryLatched) stationaryAnchor ?: measured else measured
        position = current?.let { Geo.interpolate(it, gpsTarget, gain) } ?: gpsTarget
        validSpeed?.let {
            speed = if (stationaryLatched) 0.0 else if (lastGpsSpeed == null) it else speed * 0.35 + it * 0.65
            lastGpsSpeed = it
        }
        reportedBearing?.takeIf { it.isFinite() && (reportedSpeed ?: speed) > 2.2 }?.let {
            heading = Geo.blendBearing(heading, it, 0.55)
            anchorHeadingToSensor()
        }
        accuracy = min(max(2.5, reportedAccuracy), max(accuracy * 0.72, reportedAccuracy))
        source = if (lastAbsoluteFixNs != 0L && now - lastAbsoluteFixNs < 4_000_000_000L) PositionSource.FUSED else PositionSource.GPS
        lastAbsoluteFixNs = now
        lastGpsNs = now
        lastGpsPoint = measured
        absoluteFixSequence++
        updateProjectionLocked()
        return true
    }

    /** Adds frame-to-frame camera motion without pretending monocular video has absolute scale. */
    @Synchronized
    fun observeVisualMotion(moving: Boolean, yawDeltaDegrees: Double, confidence: Double) {
        if (!confidence.isFinite() || confidence < 0.30) return
        val now = SystemClock.elapsedRealtimeNanos()
        lastVisualMotionNs = now
        visualMotionConfidence = confidence.coerceIn(0.0, 1.0)
        if (moving && confidence >= 0.45) {
            stationaryEvidence = 0
            stationaryLatched = false
            stationaryAnchor = null
            movingEvidenceUntilNs = now + 4_000_000_000L
            // Monocular flow has no dependable metric scale. It is used to
            // release zero-velocity hold and preserve the last known speed.
            if (speed < 1.2) speed = 2.2
            if (yawDeltaDegrees.isFinite() && abs(yawDeltaDegrees) in 1.5..22.0) {
                heading = Geo.blendBearing(heading, heading + yawDeltaDegrees, 0.18 * confidence)
                anchorHeadingToSensor()
            }
        } else if (!moving && confidence >= 0.70 && (lastGpsSpeed ?: 0.0) <= 0.8 && abs(filteredAccel) < 0.18) {
            stationaryEvidence++
            if (stationaryEvidence >= 3) {
                stationaryLatched = true
                stationaryAnchor = position
                speed = 0.0
                filteredAccel = 0.0
            }
        }
    }

    /**
     * Robust visual correction. Nearby corrections require two mutually
     * consistent frames; a large/off-road recovery requires four.
     */
    @Synchronized
    fun visualCorrection(
        measured: GeoPoint,
        measuredHeading: Double,
        confidence: Double,
        sigmaMeters: Double,
    ): Boolean {
        if (confidence < 0.30 || sigmaMeters > 80.0 || !confidence.isFinite()) return false
        val now = SystemClock.elapsedRealtimeNanos()
        predictLocked(now)
        while (visualCandidates.isNotEmpty() && now - visualCandidates.first.timeNs > 15_000_000_000L) {
            visualCandidates.removeFirst()
        }
        val routeProjection = projector?.project(measured, nearestRouteIndex, 120)
        val offRoad = routeProjection != null && routeProjection.distanceFromRouteMeters > 38.0
        if (offRoad && (stationaryLatched || confidence < 0.68 || now > movingEvidenceUntilNs)) return false
        val constrainedMeasurement = if (!offRoad && routeProjection != null) routeProjection.point else measured
        val previous = visualCandidates.lastOrNull()
        if (previous != null) {
            val seconds = max(0.1, (now - previous.timeNs) / 1e9)
            val plausibleDistance = (12.0 + speed * seconds * 1.7 + max(previous.sigmaMeters, sigmaMeters) * 0.55)
                .coerceIn(16.0, 65.0)
            val plausible = Geo.distance(previous.point, constrainedMeasurement) <= plausibleDistance
            val headingPlausible = Geo.angleDifference(previous.headingDeg, measuredHeading) <= 65.0
            if (!plausible || !headingPlausible) visualCandidates.clear()
        }
        visualCandidates.addLast(VisualCandidate(constrainedMeasurement, measuredHeading, confidence, sigmaMeters, now))
        while (visualCandidates.size > 5) visualCandidates.removeFirst()

        val predicted = position
        val correctionDistance = predicted?.let { Geo.distance(it, constrainedMeasurement) } ?: 0.0
        val required = when {
            stationaryLatched -> 3
            offRoad || correctionDistance > 60.0 -> 4
            else -> 2
        }
        if (visualCandidates.size < required) return false
        val recent = visualCandidates.toList().takeLast(required)
        val consistent = recent.zipWithNext().all { (a, b) ->
            val seconds = max(0.1, (b.timeNs - a.timeNs) / 1e9)
            Geo.distance(a.point, b.point) <= (14.0 + speed * seconds * 1.8 + max(a.sigmaMeters, b.sigmaMeters) * 0.6)
                .coerceIn(18.0, 70.0)
        }
        if (!consistent) return false

        if (!stationaryLatched && recent.size >= 2) {
            val a = recent[recent.lastIndex - 1]
            val b = recent.last()
            val seconds = max(0.25, (b.timeNs - a.timeNs) / 1e9)
            val visualSpeed = Geo.distance(a.point, b.point) / seconds
            if (visualSpeed in 1.0..40.0) speed = speed * 0.72 + visualSpeed * 0.28
        }

        val gain = (0.16 + confidence * 0.28).coerceIn(0.18, 0.42)
        position = predicted?.let {
            val desiredStep = correctionDistance * gain
            val maxStep = when {
                stationaryLatched -> 2.5
                offRoad || correctionDistance > 60.0 -> 18.0
                else -> 10.0
            }
            Geo.advance(it, min(desiredStep, maxStep), Geo.bearing(it, constrainedMeasurement))
        } ?: constrainedMeasurement
        if (!stationaryLatched && confidence >= 0.45) {
            heading = Geo.blendBearing(heading, measuredHeading, gain.coerceAtMost(0.30))
            anchorHeadingToSensor()
        }
        accuracy = min(accuracy, max(4.0, sigmaMeters))
        source = if (lastAbsoluteFixNs != 0L && now - lastAbsoluteFixNs < 4_000_000_000L) PositionSource.FUSED else PositionSource.VISION
        lastAbsoluteFixNs = now
        absoluteFixSequence++
        updateProjectionLocked()
        return true
    }

    @Synchronized
    fun snapshot(): MotionSnapshot? {
        val now = SystemClock.elapsedRealtimeNanos()
        predictLocked(now)
        val current = position ?: return null
        val routePosition = updateProjectionLocked() ?: return null
        val absoluteAge = if (lastAbsoluteFixNs == 0L) Long.MAX_VALUE else (now - lastAbsoluteFixNs) / 1_000_000L
        val lockThreshold = max(35.0, accuracy * 1.5).coerceAtMost(75.0)
        val roadLocked = stationaryLatched || absoluteAge > 4_000 ||
            source == PositionSource.INERTIAL || routePosition.distanceFromRouteMeters <= lockThreshold
        val navigationPosition = if (roadLocked) routePosition.point else current
        val visualAge = if (lastVisualMotionNs == 0L) Long.MAX_VALUE else (now - lastVisualMotionNs) / 1_000_000L
        return MotionSnapshot(
            position = navigationPosition,
            progressMeters = routePosition.progressMeters,
            speedMps = speed,
            headingDeg = heading,
            routeBearingDeg = routePosition.bearingDeg,
            yawDeltaDeg = yawDelta,
            forwardAccelMps2 = filteredAccel,
            stationary = stationaryLatched || (speed < 0.7 && abs(filteredAccel) < 0.12),
            nearestRouteIndex = routePosition.nearestIndex,
            distanceFromRouteMeters = routePosition.distanceFromRouteMeters,
            accuracyMeters = accuracy,
            source = if (absoluteAge > 4_000) PositionSource.INERTIAL else source,
            lastAbsoluteFixAgeMillis = absoluteAge,
            rawPosition = current,
            roadLocked = roadLocked,
            motionConfidence = if (visualAge <= 5_000) visualMotionConfidence else 0.0,
            absoluteFixSequence = absoluteFixSequence,
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(this) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> updateRotation(event)
                Sensor.TYPE_LINEAR_ACCELERATION -> updateAcceleration(event.values)
            }
        }
    }

    private fun updateRotation(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        rotationReady = true
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)
        sensorYaw = Geo.normalizeBearing(Math.toDegrees(orientation[0].toDouble()))
        if (sensorYawAtAnchor.isNaN() || headingAtAnchor.isNaN()) anchorHeadingToSensor()
        if (stationaryLatched) {
            yawDelta = 0.0
            anchorHeadingToSensor()
            lastHeadingAtNs = event.timestamp
            return
        }
        // The fixed phone mount can have any yaw relative to the car. GPS,
        // vision or the initial route provides the vehicle heading at the
        // anchor; the rotation sensor contributes only the subsequent turn.
        val candidate = Geo.normalizeBearing(headingAtAnchor + Geo.signedAngleDifference(sensorYaw, sensorYawAtAnchor))
        val now = event.timestamp
        val dt = if (lastHeadingAtNs == 0L) 0.0 else (now - lastHeadingAtNs) / 1e9
        previousHeading = heading
        heading = Geo.blendBearing(heading, candidate, 0.16)
        yawDelta = if (dt > 0.0) Geo.signedAngleDifference(heading, previousHeading) else 0.0
        lastHeadingAtNs = now
    }

    private fun updateAcceleration(values: FloatArray) {
        val forward = if (rotationReady && absoluteRotation != null) {
            // Rotation matrix maps device axes into the Earth frame: X east,
            // Y north. Project horizontal acceleration onto vehicle heading.
            val east = rotationMatrix[0] * values[0] + rotationMatrix[1] * values[1] + rotationMatrix[2] * values[2]
            val north = rotationMatrix[3] * values[0] + rotationMatrix[4] * values[1] + rotationMatrix[5] * values[2]
            east * sin(Math.toRadians(heading)) + north * cos(Math.toRadians(heading))
        } else {
            // Relative-vector fallback for devices without an absolute rotation
            // sensor. This matches a dashboard mount with the screen facing the driver.
            -values[2]
        }
        val magnitude = sqrt(values.fold(0.0) { acc, value -> acc + value.toDouble() * value.toDouble() })
        val raw = if (magnitude > 9.0) 0.0 else forward.toDouble().coerceIn(-6.0, 5.0)
        if (stationaryLatched) accelBias = accelBias * 0.985 + raw * 0.015
        val usable = (raw - accelBias).coerceIn(-5.0, 4.0)
        accelMotionSamples = if (abs(usable) > 0.75) (accelMotionSamples + 1).coerceAtMost(20)
            else (accelMotionSamples - 1).coerceAtLeast(0)
        if (stationaryLatched && accelMotionSamples >= 10) {
            stationaryLatched = false
            stationaryAnchor = null
            movingEvidenceUntilNs = SystemClock.elapsedRealtimeNanos() + 3_000_000_000L
            accelMotionSamples = 0
        }
        filteredAccel = if (stationaryLatched) 0.0 else filteredAccel * 0.92 + usable * 0.08
    }

    private fun predictLocked(nowNs: Long) {
        val current = position ?: return
        if (lastPredictNs == 0L) {
            lastPredictNs = nowNs
            return
        }
        var remaining = ((nowNs - lastPredictNs) / 1e9).coerceIn(0.0, 2.0)
        lastPredictNs = nowNs
        if (stationaryLatched && nowNs > movingEvidenceUntilNs) {
            speed = 0.0
            filteredAccel = 0.0
            stationaryAnchor?.let { position = Geo.interpolate(current, it, 0.04) }
            accuracy = (accuracy + 0.02).coerceAtMost(500.0)
            return
        }
        var predicted = current
        while (remaining > 1e-4) {
            val dt = min(0.20, remaining)
            val accel = if (abs(filteredAccel) < 0.18) 0.0 else filteredAccel
            val speedCeiling = max(8.0, (lastGpsSpeed ?: 12.0) + 5.0).coerceAtMost(40.0)
            speed = (speed + accel * dt).coerceIn(0.0, speedCeiling)
            val distance = max(0.0, speed * dt + 0.5 * accel * dt * dt)
            predicted = Geo.advance(predicted, distance, heading)
            accuracy = (accuracy + dt * (0.65 + speed * 0.025)).coerceAtMost(500.0)
            remaining -= dt
        }
        position = predicted
    }

    private fun updateProjectionLocked(): RoutePosition? {
        val point = position ?: return null
        val activeProjector = projector ?: return null
        val projected = synchronized(projectorLock) {
            activeProjector.project(point, nearestRouteIndex, 100)
        }
        nearestRouteIndex = projected.nearestIndex
        return projected
    }

    private fun anchorHeadingToSensor() {
        if (!rotationReady) return
        sensorYawAtAnchor = sensorYaw
        headingAtAnchor = heading
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
