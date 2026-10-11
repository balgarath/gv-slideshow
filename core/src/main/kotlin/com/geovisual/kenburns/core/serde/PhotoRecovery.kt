package com.geovisual.kenburns.core.serde

import com.geovisual.kenburns.core.imports.ImportLogic
import com.geovisual.kenburns.core.model.PhotoItem
import com.geovisual.kenburns.core.model.Project

/**
 * Finds photo files a project no longer lists and decides how to bring them back. Pure; the app
 * copies files and builds framing for strays. Nothing here deletes anything.
 */
object PhotoRecovery {
    /**
     * [fromBackup]: items in project.json.bak whose file still exists; they keep their framing.
     * [strays]: other photo files with no record, to re-add with fresh framing.
     * [fromTrash]: original file name to trash file name, for every file that must be copied back
     * into photos/ first.
     */
    data class Plan(
        val fromBackup: List<PhotoItem>,
        val strays: List<String>,
        val fromTrash: Map<String, String>,
    ) {
        val count: Int get() = fromBackup.size + strays.size
    }

    /**
     * [photoFiles]: names in photos/ (ideally oldest first, so strays come back in import order).
     * [trashFiles]: names in .trash/, ideally oldest first (file time); strays from the trash keep
     * this order, since their UUID names say nothing about import order.
     */
    fun plan(current: Project, backup: Project?, photoFiles: List<String>, trashFiles: List<String>): Plan {
        val listed = current.photos.mapTo(HashSet()) { it.fileName }
        val currentIds = current.photos.mapTo(HashSet()) { it.id }
        val inPhotos = photoFiles.filter { OrphanPolicy.isPhotoFile(it) }
        val inPhotosSet = inPhotos.toSet()
        // Newest trash copy per original name.
        val trashByName = LinkedHashMap<String, Pair<Long, String>>()
        for (t in trashFiles) {
            val (stamp, original) = Trash.parse(t) ?: continue
            if (!OrphanPolicy.isPhotoFile(original)) continue
            val prev = trashByName[original]
            if (prev == null || stamp > prev.first) trashByName[original] = stamp to t
        }
        fun exists(name: String) = name in inPhotosSet || name in trashByName

        val fromBackup = backup?.takeIf { it.id == current.id }?.photos.orEmpty()
            .filter { it.id !in currentIds && it.fileName !in listed && exists(it.fileName) }
            .distinctBy { it.fileName }
        val covered = listed + fromBackup.map { it.fileName }
        val strays = (inPhotos + trashByName.keys).distinct().filter { it !in covered }
        val fromTrash = (fromBackup.map { it.fileName } + strays)
            .filter { it !in inPhotosSet }
            .associateWith { trashByName.getValue(it).second }
        return Plan(fromBackup, strays, fromTrash)
    }

    /**
     * The recovered photo list. With backup items the backup's order is restored (current versions
     * of shared photos win), then current photos the backup never had, then [adopted] strays.
     * Backup framing is converted when the project's aspect changed since.
     */
    fun apply(current: Project, backup: Project?, fromBackup: List<PhotoItem>, adopted: List<PhotoItem>): Project {
        val listed = current.photos.mapTo(HashSet()) { it.fileName }
        val restored = fromBackup.filter { it.fileName !in listed }.map { item ->
            if (backup == null || backup.aspect == current.aspect) item
            else ImportLogic.convertAspect(item, backup.aspect, current.aspect)
        }
        val restoredById = restored.associateBy { it.id }
        val currentById = current.photos.associateBy { it.id }
        val ordered = if (backup == null || restored.isEmpty()) current.photos else {
            val backupIds = backup.photos.mapTo(HashSet()) { it.id }
            backup.photos.mapNotNull { currentById[it.id] ?: restoredById[it.id] } +
                current.photos.filter { it.id !in backupIds }
        }
        val taken = ordered.mapTo(HashSet()) { it.fileName }
        val extra = adopted.filter { taken.add(it.fileName) }
        return current.copy(photos = ordered + extra)
    }

    /** Photo id for a stray file: its base name when that is free, so id and file name stay paired. */
    fun idForStray(fileName: String, usedIds: Set<String>, fresh: () -> String): String {
        val base = fileName.substringBeforeLast('.')
        return if (base.isNotEmpty() && base !in usedIds) base else fresh()
    }
}
