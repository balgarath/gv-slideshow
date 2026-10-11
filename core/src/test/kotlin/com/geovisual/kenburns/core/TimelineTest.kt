package com.geovisual.kenburns.core

import com.geovisual.kenburns.core.math.KenBurnsMath
import com.geovisual.kenburns.core.model.*
import com.geovisual.kenburns.core.timeline.Timeline
import org.junit.Assert.*
import org.junit.Test

class TimelineTest {
    private val ps = listOf(
        photo("a", durationMs = 5000, easing = Easing.LINEAR),
        photo("b", durationMs = 4000, easing = Easing.LINEAR, zS = 1.2, zE = 1.3),
        photo("c", durationMs = 6000, easing = Easing.LINEAR),
    )

    @Test fun startTimesAndTotal() {
        val t = Timeline(ps, TransitionType.CROSSFADE, 600)
        assertEquals(0L, t.startMs(0)); assertEquals(5000L, t.startMs(1)); assertEquals(9000L, t.startMs(2))
        assertEquals(15000L, t.totalMs)
        assertEquals(0, t.itemAt(4999)); assertEquals(1, t.itemAt(5000)); assertEquals(2, t.itemAt(99999))
    }

    @Test fun crossfadeAlphaAtBoundaries() {
        val t = Timeline(ps, TransitionType.CROSSFADE, 600)
        // before tail: no next
        var s = t.frameState(0, 4399.0)
        assertNull(s.rectNext); assertEquals(0.0, s.alpha, 0.0)
        // tail start: alpha 0
        s = t.frameState(0, 4400.0)
        assertEquals(0.0, s.alpha, 1e-12)
        s = t.frameState(0, 4700.0)
        assertEquals(0.5, s.alpha, 1e-9); assertNotNull(s.rectNext)
        s = t.frameState(0, 4999.999)
        assertEquals(1.0, s.alpha, 1e-5)
        // item 1 starts with alpha 0 and no next in its own head
        assertNull(t.frameState(1, 0.0).rectNext)
        assertEquals(0.0, t.frameState(1, 0.0).alpha, 0.0)
        // dip never used in crossfade
        assertEquals(0.0, t.frameState(0, 4700.0).dipBlack, 0.0)
    }

    @Test fun nextPhotoMotionContinuesAcrossBoundary() {
        val t = Timeline(ps, TransitionType.CROSSFADE, 600)
        // End of tail of item 0 ~ same rect as start of item 1 (motion time c).
        val tailEnd = t.frameState(0, 4999.9999).rectNext!!
        val head = t.frameState(1, 0.0).rectCurrent
        assertEquals(head.w, tailEnd.w, 1e-6); assertEquals(head.cx, tailEnd.cx, 1e-6)
        // At the first visible instant, next photo is at its start rect.
        val first = t.frameState(0, 4400.0001).rectNext!!
        assertEquals(ps[1].startRect.w, first.w, 1e-6)
        // Item 1 motion ends at its end rect at end of the item.
        val end = t.frameState(1, 4000.0).rectCurrent
        assertEquals(ps[1].endRect.w, end.w, 1e-9)
        // Item 0 has no lead-in
        assertEquals(ps[0].startRect.w, t.frameState(0, 0.0).rectCurrent.w, 1e-12)
        assertEquals(4600.0, t.motionDurationMs(1), 0.0)
    }

    @Test fun zeroLengthCrossfadeBehavesAsCut() {
        for (t in listOf(Timeline(ps, TransitionType.CROSSFADE, 0), Timeline(ps, TransitionType.CUT, 600))) {
            for (ms in listOf(0.0, 2500.0, 4400.0, 4700.0, 4999.0)) {
                val s = t.frameState(0, ms)
                assertNull(s.rectNext); assertEquals(0.0, s.alpha, 0.0); assertEquals(0.0, s.dipBlack, 0.0)
                assertEquals(KenBurnsMath.rectAt(ps[0], ms), s.rectCurrent)
            }
            assertEquals(ps[1].startRect, t.frameState(1, 0.0).rectCurrent)
            assertEquals(15000L, t.totalMs)
        }
    }

    @Test fun overrideBeatsDefault() {
        val over = ps.toMutableList().also { it[0] = it[0].copy(transitionOverride = TransitionType.CUT) }
        val t = Timeline(over, TransitionType.CROSSFADE, 600)
        assertNull(t.frameState(0, 4700.0).rectNext)
        assertEquals(TransitionType.CUT, t.transitionAfter(0))
        assertEquals(TransitionType.CROSSFADE, t.transitionAfter(1))
        assertEquals(TransitionType.CUT, t.transitionAfter(2))
    }

    @Test fun dipToBlackRampsOutThenIn() {
        val t = Timeline(ps, TransitionType.DIP_TO_BLACK, 600)
        assertEquals(0.0, t.frameState(0, 4000.0).dipBlack, 0.0)
        assertEquals(0.0, t.frameState(0, 4700.0).dipBlack, 1e-9)
        assertEquals(0.5, t.frameState(0, 4850.0).dipBlack, 1e-9)
        assertEquals(1.0, t.frameState(0, 5000.0).dipBlack, 1e-9)
        assertEquals(1.0, t.frameState(1, 0.0).dipBlack, 1e-9)
        assertEquals(0.5, t.frameState(1, 150.0).dipBlack, 1e-9)
        assertEquals(0.0, t.frameState(1, 300.0).dipBlack, 1e-9)
        assertNull(t.frameState(0, 4900.0).rectNext)
        assertEquals(0.0, t.frameState(0, 0.0).dipBlack, 0.0) // first item has no fade-in
        assertEquals(0.0, t.frameState(2, 5999.0).dipBlack, 0.0) // last has no fade-out
    }

    @Test fun overlongTransitionIsClampedToHalfDuration() {
        val t = Timeline(ps, TransitionType.CROSSFADE, 60000)
        assertEquals(2000.0, t.crossfadeOutMs(1), 0.0) // min(4000,6000)/2
        val s = t.frameState(1, 4000.0 - 1e-6)
        assertEquals(1.0, s.alpha, 1e-6)
    }

    @Test fun singlePhotoHasNoTransitions() {
        val t = Timeline(ps.take(1), TransitionType.CROSSFADE, 600)
        assertEquals(5000L, t.totalMs)
        val s = t.frameState(0, 4900.0)
        assertNull(s.rectNext); assertEquals(0.0, s.dipBlack, 0.0)
    }

    @Test fun frameStateAtGlobalMatchesLocal() {
        val t = Timeline(ps, TransitionType.CROSSFADE, 600)
        assertEquals(t.frameState(1, 1000.0), t.frameStateAtGlobal(6000))
    }
}
