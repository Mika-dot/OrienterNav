package com.mikadot.orienternav.location

import com.mikadot.orienternav.model.GeoPoint
import com.mikadot.orienternav.model.GpsSample
import com.mikadot.orienternav.model.MotionSample
import com.mikadot.orienternav.model.TrustState
import com.mikadot.orienternav.model.VisualEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionEngineTest {
    private val base = GeoPoint(43.2389, 76.8897)

    @Test
    fun `one conflicting frame cannot replace gps`() {
        val engine = FusionEngine()
        engine.addGps(GpsSample(base, 6.0, 10.0, 90.0, 1_000))
        val result = engine.addVisual(VisualEstimate(base.offset(300.0, 0.0), 90.0, .9, 8.0, 2_000))
        assertEquals(TrustState.GPS_SUSPECTED, result.state)
        assertTrue(result.point.distanceTo(base) < 1.0)
    }

    @Test
    fun `three consistent visual frames confirm spoofing`() {
        val engine = FusionEngine()
        engine.addGps(GpsSample(base, 5.0, 8.0, 0.0, 1_000))
        val visualBase = base.offset(240.0, 20.0)
        engine.addVisual(VisualEstimate(visualBase, 10.0, .85, 8.0, 2_000))
        engine.addVisual(VisualEstimate(visualBase.offset(2.0, 1.0), 11.0, .88, 7.0, 3_000))
        val result = engine.addVisual(VisualEstimate(visualBase.offset(-1.0, 2.0), 9.0, .91, 6.0, 4_000))
        assertEquals(TrustState.SPOOF_CONFIRMED, result.state)
        assertTrue(result.point.distanceTo(visualBase) < 5.0)
    }

    @Test
    fun `inconsistent visual frames are ignored`() {
        val engine = FusionEngine()
        engine.addGps(GpsSample(base, 5.0, 8.0, 0.0, 1_000))
        engine.addVisual(VisualEstimate(base.offset(200.0, 0.0), 0.0, .9, 7.0, 2_000))
        engine.addVisual(VisualEstimate(base.offset(-200.0, 0.0), 0.0, .9, 7.0, 3_000))
        val result = engine.addVisual(VisualEstimate(base.offset(0.0, 200.0), 0.0, .9, 7.0, 4_000))
        assertEquals(TrustState.GPS_TRUSTED, result.state)
        assertTrue(result.point.distanceTo(base) < 1.0)
    }

    @Test
    fun `vision can position without gps`() {
        val engine = FusionEngine()
        val result = engine.addVisual(VisualEstimate(base, 42.0, .8, 10.0, 1_000))
        assertEquals(TrustState.VISUAL_ONLY, result.state)
        assertEquals(42.0, result.headingDegrees!!, .01)
    }

    @Test
    fun `motion propagates visual anchor with no gps`() {
        val engine = FusionEngine()
        engine.setRoute(listOf(base, base.offset(0.0, 500.0)))
        engine.addVisual(VisualEstimate(base, 0.0, .9, 5.0, 1_000))
        val result =
            engine.addMotion(
                MotionSample(
                    distanceMeters = 25.0,
                    speedMps = 10.0,
                    headingDegrees = 0.0,
                    sigmaMeters = 6.0,
                    timestampMillis = 2_000,
                ),
            )
        assertEquals(TrustState.VISUAL_ONLY, result.state)
        assertTrue(result.point.distanceTo(base) in 20.0..30.0)
        assertTrue(result.explanation.contains("IMU"))
    }

    @Test
    fun `imu disagreement marks gps suspicious but does not confirm spoof alone`() {
        val engine = FusionEngine()
        engine.addGps(GpsSample(base, 5.0, 10.0, 0.0, 1_000))
        engine.addMotion(MotionSample(20.0, 10.0, 0.0, 5.0, 2_000))
        repeat(3) { index ->
            engine.addGps(
                GpsSample(
                    base.offset(250.0 + index, 0.0),
                    5.0,
                    10.0,
                    90.0,
                    2_100L + index * 100L,
                ),
            )
        }
        val result = engine.current(2_500)
        assertEquals(TrustState.GPS_SUSPECTED, result.state)
    }
}
