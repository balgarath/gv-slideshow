package com.geovisual.kenburns.core

import com.geovisual.kenburns.core.imports.ImportLogic
import com.geovisual.kenburns.core.math.FaceFraming
import com.geovisual.kenburns.core.math.FramingRandomizer
import com.geovisual.kenburns.core.math.FramingStyle
import com.geovisual.kenburns.core.math.KenBurnsMath
import com.geovisual.kenburns.core.model.AspectRatio
import com.geovisual.kenburns.core.model.FaceBox
import com.geovisual.kenburns.core.model.FramingRect
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

class FaceFramingTest {
    private val sizes = listOf(2560 to 1920, 1920 to 2560, 2000 to 2000)
    private val one = listOf(FaceBox(0.45, 0.30, 0.10, 0.13))
    private val two = listOf(FaceBox(0.30, 0.35, 0.08, 0.10), FaceBox(0.55, 0.33, 0.08, 0.10))
    private val eps = 1e-9

    private fun contains(r: FramingRect, l: Double, t: Double, rr: Double, b: Double) =
        r.left <= l + eps && r.top <= t + eps && r.right >= rr - eps && r.bottom >= b - eps

    @Test fun deterministicForSeed() {
        val a = FramingRandomizer(42); val b = FramingRandomizer(42)
        repeat(30) {
            assertEquals(a.next(2560, 1920, AspectRatio.H16x9, two), b.next(2560, 1920, AspectRatio.H16x9, two))
        }
    }

    @Test fun allThreeStylesOccur() {
        val styles = (1L..90L).map { FramingRandomizer(it).next(2560, 1920, AspectRatio.V9x16, one).style }.toSet()
        assertEquals(FramingStyle.entries.toSet(), styles)
        // Roughly equal odds.
        val r = FramingRandomizer(5)
        val counts = (1..600).groupingBy { r.next(2560, 1920, AspectRatio.H16x9, two).style }.eachCount()
        for (s in FramingStyle.entries) assertTrue("$s ${counts[s]}", (counts[s] ?: 0) in 140..260)
    }

    @Test fun pushInEndsTightPullOutStartsTight() {
        val r = FramingRandomizer(3)
        var push = 0; var pull = 0
        repeat(200) {
            val f = r.next(2560, 1920, AspectRatio.H16x9, one)
            when (f.style) {
                FramingStyle.PUSH_IN -> { push++; assertTrue(f.end.w < f.start.w) }
                FramingStyle.PULL_OUT -> { pull++; assertTrue(f.start.w < f.end.w) }
                else -> {}
            }
        }
        assertTrue(push > 20 && pull > 20)
    }

    @Test fun tightRectContainsTargetAndRespectsMaxZoom() {
        for (a in AspectRatio.entries) for ((w, h) in sizes) for (faces in listOf(one, two)) {
            val plan = FaceFraming.plan(faces, w, h, a)!!
            val tight = FaceFraming.rectAround(plan.contain, plan.tightZoom, w, h, a)
            assertTrue(contains(tight, plan.contain.left, plan.contain.top, plan.contain.right, plan.contain.bottom))
            assertTrue(KenBurnsMath.zoomOf(tight, w, h, a) <= FramingRandomizer.ZOOM_MAX + 1e-9)
            // Target includes the faces with headroom above.
            val u = faces
            assertTrue(plan.contain.top < u.minOf { it.top })
            if (faces === one) assertTrue(plan.contain.left < u.minOf { it.left } && plan.contain.right > u.maxOf { it.right })
        }
    }

    @Test fun everyStyleKeepsFacesInBoundsAspectAndMotion() {
        for (a in AspectRatio.entries) for ((w, h) in sizes) for (faces in listOf(one, two)) {
            val plan = FaceFraming.plan(faces, w, h, a)!!
            val c = plan.contain
            for (seed in 1L..120L) {
                val f = FramingRandomizer(seed).next(w, h, a, faces)
                for (rect in listOf(f.start, f.end)) {
                    assertTrue(rect.isInBounds()); assertTrue(rect.isValidFor(w, h, a))
                    assertTrue("$seed ${f.style} $rect", contains(rect, c.left, c.top, c.right, c.bottom))
                    assertTrue(KenBurnsMath.zoomOf(rect, w, h, a) <= FramingRandomizer.ZOOM_MAX + 1e-9)
                }
                val dz = abs(ln(f.end.w) - ln(f.start.w))
                val pan = hypot(f.end.cx - f.start.cx, f.end.cy - f.start.cy)
                assertTrue("${f.style} dz=$dz pan=$pan", dz >= ln(1.12) - 1e-9 || pan >= 0.10 - 1e-9)
                assertTrue(f.durationMs in 4000..6000)
            }
        }
    }

    @Test fun keepInFrameRectsVaryInPosition() {
        val xs = (1L..300L).map { FramingRandomizer(it).next(2560, 1920, AspectRatio.H16x9, one) }
            .filter { it.style == FramingStyle.KEEP_IN_FRAME }.map { it.end.cx - it.start.cx }
        assertTrue(xs.any { it > 0.01 } && xs.any { it < -0.01 })
    }

