package com.mikadot.osmlocnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    private val base = GeoPoint(55.751244, 37.618423)

    @Test
    fun `advance preserves requested short distance`() {
        val moved = Geo.advance(base, 125.0, 73.0)
        assertEquals(125.0, Geo.distance(base, moved), 0.5)
        assertEquals(73.0, Geo.bearing(base, moved), 0.5)
    }

    @Test
    fun `bearing blend takes shortest path through north`() {
        assertEquals(0.0, Geo.blendBearing(350.0, 10.0, 0.5), 0.001)
        assertEquals(20.0, Geo.angleDifference(350.0, 10.0), 0.001)
    }

    @Test
    fun `local projection does not jump to distant route section`() {
        val route = buildList {
            repeat(260) { index -> add(Geo.advance(base, index * 5.0, 90.0)) }
            repeat(260) { index -> add(Geo.advance(base, 1_300.0 - index * 5.0, 90.0)) }
        }
        val projector = RouteProjector(route)
        val point = Geo.advance(base, 200.0, 90.0)
        val nearStart = projector.project(point, aroundIndex = 40, windowSegments = 20)
        assertTrue(nearStart.nearestIndex < 80)
    }
}
