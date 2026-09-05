package com.mikadot.orienternav.location

import com.mikadot.orienternav.model.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight polyline map matcher for an already selected route.
 *
 * Unlike nearest-vertex snapping, this projects onto every candidate segment,
 * keeps continuous progress in metres and uses heading as an ambiguity penalty.
 * It is intentionally pure Kotlin so the navigation logic is deterministic and
 * unit-testable without Android or a routing server.
 */
class RouteMatcher(
    geometry: List<GeoPoint>,
) {
    data class Match(
        val point: GeoPoint,
        val segmentIndex: Int,
        val progressMeters: Double,
        val crossTrackMeters: Double,
        val routeHeadingDegrees: Double,
        val score: Double,
    )

    private val points = geometry.toList()
    private val cumulative = DoubleArray(points.size)

    val totalLengthMeters: Double
        get() = cumulative.lastOrNull() ?: 0.0

    init {
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + points[i - 1].distanceTo(points[i])
        }
    }

    fun match(
        point: GeoPoint,
        headingDegrees: Double? = null,
        minProgressMeters: Double? = null,
        backwardsToleranceMeters: Double = 35.0,
    ): Match? {
        if (points.size < 2) return null
        var best: Match? = null
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            val segmentLength = a.distanceTo(b)
            if (segmentLength < 0.05) continue
            val (east, north) = localOffsetMeters(a, point)
            val (segEast, segNorth) = localOffsetMeters(a, b)
            val denom = segEast * segEast + segNorth * segNorth
            if (denom < 1e-6) continue
            val t = ((east * segEast + north * segNorth) / denom).coerceIn(0.0, 1.0)
            val projected = a.offset(segEast * t, segNorth * t)
            val crossTrack = point.distanceTo(projected)
            val progress = cumulative[i] + segmentLength * t
            if (minProgressMeters != null && progress < minProgressMeters - backwardsToleranceMeters) continue

            val routeHeading = a.bearingTo(b)
            val headingPenalty =
                headingDegrees?.let {
                    val delta = angleDifference(it, routeHeading)
                    // Below 20 degrees heading does not affect the candidate.
                    // Opposite-direction parallel roads are heavily penalized.
                    max(0.0, delta - 20.0) * 0.45
                } ?: 0.0
            val candidate = Match(projected, i, progress, crossTrack, routeHeading, crossTrack + headingPenalty)
            if (best == null || candidate.score < best!!.score) best = candidate
        }
        return best
    }

    fun pointAt(progressMeters: Double): GeoPoint? {
        if (points.isEmpty()) return null
        if (points.size == 1) return points.first()
        val target = progressMeters.coerceIn(0.0, totalLengthMeters)
        var index = 0
        while (index < points.lastIndex - 1 && cumulative[index + 1] < target) index++
        val a = points[index]
        val b = points[index + 1]
        val length = max(0.001, a.distanceTo(b))
        val ratio = ((target - cumulative[index]) / length).coerceIn(0.0, 1.0)
        val (east, north) = localOffsetMeters(a, b)
        return a.offset(east * ratio, north * ratio)
    }

    fun headingAt(progressMeters: Double): Double? {
        if (points.size < 2) return null
        val target = progressMeters.coerceIn(0.0, totalLengthMeters)
        var index = 0
        while (index < points.lastIndex - 1 && cumulative[index + 1] < target) index++
        return points[index].bearingTo(points[index + 1])
    }

    private fun localOffsetMeters(
        origin: GeoPoint,
        target: GeoPoint,
    ): Pair<Double, Double> {
        val earth = 6_378_137.0
        val meanLat = Math.toRadians((origin.latitude + target.latitude) * 0.5)
        val east = Math.toRadians(target.longitude - origin.longitude) * earth * cos(meanLat)
        val north = Math.toRadians(target.latitude - origin.latitude) * earth
        return east to north
    }

    companion object {
        fun angleDifference(
            a: Double,
            b: Double,
        ): Double {
            val raw = abs((a - b) % 360.0)
            return min(raw, 360.0 - raw)
        }
    }
}
