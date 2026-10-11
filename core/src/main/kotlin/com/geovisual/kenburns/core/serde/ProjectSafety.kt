package com.geovisual.kenburns.core.serde

import com.geovisual.kenburns.core.model.Project

/** Thrown by [ProjectFiles.save] when [SaveGuard] refuses a write. The file on disk is untouched. */
class SaveRejectedException(message: String, val missingPhotoIds: Set<String>) : Exception(message)

/**
 * Refuses a save that would silently drop photos. A photo listed in the file on disk may only
 * disappear from the saved project when the user removed it on purpose in this session; anything
 * else means the in-memory copy is stale (an old list entry, a lost reload) and writing it would
 * orphan the photo files.
 */
object SaveGuard {
    /** Photo ids on disk that [toSave] lacks and that were not removed on purpose. Empty = safe to save. */
    fun missingPhotos(onDisk: Project?, toSave: Project, removedOnPurpose: Set<String>): Set<String> {
        if (onDisk == null) return emptySet()
        val kept = toSave.photos.mapTo(HashSet()) { it.id }
        return onDisk.photos.map { it.id }.filterTo(LinkedHashSet()) { it !in kept && it !in removedOnPurpose }
    }

    fun check(onDisk: Project?, toSave: Project, removedOnPurpose: Set<String>) {
        if (onDisk != null && onDisk.id != toSave.id) {
            throw SaveRejectedException("refused to save project ${toSave.id} over ${onDisk.id}", emptySet())
        }
        val missing = missingPhotos(onDisk, toSave, removedOnPurpose)
        if (missing.isNotEmpty()) {
            throw SaveRejectedException(
                "refused to save project ${toSave.id}: it would drop ${missing.size} of ${onDisk!!.photos.size} " +
                    "saved photos (would write ${toSave.photos.size}). Missing ids: ${missing.joinToString()}",
                missing,
            )
        }
    }
}

/** Which files in a project's photos/ directory may be moved out as leftovers. */
object OrphanPolicy {
    data class FileInfo(val name: String, val lastModifiedMs: Long)

    fun isPhotoFile(name: String): Boolean = name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)
    private fun isLeftover(name: String): Boolean = isPhotoFile(name) || name.endsWith(".tmp")

    /**
     * Names of files not referenced by [keep] that were last written before [cutoffMs] (process
     * start, so a running import is never touched). [keep] must come from project.json as just
     * read from disk. When the project lists no photos at all nothing is selected: an empty list
     * next to existing photo files is exactly what a lost save looks like, and the files are the
     * only copy left.
     */
    fun select(keep: Set<String>, files: List<FileInfo>, cutoffMs: Long): List<String> {
        if (keep.isEmpty()) return emptyList()
        return files.filter { it.name !in keep && isLeftover(it.name) && it.lastModifiedMs < cutoffMs }.map { it.name }
    }
}

/**
 * Orphans go to <project>/.trash/ as "<movedAtMs>_<name>" instead of being deleted, and are purged
 * after [MAX_AGE_MS]. The stamp is in the name because a rename keeps the old file time.
 */
object Trash {
    const val DIR = ".trash"
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    fun nameFor(original: String, movedAtMs: Long): String = "${movedAtMs}_$original"

    /** (movedAtMs, original name), or null for a name this class did not write. */
    fun parse(trashName: String): Pair<Long, String>? {
        val i = trashName.indexOf('_')
        if (i <= 0 || i == trashName.length - 1) return null
        val stamp = trashName.substring(0, i).toLongOrNull() ?: return null
        return stamp to trashName.substring(i + 1)
    }

    /** Trash entries older than [maxAgeMs]. Names that do not parse are left alone. */
    fun expired(trashNames: List<String>, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): List<String> =
        trashNames.filter { n -> parse(n)?.let { nowMs - it.first > maxAgeMs } ?: false }
}
