package com.mikadot.osmlocnav

import kotlin.math.max

/** Debounced off-route detector. A single noisy fix can never trigger rerouting. */
class RouteMonitor(
    private val confirmations: Int = 5,
    private val rerouteCooldownMillis: Long = 25_000,
) {
    private var awayCount = 0
    private var previousDistance = 0.0
    private var lastRerouteAt = -rerouteCooldownMillis
    private var lastObservedFixSequence = -1L

    fun reset(nowMillis: Long = 0L) {
        awayCount = 0
        previousDistance = 0.0
        lastObservedFixSequence = -1L
        if (nowMillis > 0L) lastRerouteAt = nowMillis
    }

    fun shouldReroute(snapshot: MotionSnapshot, nowMillis: Long): Boolean {
        if (nowMillis - lastRerouteAt < rerouteCooldownMillis) return false
        if (snapshot.absoluteFixSequence == lastObservedFixSequence) return false
        lastObservedFixSequence = snapshot.absoluteFixSequence
        // IMU prediction is never evidence that the car left a road. Only
        // recent absolute fixes observed while actually driving may reroute.
        if (snapshot.stationary || snapshot.speedMps < 2.0 || snapshot.source == PositionSource.INERTIAL ||
            snapshot.accuracyMeters > 70.0 || snapshot.lastAbsoluteFixAgeMillis > 8_000
        ) {
            awayCount = 0
            previousDistance = snapshot.distanceFromRouteMeters
            return false
        }
        val threshold = max(45.0, snapshot.accuracyMeters * 2.2).coerceAtMost(110.0)
        val far = snapshot.distanceFromRouteMeters > threshold
        val wrongHeading = snapshot.speedMps > 4.0 && Geo.angleDifference(snapshot.headingDeg, snapshot.routeBearingDeg) > 70.0
        val movingAway = snapshot.distanceFromRouteMeters > previousDistance + 3.0
        val clearlyElsewhere = snapshot.distanceFromRouteMeters > threshold * 1.45
        previousDistance = snapshot.distanceFromRouteMeters
        awayCount = if (far && (wrongHeading || movingAway || clearlyElsewhere)) awayCount + 1
            else (awayCount - 1).coerceAtLeast(0)
        if (awayCount < confirmations) return false
        awayCount = 0
        lastRerouteAt = nowMillis
        return true
    }
}
