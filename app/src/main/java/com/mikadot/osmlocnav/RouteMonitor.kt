package com.mikadot.osmlocnav

import kotlin.math.max

/** Debounced off-route detector. A single noisy fix can never trigger rerouting. */
class RouteMonitor(
    private val confirmations: Int = 3,
    private val rerouteCooldownMillis: Long = 10_000,
) {
    private var awayCount = 0
    private var previousDistance = 0.0
    private var lastRerouteAt = -rerouteCooldownMillis

    fun reset(nowMillis: Long = 0L) {
        awayCount = 0
        previousDistance = 0.0
        if (nowMillis > 0L) lastRerouteAt = nowMillis
    }

    fun shouldReroute(snapshot: MotionSnapshot, nowMillis: Long): Boolean {
        if (nowMillis - lastRerouteAt < rerouteCooldownMillis) return false
        if (snapshot.accuracyMeters > 90.0 || snapshot.lastAbsoluteFixAgeMillis > 30_000) {
            awayCount = 0
            return false
        }
        val threshold = max(28.0, snapshot.accuracyMeters * 1.6).coerceAtMost(75.0)
        val far = snapshot.distanceFromRouteMeters > threshold
        val wrongHeading = snapshot.speedMps > 3.0 && Geo.angleDifference(snapshot.headingDeg, snapshot.routeBearingDeg) > 55.0
        val movingAway = snapshot.distanceFromRouteMeters > previousDistance + 2.0
        previousDistance = snapshot.distanceFromRouteMeters
        awayCount = if (far && (wrongHeading || movingAway)) awayCount + 1 else (awayCount - 1).coerceAtLeast(0)
        if (awayCount < confirmations) return false
        awayCount = 0
        lastRerouteAt = nowMillis
        return true
    }
}
