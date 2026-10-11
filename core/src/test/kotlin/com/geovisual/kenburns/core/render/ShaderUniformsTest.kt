package com.geovisual.kenburns.core.render

import com.geovisual.kenburns.core.model.FramingRect
import com.geovisual.kenburns.core.model.TransitionType
import com.geovisual.kenburns.core.photo
import com.geovisual.kenburns.core.timeline.FrameState
import com.geovisual.kenburns.core.timeline.Timeline
import org.junit.Assert.*
import org.junit.Test

class ShaderUniformsTest {
    private val eps = 1e-6f

    /** Sample point the shader would use for output position (u, v), v = 0 at the bottom. */
    private fun sample(r: Vec4, u: Float, v: Float) = Pair(r.x + u * r.z, r.y + v * r.w)

    @Test fun currentRectMapsOutputCornersToImageWithYMirrored() {
        val rect = FramingRect(cx = 0.4, cy = 0.3, w = 0.5, h = 0.2) // left .15, top .2, bottom .4
        val uv = ShaderUniformMath.currentRectUv(rect)
        // Output top-left (0,1) must hit image (left, top); input texture has top at v = 1.
        val (s, t) = sample(uv, 0f, 1f)
        assertEquals(0.15f, s, eps); assertEquals(1f - 0.2f, t, eps)
        // Output bottom-right (1,0) must hit image (right, bottom).
        val (s2, t2) = sample(uv, 1f, 0f)
        assertEquals(0.65f, s2, eps); assertEquals(1f - 0.4f, t2, eps)
    }

    @Test fun nextRectMapsOutputCornersToImageWithBitmapOrientation() {
        val rect = FramingRect(cx = 0.4, cy = 0.3, w = 0.5, h = 0.2)
        val uv = ShaderUniformMath.nextRectUv(rect)
        val (s, t) = sample(uv, 0f, 1f) // output top-left -> image top-left, bitmap top at v = 0
        assertEquals(0.15f, s, eps); assertEquals(0.2f, t, eps)
        val (s2, t2) = sample(uv, 1f, 0f)
        assertEquals(0.65f, s2, eps); assertEquals(0.4f, t2, eps)
    }

    @Test fun noNextMeansNoBlend() {
        val r = FramingRect(0.5, 0.5, 0.5, 0.5)
        val u = ShaderUniformMath.from(FrameState(r, null, alpha = 0.7, dipBlack = 0.25))
        assertFalse(u.hasNext)
        assertEquals(0f, u.alpha, 0f)
        assertEquals(0.25f, u.dip, eps)
        assertEquals(u.currentRect, u.nextRect)
    }

    @Test fun clampsAlphaAndDip() {
        val r = FramingRect(0.5, 0.5, 0.5, 0.5)
        val u = ShaderUniformMath.from(FrameState(r, r, alpha = 1.5, dipBlack = -0.2))
        assertTrue(u.hasNext); assertEquals(1f, u.alpha, 0f); assertEquals(0f, u.dip, 0f)
    }

    @Test fun crossfadeTailFromTimeline() {
        val ps = listOf(photo("a", durationMs = 5000), photo("b", durationMs = 5000))
        val t = Timeline(ps, TransitionType.CROSSFADE, 600)
        val head = ShaderUniformMath.from(t.frameState(0, 1000.0))
        assertFalse(head.hasNext); assertEquals(0f, head.alpha, 0f)
        val mid = ShaderUniformMath.from(t.frameState(0, 4700.0))
        assertTrue(mid.hasNext); assertEquals(0.5f, mid.alpha, 1e-5f)
        val s = t.frameState(0, 4700.0)
        assertEquals(ShaderUniformMath.nextRectUv(s.rectNext!!), mid.nextRect)
        assertEquals(ShaderUniformMath.currentRectUv(s.rectCurrent), mid.currentRect)
    }

    @Test fun cutNeverBlendsAndDipFades() {
        val ps = listOf(photo("a", durationMs = 5000), photo("b", durationMs = 5000))
        val cut = Timeline(ps, TransitionType.CUT, 600)
        for (ms in listOf(0.0, 4400.0, 4999.0)) {
            val u = ShaderUniformMath.from(cut.frameState(0, ms))
            assertFalse(u.hasNext); assertEquals(0f, u.alpha, 0f); assertEquals(0f, u.dip, 0f)
        }
        val dip = Timeline(ps, TransitionType.DIP_TO_BLACK, 600)
        val tail = ShaderUniformMath.from(dip.frameState(0, 4850.0))
        assertFalse(tail.hasNext); assertEquals(0.5f, tail.dip, 1e-5f)
        val head = ShaderUniformMath.from(dip.frameState(1, 150.0))
        assertEquals(0.5f, head.dip, 1e-5f)
    }

    @Test fun nonSlideFramesHaveNoShiftAndSampleNextCropExactly() {
        val ps = listOf(photo("a", durationMs = 5000), photo("b", durationMs = 5000))
        for (type in listOf(TransitionType.CROSSFADE, TransitionType.DIP_TO_BLACK, TransitionType.CUT)) {
            val t = Timeline(ps, type, 600)
            for (ms in listOf(0.0, 2000.0, 4400.0, 4700.0, 4850.0, 4999.0)) {
                val st = t.frameState(0, ms)
                val v = ShaderUniformMath.from(st)
                assertEquals("$type $ms currentShift", 0f, v.currentShift, 0f)
                assertEquals("$type $ms nextShift", 0f, v.nextShift, 0f)
                assertEquals(1f, v.split, 0f)
                // Shader samples nextRect.xy + (x + nextShift) * zw: at x = 0 that is the crop's own corner.
                st.rectNext?.let {
                    assertEquals(ShaderUniformMath.nextRectUv(it), v.nextRect)
                    val (s0, _) = sample(v.nextRect, 0f + v.nextShift, 0f)
                    assertEquals(it.left.toFloat(), s0, eps)
                }
            }
        }
    }

    @Test fun itemClockLocalTimestamps() {
        val c = ItemClock(itemStartUs = 5_000_000)
        assertEquals(0L, c.localUs(0))
        assertEquals(ItemClock.Mode.LOCAL, c.mode)
        assertEquals(16_666L, c.localUs(16_666))
    }

    @Test fun itemClockGlobalTimestamps() {
        val c = ItemClock(itemStartUs = 5_000_000)
        assertEquals(0L, c.localUs(5_000_000))
        assertEquals(ItemClock.Mode.GLOBAL, c.mode)
        assertEquals(1_000_000L, c.localUs(6_000_000))
    }

    @Test fun itemClockFirstItemIsLocal() {
        val c = ItemClock(itemStartUs = 0)
        assertEquals(0L, c.localUs(0)); assertEquals(ItemClock.Mode.LOCAL, c.mode)
    }

    @Test fun itemClockUnknownBaseAnchorsOnFirstFrame() {
        val c = ItemClock(itemStartUs = 5_000_000)
        assertEquals(0L, c.localUs(1_000_000_000))
        assertEquals(ItemClock.Mode.ANCHORED, c.mode)
        assertEquals(500_000L, c.localUs(1_000_500_000))
    }
}
