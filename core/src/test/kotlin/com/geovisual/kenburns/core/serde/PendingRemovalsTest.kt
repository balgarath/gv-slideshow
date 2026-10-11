package com.geovisual.kenburns.core.serde

import com.geovisual.kenburns.core.model.AspectRatio
import com.geovisual.kenburns.core.model.Project
import com.geovisual.kenburns.core.photo
import org.junit.Assert.*
import org.junit.Test

class PendingRemovalsTest {
    private fun project(vararg ids: String, id: String = "p1") = Project(
        id = id, name = "n", createdAt = 1, modifiedAt = 2, aspect = AspectRatio.H16x9,
        photos = ids.map { photo(it) },
    )

    @Test fun settleReturnsFilesOfSavedRemovalsAndForgetsThem() {
        val r = PendingRemovals()
        r.add("p1", "a", "a.jpg")
        assertEquals(setOf("a"), r.ids("p1"))
        assertEquals(listOf("a.jpg"), r.settle(project("b")))
        assertEquals(emptySet<String>(), r.ids("p1"))
        assertEquals(emptyList<String>(), r.settle(project("b")))
    }

    @Test fun removalNotYetInTheSavedCopyStaysPending() {
        val r = PendingRemovals()
        r.add("p1", "a", "a.jpg")
        assertEquals(emptyList<String>(), r.settle(project("a", "b")))
        assertEquals(setOf("a"), r.ids("p1"))
        assertEquals(setOf("a.jpg"), r.fileNames("p1"))
    }

    @Test fun clearForgetsOnlyThatProject() {
        val r = PendingRemovals()
        r.add("p1", "a", "a.jpg"); r.add("p2", "x", "x.jpg")
        r.clear("p1")
        assertEquals(emptySet<String>(), r.ids("p1"))
        assertEquals(setOf("x"), r.ids("p2"))
        // After a reload, a save that drops "a" is refused again instead of allowed.
        assertEquals(setOf("a"), SaveGuard.missingPhotos(project("a"), project(), r.ids("p1")))
    }

    @Test fun settleIgnoresOtherProjects() {
        val r = PendingRemovals()
        r.add("p2", "x", "x.jpg")
        assertEquals(emptyList<String>(), r.settle(project(id = "p1")))
        assertEquals(setOf("x"), r.ids("p2"))
    }

    @Test fun appendMissingAddsOnlyNewPhotos() {
        val p = project("a")
        val out = PhotoMerge.appendMissing(p, listOf(photo("a"), photo("b")))
        assertEquals(listOf("a", "b"), out.photos.map { it.id })
        assertSame(out, PhotoMerge.appendMissing(out, listOf(photo("b"))))
    }
}
