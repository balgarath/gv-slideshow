package com.geovisual.kenburns.core

import com.geovisual.kenburns.core.model.*
import com.geovisual.kenburns.core.render.ShaderUniformMath
import com.geovisual.kenburns.core.serde.ProjectJson
import com.geovisual.kenburns.core.timeline.Timeline
import org.junit.Assert.*
import org.junit.Test

class SlideTransitionTest {
    private fun project(ms: Long = 600, default: TransitionType = TransitionType.SLIDE) = Project(
        id = "p", name = "Slide", createdAt = 1, modifiedAt = 1, aspect = AspectRatio.H16x9,
        transition = default, transitionDurationMs = ms,
        photos = listOf(photo("a", durationMs = 5000), photo("b", durationMs = 5000), photo("c", durationMs = 5000)),
    )

    private fun u(t: Timeline, i: Int, ms: Double) = ShaderUniformMath.from(t.frameState(i, ms))

    @Test fun slideOverlapsLikeCrossfade() {
        val s = Timeline(project())
        val c = Timeline(project(default = TransitionType.CROSSFADE))
        for (i in 0 until 3) {
            assertEquals(c.needsNextPhoto(i), s.needsNextPhoto(i))
            assertEquals(c.motionDurationMs(i), s.motionDurationMs(i), 0.0)
        }
        assertTrue(s.needsNextPhoto(0)); assertFalse(s.needsNextPhoto(2))
        // Ken Burns keeps moving on both photos: next photo's rect equals the crossfade one.
        assertEquals(c.frameState(0, 4800.0).rectNext, s.frameState(0, 4800.0).rectNext)
        assertEquals(c.frameState(0, 4800.0).rectCurrent, s.frameState(0, 4800.0).rectCurrent)
    }

    @Test fun startOfSlideShowsOnlyCurrent() {
        val t = Timeline(project())
        val before = u(t, 0, 4400.0) // exactly at tail start
        assertEquals(1f, before.split, 0f); assertEquals(0f, before.currentShift, 0f); assertFalse(before.hasNext)
        val x = ShaderUniformMath.slideSample(before, 0.9f)
        assertFalse(x.next); assertEquals(0.9f, x.cropX, 1e-6f)
    }

    @Test fun midwaySplitsAtHalf() {
        val t = Timeline(project())
        val mid = u(t, 0, 4700.0) // 300 ms into a 600 ms slide
        assertEquals(0.5f, mid.split, 1e-6f); assertTrue(mid.hasNext); assertEquals(0f, mid.alpha, 0f)
        val l = ShaderUniformMath.slideSample(mid, 0.25f)
        assertFalse(l.next); assertEquals(0.75f, l.cropX, 1e-6f)   // current pushed left
        val r = ShaderUniformMath.slideSample(mid, 0.75f)
        assertTrue(r.next); assertEquals(0.25f, r.cropX, 1e-6f)    // next follows in
    }

    @Test fun hardEdgeNoBlending() {
        val t = Timeline(project())
        var ms = 4401.0
        while (ms < 5000.0) {
            val v = u(t, 0, ms)
            assertEquals(0f, v.alpha, 0f)
            val eps = 1e-4f
            assertFalse(ShaderUniformMath.slideSample(v, v.split - eps).next)
            assertTrue(ShaderUniformMath.slideSample(v, v.split + eps).next)
            ms += 37.0
        }
    }

    @Test fun endOfSlideShowsOnlyNext() {
        val t = Timeline(project())
        val end = u(t, 0, 5000.0 - 1e-9)
        assertEquals(0f, end.split, 1e-5f)
        val s = ShaderUniformMath.slideSample(end, 0.01f)
        assertTrue(s.next); assertEquals(0.01f, s.cropX, 1e-4f)
        // Head of the next item: no slide state any more.
        val head = u(t, 1, 0.0)
        assertEquals(1f, head.split, 0f); assertFalse(head.hasNext)
    }

    @Test fun slideIsEasedAndMonotonic() {
        val t = Timeline(project())
        var prev = 0.0
        var ms = 4400.0
        while (ms <= 5000.0) {
            val s = t.frameState(0, ms).slide
            assertTrue(s >= prev - 1e-12); prev = s; ms += 10.0
        }
        assertEquals(0.5, t.frameState(0, 4700.0).slide, 1e-9)
        assertTrue(t.frameState(0, 4500.0).slide < 0.25) // ease-in-out starts slow
    }

    @Test fun lengthZeroIsCutAndOverrideWorks() {
        val z = Timeline(project(ms = 0))
        assertFalse(z.needsNextPhoto(0)); assertEquals(0.0, z.frameState(0, 4999.0).slide, 0.0)
        val p = project(default = TransitionType.CUT)
        val o = p.copy(photos = p.photos.mapIndexed { i, ph -> if (i == 0) ph.copy(transitionOverride = TransitionType.SLIDE) else ph })
        val t = Timeline(ProjectJson.decode(ProjectJson.encode(o)))
        assertTrue(t.needsNextPhoto(0)); assertFalse(t.needsNextPhoto(1))
    }

    @Test fun existingProjectFilesStillLoad() {
        val json = ProjectJson.encode(project(default = TransitionType.CROSSFADE))
        assertEquals(TransitionType.CROSSFADE, ProjectJson.decode(json).transition)
        assertEquals(TransitionType.SLIDE, ProjectJson.decode(ProjectJson.encode(project())).transition)
    }
}
