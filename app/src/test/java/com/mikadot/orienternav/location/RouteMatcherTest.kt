package com.mikadot.orienternav.location

import com.mikadot.orienternav.model.GeoPoint
import com.mikadot.orienternav.model.RoutePlan
import com.mikadot.orienternav.model.RouteStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMatcherTest {
    private val base = GeoPoint(55.751244, 37.618423)

    @Test
    fun `projects onto segment instead of nearest vertex`() {
        val route = listOf(base, base.offset(0.0, 200.0))
        val matcher = RouteMatcher(route)
        val noisy = base.offset(14.0, 93.0)
        val match = matcher.match(noisy, 0.0)
        assertNotNull(match)
        assertTrue(match!!.crossTrackMeters in 12.0..16.0)
        assertTrue(match.progressMeters in 88.0..98.0)
    }

    @Test
    fun `heading penalizes opposite parallel segment`() {
        val northA = base
        val northB = base.offset(0.0, 160.0)
        val east = northB.offset(18.0, 0.0)
        val south = east.offset(0.0, -160.0)
        val matcher = RouteMatcher(listOf(northA, northB, east, south))
        val pointBetween = base.offset(9.0, 80.0)
        val match = matcher.match(pointBetween, 0.0)
        assertNotNull(match)
        assertEquals(0, match!!.segmentIndex)
    }

    @Test
    fun `navigator measures distance along route`() {
        val p1 = base
        val p2 = p1.offset(0.0, 100.0)
        val p3 = p2.offset(100.0, 0.0)
        val plan =
            RoutePlan(
                geometry = listOf(p1, p2, p3),
                steps = listOf(
                    RouteStep(p2, "Поверните направо", 100.0),
                    RouteStep(p3, "Вы прибыли", 100.0),
                ),
                distanceMeters = 200.0,
                durationSeconds = 30.0,
            )
        val nav = RouteNavigator(plan)
        val guidance = nav.update(p1.offset(0.0, 40.0), 0.0)
        assertNotNull(guidance)
        assertTrue(guidance!!.distanceToStepMeters in 55.0..65.0)
        assertEquals("Поверните направо", guidance.instruction)
    }
}
