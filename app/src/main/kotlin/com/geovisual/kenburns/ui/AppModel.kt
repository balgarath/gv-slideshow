package com.geovisual.kenburns.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.geovisual.kenburns.core.imports.ImportLogic
import com.geovisual.kenburns.core.math.FramingRandomizer
import com.geovisual.kenburns.core.model.AspectRatio
import com.geovisual.kenburns.core.model.PhotoItem
import com.geovisual.kenburns.core.model.Project
import com.geovisual.kenburns.core.model.TransitionType
import com.geovisual.kenburns.core.serde.PendingRemovals
import com.geovisual.kenburns.core.serde.PhotoMerge
import com.geovisual.kenburns.core.serde.PhotoRecovery
import com.geovisual.kenburns.core.serde.SaveRejectedException
import com.geovisual.kenburns.export.ExportState
import com.geovisual.kenburns.export.Exporter
import com.geovisual.kenburns.io.ErrorLog
import com.geovisual.kenburns.io.PhotoImporter
import com.geovisual.kenburns.io.ProjectStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Process-scoped state for the project list and the open project. Imports, saves and exports run
 * in this object's scope, so rotating the screen cannot cancel them.
 */
object AppModel {
    private const val SAVE_DEBOUNCE_MS = 400L

    private lateinit var appContext: Context
    private lateinit var store: ProjectStore
    private lateinit var importer: PhotoImporter
    /** Process-scoped export engine; runs under export/ExportService. */
    val exporter: Exporter get() = Exporter
    private var initialized = false

    /** Backstop: an uncaught failure in any background job shows up as a save error instead of a crash. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> saveError = "Background task failed: ${Exporter.describe(e)}" },
    )

    /** Photos the user removed: the only ones a save may drop; their files go after that save. */
    private val removals = PendingRemovals()
    /** Serializes openProject so two calls cannot each replace `open` with a different project. */
    private val openMutex = Mutex()
    private var startedAtMs = 0L

    // ---- project list ----
    val projects = mutableStateListOf<Project>()
    /** Photos each project could get back (see [recoverPhotos]). Absent: none, or not checked yet. */
    val recoverable = mutableStateMapOf<String, Int>()
    var listLoaded by mutableStateOf(false)
        private set

    // ---- open project ----
    var open by mutableStateOf<Project?>(null)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set

    // ---- import ----
    var importing by mutableStateOf(false)
        private set
    var importingProjectId by mutableStateOf<String?>(null)
        private set
    var importStatus by mutableStateOf<String?>(null)
    /** Full stack trace of the last import failure, for the "Copy details" button. */
    var importDetails by mutableStateOf<String?>(null)