    @Test fun spreadOutFacesFallBackToBiggestFace() {
        val big = FaceBox(0.05, 0.40, 0.10, 0.14)
        val small = FaceBox(0.88, 0.42, 0.06, 0.08)
        val plan = FaceFraming.plan(listOf(small, big), 2560, 1920, AspectRatio.H16x9)!!
        // Target sits around the big face only.
        assertTrue(plan.contain.right < 0.5)
        assertTrue(plan.contain.left <= big.left && plan.contain.right >= big.right)
        repeat(50) { seed ->
            val f = FramingRandomizer(seed.toLong()).next(2560, 1920, AspectRatio.H16x9, listOf(small, big))
            for (rect in listOf(f.start, f.end)) {
                assertTrue(contains(rect, plan.contain.left, plan.contain.top, plan.contain.right, plan.contain.bottom))
            }
        }
    }

    @Test fun wideGroupTriesTenPercentMarginBeforeBiggestFace() {
        // Union is 0.70 wide on a 16:9 crop of a 4:3 image (base crop width 1.0): the 50% margin
        // box would be 1.4 wide and overflows, the 10% box is 0.84 wide, so the whole group is kept.
        val a = FaceBox(0.10, 0.40, 0.10, 0.14)
        val b = FaceBox(0.70, 0.42, 0.10, 0.14)
        val plan = FaceFraming.plan(listOf(a, b), 2560, 1920, AspectRatio.H16x9)!!
        assertTrue(plan.contain.left <= a.left && plan.contain.right >= b.right)
        assertTrue(plan.contain.w < 0.9)
    }

    @Test fun trulySpreadGroupStillUsesBiggestFace() {
        val big = FaceBox(0.02, 0.40, 0.10, 0.14)
        val far = FaceBox(0.90, 0.42, 0.08, 0.10)
        val plan = FaceFraming.plan(listOf(big, far), 2560, 1920, AspectRatio.H16x9)!!
        assertTrue(plan.contain.right < 0.5)
    }

    @Test fun groupShotFramesAllFaces() {
        val plan = FaceFraming.plan(two, 2560, 1920, AspectRatio.H16x9)!!
        assertTrue(plan.contain.left <= 0.30 && plan.contain.right >= 0.63)
    }

    @Test fun noFacesMatchesPlainBehaviour() {
        for (a in AspectRatio.entries) {
            val plain = FramingRandomizer(8); val nul = FramingRandomizer(8); val empty = FramingRandomizer(8)
            repeat(25) {
                val p = plain.next(4000, 3000, a)
                assertEquals(p, nul.next(4000, 3000, a, null))
                assertEquals(p, empty.next(4000, 3000, a, emptyList()))
                assertNull(p.style)
            }
        }
    }

    @Test fun faceFillingFrameFallsBackToPlain() {
        val huge = listOf(FaceBox(0.05, 0.05, 0.9, 0.9))
        assertNull(FaceFraming.plan(huge, 2560, 1920, AspectRatio.H16x9))
        val f = FramingRandomizer(1).next(2560, 1920, AspectRatio.H16x9, huge)
        assertNull(f.style)
        assertTrue(f.start.isInBounds() && f.end.isInBounds())
    }

    @Test fun largeFaceStillMovesVisibly() {
        // Tight zoom lands near the 1.12 floor; motion must still be guaranteed.
        val faces = listOf(FaceBox(0.30, 0.15, 0.40, 0.55))
        for (a in AspectRatio.entries) for (seed in 1L..80L) {
            val f = FramingRandomizer(seed).next(2560, 1920, a, faces)
            val dz = abs(ln(f.end.w) - ln(f.start.w))
            val pan = hypot(f.end.cx - f.start.cx, f.end.cy - f.start.cy)
            assertTrue("$a ${f.style} dz=$dz pan=$pan", dz >= ln(1.12) - 1e-9 || pan >= 0.10 - 1e-9)
            assertTrue(f.start.isInBounds() && f.end.isInBounds())
        }
    }

    @Test fun reframeUsesFaces() {
        val rnd = FramingRandomizer(2)
        val p = ImportLogic.newPhotoItem("id", null, 2560, 1920, AspectRatio.H16x9, rnd, two)
        assertEquals(two, p.faces)
        val plan = FaceFraming.plan(two, 2560, 1920, AspectRatio.V9x16)!!
        repeat(20) {
            val r = ImportLogic.reframe(p, AspectRatio.V9x16, rnd)
            assertEquals(two, r.faces)
            assertTrue(contains(r.startRect, plan.contain.left, plan.contain.top, plan.contain.right, plan.contain.bottom))
            assertTrue(contains(r.endRect, plan.contain.left, plan.contain.top, plan.contain.right, plan.contain.bottom))
        }
    }

    @Test fun degenerateFaceBoxesAreIgnored() {
        assertNull(FaceFraming.plan(listOf(FaceBox(0.5, 0.5, 0.0, 0.0)), 2560, 1920, AspectRatio.H16x9))
        assertNull(FaceFraming.plan(listOf(FaceBox(Double.NaN, 0.5, 0.1, 0.1)), 2560, 1920, AspectRatio.H16x9))
        assertNull(FaceFraming.plan(emptyList(), 2560, 1920, AspectRatio.H16x9))
        assertNull(FaceFraming.plan(null, 2560, 1920, AspectRatio.H16x9))
    }
}
