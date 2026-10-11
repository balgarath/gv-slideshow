package com.geovisual.kenburns.core

import com.geovisual.kenburns.core.math.FramingRandomizer
import com.geovisual.kenburns.core.math.KenBurnsMath
import com.geovisual.kenburns.core.model.AspectRatio
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

class FramingRandomizerTest {
    private val sizes = listOf(4000 to 3000, 3000 to 4000, 3000 to 3000, 8000 to 1500, 1500 to 8000)

    @Test fun sameSeedIsDeterministic() {
        val a = FramingRandomizer(42); val b = FramingRandomizer(42)
        repeat(20) {
            assertEquals(a.next(4000, 3000, AspectRatio.H16x9), b.next(4000, 3000, AspectRatio.H16x9))
        }
        assertNotEquals(
            FramingRandomizer(1).next(4000, 3000, AspectRatio.H16x9),
            FramingRandomizer(2).next(4000, 3000, AspectRatio.H16x9),
        )
    }

    @Test fun respectsBoundsZoomRangeAspectAndDuration() {
        val r = FramingRandomizer(7)
        for (a in AspectRatio.values()) for ((w, h) in sizes) repeat(100) {
            val f = r.next(w, h, a)
            for (rect in listOf(f.start, f.end)) {
                assertTrue(rect.isInBounds())
                assertTrue(rect.isValidFor(w, h, a))
                val z = KenBurnsMath.zoomOf(rect, w, h, a)
                assertTrue("zoom $z", z >= 1.05 - 1e-9 && z <= 1.6 + 1e-9)
            }
            assertTrue(f.durationMs in 4000..6000)
        }
    }

    @Test fun alwaysVisibleMotion() {
        val r = FramingRandomizer(99)
        for (a in AspectRatio.values()) for ((w, h) in sizes) repeat(200) {
            val f = r.next(w, h, a)
            val dz = abs(ln(f.end.w) - ln(f.start.w))
            val pan = hypot(f.end.cx - f.start.cx, f.end.cy - f.start.cy)
            assertTrue("dz=$dz pan=$pan", dz >= ln(1.12) - 1e-9 || pan >= 0.10 - 1e-9)
        }
    }

    @Test fun mixesZoomInAndZoomOut() {
        val r = FramingRandomizer(5)
        var zoomIn = 0
        repeat(400) { val f = r.next(4000, 3000, AspectRatio.H16x9); if (f.end.w < f.start.w) zoomIn++ }
        assertTrue("zoomIn=$zoomIn", zoomIn in 140..260)
    }

    @Test fun durationsCoverTheRange() {
        val r = FramingRandomizer(11)
        val ds = (1..300).map { r.next(4000, 3000, AspectRatio.S1x1).durationMs }
        assertTrue(ds.min() < 4400 && ds.max() > 5600)
    }
}
