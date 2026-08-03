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
)

/**
 * Short-gap 2D dead reckoning for a road vehicle.
 *
 * Unlike the v0.4 implementation, position is not forced to the original
 * route. The route is used only to report progress and off-route distance.
 * Absolute GPS/visual measurements softly correct this continuously predicted
 * state. Phone IMU cannot replace an absolute fix indefinitely, therefore the
 * uncertainty grows while the application is running inertially.
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
        if (current != null && dt < 4.0) {
            val physicallyReachable = max(80.0, speed * dt + reportedAccuracy * 4.0)
            if (Geo.distance(current, measured) > physicallyReachable) return false
        }
        val gain = when {
            current == null -> 1.0
            reportedAccuracy <= 6.0 -> 0.62
            reportedAccuracy <= 15.0 -> 0.45
            reportedAccuracy <= 35.0 -> 0.28
            else -> 0.14
        }
        position = current?.let { Geo.interpolate(it, measured, gain) } ?: measured
        reportedSpeed?.takeIf { it.isFinite() && it in 0.0..65.0 }?.let {
            speed = if (lastGpsSpeed == null) it else speed * 0.35 + it * 0.65
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
        updateProjectionLocked()
        return true
    }

    /**
     * Robust visual correction. Nearby corrections require two mutually
     * consistent frames; a large recovery jump requires three.
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
        val previous = visualCandidates.lastOrNull()
        if (previous != null) {
            val seconds = max(0.1, (now - previous.timeNs) / 1e9)
            val plausible = Geo.distance(previous.point, measured) <= 35.0 + 60.0 * seconds
            val headingPlausible = Geo.angleDifference(previous.headingDeg, measuredHeading) <= 100.0
            if (!plausible || !headingPlausible) visualCandidates.clear()
        }
        visualCandidates.addLast(VisualCandidate(measured, measuredHeading, confidence, sigmaMeters, now))
        while (visualCandidates.size > 4) visualCandidates.removeFirst()

        val predicted = position
        val correctionDistance = predicted?.let { Geo.distance(it, measured) } ?: 0.0
        val required = if (correctionDistance > 90.0) 3 else 2
        if (visualCandidates.size < required) return false
        val recent = visualCandidates.toList().takeLast(required)
        val consistent = recent.zipWithNext().all { (a, b) ->
            Geo.distance(a.point, b.point) <= 50.0 + 60.0 * max(0.1, (b.timeNs - a.timeNs) / 1e9)
        }
        if (!consistent) return false

        val gain = (0.30 + confidence * 0.55).coerceIn(0.35, 0.82)
        position = predicted?.let { Geo.interpolate(it, measured, gain) } ?: measured
        heading = Geo.blendBearing(heading, measuredHeading, gain.coerceAtMost(0.65))
        anchorHeadingToSensor()
        accuracy = min(accuracy, max(4.0, sigmaMeters))
        source = if (lastAbsoluteFixNs != 0L && now - lastAbsoluteFixNs < 4_000_000_000L) PositionSource.FUSED else PositionSource.VISION
        lastAbsoluteFixNs = now
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
        return MotionSnapshot(
            position = current,
            progressMeters = routePosition.progressMeters,
            speedMps = speed,
            headingDeg = heading,
            routeBearingDeg = routePosition.bearingDeg,
            yawDeltaDeg = yawDelta,
            forwardAccelMps2 = filteredAccel,
            stationary = speed < 0.7 && abs(filteredAccel) < 0.12,
            nearestRouteIndex = routePosition.nearestIndex,
            distanceFromRouteMeters = routePosition.distanceFromRouteMeters,
            accuracyMeters = accuracy,
            source = if (absoluteAge > 4_000) PositionSource.INERTIAL else source,
            lastAbsoluteFixAgeMillis = absoluteAge,
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
        // The fixed phone mount can have any yaw relative to the car. GPS,
        // vision or the initial route provides the vehicle heading at the
        // anchor; the rotation sensor contributes only the subsequent turn.
        val candidate = Geo.normalizeBearing(headingAtAnchor + Geo.signedAngleDifference(sensorYaw, sensorYawAtAnchor))
        val now = event.timestamp
        val dt = if (lastHeadingAtNs == 0L) 0.0 else (now - lastHeadingAtNs) / 1e9
        previousHeading = heading
        heading = Geo.blendBearing(heading, candidate, 0.35)
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
        val usable = if (magnitude > 9.0) 0.0 else forward.coerceIn(-6.0, 5.0)
        filteredAccel = filteredAccel * 0.90 + usable * 0.10
    }

    private fun predictLocked(nowNs: Long) {
        val current = position ?: return
        if (lastPredictNs == 0L) {
            lastPredictNs = nowNs
            return
        }
        var remaining = ((nowNs - lastPredictNs) / 1e9).coerceIn(0.0, 2.0)
        lastPredictNs = nowNs
        var predicted = current
        while (remaining > 1e-4) {
            val dt = min(0.20, remaining)
            val accel = if (abs(filteredAccel) < 0.06) 0.0 else filteredAccel
            speed = (speed + accel * dt).coerceIn(0.0, 65.0)
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
