package com.geovisual.kenburns.core.serde

import com.geovisual.kenburns.core.model.Project
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Project directories under an injected [root] (the app uses filesDir/projects):
 * root/<id>/project.json (+ .bak), root/<id>/photos/ and root/<id>/.trash/. Plain java.io so it runs under JUnit.
 */
class ProjectFiles(val root: File) {

    fun projectDir(id: String): File = File(root, requireSafeId(id))
    fun photosDir(id: String): File = File(projectDir(id), "photos")
    private fun jsonFile(id: String) = File(projectDir(id), FILE_NAME)

    /**
     * Writes project.json.tmp, syncs it, then renames over project.json. A failure leaves the old
     * file intact. Before that the current file is checked by [SaveGuard] (throws
     * [SaveRejectedException] if photos would be dropped that are not in [removedOnPurpose]) and
     * copied to project.json.bak, a one-deep backup that photo recovery can read.
     */
    fun save(project: Project, removedOnPurpose: Set<String> = emptySet()) {
        val dir = projectDir(project.id)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create $dir")
        val target = jsonFile(project.id)
        if (target.isFile) {
            // An unreadable file cannot be compared; it is still backed up below before the write.
            val onDisk = try { ProjectJson.decode(target.readText(Charsets.UTF_8)) } catch (e: Exception) { null }
            SaveGuard.check(onDisk, project, removedOnPurpose)
            backUp(target, File(dir, BACKUP_NAME))
        }
        val tmp = File(dir, "$FILE_NAME.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(ProjectJson.encode(project).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            move(tmp, target)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** Copies [from] to [to] through a synced temp file, so [to] is always a whole file. */
    private fun backUp(from: File, to: File) {
        val tmp = File(to.parentFile, to.name + ".tmp")
        try {
            java.io.FileInputStream(from).use { input ->
                FileOutputStream(tmp).use { out ->
                    input.copyTo(out)
                    out.fd.sync()
                }
            }
            move(tmp, to)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** project.json.bak, or null if there is none or it does not parse. */
    fun loadBackup(id: String): Project? = try {
        val f = File(projectDir(id), BACKUP_NAME)
        if (f.isFile) ProjectJson.decode(f.readText(Charsets.UTF_8)) else null
    } catch (e: Exception) {
        null
    }

    fun trashDir(id: String): File = File(projectDir(id), Trash.DIR)

    fun load(id: String): Project {
        val f = jsonFile(id)
        if (!f.isFile) throw ProjectFormatException("No project file for $id")
        return ProjectJson.decode(f.readText(Charsets.UTF_8))
    }

    /** All readable projects, newest edit first. Unreadable ones are skipped. */
    fun list(): List<Project> =
        (root.listFiles { f -> f.isDirectory } ?: emptyArray())
            .mapNotNull { dir -> try { load(dir.name) } catch (e: Exception) { null } }
            .sortedByDescending { it.modifiedAt }

    /** Removes the whole project directory, photos included. */
    fun delete(id: String): Boolean = projectDir(id).deleteRecursively()

    private fun requireSafeId(id: String): String {
        require(id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "bad project id: $id" }
        return id
    }

    companion object {
        const val FILE_NAME = "project.json"
        const val BACKUP_NAME = "project.json.bak"
    }
}
