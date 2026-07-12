package com.mikadot.orienternav.model

data class GpsSample(
    val point: GeoPoint,
    val accuracyMeters: Double,
    val speedMps: Double?,
    val bearingDegrees: Double?,
    val timestampMillis: Long,
)

data class VisualEstimate(
    val point: GeoPoint,
    val yawDegrees: Double,
    val confidence: Double,
    val sigmaMeters: Double,
    val timestampMillis: Long,
)

enum class TrustState {
    WAITING,
    GPS_TRUSTED,
    GPS_SUSPECTED,
    SPOOF_CONFIRMED,
    VISUAL_ONLY,
    DEGRADED,
}

data class FusedPosition(
    val point: GeoPoint,
    val accuracyMeters: Double,
    val headingDegrees: Double?,
    val state: TrustState,
    val gpsVisualDeltaMeters: Double?,
    val explanation: String,
)

data class RouteStep(
    val point: GeoPoint,
    val instruction: String,
    val distanceMeters: Double,
)

data class RoutePlan(
    val geometry: List<GeoPoint>,
    val steps: List<RouteStep>,
    val distanceMeters: Double,
    val durationSeconds: Double,
)
