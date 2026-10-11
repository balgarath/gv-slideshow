package com.geovisual.kenburns.core.editor

import com.geovisual.kenburns.core.math.KenBurnsMath
import com.geovisual.kenburns.core.model.AspectRatio
import com.geovisual.kenburns.core.model.FramingRect
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class FramingGestureTest {
    private val images = listOf(4000 to 3000, 3000 to 4000, 3000 to 3000, 6000 to 1500, 1500 to 6000)
    private val SLOP = 0.05

    private fun rect(g: FramingGeometry, zoom: Double, cx: Double, cy: Double) =
        KenBurnsMath.rectForZoom(g.imageW, g.imageH, g.aspect, zoom, cx, cy)

    private fun corner(r: FramingRect, p: HitPart) =
        (if (p.sx < 0) r.left else r.right) to (if (p.sy < 0) r.top else r.bottom)

    private fun assertValid(g: FramingGeometry, r: FramingRect, msg: String = "") {
        assertTrue("$msg aspect $r", r.isValidFor(g.imageW, g.imageH, g.aspect, 1e-9))
        assertTrue("$msg bounds $r", r.isInBounds(1e-12))
        val z = g.zoomOf(r)
        assertTrue("$msg zoom $z", z >= 1.0 - 1e-9 && z <= 2.0 + 1e-9)
    }

    private fun drag(
        g: FramingGeometry, a: FramingRect, part: HitPart, tx: Double, ty: Double, steps: Int = 1,
    ): FramingRect {
        val (cx, cy) = if (part == HitPart.BODY) a.cx to a.cy else corner(a, part)
        var s = FramingGesture.down(FramingGestureState(), a, a.copy(cx = 5.0), cx, cy, SLOP, SLOP) // B is far away
        assertNotNull(s.drag); assertEquals(part, s.drag!!.part)
        var out = a
        for (i in 1..steps) {
            val f = i.toDouble() / steps
            out = FramingGesture.move(s, g, cx + (tx - cx) * f, cy + (ty - cy) * f)!!.rect
        }
        s = FramingGesture.up(s)
        assertNull(s.drag)
        return out
    }

    @Test fun hitCornerBodyOutside() {
        val a = FramingRect(0.3, 0.3, 0.2, 0.2); val b = FramingRect(0.7, 0.7, 0.2, 0.2)
        var h = FramingGesture.hitTest(a, b, FramingTarget.A, 0.2 + 0.01, 0.2 - 0.01, SLOP, SLOP)
        assertEquals(FramingHit(FramingTarget.A, HitPart.TOP_LEFT), h)
        h = FramingGesture.hitTest(a, b, FramingTarget.A, 0.3, 0.3, SLOP, SLOP)
        assertEquals(FramingHit(FramingTarget.A, HitPart.BODY), h)
        h = FramingGesture.hitTest(a, b, FramingTarget.A, 0.8 + 0.02, 0.8, SLOP, SLOP)
        assertEquals(FramingHit(FramingTarget.B, HitPart.BOTTOM_RIGHT), h)
        assertNull(FramingGesture.hitTest(a, b, FramingTarget.A, 0.5, 0.05, SLOP, SLOP))
        // Just outside the slop radius.
        assertNull(FramingGesture.hitTest(a, b, FramingTarget.A, 0.2 - 0.06, 0.2, SLOP, SLOP))
    }

    @Test fun cornerBeatsBodyOfOtherBox() {
        val a = FramingRect(0.5, 0.5, 0.6, 0.6)     // big body covering B's corner
        val b = FramingRect(0.5, 0.5, 0.2, 0.2)
        val h = FramingGesture.hitTest(a, b, FramingTarget.A, 0.6, 0.6, SLOP, SLOP)
        assertEquals(FramingHit(FramingTarget.B, HitPart.BOTTOM_RIGHT), h)
    }

    @Test fun overlapPrefersSelectedAndIsDeterministic() {
        val r = FramingRect(0.5, 0.5, 0.4, 0.3)
        for (sel in FramingTarget.entries) {
            repeat(3) {
                assertEquals(FramingHit(sel, HitPart.BODY), FramingGesture.hitTest(r, r, sel, 0.5, 0.5, SLOP, SLOP))
                assertEquals(FramingHit(sel, HitPart.TOP_LEFT), FramingGesture.hitTest(r, r, sel, r.left, r.top, SLOP, SLOP))
            }
        }
        // Selection follows the last touch.
        val a = FramingRect(0.3, 0.5, 0.2, 0.2); val b = FramingRect(0.7, 0.5, 0.2, 0.2)
        val s = FramingGesture.down(FramingGestureState(FramingTarget.A), a, b, 0.7, 0.5, SLOP, SLOP)
        assertEquals(FramingTarget.B, s.selected)
        assertEquals(FramingTarget.B, FramingGesture.up(s).selected)
    }

    @Test fun missKeepsSelectionAndStartsNoDrag() {
        val a = FramingRect(0.3, 0.3, 0.2, 0.2)
        val s = FramingGesture.down(FramingGestureState(FramingTarget.B), a, a, 0.9, 0.9, SLOP, SLOP)
        assertEquals(FramingTarget.B, s.selected); assertNull(s.drag)
        assertNull(FramingGesture.move(s, FramingGeometry(100, 100, AspectRatio.S1x1), 0.5, 0.5))
    }

    @Test fun bodyDragMovesAndClamps() {
        val g = FramingGeometry(4000, 3000, AspectRatio.H16x9)
        val a = rect(g, 1.5, 0.5, 0.5)
        val moved = drag(g, a, HitPart.BODY, a.cx + 0.05, a.cy - 0.02)
        assertEquals(a.cx + 0.05, moved.cx, 1e-9); assertEquals(a.cy - 0.02, moved.cy, 1e-9)
        assertEquals(a.w, moved.w, 1e-12)
        val far = drag(g, a, HitPart.BODY, 5.0, -5.0)
        assertEquals(1.0, far.right, 1e-9); assertEquals(0.0, far.top, 1e-9)
        assertValid(g, far)
    }

    @Test fun cornerResizeKeepsOppositeCornerFixed() {
        for (aspect in AspectRatio.entries) for ((w, h) in images) {
            val g = FramingGeometry(w, h, aspect)
            for (part in HitPart.entries.filter { it.isCorner }) {
                val a = rect(g, 1.3, 0.5, 0.5)
                val (ox, oy) = corner(a, HitPart.entries.first { it.isCorner && it.sx == -part.sx && it.sy == -part.sy })
                val (px, py) = corner(a, part)
                // Shrink slightly along the diagonal: stays inside limits.
                val tx = px - part.sx * a.w * 0.1; val ty = py - part.sy * a.h * 0.1
                val r = drag(g, a, part, tx, ty, steps = 5)
                val (nx, ny) = corner(r, HitPart.entries.first { it.isCorner && it.sx == -part.sx && it.sy == -part.sy })
                assertEquals("anchor x $aspect ${w}x$h $part", ox, nx, 1e-9)
                assertEquals("anchor y $aspect ${w}x$h $part", oy, ny, 1e-9)
                assertTrue(r.w < a.w)
                assertValid(g, r, "$aspect ${w}x$h $part")
            }
        }
    }

    @Test fun cornerStaysUnderFingerOnNonSquareImage() {
        for (aspect in AspectRatio.entries) for ((w, h) in images.filter { it.first != it.second }) {
            val g = FramingGeometry(w, h, aspect)
            for (part in HitPart.entries.filter { it.isCorner }) {
                val a = rect(g, 1.4, 0.5, 0.5)
                val opp = HitPart.entries.first { it.isCorner && it.sx == -part.sx && it.sy == -part.sy }
                val (ox, oy) = corner(a, opp)
                val (px, py) = corner(a, part)
                // Finger at 85% of the on-screen diagonal from the anchor (inside limits).
                val tx = ox + (px - ox) * 0.85; val ty = oy + (py - oy) * 0.85
                val r = drag(g, a, part, tx, ty, steps = 3)
                val (nx, ny) = corner(r, part)
                assertEquals("x $aspect ${w}x$h $part", tx, nx, 1e-9)
                assertEquals("y $aspect ${w}x$h $part", ty, ny, 1e-9)
                assertValid(g, r, "$aspect ${w}x$h $part")
            }
        }
    }

    @Test fun growingStopsAtZoomOneAndShrinkingAtZoomTwo() {
        for (aspect in AspectRatio.entries) for ((w, h) in images) {
            val g = FramingGeometry(w, h, aspect)
            val a = rect(g, 1.5, 0.5, 0.5)
            val grown = drag(g, a, HitPart.BOTTOM_RIGHT, 10.0, 10.0)
            assertTrue(g.zoomOf(grown) >= 1.0 - 1e-9)
            assertValid(g, grown)
            val shrunk = drag(g, a, HitPart.TOP_LEFT, 10.0, 10.0)
            assertEquals(2.0, g.zoomOf(shrunk), 1e-9)
            assertValid(g, shrunk)
        }
    }

    @Test fun anchorNearEdgeLimitsGrowthWithoutMovingAnchor() {
        val g = FramingGeometry(4000, 3000, AspectRatio.H16x9)
        val a = rect(g, 1.6, 0.0, 0.0) // clamped to the top-left corner
        val r = drag(g, a, HitPart.BOTTOM_RIGHT, 0.9, 0.9)
        assertEquals(a.left, r.left, 1e-9); assertEquals(a.top, r.top, 1e-9)
        assertValid(g, r)
        assertTrue(r.w > a.w)
    }

    @Test fun outOfRangeStartIsPulledIntoZoomRange() {
        // A rect above zoom 2 (hand-edited file) snaps to zoom 2 on the first resize move.
        val g = FramingGeometry(4000, 3000, AspectRatio.H16x9)
        val tiny = FramingRect(0.9, 0.9, g.baseW / 3, g.baseH / 3)
        val r = drag(g, tiny, HitPart.TOP_LEFT, 0.0, 0.0)
        assertValid(g, r)
        assertTrue(g.zoomOf(r) <= 2.0 + 1e-9)
    }

    @Test fun fuzzEveryGestureKeepsInvariants() {
        val rnd = Random(42)
        for (aspect in AspectRatio.entries) for ((w, h) in images) {
            val g = FramingGeometry(w, h, aspect)
            repeat(300) {
                val a = rect(g, 1.0 + rnd.nextDouble(), rnd.nextDouble(), rnd.nextDouble())
                val b = rect(g, 1.0 + rnd.nextDouble(), rnd.nextDouble(), rnd.nextDouble())
                var s = FramingGesture.down(FramingGestureState(), a, b, rnd.nextDouble(), rnd.nextDouble(), 0.08, 0.08)
                if (s.drag == null) {
                    val tgt = if (rnd.nextBoolean()) a else b
                    val part = HitPart.entries[rnd.nextInt(HitPart.entries.size)]
                    val (cx, cy) = if (part == HitPart.BODY) tgt.cx to tgt.cy else corner(tgt, part)
                    s = FramingGesture.down(FramingGestureState(), a, b, cx, cy, 0.08, 0.08)
                }
                repeat(10) {
                    val u = FramingGesture.move(s, g, rnd.nextDouble() * 3 - 1, rnd.nextDouble() * 3 - 1) ?: return@repeat
                    assertValid(g, u.rect, "$aspect ${w}x$h ${s.drag}")
                }
            }
        }
    }

    @Test fun movesAreStatelessInThePointer() {
        val g = FramingGeometry(4000, 3000, AspectRatio.V9x16)
        val a = rect(g, 1.4, 0.5, 0.5)
        val (px, py) = corner(a, HitPart.TOP_RIGHT)
        val s = FramingGesture.down(FramingGestureState(), a, a.copy(cx = 5.0), px, py, SLOP, SLOP)
        val first = FramingGesture.move(s, g, px - 0.03, py + 0.05)!!.rect
        FramingGesture.move(s, g, 9.0, -9.0)
        assertEquals(first, FramingGesture.move(s, g, px - 0.03, py + 0.05)!!.rect)
    }

    @Test fun noJumpOnTouchDown() {
        val g = FramingGeometry(4000, 3000, AspectRatio.H16x9)
        val a = rect(g, 1.5, 0.5, 0.5)
        val x = a.right + 0.01; val y = a.bottom + 0.01      // inside slop, off the exact corner
        val s = FramingGesture.down(FramingGestureState(), a, a.copy(cx = 5.0), x, y, SLOP, SLOP)
        val r = FramingGesture.move(s, g, x, y)!!.rect
        assertEquals(a.w, r.w, 1e-9); assertEquals(a.cx, r.cx, 1e-9); assertEquals(a.cy, r.cy, 1e-9)
    }
}
