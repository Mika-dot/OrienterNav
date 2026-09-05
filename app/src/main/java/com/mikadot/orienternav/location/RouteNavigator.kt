package com.mikadot.orienternav.location

import com.mikadot.orienternav.model.GeoPoint
import com.mikadot.orienternav.model.RoutePlan
import kotlin.math.max

/**
 * Stateful route-progress tracker used for turn guidance and off-route detection.
 * Progress is monotonic with a small backwards tolerance to survive noisy fixes.
 */
class RouteNavigator(
    private val plan: RoutePlan,
) {
    data class Guidance(
        val stepIndex: Int,
        val instruction: String,
        val distanceToStepMeters: Double,
        val crossTrackMeters: Double,
        val progressMeters: Double,
        val matchedPoint: GeoPoint,
        val arrived: Boolean,
    )

    private val matcher = RouteMatcher(plan.geometry)
    private val stepProgress =
        plan.steps.map { step -> matcher.match(step.point)?.progressMeters ?: Double.NaN }
    private var progressMeters = 0.0
    private var initialized = false
    private var stepIndex = 0

    fun reset() {
        progressMeters = 0.0
        initialized = false
        stepIndex = 0
    }

    fun update(
        point: GeoPoint,
        headingDegrees: Double? = null,
    ): Guidance? {
        val match = matcher.match(
            point = point,
            headingDegrees = headingDegrees,
            minProgressMeters = if (initialized) progressMeters else null,
        ) ?: return null

        if (!initialized) {
            progressMeters = match.progressMeters
            initialized = true
        } else if (match.progressMeters >= progressMeters - 12.0) {
            // Never let a noisy nearest-segment decision move the car far backwards.
            progressMeters = max(progressMeters, match.progressMeters)
        }

        while (stepIndex < plan.steps.lastIndex) {
            val p = stepProgress.getOrNull(stepIndex)
            if (p == null || p.isNaN() || progressMeters <= p + 18.0) break
            stepIndex++
        }

        val step = plan.steps.getOrNull(stepIndex)
        val targetProgress = stepProgress.getOrNull(stepIndex)?.takeUnless { it.isNaN() }
        val distance = targetProgress?.let { max(0.0, it - progressMeters) }
            ?: step?.point?.distanceTo(match.point)
            ?: max(0.0, matcher.totalLengthMeters - progressMeters)
        val remaining = max(0.0, matcher.totalLengthMeters - progressMeters)
        val arrived = remaining < 18.0 || plan.geometry.lastOrNull()?.distanceTo(point)?.let { it < 22.0 } == true

        return Guidance(
            stepIndex = stepIndex,
            instruction = step?.instruction ?: if (arrived) "Вы прибыли" else "Продолжайте движение",
            distanceToStepMeters = distance,
            crossTrackMeters = match.crossTrackMeters,
            progressMeters = progressMeters,
            matchedPoint = match.point,
            arrived = arrived,
        )
    }

    fun predictedPoint(distanceAheadMeters: Double): GeoPoint? =
        matcher.pointAt(progressMeters + distanceAheadMeters)

    fun currentRouteHeading(): Double? = matcher.headingAt(progressMeters)

    fun currentProgressMeters(): Double = progressMeters
}
