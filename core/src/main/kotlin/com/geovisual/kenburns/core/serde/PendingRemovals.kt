package com.geovisual.kenburns.core.serde

import com.geovisual.kenburns.core.model.PhotoItem
import com.geovisual.kenburns.core.model.Project
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Photos the user removed this session. The ids are the only photos a save may drop ([SaveGuard]);
 * the file names are deleted once a save without them has reached disk. Entries are forgotten
 * when the project is reloaded from disk, because the file on disk is then the truth.
 */
class PendingRemovals {
    private data class Entry(val projectId: String, val photoId: String, val fileName: String)

    private val entries = ConcurrentLinkedQueue<Entry>()

    fun add(projectId: String, photoId: String, fileName: String) {
        entries.add(Entry(projectId, photoId, fileName))
    }

    /** Photo ids a save of [projectId] may drop. */
    fun ids(projectId: String): Set<String> = entries.filter { it.projectId == projectId }.mapTo(HashSet()) { it.photoId }

    /** File names of removed photos not yet deleted, for [projectId]. */
    fun fileNames(projectId: String): Set<String> = entries.filter { it.projectId == projectId }.mapTo(HashSet()) { it.fileName }

    /** Forget everything pending for [projectId] (it was reloaded from disk). */
    fun clear(projectId: String) {
        entries.removeAll { it.projectId == projectId }
    }

    /**
     * Call after [saved] reached disk. Returns file names of removals it no longer lists, and
     * forgets them. Entries whose photo [saved] still lists stay pending: the save was taken
     * before the removal.
     */
    fun settle(saved: Project): List<String> {
        val listedIds = saved.photos.mapTo(HashSet()) { it.id }
        val listedFiles = saved.photos.mapTo(HashSet()) { it.fileName }
        val done = ArrayList<String>()
        for (e in entries.toList()) {
            if (e.projectId != saved.id || e.photoId in listedIds || e.fileName in listedFiles) continue
            done += e.fileName
            entries.remove(e)
        }
        return done
    }
}

/** Merging photos that were saved to disk behind an open project's back. */
object PhotoMerge {
    /** [project] plus those of [items] it does not list yet (by id or file name). Same instance if none. */
    fun appendMissing(project: Project, items: List<PhotoItem>): Project {
        val ids = project.photos.mapTo(HashSet()) { it.id }
        val files = project.photos.mapTo(HashSet()) { it.fileName }
        val missing = items.filter { it.id !in ids && it.fileName !in files }
        return if (missing.isEmpty()) project else project.copy(photos = project.photos + missing)
    }
}
