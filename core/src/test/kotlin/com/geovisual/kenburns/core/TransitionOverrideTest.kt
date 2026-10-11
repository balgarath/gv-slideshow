package com.geovisual.kenburns.core

import com.geovisual.kenburns.core.model.*
import com.geovisual.kenburns.core.render.ShaderUniformMath
import com.geovisual.kenburns.core.serde.ProjectJson
import com.geovisual.kenburns.core.timeline.Timeline
import org.junit.Assert.*
import org.junit.Test

/** PhotoItem.transitionOverride end to end: saved project -> Timeline -> shader uniforms. */
class TransitionOverrideTest {
    private fun project(default: TransitionType, ms: Long, vararg overrides: TransitionType?) = Project(
        id = "p", name = "Overrides", createdAt = 1, modifiedAt = 1, aspect = AspectRatio.H16x9,
        transition = default, transitionDurationMs = ms,
        photos = overrides.mapIndexed { i, o -> photo("p$i", durationMs = 5000, override = o) },
    )

    /** Loads the project the way the app does (through JSON) and builds the export timeline. */
    private fun timelineOf(p: Project): Timeline {
        val loaded = ProjectJson.decode(ProjectJson.encode(p))
        assertEquals(p, loaded)
        return Timeline(loaded)
    }

    private fun uniforms(t: Timeline, i: Int, ms: Double) = ShaderUniformMath.from(t.frameState(i, ms))

    /** The effect may only ask for a second texture where CompositionBuilder supplied one. */
    private fun assertNextTextureOnlyWhereSupplied(t: Timeline) {
        for (i in 0 until t.size) {
            var ms = 0.0
            while (ms < 5000.0) {
                if (uniforms(t, i, ms).hasNext) assertTrue("item $i at $ms", t.needsNextPhoto(i))
                ms += 10.0
            }
        }
    }

    @Test fun overridesBeatCrossfadeDefault() {
        val t = timelineOf(project(TransitionType.CROSSFADE, 600, TransitionType.DIP_TO_BLACK, TransitionType.CUT, null, TransitionType.DIP_TO_BLACK))
        assertEquals(TransitionType.DIP_TO_BLACK, t.transitionAfter(0))
        assertEquals(TransitionType.CUT, t.transitionAfter(1))
        assertEquals(TransitionType.CROSSFADE, t.transitionAfter(2))
        assertEquals(TransitionType.CUT, t.transitionAfter(3)) // last photo: override has no boundary
        assertEquals(listOf(false, false, true, false), (0 until 4).map { t.needsNextPhoto(it) })

        // Photo 0 -> 1: dip to black, no second texture.
        var u = uniforms(t, 0, 4850.0)
        assertEquals(0.5f, u.dip, 1e-6f); assertFalse(u.hasNext); assertEquals(0f, u.alpha, 0f)
        assertEquals(1f, uniforms(t, 1, 0.0).dip, 1e-6f)
        assertEquals(0f, uniforms(t, 1, 300.0).dip, 1e-6f)
        // Photo 1 -> 2: hard cut.
        u = uniforms(t, 1, 4990.0)
        assertEquals(0f, u.dip, 0f); assertFalse(u.hasNext); assertEquals(0f, u.alpha, 0f)
        assertEquals(0.0, t.crossfadeInMs(2), 0.0)
        // Photo 2 -> 3: project default crossfade.
        u = uniforms(t, 2, 4700.0)
        assertTrue(u.hasNext); assertEquals(0.5f, u.alpha, 1e-6f); assertEquals(0f, u.dip, 0f)
        assertEquals(600.0, t.crossfadeInMs(3), 0.0)
        // Last photo never fades out.
        assertEquals(0f, uniforms(t, 3, 4999.0).dip, 0f)
        assertNextTextureOnlyWhereSupplied(t)
    }

    @Test fun overridesWorkOnCutDefault() {
        val t = timelineOf(project(TransitionType.CUT, 600, TransitionType.CROSSFADE, TransitionType.DIP_TO_BLACK, null))
        assertEquals(listOf(true, false, false), (0 until 3).map { t.needsNextPhoto(it) })
        val u = uniforms(t, 0, 4700.0)
        assertTrue(u.hasNext); assertEquals(0.5f, u.alpha, 1e-6f)
        assertEquals(5600.0, t.motionDurationMs(1), 0.0) // crossfade lead-in on the incoming photo
        assertEquals(0.5f, uniforms(t, 1, 4850.0).dip, 1e-6f)
        assertEquals(1f, uniforms(t, 2, 0.0).dip, 1e-6f)
        assertEquals(TransitionType.CUT, t.transitionAfter(2))
        assertNextTextureOnlyWhereSupplied(t)
    }

    @Test fun zeroLengthMakesEveryBoundaryACut() {
        val t = timelineOf(project(TransitionType.CROSSFADE, 0, TransitionType.CROSSFADE, TransitionType.DIP_TO_BLACK, null))
        for (i in 0 until 3) {
            assertEquals(TransitionType.CUT, t.transitionAfter(i))
            assertFalse(t.needsNextPhoto(i))
            val u = uniforms(t, i, 4999.0)
            assertFalse(u.hasNext); assertEquals(0f, u.dip, 0f)
        }
    }
}
