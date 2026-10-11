package com.geovisual.kenburns.core.serde

import com.geovisual.kenburns.core.model.*
import com.geovisual.kenburns.core.photo
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectJsonTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun project(id: String = "p1", modified: Long = 10) = Project(
        id = id, name = "Cave Fest", createdAt = 1, modifiedAt = modified, aspect = AspectRatio.V9x16,
        transition = TransitionType.DIP_TO_BLACK, transitionDurationMs = 800,
        photos = listOf(
            photo("a").copy(faces = listOf(FaceBox(0.1, 0.2, 0.3, 0.4), FaceBox(0.5, 0.5, 0.1, 0.1))),
            photo("b").copy(faces = emptyList(), transitionOverride = TransitionType.CUT),
            photo("c"),
        ),
    )

    @Test fun roundTripIncludingFaces() {
        val p = project()
        val back = ProjectJson.decode(ProjectJson.encode(p))
        assertEquals(p, back)
        assertEquals(2, back.photos[0].faces!!.size)
        assertEquals(emptyList<FaceBox>(), back.photos[1].faces)
        assertNull(back.photos[2].faces)
    }

    @Test fun oldFileWithoutFacesStillLoads() {
        val text = ProjectJson.encode(project()).replace(Regex(",\\s*\"faces\"\\s*:\\s*null"), "")
        assertNull(ProjectJson.decode(text).photos[2].faces)
    }

    @Test fun futureVersionIsRejected() {
        val text = ProjectJson.encode(project()).replace("\"schemaVersion\": 2", "\"schemaVersion\": 3")
        val e = assertThrows(UnsupportedVersionException::class.java) { ProjectJson.decode(text) }
        assertEquals(3, e.found); assertEquals(2, e.supported)
        assertTrue(e.message!!.contains("newer version"))
    }

    @Test fun v1FileLoadsAndSavesAsV2() {
        val v1 = ProjectJson.encode(project()).replace("\"schemaVersion\": 2", "\"schemaVersion\": 1")
        assertTrue(v1.contains("\"schemaVersion\": 1"))
        val p = ProjectJson.decode(v1)
        assertEquals(project(), p.copy(schemaVersion = project().schemaVersion))
        assertEquals(2, ProjectJson.CURRENT_VERSION)
        assertTrue(ProjectJson.encode(p).contains("\"schemaVersion\": 2"))
    }

    @Test fun garbageIsRejected() {
        assertThrows(ProjectFormatException::class.java) { ProjectJson.decode("not json") }
        assertThrows(ProjectFormatException::class.java) { ProjectJson.decode("{}") }
        assertThrows(ProjectFormatException::class.java) { ProjectJson.decode("{\"schemaVersion\": 0}") }
        assertThrows(ProjectFormatException::class.java) { ProjectJson.decode("{\"schemaVersion\": 1}") }
    }

    @Test fun migrationScaffoldRunsStepsInOrder() {
        // Pretend v2 is current and v1 stored the name under "title".
        val v1 = ProjectJson.encode(project()).replace("\"name\"", "\"title\"").replace("\"schemaVersion\": 2", "\"schemaVersion\": 1")
        val migrations = mapOf<Int, (JsonObject) -> JsonObject>(
            1 to { o -> JsonObject((o - "title") + ("name" to o["title"]!!)) },
        )
        val p = ProjectJson.decode(v1, migrations, current = 2)
        assertEquals("Cave Fest", p.name)
        assertThrows(ProjectFormatException::class.java) { ProjectJson.decode(v1, emptyMap(), current = 2) }
    }

    @Test fun saveLoadAndNoTempLeftBehind() {
        val files = ProjectFiles(tmp.newFolder("projects"))
        files.save(project())
        assertEquals(project(), files.load("p1"))
        assertEquals(listOf("project.json"), files.projectDir("p1").list()!!.toList())
        files.save(project().copy(name = "Renamed"))
        assertEquals("Renamed", files.load("p1").name)
    }

    @Test fun failedWriteLeavesOldFileIntact() {
        val files = ProjectFiles(tmp.newFolder("projects"))
        files.save(project())
        val before = File(files.projectDir("p1"), "project.json").readText()
        // A directory squatting on the temp name makes the temp write fail.
        File(files.projectDir("p1"), "project.json.tmp").mkdir()
        assertThrows(Exception::class.java) { files.save(project().copy(name = "Never written")) }
        assertEquals(before, File(files.projectDir("p1"), "project.json").readText())
        assertEquals("Cave Fest", files.load("p1").name)
    }

    @Test fun staleTempFileIsReplaced() {
        val files = ProjectFiles(tmp.newFolder("projects"))
        files.projectDir("p1").mkdirs()
        File(files.projectDir("p1"), "project.json.tmp").writeText("half written garbage")
        files.save(project())
        assertEquals(project(), files.load("p1"))
        assertFalse(File(files.projectDir("p1"), "project.json.tmp").exists())
    }

    @Test fun listSortsByModifiedAndSkipsBroken() {
        val files = ProjectFiles(tmp.newFolder("projects"))
        files.save(project("a", 5)); files.save(project("b", 50)); files.save(project("c", 20))
        File(files.root, "broken").mkdirs(); File(files.root, "broken/project.json").writeText("nope")
        File(files.root, "empty").mkdirs()
        assertEquals(listOf("b", "c", "a"), files.list().map { it.id })
    }

    @Test fun deleteRemovesDirectoryWithPhotos() {
        val files = ProjectFiles(tmp.newFolder("projects"))
        files.save(project())
        files.photosDir("p1").mkdirs(); File(files.photosDir("p1"), "x.jpg").writeText("x")
        assertTrue(files.delete("p1"))
        assertFalse(files.projectDir("p1").exists())
        assertTrue(files.list().isEmpty())
    }

    @Test fun unsafeIdsAreRefused() {
        val files = ProjectFiles(tmp.newFolder("projects"))
        assertThrows(IllegalArgumentException::class.java) { files.projectDir("../x") }
        assertThrows(IllegalArgumentException::class.java) { files.delete("") }
    }
}