    // ---- export ----
    var exportProjectId by mutableStateOf<String?>(null)
        private set

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        startedAtMs = System.currentTimeMillis()
        appContext = context.applicationContext
        store = ProjectStore(appContext)
        importer = PhotoImporter(appContext)
        Exporter.init(appContext)
        scope.launch(Dispatchers.IO) {
            store.removeLegacyDirs()
            // Temp files and pending MediaStore entries left when the process died mid-export.
            Exporter.cleanupStale()
        }
        refreshList()
    }

    fun photosDir(projectId: String): File = store.photosDir(projectId)

    // ---------------- list ----------------

    fun refreshList() {
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { store.list() }
                projects.clear(); projects.addAll(loaded)
                for (p in loaded) checkRecoverable(p.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saveError = "Could not read the project list: ${Exporter.describe(e)}"
            }
            listLoaded = true
        }
    }

    fun createProject(name: String, aspect: AspectRatio): Project {
        val p = store.create(cleanName(name), aspect)
        projects.add(0, p)
        return p
    }

    fun renameProject(id: String, name: String) {
        val clean = cleanName(name)
        if (open?.id == id) { update { it.copy(name = clean) }; open?.let(::syncListEntry); return }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    saveMutex.withLock { store.save(store.load(id).copy(name = clean, modifiedAt = System.currentTimeMillis())) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saveError = "Could not rename the project: ${Exporter.describe(e)}"
            }
            refreshList()
        }
    }

    /** False when the project is busy (importing or exporting) and was left alone. */
    fun deleteProject(id: String): Boolean {
        if (isBusy(id)) return false
        if (open?.id == id) { saveJob?.cancel(); open = null }
        if (exportProjectId == id) exporter.reset()
        projects.removeAll { it.id == id }
        recoverable.remove(id)
        scope.launch(Dispatchers.IO) {
            try {
                saveMutex.withLock { store.delete(id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saveError = "Could not delete the project: ${Exporter.describe(e)}"
            }
        }
        return true
    }

    fun isBusy(id: String): Boolean =
        (importing && importingProjectId == id) || (exportProjectId == id && exporterBusy())

    fun exporterBusy(): Boolean = exporter.state.value.let { it is ExportState.Running || it is ExportState.Saving }

    private fun cleanName(name: String) = name.trim().ifEmpty { "Untitled" }.take(60)

    // ---------------- open project ----------------

    /**
     * Opens project [id] as it is on disk. The list's copy is never trusted: it can be older than
     * the file. Waits for any save in flight so the read sees it. Returns false if it is gone.
     */
    suspend fun openProject(id: String): Boolean = openMutex.withLock {
        if (open?.id == id) return@withLock true
        // A different open project is flushed and closed first, never silently replaced.
        if (open != null) closeProject()
        val p = try {
            withContext(Dispatchers.IO) {
                saveMutex.withLock {
                    store.load(id).also { removals.clear(id) } // disk is the truth now
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            refreshList()
            return@withLock false
        }
        open = p
        saveError = null
        importStatus = null
        syncListEntry(p)
        removeOrphans(p)
        checkRecoverable(id)
        true
    }

    /**
     * Moves photo JPEGs that [loaded] does not reference (left when the process died mid-import)
     * to the project's .trash/, where they stay 7 days. [loaded] must be project.json as read from
     * disk by this same open, never an in-memory copy. Files written since this process started
     * are never touched, a project that lists no photos is left alone, and a project that is
     * importing right now is skipped.
     */
    private fun removeOrphans(loaded: Project) {
        if (importing && importingProjectId == loaded.id) return
        val cutoff = startedAtMs
        scope.launch(Dispatchers.IO) {
            try {
                saveMutex.withLock {
                    if (importing && importingProjectId == loaded.id) return@withLock
                    store.trashOrphans(loaded, cutoff)
                }
            } catch (e: Exception) {
                // Housekeeping only; leftover files are harmless.
            }
        }
    }

    fun closeProject() {
        val p = open
        flush()
        open = null
        // The list entry must match what was just saved, or reopening it shows stale data.
        p?.let(::syncListEntry)
        p?.let { checkRecoverable(it.id) }
    }

    /** Applies [transform] to the open project and saves it shortly after. */
    fun update(transform: (Project) -> Project) {
        val p = open ?: return
        open = transform(p).copy(modifiedAt = System.currentTimeMillis())
        if (exportProjectId == p.id) exporter.reset()
        scheduleSave()
    }

    private fun syncListEntry(p: Project) {
        val i = projects.indexOfFirst { it.id == p.id }
        if (i >= 0) projects[i] = p
    }

    private var saveJob: Job? = null
    private val saveMutex = Mutex()

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            // Snapshot before taking the lock: closeProject may null `open` while we wait for it.
            val p = open ?: return@launch
            persistOpen(p)
        }
    }

    /** Writes any pending change now (leaving the screen, app going to background). */
    fun flush() {
        if (saveJob?.isActive == true) {
            saveJob?.cancel()
            val p = open ?: return
            scope.launch { persistOpen(p) }
        }
    }

    private suspend fun persistOpen(p: Project) {
        saveMutex.withLock {
            try {
                val removed = removals.ids(p.id)
                withContext(Dispatchers.IO + NonCancellable) {
                    store.save(p, removed)
                    deleteDroppedPhotos(p)
                }
                saveError = null
                syncListEntry(p)
            } catch (e: SaveRejectedException) {
                // The in-memory copy is older than the file. Keep the file, show what it holds.
                ErrorLog.record(appContext, "save refused (stale project in memory)", e)
                val fresh = try { withContext(Dispatchers.IO) { store.load(p.id) } } catch (x: Exception) { null }
                if (fresh != null) {
                    removals.clear(p.id)
                    if (open?.id == p.id) open = fresh
                    syncListEntry(fresh)
                }
                saveError = "This screen was out of date, so the last change was not saved and the project was reloaded."
            } catch (e: Exception) {
                saveError = "Could not save the project: ${Exporter.describe(e)}"
            }
        }
    }

    /** Called after [saved] reached disk: removes files of photos it no longer lists. */
    private fun deleteDroppedPhotos(saved: Project) {
        for (name in removals.settle(saved)) File(store.photosDir(saved.id), name).delete()
    }

    // ---------------- editing ----------------

    fun movePhoto(from: Int, to: Int) = update { p ->
        if (from !in p.photos.indices || to !in p.photos.indices) p
        else p.copy(photos = p.photos.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun removePhoto(photoId: String) {
        val p = open ?: return
        val photo = p.photos.firstOrNull { it.id == photoId } ?: return
        // Registered before the edit so a save that races it cannot see the removal unannounced.
        // The file goes only after the save that drops it has succeeded (see persistOpen).
        removals.add(p.id, photoId, photo.fileName)
        update { it.copy(photos = it.photos.filterNot { x -> x.id == photoId }) }
    }

    fun setTransition(t: TransitionType) = update { it.copy(transition = t) }
    fun setTransitionMs(ms: Long) = update { it.copy(transitionDurationMs = ms) }

    /**
     * A framing rect's pixel aspect must equal the output aspect, so every rect is converted to the
     * new aspect. Hand-tuned framing is kept: same center and zoom level, clamped into the image.
     */
    fun setAspect(a: AspectRatio) = update { p ->
        if (p.aspect == a) p
        else p.copy(aspect = a, photos = p.photos.map { ImportLogic.convertAspect(it, p.aspect, a) })
    }

    /** Applies [transform] to one photo of the open project (editor changes; autosaved). */
    fun updatePhoto(photoId: String, transform: (PhotoItem) -> PhotoItem) = update { p ->
        p.copy(photos = p.photos.map { if (it.id == photoId) transform(it) else it })
    }

    /** New face-aware random framing for one photo. Its duration and easing are kept. */
    fun rerandomize(photoId: String) {
        val rnd = FramingRandomizer(System.nanoTime())
        val aspect = open?.aspect ?: return
        updatePhoto(photoId) {
            val f = rnd.next(it.imageWidth, it.imageHeight, aspect, it.faces)
            it.copy(startRect = f.start, endRect = f.end)
        }
    }

    // ---------------- import ----------------

    /** Runs in process scope; [importing] is always reset in finally. */
    fun importPhotos(uris: List<Uri>) {
        val project = open ?: return
        if (importing || uris.isEmpty()) return
        importDetails = null
        importing = true
        importingProjectId = project.id
        importStatus = "Importing 0 of ${uris.size}"
        if (exportProjectId == project.id) exporter.reset()
        scope.launch {
            try {
                val result = importer.import(project.id, uris, project.aspect) { done, total ->
                    importStatus = "Importing $done of $total"
                }
                addPhotos(project.id, result.items)
                importStatus = if (result.failedUris.isNotEmpty()) {
                    "${result.failedUris.size} photo(s) could not be read and were skipped"
                } else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                importStatus = "Import failed: ${Exporter.describe(e)}"
                importDetails = ErrorLog.record(appContext, "import", e)
            } finally {
                importing = false
                importingProjectId = null
            }
        }
    }

    // ---------------- recovery ----------------

    /**
     * Counts photo files [id] does not list (photos/, .trash/, project.json.bak) into [recoverable].
     * Runs after pending saves; skipped while the project is importing, since an import's files
     * are unlisted until it finishes.
     */
    private fun checkRecoverable(id: String) {
        scope.launch {
            try {
                val n = withContext(Dispatchers.IO) {
                    saveMutex.withLock {
                        if (importing && importingProjectId == id) return@withLock null
                        // The open copy is what the user sees; its unsaved additions are not strays.
                        val current = open?.takeIf { it.id == id } ?: store.load(id)
                        store.recoveryPlan(current, pendingDeleteNames(id)).count
                    }
                } ?: return@launch
                if (n > 0) recoverable[id] = n else recoverable.remove(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recoverable.remove(id)
            }
        }
    }

    private fun pendingDeleteNames(id: String): Set<String> = removals.fileNames(id)

    /**
     * Puts unlisted photo files back into project [id]. Photos still in project.json.bak keep their
     * framing; other files get fresh face-aware framing as on import. Trashed files are copied
     * back, never moved, and nothing is deleted. Uses the import lock and status line.
     */
    fun recoverPhotos(id: String) {
        if (importing || isBusy(id)) return
        importDetails = null
        importing = true
        importingProjectId = id
        importStatus = "Recovering photos"
        if (exportProjectId == id) exporter.reset()
        scope.launch {
            try {
                // Unsaved edits of the open project go to disk first, so recovery builds on them.
                if (open?.id == id) { saveJob?.cancel(); open?.let { persistOpen(it) } }
                val (merged, added) = withContext(Dispatchers.IO) {
                    val (before, backup, plan) = saveMutex.withLock {
                        val before = store.load(id)
                        Triple(before, store.loadBackup(id), store.recoveryPlan(before, pendingDeleteNames(id)))
                    }
                    store.copyFromTrash(id, plan.fromTrash)
                    val used = HashSet<String>().apply {
                        before.photos.mapTo(this) { it.id }; plan.fromBackup.mapTo(this) { it.id }
                    }
                    val strays = plan.strays.map { name ->
                        val pid = PhotoRecovery.idForStray(name, used) { java.util.UUID.randomUUID().toString() }
                        used += pid
                        pid to File(store.photosDir(id), name)
                    }
                    // Face detection is slow; it runs outside the save lock.
                    val adopted = importer.adopt(strays, before.aspect)
                    saveMutex.withLock {
                        val current = store.load(id)
                        val out = PhotoRecovery.apply(current, backup, plan.fromBackup, adopted)
                        val added = out.photos.size - current.photos.size
                        val saved = if (added > 0) out.copy(modifiedAt = System.currentTimeMillis()).also { store.save(it) } else current
                        saved to added
                    }
                }
                if (open?.id == id) open = merged
                syncListEntry(merged)
                importStatus = if (added > 0) "Recovered $added ${if (added == 1) "photo" else "photos"}" else "No photos could be recovered"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                importStatus = "Recovery failed: ${Exporter.describe(e)}"
                importDetails = ErrorLog.record(appContext, "recover photos", e)
            } finally {
                importing = false
                importingProjectId = null
                checkRecoverable(id)
            }
        }
    }

    private suspend fun addPhotos(projectId: String, items: List<PhotoItem>) {
        if (open?.id == projectId) {
            update { it.copy(photos = it.photos + items) }
        } else {
            withContext(Dispatchers.IO) {
                saveMutex.withLock {
                    val p = store.load(projectId)
                    store.save(p.copy(photos = p.photos + items, modifiedAt = System.currentTimeMillis()))
                }
            }
            // The user may have opened this project while the import ran; that copy was loaded
            // before these photos hit disk and would be refused at its next save.
            open?.takeIf { it.id == projectId }?.let { o ->
                val merged = PhotoMerge.appendMissing(o, items)
                if (merged !== o) open = merged
            }
            refreshList()
        }
    }

    // ---------------- export ----------------

    fun startExport() {
        val p = open ?: return
        if (p.photos.isEmpty() || importing || exporterBusy()) return
        flush()
        exportProjectId = p.id
        exporter.start(p, store.photosDir(p.id))
    }
}
