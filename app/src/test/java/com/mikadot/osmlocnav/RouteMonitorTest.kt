package com.mikadot.osmlocnav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMonitorTest {
    private val point = GeoPoint(55.751244, 37.618423)

    private fun snapshot(distance: Double, heading: Double = 90.0, accuracy: Double = 8.0) = MotionSnapshot(
        position = point,
        progressMeters = 100.0,
        speedMps = 12.0,
        headingDeg = heading,
        routeBearingDeg = 0.0,
        yawDeltaDeg = 0.0,
        forwardAccelMps2 = 0.0,
        stationary = false,
        nearestRouteIndex = 10,
        distanceFromRouteMeters = distance,
        accuracyMeters = accuracy,
        source = PositionSource.FUSED,
        lastAbsoluteFixAgeMillis = 1_000,
    )

    @Test
    fun `single noisy fix never reroutes`() {
        val monitor = RouteMonitor()
        assertFalse(monitor.shouldReroute(snapshot(70.0), 20_000))
        assertFalse(monitor.shouldReroute(snapshot(4.0), 21_000))
    }

    @Test
    fun `three confirmed wrong-way fixes trigger reroute`() {
        val monitor = RouteMonitor()
        assertFalse(monitor.shouldReroute(snapshot(45.0), 20_000))
        assertFalse(monitor.shouldReroute(snapshot(55.0), 21_000))
        assertTrue(monitor.shouldReroute(snapshot(66.0), 22_000))
    }

    @Test
    fun `uncertain inertial position cannot trigger reroute`() {
        val monitor = RouteMonitor()
        repeat(5) { index ->
            assertFalse(monitor.shouldReroute(snapshot(100.0 + index, accuracy = 120.0), 20_000L + index * 1_000))
        }
    }
}
