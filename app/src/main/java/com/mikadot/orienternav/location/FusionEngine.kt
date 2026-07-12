package com.mikadot.orienternav.location

import com.mikadot.orienternav.model.FusedPosition
import com.mikadot.orienternav.model.GeoPoint
import com.mikadot.orienternav.model.GpsSample
import com.mikadot.orienternav.model.TrustState
import com.mikadot.orienternav.model.VisualEstimate
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative GPS/vision integrity monitor. It never trusts one neural estimate:
 * spoofing is confirmed only after several spatially consistent visual fixes disagree
 * with GPS. This avoids replacing a good GNSS fix with one bad camera frame.
 */
class FusionEngine(
    private val confirmationCount: Int = 3,
    private val visualMinConfidence: Double = 0.38,
    private val consistencyRadiusMeters: Double = 28.0,
    private val staleAfterMillis: Long = 45_000,
) {
    private var gps: GpsSample? = null
    private val visuals = ArrayDeque<VisualEstimate>()
    private var confirmedSpoof = false

    fun reset() {
        gps = null
        visuals.clear()
        confirmedSpoof = false
    }

    fun addGps(sample: GpsSample): FusedPosition {
        gps = sample
        prune(sample.timestampMillis)
        return current(sample.timestampMillis)
    }

    fun addVisual(estimate: VisualEstimate): FusedPosition {
        if (estimate.confidence >= visualMinConfidence && estimate.sigmaMeters <= 45.0) {
            visuals.addLast(estimate)
        }
        prune(estimate.timestampMillis)
        updateSpoofDecision()
        return current(estimate.timestampMillis)
    }

    fun current(nowMillis: Long = System.currentTimeMillis()): FusedPosition {
        prune(nowMillis)
        val currentGps = gps?.takeIf { nowMillis - it.timestampMillis <= staleAfterMillis }
        val visual = consistentVisualCluster()

        if (currentGps == null && visual == null) {
            return FusedPosition(
                GeoPoint(0.0, 0.0),
                9_999.0,
                null,
                TrustState.WAITING,
                null,
                "Нет свежих данных GPS и камеры",
            )
        }
        if (currentGps == null && visual != null) {
            return FusedPosition(
                visual.point,
                visual.sigmaMeters,
                visual.yawDegrees,
                TrustState.VISUAL_ONLY,
                null,
                "GPS недоступен, позиция подтверждена камерой",
            )
        }
        currentGps!!
        if (visual == null) {
            val poor = currentGps.accuracyMeters > 80.0
            return FusedPosition(
                currentGps.point,
                currentGps.accuracyMeters,
                currentGps.bearingDegrees,
                if (poor) TrustState.DEGRADED else TrustState.GPS_TRUSTED,
                null,
                if (poor) "Низкая точность GPS, камера ещё не подтвердила позицию" else "GPS в норме",
            )
        }

        val delta = currentGps.point.distanceTo(visual.point)
        val threshold = max(25.0, max(currentGps.accuracyMeters * 2.5, visual.sigmaMeters * 2.5))
        if (confirmedSpoof && delta > threshold * 0.75) {
            return FusedPosition(
                visual.point,
                visual.sigmaMeters,
                visual.yawDegrees,
                TrustState.SPOOF_CONFIRMED,
                delta,
                "GPS отклонён: ${delta.toInt()} м до устойчивой визуальной позиции",
            )
        }
        if (delta > threshold) {
            return FusedPosition(
                currentGps.point,
                currentGps.accuracyMeters,
                currentGps.bearingDegrees,
                TrustState.GPS_SUSPECTED,
                delta,
                "Расхождение GPS/камеры ${delta.toInt()} м; требуется $confirmationCount подтверждения",
            )
        }

        confirmedSpoof = false
        val gpsWeight = 1.0 / max(4.0, currentGps.accuracyMeters).let { it * it }
        val visionWeight = visual.confidence / max(4.0, visual.sigmaMeters).let { it * it }
        val t = (visionWeight / (gpsWeight + visionWeight)).coerceIn(0.05, 0.65)
        val east =
            currentGps.point.distanceTo(GeoPoint(currentGps.point.latitude, visual.point.longitude)) *
                if (visual.point.longitude >= currentGps.point.longitude) 1 else -1
        val north =
            currentGps.point.distanceTo(GeoPoint(visual.point.latitude, currentGps.point.longitude)) *
                if (visual.point.latitude >= currentGps.point.latitude) 1 else -1
        val fused = currentGps.point.offset(east * t, north * t)
        return FusedPosition(
            fused,
            min(currentGps.accuracyMeters, max(visual.sigmaMeters, 4.0)),
            visual.yawDegrees,
            TrustState.GPS_TRUSTED,
            delta,
            "GPS подтверждён визуально (${delta.toInt()} м)",
        )
    }

    private fun updateSpoofDecision() {
        val currentGps = gps ?: return
        val recent = visuals.takeLast(confirmationCount)
        if (recent.size < confirmationCount) return
        val mutuallyConsistent = isMotionConsistent(recent)
        val medianDelta = recent.map { it.point.distanceTo(currentGps.point) }.sorted()[recent.size / 2]
        val threshold = max(30.0, currentGps.accuracyMeters * 2.5)
        if (mutuallyConsistent && medianDelta > threshold) confirmedSpoof = true
    }

    private fun consistentVisualCluster(): VisualEstimate? {
        val recent = visuals.takeLast(confirmationCount)
        if (recent.isEmpty()) return null
        if (!isMotionConsistent(recent)) return null
        // A car moves between frames, so averaging coordinates would pull the fix
        // backwards. Return the newest fix while smoothing only uncertainty.
        val latest = recent.last()
        return VisualEstimate(
            latest.point,
            latest.yawDegrees,
            recent.map { it.confidence }.average(),
            recent.map { it.sigmaMeters }.average(),
            latest.timestampMillis,
        )
    }

    private fun isMotionConsistent(recent: List<VisualEstimate>): Boolean {
        if (recent.size < 2) return true
        return recent.zipWithNext().all { (a, b) ->
            val seconds = ((b.timestampMillis - a.timestampMillis).coerceAtLeast(1L)) / 1000.0
            val allowedDistance = consistencyRadiusMeters + 55.0 * seconds
            val distance = a.point.distanceTo(b.point)
            val course = a.point.bearingTo(b.point)
            val yawDelta = angleDifference(course, b.yawDegrees)
            distance <= allowedDistance && (distance < 15.0 || yawDelta <= 85.0)
        }
    }

    private fun angleDifference(
        a: Double,
        b: Double,
    ): Double {
        val raw = kotlin.math.abs((a - b) % 360.0)
        return min(raw, 360.0 - raw)
    }

    private fun prune(nowMillis: Long) {
        while (visuals.isNotEmpty() && nowMillis - visuals.first().timestampMillis > staleAfterMillis) {
            visuals.removeFirst()
        }
        while (visuals.size > max(confirmationCount * 2, 6)) visuals.removeFirst()
    }

    private fun <T> ArrayDeque<T>.takeLast(count: Int): List<T> = toList().takeLast(count)
}
