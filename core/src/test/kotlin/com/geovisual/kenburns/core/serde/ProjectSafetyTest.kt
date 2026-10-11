package com.geovisual.kenburns.core.serde

import com.geovisual.kenburns.core.model.AspectRatio
import com.geovisual.kenburns.core.model.Project
import com.geovisual.kenburns.core.photo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectSafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun project(vararg ids: String, name: String = "Cave Fest") = Project(
        id = "p1", name = name, createdAt = 1, modifiedAt = 2, aspect = AspectRatio.H16x9,
        photos = ids.map { photo(it) },
    )

    // ---- the reported chain ----

    /**
     * createProject put an empty copy in the list, imports only updated the open copy, and
     * closeProject never refreshed the list. Reopening from the list and touching any setting then
     * saved that empty copy over the real project.json. That save must now be refused.
     */
    @Test fun staleEmptyProjectCannotOverwritePopulatedFile() {
        val files = ProjectFiles(tmp.newFolder("projects"))
        files.save(project())                      // createProject
        files.save(project("a", "b", "c"))         // import autosave
        val stale = project(name = "Renamed")      // the list's empty copy, then a rename
        val e = assertThrows(SaveRejectedException::class.java) { files.save(stale) }
        assertEquals(setOf("a", "b", "c"), e.missingPhotoIds)
        assertEquals(listOf("a", "b", "c"), files.load("p1").photos.map { it.id })
        assertEquals("Cave Fest", files.load("p1").name)
    }

    @Test fun orphanSelectionWithEmptyKeepSetSelectsNothing() {
        val files = listOf(OrphanPolicy.FileInfo("a.jpg", 10), OrphanPolicy.FileInfo("b.jpg", 10), OrphanPolicy.FileInfo("c.jpg.tmp", 10))
        assertEquals(emptyList<String>(), OrphanPolicy.select(emptySet(), files, cutoffMs = 1_000))
    }

    // ---- save guard ----

    @Test fun removalsOnPurposeAreAllowed() {
        val files = ProjectFiles(tmp.newFolder("projects"))
        files.save(project("a", "b", "c"))
        files.save(project("a", "c"), removedOnPurpose = setOf("b"))
        assertEquals(listOf("a", "c"), files.load("p1").photos.map { it.id })
    }

    @Test fun removalNotOnRecordIsRefusedEvenWhenCountGrows() {
        val g = SaveGuard.missingPhotos(project("a", "b"), project("a", "x", "y"), emptySet())
        assertEquals(setOf("b"), g)
    }

    @Test fun reorderAddAndEditPass() {
        assertTrue(SaveGuard.missingPhotos(project("a", "b"), project("b", "a", "c"), emptySet()).isEmpty())
        assertTrue(SaveGuard.missingPhotos(null, project(), emptySet()).isEmpty())
        SaveGuard.check(project("a"), project("a", name = "New name"), emptySet())
    }

    @Test fun differentProjectIdIsRefused() {
        assertThrows(SaveRejectedException::class.java) {
            SaveGuard.check(project("a"), project("a").copy(id = "p2"), emptySet())
        }
    }

    @Test fun unreadableFileOnDiskDoesNotBlockSave() {
        val files = ProjectFiles(tmp.newFolder("projects"))
        files.projectDir("p1").mkdirs()
        File(files.projectDir("p1"), "project.json").writeText("garbage")
        files.save(project("a"))
        assertEquals(listOf("a"), files.load("p1").photos.map { it.id })
        assertEquals("garbage", File(files.projectDir("p1"), "project.json.bak").readText())
    }

    // ---- backup ----

    @Test fun saveKeepsOneDeepBackup() {
        val files = ProjectFiles(tmp.newFolder("projects"))
        files.save(project("a"))
        assertNull(files.loadBackup("p1"))
        files.save(project("a", "b"))
        assertEquals(listOf("a"), files.loadBackup("p1")!!.photos.map { it.id })
        files.save(project("a", "b", "c"))
        assertEquals(listOf("a", "b"), files.loadBackup("p1")!!.photos.map { it.id })
        assertFalse(File(files.projectDir("p1"), "project.json.bak.tmp").exists())
    }

    @Test fun refusedSaveLeavesFileAndBackupAlone() {
        val files = ProjectFiles(tmp.newFolder("projects"))
        files.save(project("a"))
        files.save(project("a", "b"))
        assertThrows(SaveRejectedException::class.java) { files.save(project()) }
        assertEquals(listOf("a", "b"), files.load("p1").photos.map { it.id })
        assertEquals(listOf("a"), files.loadBackup("p1")!!.photos.map { it.id })
    }

    // ---- orphans ----

    @Test fun orphanSelectionSkipsKeptNewAndForeignFiles() {
        val files = listOf(
            OrphanPolicy.FileInfo("a.jpg", 10),       // kept
            OrphanPolicy.FileInfo("old.jpg", 10),     // orphan
            OrphanPolicy.FileInfo("half.jpg.tmp", 10),// orphan
            OrphanPolicy.FileInfo("new.jpg", 5_000),  // written by this process
            OrphanPolicy.FileInfo("notes.txt", 10),   // not ours
        )
        assertEquals(listOf("old.jpg", "half.jpg.tmp"), OrphanPolicy.select(setOf("a.jpg"), files, cutoffMs = 1_000))
    }

    // ---- trash ----

    @Test fun trashNamesRoundTripAndExpire() {
        val n = Trash.nameFor("abc_def.jpg", 1_000)
        assertEquals(1_000L to "abc_def.jpg", Trash.parse(n))
        assertNull(Trash.parse("nounderscore.jpg"))
        assertNull(Trash.parse("x_y.jpg"))
        val day = 24L * 60 * 60 * 1000
        val names = listOf(Trash.nameFor("old.jpg", 0), Trash.nameFor("fresh.jpg", 6 * day), "stray.jpg")
        assertEquals(listOf(Trash.nameFor("old.jpg", 0)), Trash.expired(names, nowMs = 8 * day))
    }
}
