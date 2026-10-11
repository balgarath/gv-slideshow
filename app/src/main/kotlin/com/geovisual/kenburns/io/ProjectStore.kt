package com.geovisual.kenburns.io

import android.content.Context
import com.geovisual.kenburns.core.model.AspectRatio
import com.geovisual.kenburns.core.model.Project
import com.geovisual.kenburns.core.serde.OrphanPolicy
import com.geovisual.kenburns.core.serde.PhotoRecovery
import com.geovisual.kenburns.core.serde.ProjectFiles
import com.geovisual.kenburns.core.serde.Trash
import java.io.File
import java.util.UUID

/** Thin Android wrapper: projects live in filesDir/projects/<id>/ (see design section 3). */
class ProjectStore(context: Context) {
    private val filesDir = context.applicationContext.filesDir
    private val files = ProjectFiles(File(filesDir, "projects").also { it.mkdirs() })

    fun list(): List<Project> = files.list()
    fun load(id: String): Project = files.load(id)
    /** Throws SaveRejectedException when photos not in [removedOnPurpose] would be dropped. */
    fun save(project: Project, removedOnPurpose: Set<String> = emptySet()) = files.save(project, removedOnPurpose)
    fun loadBackup(id: String): Project? = files.loadBackup(id)
    fun trashDir(id: String): File = files.trashDir(id)
    fun delete(id: String): Boolean = files.delete(id)
    fun photosDir(id: String): File = files.photosDir(id).also { it.mkdirs() }

    fun create(name: String, aspect: AspectRatio): Project {
        val now = System.currentTimeMillis()
        val p = Project(
            id = UUID.randomUUID().toString(), name = name, createdAt = now, modifiedAt = now,
            aspect = aspect, photos = emptyList(),
        )
        files.save(p)
        return p
    }

    /**
     * Moves photo files that [loaded] (project.json as just read from disk) does not list into
     * .trash/, and purges trash older than 7 days. Files written after [cutoffMs] are left alone.
     */
    fun trashOrphans(loaded: Project, cutoffMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val dir = photosDir(loaded.id)
        val keep = loaded.photos.mapTo(HashSet()) { it.fileName }
        val found = (dir.listFiles() ?: emptyArray()).filter { it.isFile }
            .map { OrphanPolicy.FileInfo(it.name, it.lastModified()) }
        val orphans = OrphanPolicy.select(keep, found, cutoffMs)
        val trash = trashDir(loaded.id)
        if (orphans.isNotEmpty()) trash.mkdirs()
        for (name in orphans) File(dir, name).renameTo(File(trash, Trash.nameFor(name, nowMs)))
        val inTrash = trash.list()?.toList().orEmpty()
        for (name in Trash.expired(inTrash, nowMs)) File(trash, name).delete()
    }

    /**
     * What recovery could bring back for [current]: files in photos/ (oldest first) and .trash/
     * that it does not list, matched against project.json.bak. [exclude]: file names to ignore
     * (photos removed on purpose whose files are not deleted yet).
     */
    fun recoveryPlan(current: Project, exclude: Set<String> = emptySet()): PhotoRecovery.Plan {
        val photos = (photosDir(current.id).listFiles() ?: emptyArray())
            .filter { it.isFile && it.name !in exclude }
            .sortedWith(compareBy({ it.lastModified() }, { it.name }))
            .map { it.name }
        // Oldest first by file time (a rename keeps it), so trashed strays come back in import order.
        val trash = (trashDir(current.id).listFiles() ?: emptyArray())
            .filter { it.isFile && Trash.parse(it.name)?.second !in exclude }
            .sortedWith(compareBy({ it.lastModified() }, { Trash.parse(it.name)?.first ?: 0L }, { it.name }))
            .map { it.name }
        return PhotoRecovery.plan(current, loadBackup(current.id), photos, trash)
    }

    /** Copies trashed files back into photos/ (original name to trash name). The trash copy stays. */
    fun copyFromTrash(id: String, files: Map<String, String>) {
        val dir = photosDir(id)
        val trash = trashDir(id)
        for ((original, trashName) in files) {
            val target = File(dir, original)
            if (target.exists()) continue
            val tmp = File(dir, "$original.tmp")
            File(trash, trashName).copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw java.io.IOException("could not restore $original")
            }
        }
    }

    /** Removes directories left by earlier single-project builds. */
    fun removeLegacyDirs() {
        for (legacy in listOf("session", "stub")) File(files.root, legacy).deleteRecursively()
    }
}
