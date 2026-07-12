package com.mikadot.orienternav.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "Invalid latitude" }
        require(longitude in -180.0..180.0) { "Invalid longitude" }
    }

    fun distanceTo(other: GeoPoint): Double {
        val earth = 6_371_000.0
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(other.longitude - longitude)
        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * earth * atan2(sqrt(a), sqrt(1 - a))
    }

    fun bearingTo(other: GeoPoint): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun offset(
        eastMeters: Double,
        northMeters: Double,
    ): GeoPoint {
        val earth = 6_378_137.0
        val dLat = northMeters / earth
        val dLon = eastMeters / (earth * cos(Math.toRadians(latitude)).coerceAtLeast(0.01))
        return GeoPoint(latitude + Math.toDegrees(dLat), longitude + Math.toDegrees(dLon))
    }
}
