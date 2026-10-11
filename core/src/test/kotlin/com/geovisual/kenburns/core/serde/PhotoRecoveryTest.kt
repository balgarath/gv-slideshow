package com.geovisual.kenburns.core.serde

import com.geovisual.kenburns.core.model.AspectRatio
import com.geovisual.kenburns.core.model.Project
import com.geovisual.kenburns.core.photo
import org.junit.Assert.*
import org.junit.Test

class PhotoRecoveryTest {
    private fun project(vararg ids: String, aspect: AspectRatio = AspectRatio.H16x9) = Project(
        id = "p1", name = "Cave Fest", createdAt = 1, modifiedAt = 2, aspect = aspect,
        photos = ids.map { photo(it, aspect = aspect) },
    )

    @Test fun nothingToRecoverWhenEverythingIsListed() {
        val plan = PhotoRecovery.plan(project("a", "b"), project("a"), listOf("a.jpg", "b.jpg"), emptyList())
        assertEquals(0, plan.count)
    }

    /** The overwritten project: empty project.json, full backup, files still in photos/. */
    @Test fun backupRestoresOriginalFramingAndOrder() {
        val backup = project("c", "a", "b")
        val current = project()
        val plan = PhotoRecovery.plan(current, backup, listOf("a.jpg", "b.jpg", "c.jpg"), emptyList())
        assertEquals(3, plan.count)
        assertEquals(emptyList<String>(), plan.strays)
        val out = PhotoRecovery.apply(current, backup, plan.fromBackup, emptyList())
        assertEquals(backup.photos, out.photos)
    }

    @Test fun backupItemsWithoutFilesAreSkippedAndStraysReAdded() {
        val backup = project("a", "gone")
        val plan = PhotoRecovery.plan(project("n"), backup, listOf("n.jpg", "a.jpg", "x.jpg", "x.jpg.tmp"), emptyList())
        assertEquals(listOf("a"), plan.fromBackup.map { it.id })
        assertEquals(listOf("x.jpg"), plan.strays)
        val adopted = photo("x")
        val out = PhotoRecovery.apply(project("n"), backup, plan.fromBackup, listOf(adopted))
        // Backup order first, then photos added since, then strays.
        assertEquals(listOf("a", "n", "x"), out.photos.map { it.id })
    }

    @Test fun trashedFilesCountAndAreCopiedBack() {
        val trash = listOf(Trash.nameFor("a.jpg", 5), Trash.nameFor("a.jpg", 9), Trash.nameFor("t.jpg", 7), "junk")
        val plan = PhotoRecovery.plan(project("k"), project("a"), listOf("k.jpg"), trash)
        assertEquals(listOf("a"), plan.fromBackup.map { it.id })
        assertEquals(listOf("t.jpg"), plan.strays)
        assertEquals(mapOf("a.jpg" to Trash.nameFor("a.jpg", 9), "t.jpg" to Trash.nameFor("t.jpg", 7)), plan.fromTrash)
    }

    @Test fun backupOfAnotherAspectIsConverted() {
        val backup = project("a", aspect = AspectRatio.H16x9)
        val current = project(aspect = AspectRatio.V9x16)
        val plan = PhotoRecovery.plan(current, backup, listOf("a.jpg"), emptyList())
        val out = PhotoRecovery.apply(current, backup, plan.fromBackup, emptyList()).photos.single()
        assertTrue(out.startRect.isValidFor(out.imageWidth, out.imageHeight, AspectRatio.V9x16))
        assertTrue(out.endRect.isValidFor(out.imageWidth, out.imageHeight, AspectRatio.V9x16))
    }

    @Test fun backupOfAnotherProjectIsIgnored() {
        val plan = PhotoRecovery.plan(project(), project("a").copy(id = "p2"), listOf("a.jpg"), emptyList())
        assertEquals(emptyList<Any>(), plan.fromBackup)
        assertEquals(listOf("a.jpg"), plan.strays)
    }

    @Test fun strayIdsReuseFileNameWhenFree() {
        assertEquals("abc", PhotoRecovery.idForStray("abc.jpg", setOf("x"), { "fresh" }))
        assertEquals("fresh", PhotoRecovery.idForStray("abc.jpg", setOf("abc"), { "fresh" }))
    }

    @Test fun trashedStraysKeepTheOrderTheyWereGivenIn() {
        // zz was imported first; alphabetical order would put it last.
        val trash = listOf(Trash.nameFor("zz.jpg", 9), Trash.nameFor("aa.jpg", 9), Trash.nameFor("mm.jpg", 9))
        val plan = PhotoRecovery.plan(project(), null, emptyList(), trash)
        assertEquals(listOf("zz.jpg", "aa.jpg", "mm.jpg"), plan.strays)
    }
}
