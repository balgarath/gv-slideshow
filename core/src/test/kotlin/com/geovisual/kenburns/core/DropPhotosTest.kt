package com.geovisual.kenburns.core

import com.geovisual.kenburns.core.model.*
import com.geovisual.kenburns.core.timeline.Timeline
import com.geovisual.kenburns.core.timeline.keepingPhotos
import org.junit.Assert.*
import org.junit.Test

class DropPhotosTest {
    private val cut = TransitionType.CUT
    private val dip = TransitionType.DIP_TO_BLACK

    private fun project(vararg overrides: TransitionType?) = Project(
        id = "p", name = "Drop", createdAt = 1, modifiedAt = 1, aspect = AspectRatio.H16x9,
        transition = TransitionType.CROSSFADE, transitionDurationMs = 600,
        photos = overrides.mapIndexed { i, o -> photo("p$i", durationMs = 5000, override = o) },
    )

    private fun drop(p: Project, vararg ids: String) = p.keepingPhotos { it.id !in ids }

    @Test fun droppedPhotosOverrideGovernsTheNewBoundary() {
        val r = drop(project(dip, cut, null), "p1")
        assertEquals(listOf("p0", "p2"), r.photos.map { it.id })
        assertEquals(cut, Timeline(r).transitionAfter(0))
    }

    @Test fun droppedDefaultClearsEarlierOverride() {
        val r = drop(project(cut, null, null), "p1")
        assertEquals(TransitionType.CROSSFADE, Timeline(r).transitionAfter(0))
    }

    @Test fun runOfDroppedUsesLastOne() {
        val r = drop(project(null, cut, dip, null), "p1", "p2")
        assertEquals(dip, Timeline(r).transitionAfter(0))
    }

    @Test fun leadingAndTrailingDropsLeaveNoBoundary() {
        val p = project(cut, dip, cut)
        val r = drop(p, "p0", "p2")
        assertEquals(listOf("p1"), r.photos.map { it.id })
        assertEquals(1, Timeline(r).size)
    }

    @Test fun nothingDroppedIsIdentity() {
        val p = project(cut, dip, null)
        assertEquals(p, drop(p))
    }
}
