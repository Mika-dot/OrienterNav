package com.mikadot.osmlocnav

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

data class MotionSnapshot(
    val position: GeoPoint,
    val progressMeters: Double,
    val speedMps: Double,
    val routeBearingDeg: Double,
    val yawDeltaDeg: Double,
    val forwardAccelMps2: Double,
    val stationary: Boolean,
    val nearestRouteIndex: Int,
)

class InertialNavigator(context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val linear = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private var projector: RouteProjector? = null
    private var progress = 0.0
    private var speed = 0.0
    private var filteredAccel = 0.0
    private var yawNow = 0.0
    private var yawBase = Double.NaN
    private var lastTickNs = 0L
    private var lastCorrectionNs = 0L
    private var lastCorrectionProgress = 0.0
    private var stillSinceNs = 0L
    @Volatile private var running = false

    @Synchronized
    fun setRoute(route: List<GeoPoint>, start: GeoPoint) {
        projector = RouteProjector(route)
        val p = projector!!.project(start)
        progress = p.progressMeters
        speed = 0.0
        yawBase = Double.NaN
        lastTickNs = SystemClock.elapsedRealtimeNanos()
        lastCorrectionNs = lastTickNs
        lastCorrectionProgress = progress
    }

    fun start() {
        if (running) return
        running = true
        rotation?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linear?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        running = false
        sensors.unregisterListener(this)
    }

    @Synchronized
    fun visualCorrection(position: GeoPoint, confidence: Double) {
        val p = projector?.project(position) ?: return
        if (p.distanceFromRouteMeters > 55.0 || confidence < 0.08) return
        val now = SystemClock.elapsedRealtimeNanos()
        val dt = (now - lastCorrectionNs) / 1e9
        if (dt in 0.3..15.0) {
            val measured = ((p.progressMeters - lastCorrectionProgress) / dt).coerceIn(0.0, 55.0)
            speed = speed * 0.35 + measured * 0.65
        }
        val blend = (0.35 + confidence * 0.65).coerceIn(0.35, 0.95)
        progress = progress * (1.0 - blend) + p.progressMeters * blend
        lastCorrectionProgress = p.progressMeters
        lastCorrectionNs = now
        lastTickNs = now
    }

    @Synchronized
    fun supplementGps(position: GeoPoint, reportedSpeed: Double?, trusted: Boolean) {
        if (!trusted) return
        val p = projector?.project(position) ?: return
        if (p.distanceFromRouteMeters < 25.0) progress = progress * 0.85 + p.progressMeters * 0.15
        reportedSpeed?.takeIf { it.isFinite() && it in 0.0..60.0 }?.let { speed = speed * 0.8 + it * 0.2 }
    }

    @Synchronized
    fun snapshot(): MotionSnapshot? {
        val prj = projector ?: return null
        val now = SystemClock.elapsedRealtimeNanos()
        if (lastTickNs == 0L) lastTickNs = now
        val dt = ((now - lastTickNs) / 1e9).coerceIn(0.0, 0.25)
        lastTickNs = now
        val accel = if (abs(filteredAccel) < 0.08) 0.0 else filteredAccel
        speed = (speed + accel * dt).coerceIn(0.0, 55.0)
        val isStill = abs(filteredAccel) < 0.12 && speed < 0.8
        if (isStill) {
            if (stillSinceNs == 0L) stillSinceNs = now
            if ((now - stillSinceNs) > 1_200_000_000L) speed *= 0.78
        } else stillSinceNs = 0L
        speed *= 0.999
        progress = (progress + speed * dt).coerceIn(0.0, prj.totalMeters)
        val routePos = prj.positionAt(progress)
        val yawDelta = if (yawBase.isNaN()) 0.0 else normalizeDeg(yawNow - yawBase)
        return MotionSnapshot(routePos.point, progress, speed, routePos.bearingDeg, yawDelta, filteredAccel, isStill, routePos.nearestIndex)
    }

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(this) {
            when (event.sensor.type) {
                Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                    val matrix = FloatArray(9)
                    val orientation = FloatArray(3)
                    SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    SensorManager.getOrientation(matrix, orientation)
                    yawNow = Math.toDegrees(orientation[0].toDouble())
                    if (yawBase.isNaN()) yawBase = yawNow
                }
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    val forward = -event.values[2].toDouble()
                    val magnitude = sqrt(event.values.fold(0.0) { acc, v -> acc + v.toDouble() * v.toDouble() })
                    val usable = if (magnitude > 8.0) 0.0 else forward.coerceIn(-5.0, 5.0)
                    filteredAccel = filteredAccel * 0.92 + usable * 0.08
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun normalizeDeg(v: Double): Double {
        var x = v % 360.0
        if (x > 180.0) x -= 360.0
        if (x < -180.0) x += 360.0
        return x
    }
}
