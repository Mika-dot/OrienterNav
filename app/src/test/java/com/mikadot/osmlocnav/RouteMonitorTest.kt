package com.mikadot.osmlocnav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMonitorTest {
    private val point = GeoPoint(55.751244, 37.618423)

    private fun snapshot(distance: Double, heading: Double = 90.0, accuracy: Double = 8.0, sequence: Long = 1L) = MotionSnapshot(
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
        absoluteFixSequence = sequence,
    )

    @Test
    fun `single noisy fix never reroutes`() {
        val monitor = RouteMonitor()
        assertFalse(monitor.shouldReroute(snapshot(70.0), 20_000))
        assertFalse(monitor.shouldReroute(snapshot(4.0), 21_000))
    }

    @Test
    fun `five confirmed wrong-way fixes trigger reroute`() {
        val monitor = RouteMonitor()
        assertFalse(monitor.shouldReroute(snapshot(60.0, sequence = 1), 30_000))
        assertFalse(monitor.shouldReroute(snapshot(66.0, sequence = 2), 31_000))
        assertFalse(monitor.shouldReroute(snapshot(72.0, sequence = 3), 32_000))
        assertFalse(monitor.shouldReroute(snapshot(78.0, sequence = 4), 33_000))
        assertTrue(monitor.shouldReroute(snapshot(84.0, sequence = 5), 34_000))
    }

    @Test
    fun `uncertain inertial position cannot trigger reroute`() {
        val monitor = RouteMonitor()
        repeat(5) { index ->
            assertFalse(monitor.shouldReroute(snapshot(100.0 + index, accuracy = 120.0, sequence = index.toLong()), 30_000L + index * 1_000))
        }
    }

    @Test
    fun `stopped vehicle never reroutes`() {
        val monitor = RouteMonitor()
        val stopped = snapshot(160.0).copy(speedMps = 0.0, stationary = true)
        repeat(8) { index ->
            assertFalse(monitor.shouldReroute(stopped.copy(absoluteFixSequence = index.toLong()), 30_000L + index * 1_000))
        }
    }

    @Test
    fun `ui ticks cannot count one fix five times`() {
        val monitor = RouteMonitor()
        val oneFix = snapshot(120.0, sequence = 7)
        repeat(20) { index ->
            assertFalse(monitor.shouldReroute(oneFix, 30_000L + index * 100))
        }
    }
}
