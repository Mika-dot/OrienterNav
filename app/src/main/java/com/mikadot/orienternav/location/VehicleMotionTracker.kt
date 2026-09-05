package com.mikadot.orienternav.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.mikadot.orienternav.model.MotionSample
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Short-horizon vehicle dead reckoning from commodity phone sensors.
 *
 * It intentionally does not claim long-term INS accuracy. The tracker is useful
 * between visual fixes and through short GNSS outages: a trusted course/speed
 * calibrates the phone-to-vehicle heading offset, then rotation-vector heading
 * and linear acceleration propagate motion while uncertainty grows.
 */
class VehicleMotionTracker(
    context: Context,
    private val onMotion: (MotionSample) -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private var started = false
    private var sensorAzimuthDeg: Double? = null
    private var headingOffsetDeg: Double? = null
    private var pendingCourseDegrees: Double? = null
    private var speedMps = 0.0
    private var lastAccelerationNs = 0L
    private var lastEmitNs = 0L
    private var distanceSinceEmit = 0.0
    private var uncertaintyMeters = 4.0
    private var lastCalibratedMillis = 0L

    fun start() {
        if (started) return
        started = true
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        accelerationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        if (!started) return
        sensorManager.unregisterListener(this)
        started = false
        lastAccelerationNs = 0L
        lastEmitNs = 0L
        distanceSinceEmit = 0.0
    }

    fun reset() {
        speedMps = 0.0
        headingOffsetDeg = null
        pendingCourseDegrees = null
        lastAccelerationNs = 0L
        lastEmitNs = 0L
        distanceSinceEmit = 0.0
        uncertaintyMeters = 4.0
        lastCalibratedMillis = 0L
    }

    /** Calibrate against any position source currently considered trustworthy. */
    fun calibrate(
        speed: Double?,
        courseDegrees: Double?,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        speed?.takeIf { it.isFinite() }?.let {
            speedMps = it.coerceIn(0.0, 55.0)
        }
        if (courseDegrees != null && (speed ?: speedMps) > 2.0) {
            applyOrQueueCourse(courseDegrees)
        }
        uncertaintyMeters = 3.0
        lastCalibratedMillis = timestampMillis
    }

    fun correctHeading(courseDegrees: Double) {
        correctHeading(courseDegrees, System.currentTimeMillis())
    }

    /** A visual/route yaw correction is independent of GNSS and remains valid if it arrives before the first sensor event. */
    fun correctHeading(
        courseDegrees: Double,
        timestampMillis: Long,
    ) {
        applyOrQueueCourse(courseDegrees)
        uncertaintyMeters = uncertaintyMeters.coerceAtMost(5.0)
        lastCalibratedMillis = timestampMillis
    }

    private fun applyOrQueueCourse(courseDegrees: Double) {
        val normalizedCourse = normalizeDegrees(courseDegrees)
        val azimuth = sensorAzimuthDeg
        if (azimuth == null) {
            pendingCourseDegrees = normalizedCourse
        } else {
            headingOffsetDeg = normalizeDegrees(normalizedCourse - azimuth)
            pendingCourseDegrees = null
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> updateOrientation(event)
            Sensor.TYPE_LINEAR_ACCELERATION -> updateAcceleration(event)
        }
    }

    private fun updateOrientation(event: SensorEvent) {
        val rotation = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        SensorManager.getOrientation(rotation, orientation)
        val azimuth = normalizeDegrees(Math.toDegrees(orientation[0].toDouble()))
        sensorAzimuthDeg = azimuth
        pendingCourseDegrees?.let { course ->
            headingOffsetDeg = normalizeDegrees(course - azimuth)
            pendingCourseDegrees = null
        }
    }

    private fun updateAcceleration(event: SensorEvent) {
        val now = event.timestamp
        if (lastAccelerationNs == 0L) {
            lastAccelerationNs = now
            lastEmitNs = now
            return
        }
        val dt = ((now - lastAccelerationNs) / 1_000_000_000.0).coerceIn(0.001, 0.1)
        lastAccelerationNs = now

        val heading = currentHeadingDegrees() ?: return
        val azimuth = sensorAzimuthDeg ?: return
        val relativeHeading = Math.toRadians(normalizeSignedDegrees(heading - azimuth))

        // TYPE_LINEAR_ACCELERATION is in device coordinates. For a normally mounted
        // phone the horizontal forward component is predominantly +/-Y. Blend X/Y
        // by the calibrated vehicle/device yaw instead of assuming portrait/landscape.
        val ax = event.values.getOrElse(0) { 0f }.toDouble()
        val ay = event.values.getOrElse(1) { 0f }.toDouble()
        var forwardAcceleration = ax * sin(relativeHeading) + ay * cos(relativeHeading)
        if (!forwardAcceleration.isFinite()) forwardAcceleration = 0.0
        forwardAcceleration = forwardAcceleration.coerceIn(-7.0, 5.0)

        speedMps = (speedMps + forwardAcceleration * dt).coerceIn(0.0, 55.0)
        if (speedMps < 0.8 && abs(forwardAcceleration) < 0.12) speedMps *= exp(-5.0 * dt)

        distanceSinceEmit += speedMps * dt
        val ageSeconds = ((System.currentTimeMillis() - lastCalibratedMillis).coerceAtLeast(0L)) / 1000.0
        uncertaintyMeters = 3.0 + ageSeconds * 0.7 + distanceSinceEmit * 0.04

        if (now - lastEmitNs >= 250_000_000L) {
            onMotion(
                MotionSample(
                    distanceMeters = distanceSinceEmit.coerceAtMost(30.0),
                    speedMps = speedMps,
                    headingDegrees = heading,
                    sigmaMeters = uncertaintyMeters.coerceIn(3.0, 160.0),
                    timestampMillis = System.currentTimeMillis(),
                ),
            )
            distanceSinceEmit = 0.0
            lastEmitNs = now
        }
    }

    private fun currentHeadingDegrees(): Double? {
        val azimuth = sensorAzimuthDeg ?: return null
        val offset = headingOffsetDeg ?: return null
        return normalizeDegrees(azimuth + offset)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private fun normalizeDegrees(value: Double): Double {
            var result = value % 360.0
            if (result < 0.0) result += 360.0
            return result
        }

        private fun normalizeSignedDegrees(value: Double): Double {
            var result = normalizeDegrees(value)
            if (result > 180.0) result -= 360.0
            return result
        }
    }
}
