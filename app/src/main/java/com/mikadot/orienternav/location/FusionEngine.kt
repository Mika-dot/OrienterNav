package com.mikadot.orienternav.location

import com.mikadot.orienternav.model.FusedPosition
import com.mikadot.orienternav.model.GeoPoint
import com.mikadot.orienternav.model.GpsSample
import com.mikadot.orienternav.model.MotionSample
import com.mikadot.orienternav.model.TrustState
import com.mikadot.orienternav.model.VisualEstimate
import java.util.ArrayDeque
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Multi-source integrity fusion for car navigation.
 *
 * GNSS is an optional observation, not the state itself. Vision can establish an
 * independent absolute anchor; the phone IMU propagates that anchor between fixes;
 * and an active route can constrain short-term drift to a plausible road segment.
 * GNSS disagreement with IMU is enough to mark it suspicious, but only a stable
 * visual cluster is allowed to confirm spoofing and fully reject GNSS.
 */
class FusionEngine(
    private val confirmationCount: Int = 3,
    private val visualMinConfidence: Double = 0.38,
    private val consistencyRadiusMeters: Double = 28.0,
    private val gpsStaleAfterMillis: Long = 8_000,
    private val visualStaleAfterMillis: Long = 25_000,
    private val motionStaleAfterMillis: Long = 3_000,
) {
    private var gps: GpsSample? = null
    private val visuals = ArrayDeque<VisualEstimate>()
    private var confirmedSpoof = false
    private var gpsMotionDisagreementCount = 0

    private var routeMatcher: RouteMatcher? = null
    private var motionPoint: GeoPoint? = null
    private var motionAccuracyMeters = 9_999.0
    private var motionHeadingDegrees: Double? = null
    private var motionTimestampMillis = 0L
    private var motionRouteProgressMeters: Double? = null

    fun reset() {
        gps = null
        visuals.clear()
        confirmedSpoof = false
        gpsMotionDisagreementCount = 0
        motionPoint = null
        motionAccuracyMeters = 9_999.0
        motionHeadingDegrees = null
        motionTimestampMillis = 0L
        motionRouteProgressMeters = null
    }

    fun setRoute(geometry: List<GeoPoint>) {
        routeMatcher = geometry.takeIf { it.size >= 2 }?.let(::RouteMatcher)
        motionRouteProgressMeters = motionPoint?.let { point ->
            routeMatcher?.match(point, motionHeadingDegrees)?.progressMeters
        }
        // A route created with no GNSS/visual state necessarily came from an explicit
        // user-supplied start. Treat that route origin as a coarse manual anchor so
        // the navigator can start in a completely GNSS-free environment.
        if (gps == null && motionPoint == null && geometry.size >= 2) {
            anchorMotion(
                point = geometry.first(),
                accuracyMeters = 18.0,
                headingDegrees = geometry[0].bearingTo(geometry[1]),
                timestampMillis = System.currentTimeMillis(),
            )
        }
    }

    fun clearRoute() {
        routeMatcher = null
        motionRouteProgressMeters = null
    }

    fun addGps(sample: GpsSample): FusedPosition {
        prune(sample.timestampMillis)
        val motionBeforeGps = freshMotion(sample.timestampMillis)
        val disagreesWithMotion =
            motionBeforeGps?.let { motion ->
                val delta = sample.point.distanceTo(motion.point)
                val threshold = max(35.0, sample.accuracyMeters * 2.5 + motion.accuracyMeters * 1.5)
                delta > threshold
            } ?: false

        if (disagreesWithMotion) {
            gpsMotionDisagreementCount = min(6, gpsMotionDisagreementCount + 1)
        } else {
            gpsMotionDisagreementCount = max(0, gpsMotionDisagreementCount - 1)
        }

        gps = sample
        val visual = consistentVisualCluster()
        val visualDelta = visual?.point?.distanceTo(sample.point)
        val visualThreshold = visual?.let { dynamicThreshold(sample, it) }
        val visualDisagrees = visualDelta != null && visualThreshold != null && visualDelta > visualThreshold

        // Do not let a suspicious GNSS sample overwrite the independent inertial state.
        if (!confirmedSpoof && !disagreesWithMotion && !visualDisagrees && sample.accuracyMeters <= 60.0) {
            anchorMotion(
                point = sample.point,
                accuracyMeters = sample.accuracyMeters,
                headingDegrees = sample.bearingDegrees ?: motionHeadingDegrees,
                timestampMillis = sample.timestampMillis,
            )
        }
        updateSpoofDecision()
        return current(sample.timestampMillis)
    }

    fun addVisual(estimate: VisualEstimate): FusedPosition {
        if (estimate.confidence >= visualMinConfidence && estimate.sigmaMeters <= 60.0) {
            visuals.addLast(estimate)
        }
        prune(estimate.timestampMillis)
        updateSpoofDecision()
        val visual = consistentVisualCluster()
        val currentGps = freshGps(estimate.timestampMillis)
        if (visual != null) {
            val gpsAgrees = currentGps?.let { it.point.distanceTo(visual.point) <= dynamicThreshold(it, visual) } ?: false
            val enoughIndependentVision = visuals.size >= confirmationCount || currentGps == null
            if (gpsAgrees || enoughIndependentVision || confirmedSpoof) {
                anchorMotion(
                    point = visual.point,
                    accuracyMeters = max(3.0, visual.sigmaMeters),
                    headingDegrees = visual.yawDegrees,
                    timestampMillis = visual.timestampMillis,
                )
            }
        }
        return current(estimate.timestampMillis)
    }

    fun addMotion(sample: MotionSample): FusedPosition {
        prune(sample.timestampMillis)
        if (motionPoint == null) {
            val visual = consistentVisualCluster()
            val currentGps = freshGps(sample.timestampMillis)
            when {
                visual != null -> anchorMotion(visual.point, visual.sigmaMeters, visual.yawDegrees, visual.timestampMillis)
                currentGps != null && !confirmedSpoof ->
                    anchorMotion(
                        currentGps.point,
                        currentGps.accuracyMeters,
                        currentGps.bearingDegrees ?: sample.headingDegrees,
                        currentGps.timestampMillis,
                    )
                else -> return current(sample.timestampMillis)
            }
        }

        val origin = motionPoint ?: return current(sample.timestampMillis)
        val distance = sample.distanceMeters.coerceIn(0.0, 35.0)
        val radians = Math.toRadians(sample.headingDegrees)
        val raw = origin.offset(
            eastMeters = sin(radians) * distance,
            northMeters = cos(radians) * distance,
        )

        val route = routeMatcher
        val match = route?.match(
            point = raw,
            headingDegrees = sample.headingDegrees,
            minProgressMeters = motionRouteProgressMeters,
            backwardsToleranceMeters = 20.0,
        )
        val headingCompatible =
            match?.let { RouteMatcher.angleDifference(it.routeHeadingDegrees, sample.headingDegrees) <= 70.0 } ?: false
        val snapLimit = max(18.0, min(65.0, motionAccuracyMeters * 1.8 + distance * 0.5))
        if (match != null && headingCompatible && match.crossTrackMeters <= snapLimit) {
            motionPoint = match.point
            motionRouteProgressMeters = max(motionRouteProgressMeters ?: 0.0, match.progressMeters)
            motionHeadingDegrees = blendAngle(sample.headingDegrees, match.routeHeadingDegrees, 0.28)
        } else {
            motionPoint = raw
            motionHeadingDegrees = sample.headingDegrees
        }
        motionAccuracyMeters =
            max(sample.sigmaMeters, motionAccuracyMeters + 0.35 + distance * 0.035)
                .coerceIn(3.0, 250.0)
        motionTimestampMillis = sample.timestampMillis
        return current(sample.timestampMillis)
    }

    fun current(nowMillis: Long = System.currentTimeMillis()): FusedPosition {
        prune(nowMillis)
        val currentGps = freshGps(nowMillis)
        val visual = consistentVisualCluster()
        val motion = freshMotion(nowMillis)

        if (currentGps == null) {
            if (motion != null) {
                val visualAge = visual?.let { nowMillis - it.timestampMillis } ?: Long.MAX_VALUE
                val motionIsUseful = visual == null || motion.timestampMillis >= visual.timestampMillis || visualAge > 1_000
                if (motionIsUseful) {
                    return FusedPosition(
                        motion.point,
                        motion.accuracyMeters,
                        motion.headingDegrees,
                        if (visual != null) TrustState.VISUAL_ONLY else TrustState.DEGRADED,
                        null,
                        if (visual != null) {
                            "GPS нет: камера + IMU${if (routeMatcher != null) " + привязка к маршруту" else ""}"
                        } else {
                            "GPS нет: кратковременный IMU dead-reckoning${if (routeMatcher != null) " по маршруту" else ""}"
                        },
                    )
                }
            }
            if (visual != null) {
                return FusedPosition(
                    visual.point,
                    visual.sigmaMeters,
                    visual.yawDegrees,
                    TrustState.VISUAL_ONLY,
                    null,
                    "GPS недоступен, позиция подтверждена камерой",
                )
            }
            return FusedPosition(
                GeoPoint(0.0, 0.0),
                9_999.0,
                null,
                TrustState.WAITING,
                null,
                "Нет свежего абсолютного якоря; IMU не выдаётся как абсолютная координата",
            )
        }

        if (visual == null) {
            if (gpsMotionDisagreementCount > 0 && motion != null) {
                val delta = currentGps.point.distanceTo(motion.point)
                return FusedPosition(
                    motion.point,
                    motion.accuracyMeters,
                    motion.headingDegrees ?: currentGps.bearingDegrees,
                    TrustState.GPS_SUSPECTED,
                    delta,
                    "GPS расходится с независимым IMU прогнозом на ${delta.toInt()} м; GNSS временно не управляет позицией",
                )
            }
            val poor = currentGps.accuracyMeters > 80.0
            return FusedPosition(
                currentGps.point,
                currentGps.accuracyMeters,
                currentGps.bearingDegrees ?: motion?.headingDegrees,
                if (poor) TrustState.DEGRADED else TrustState.GPS_TRUSTED,
                null,
                if (poor) "Низкая точность GPS, камера ещё не подтвердила позицию" else "GPS в норме",
            )
        }

        val delta = currentGps.point.distanceTo(visual.point)
        val threshold = dynamicThreshold(currentGps, visual)
        if (confirmedSpoof && delta > threshold * 0.75) {
            val independent = motion?.takeIf { it.accuracyMeters <= max(visual.sigmaMeters * 3.0, 90.0) }
            return FusedPosition(
                independent?.point ?: visual.point,
                independent?.accuracyMeters ?: visual.sigmaMeters,
                independent?.headingDegrees ?: visual.yawDegrees,
                TrustState.SPOOF_CONFIRMED,
                delta,
                "GPS отклонён: ${delta.toInt()} м до устойчивой визуально-инерциальной позиции",
            )
        }
        if (delta > threshold) {
            val independent = motion?.takeIf { it.accuracyMeters <= max(visual.sigmaMeters * 3.0, 90.0) }
            return FusedPosition(
                independent?.point ?: currentGps.point,
                independent?.accuracyMeters ?: currentGps.accuracyMeters,
                independent?.headingDegrees ?: currentGps.bearingDegrees,
                TrustState.GPS_SUSPECTED,
                delta,
                "Расхождение GPS/камеры ${delta.toInt()} м; требуется $confirmationCount визуальных подтверждения",
            )
        }

        confirmedSpoof = false
        val gpsWeight = 1.0 / max(4.0, currentGps.accuracyMeters).let { it * it }
        val visionWeight = visual.confidence / max(4.0, visual.sigmaMeters).let { it * it }
        val t = (visionWeight / (gpsWeight + visionWeight)).coerceIn(0.08, 0.72)
        val east =
            currentGps.point.distanceTo(GeoPoint(currentGps.point.latitude, visual.point.longitude)) *
                if (visual.point.longitude >= currentGps.point.longitude) 1 else -1
        val north =
            currentGps.point.distanceTo(GeoPoint(visual.point.latitude, currentGps.point.longitude)) *
                if (visual.point.latitude >= currentGps.point.latitude) 1 else -1
        var fusedPoint = currentGps.point.offset(east * t, north * t)
        routeMatcher?.match(fusedPoint, visual.yawDegrees)?.let { routeMatch ->
            if (routeMatch.crossTrackMeters <= 24.0) fusedPoint = routeMatch.point
        }
        val fusedAccuracy = min(currentGps.accuracyMeters, max(visual.sigmaMeters, 4.0))
        anchorMotion(fusedPoint, fusedAccuracy, visual.yawDegrees, nowMillis)
        return FusedPosition(
            fusedPoint,
            fusedAccuracy,
            visual.yawDegrees,
            TrustState.GPS_TRUSTED,
            delta,
            "GPS подтверждён камерой (${delta.toInt()} м), позиция согласована с маршрутом",
        )
    }

    private fun updateSpoofDecision() {
        val currentGps = gps ?: return
        val recent = visuals.takeLast(confirmationCount)
        if (recent.size < confirmationCount) return
        val mutuallyConsistent = isMotionConsistent(recent)
        val medianDelta = recent.map { it.point.distanceTo(currentGps.point) }.sorted()[recent.size / 2]
        val medianSigma = recent.map { it.sigmaMeters }.sorted()[recent.size / 2]
        val threshold = max(30.0, max(currentGps.accuracyMeters * 2.5, medianSigma * 2.5))
        if (mutuallyConsistent && medianDelta > threshold) confirmedSpoof = true
    }

    private fun consistentVisualCluster(): VisualEstimate? {
        val recent = visuals.takeLast(confirmationCount)
        if (recent.isEmpty()) return null
        if (!isMotionConsistent(recent)) return null
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
            val yawDelta = RouteMatcher.angleDifference(course, b.yawDegrees)
            distance <= allowedDistance && (distance < 15.0 || yawDelta <= 85.0)
        }
    }

    private fun anchorMotion(
        point: GeoPoint,
        accuracyMeters: Double,
        headingDegrees: Double?,
        timestampMillis: Long,
    ) {
        var anchor = point
        routeMatcher?.match(point, headingDegrees)?.let { match ->
            if (match.crossTrackMeters <= max(18.0, accuracyMeters * 1.8)) {
                anchor = match.point
                motionRouteProgressMeters = match.progressMeters
            }
        }
        motionPoint = anchor
        motionAccuracyMeters = accuracyMeters.coerceIn(3.0, 250.0)
        motionHeadingDegrees = headingDegrees
        motionTimestampMillis = timestampMillis
    }

    private fun dynamicThreshold(
        gps: GpsSample,
        visual: VisualEstimate,
    ): Double = max(25.0, max(gps.accuracyMeters * 2.5, visual.sigmaMeters * 2.5))

    private data class MotionFix(
        val point: GeoPoint,
        val accuracyMeters: Double,
        val headingDegrees: Double?,
        val timestampMillis: Long,
    )

    private fun freshGps(nowMillis: Long): GpsSample? =
        gps?.takeIf { nowMillis - it.timestampMillis <= gpsStaleAfterMillis }

    private fun freshMotion(nowMillis: Long): MotionFix? =
        motionPoint?.takeIf { nowMillis - motionTimestampMillis <= motionStaleAfterMillis }?.let {
            MotionFix(it, motionAccuracyMeters, motionHeadingDegrees, motionTimestampMillis)
        }

    private fun blendAngle(
        primaryDegrees: Double,
        secondaryDegrees: Double,
        secondaryWeight: Double,
    ): Double {
        val a = Math.toRadians(primaryDegrees)
        val b = Math.toRadians(secondaryDegrees)
        val x = cos(a) * (1.0 - secondaryWeight) + cos(b) * secondaryWeight
        val y = sin(a) * (1.0 - secondaryWeight) + sin(b) * secondaryWeight
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
    }

    private fun prune(nowMillis: Long) {
        while (visuals.isNotEmpty() && nowMillis - visuals.first().timestampMillis > visualStaleAfterMillis) {
            visuals.removeFirst()
        }
        while (visuals.size > max(confirmationCount * 3, 9)) visuals.removeFirst()
    }

    private fun <T> ArrayDeque<T>.takeLast(count: Int): List<T> = toList().takeLast(count)
}
