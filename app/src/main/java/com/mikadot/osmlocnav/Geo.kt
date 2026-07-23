package com.mikadot.osmlocnav

import kotlin.math.*

data class GeoPoint(val lat: Double, val lon: Double)

data class RoutePosition(
    val point: GeoPoint,
    val progressMeters: Double,
    val bearingDeg: Double,
    val distanceFromRouteMeters: Double,
    val nearestIndex: Int,
)

object Geo {
    private const val R = 6_371_000.0

    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun interpolate(a: GeoPoint, b: GeoPoint, t: Double): GeoPoint {
        val k = t.coerceIn(0.0, 1.0)
        return GeoPoint(a.lat + (b.lat - a.lat) * k, a.lon + (b.lon - a.lon) * k)
    }

    fun localXY(origin: GeoPoint, p: GeoPoint): Pair<Double, Double> {
        val y = Math.toRadians(p.lat - origin.lat) * R
        val x = Math.toRadians(p.lon - origin.lon) * R * cos(Math.toRadians(origin.lat))
        return x to y
    }
}

class RouteProjector(private val points: List<GeoPoint>) {
    private val cumulative = DoubleArray(points.size)
    val totalMeters: Double

    init {
        for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + Geo.distance(points[i - 1], points[i])
        totalMeters = cumulative.lastOrNull() ?: 0.0
    }

    fun project(p: GeoPoint): RoutePosition {
        require(points.isNotEmpty())
        if (points.size == 1) return RoutePosition(points[0], 0.0, 0.0, Geo.distance(p, points[0]), 0)
        var bestD2 = Double.POSITIVE_INFINITY
        var bestPoint = points[0]
        var bestProgress = 0.0
        var bestBearing = Geo.bearing(points[0], points[1])
        var bestIndex = 0
        for (i in 0 until points.lastIndex) {
            val origin = points[i]
            val (bx, by) = Geo.localXY(origin, points[i + 1])
            val (px, py) = Geo.localXY(origin, p)
            val len2 = bx * bx + by * by
            val t = if (len2 < 1e-6) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
            val qx = bx * t
            val qy = by * t
            val d2 = (px - qx).pow(2) + (py - qy).pow(2)
            if (d2 < bestD2) {
                bestD2 = d2
                bestPoint = Geo.interpolate(points[i], points[i + 1], t)
                bestProgress = cumulative[i] + sqrt(len2) * t
                bestBearing = Geo.bearing(points[i], points[i + 1])
                bestIndex = i
            }
        }
        return RoutePosition(bestPoint, bestProgress, bestBearing, sqrt(bestD2), bestIndex)
    }

    fun positionAt(progressMeters: Double): RoutePosition {
        require(points.isNotEmpty())
        val d = progressMeters.coerceIn(0.0, totalMeters)
        if (points.size == 1) return RoutePosition(points[0], d, 0.0, 0.0, 0)
        var i = cumulative.binarySearch(d)
        if (i < 0) i = (-i - 2).coerceIn(0, points.lastIndex - 1)
        if (i >= points.lastIndex) i = points.lastIndex - 1
        val seg = (cumulative[i + 1] - cumulative[i]).coerceAtLeast(1e-6)
        val t = (d - cumulative[i]) / seg
        return RoutePosition(Geo.interpolate(points[i], points[i + 1], t), d, Geo.bearing(points[i], points[i + 1]), 0.0, i)
    }

    fun corridor(centerIndex: Int, radius: Int = 20): List<GeoPoint> {
        val from = (centerIndex - radius).coerceAtLeast(0)
        val to = (centerIndex + radius + 1).coerceAtMost(points.size)
        return points.subList(from, to)
    }
}
