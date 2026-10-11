package com.geovisual.kenburns.core

import com.geovisual.kenburns.core.math.KenBurnsMath
import com.geovisual.kenburns.core.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class KenBurnsMathTest {
    private val sizes = listOf(4000 to 3000, 3000 to 4000, 3000 to 3000, 6000 to 1500, 1500 to 6000)

    @Test fun baseCropIsLargestInBoundsWithOutputAspect() {
        for (a in AspectRatio.values()) for ((w, h) in sizes) {
            val (bw, bh) = KenBurnsMath.baseSize(w, h, a)
            assertTrue(bw <= 1.0 + 1e-12 && bh <= 1.0 + 1e-12)
            assertEquals(a.ratio, (bw * w) / (bh * h), 1e-9)
            // largest: touches at least one edge
            assertTrue(abs(bw - 1.0) < 1e-12 || abs(bh - 1.0) < 1e-12)
        }
    }

    @Test fun baseCropKnownValues() {
        // wide 4000x3000 at 16:9: full width, height 4000/(16/9)=2250 -> 0.75
        var (bw, bh) = KenBurnsMath.baseSize(4000, 3000, AspectRatio.H16x9)
        assertEquals(1.0, bw, 1e-12); assertEquals(0.75, bh, 1e-12)
        // wide at 9:16: height-limited. width px = 3000*9/16 = 1687.5 -> 0.421875
        KenBurnsMath.baseSize(4000, 3000, AspectRatio.V9x16).let { bw = it.first; bh = it.second }
        assertEquals(0.421875, bw, 1e-12); assertEquals(1.0, bh, 1e-12)
        // tall 3000x4000 at 16:9: full width, height 3000*9/16=1687.5 -> 0.421875
        KenBurnsMath.baseSize(3000, 4000, AspectRatio.H16x9).let { bw = it.first; bh = it.second }
        assertEquals(1.0, bw, 1e-12); assertEquals(0.421875, bh, 1e-12)
        // square image at 1:1 -> everything
        KenBurnsMath.baseSize(3000, 3000, AspectRatio.S1x1).let { bw = it.first; bh = it.second }
        assertEquals(1.0, bw, 1e-12); assertEquals(1.0, bh, 1e-12)
        // square image at 9:16: width 0.5625 of image
        KenBurnsMath.baseSize(3000, 3000, AspectRatio.V9x16).let { bw = it.first; bh = it.second }
        assertEquals(0.5625, bw, 1e-12); assertEquals(1.0, bh, 1e-12)
    }

    @Test fun rectForZoomIsValidAndClamped() {
        for (a in AspectRatio.values()) for ((w, h) in sizes) for (z in listOf(1.0, 1.3, 2.0)) {
            val r = KenBurnsMath.rectForZoom(w, h, a, z, -0.5, 1.7)
            assertTrue(r.isInBounds())
            assertTrue(r.isValidFor(w, h, a))
            assertEquals(z, KenBurnsMath.zoomOf(r, w, h, a), 1e-9)
        }
    }

    @Test fun logZoomMidpointIsGeometricMean() {
        val p = photo(zS = 1.05, zE = 1.6, easing = Easing.LINEAR)
        val r = KenBurnsMath.rectAt(p, 2500.0)
        assertEquals(sqrt(p.startRect.w * p.endRect.w), r.w, 1e-9)
        assertEquals(sqrt(p.startRect.h * p.endRect.h), r.h, 1e-9)
        assertTrue(r.isValidFor(p.imageWidth, p.imageHeight, AspectRatio.H16x9))
        // smootherstep also hits 0.5 at midpoint
        val q = p.copy(easing = Easing.EASE_IN_OUT)
        assertEquals(r.w, KenBurnsMath.rectAt(q, 2500.0).w, 1e-9)
    }

    @Test fun endpointsMatchAndTimeIsClamped() {
        val p = photo()
        assertEquals(p.startRect.w, KenBurnsMath.rectAt(p, -100L).w, 1e-12)
        assertEquals(p.endRect.cx, KenBurnsMath.rectAt(p, 99999L).cx, 1e-12)
        assertEquals(p.startRect.cx, KenBurnsMath.rectAt(p, 0L).cx, 1e-12)
    }

    @Test fun clampingNeverExitsUnitSquare() {
        for (a in AspectRatio.values()) for ((w, h) in sizes) {
            for (z in listOf(1.0, 1.05, 1.6, 2.0)) for (cx in listOf(-1.0, 0.0, 0.5, 1.0, 2.0)) {
                val s = KenBurnsMath.rectForZoom(w, h, a, z, cx, cx)
                val e = KenBurnsMath.rectForZoom(w, h, a, 2.0, 1 - cx.coerceIn(0.0, 1.0), cx)
                val p = PhotoItem("x", "x", null, w, h, s, e, 5000, Easing.EASE_IN_OUT)
                for (t in 0..5000 step 125) assertTrue(KenBurnsMath.rectAt(p, t.toLong()).isInBounds(1e-12))
            }
        }
    }

    @Test fun clampToBoundsMovesCenterOnly() {
        val r = KenBurnsMath.clampToBounds(FramingRect(0.0, 1.0, 0.4, 0.2))
        assertEquals(0.2, r.cx, 1e-12); assertEquals(0.9, r.cy, 1e-12)
        assertEquals(0.4, r.w, 1e-12)
    }

    @Test fun framingRectRejectsBadSizes() {
        assertThrows(IllegalArgumentException::class.java) { FramingRect(0.5, 0.5, 0.0, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { FramingRect(0.5, Double.NaN, 0.5, 0.5) }
    }

    private fun assertThrows(c: Class<out Throwable>, f: () -> Unit) {
        try { f() } catch (t: Throwable) { assertTrue(c.isInstance(t)); return }
        fail("expected ${c.simpleName}")
    }
}
