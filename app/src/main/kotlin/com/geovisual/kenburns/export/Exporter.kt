package com.geovisual.kenburns.export

import android.content.Context
import android.media.MediaCodecInfo
import android.net.Uri
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.geovisual.kenburns.core.export.ExportEstimate
import com.geovisual.kenburns.core.model.Project
import com.geovisual.kenburns.io.ErrorLog
import com.geovisual.kenburns.render.CompositionBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ExportState {
    data object Idle : ExportState
    /** [percent] is null while Transformer cannot estimate progress yet. */
    data class Running(val percent: Int?) : ExportState
    data object Saving : ExportState
    data class Done(val uri: Uri, val fileName: String, val warning: String? = null, val sizeBytes: Long = 0L) : ExportState
    /** [keptVideo]: the encode succeeded and only saving failed; "Try again" saves without re-encoding. */
    data class Failed(val message: String, val details: String? = null, val keptVideo: Boolean = false) : ExportState
    data object Cancelled : ExportState
}

val ExportState.inProgress: Boolean get() = this is ExportState.Running || this is ExportState.Saving

/**
 * Process-scoped export engine (design section 5). Transformer runs on the main looper and writes
 * to cacheDir; on success the file is copied into MediaStore [MediaStorePublisher.RELATIVE_DIR]. [ExportService]
 * keeps the process in the foreground (and the CPU awake) while [state] is in progress and mirrors
 * it in a notification; the UI collects the same [state], so it survives activity death.
 */
@OptIn(UnstableApi::class)
object Exporter {
    const val TAG = "KenBurnsExport"
    private const val POLL_MS = 500L

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    /** Project of the running or most recent export. */
    @Volatile var projectId: String? = null
        private set
    var projectName: String = ""
        private set

    private var transformer: Transformer? = null
    private var pollJob: Job? = null
    @Volatile private var tempFile: File? = null

    /** An encoded video whose MediaStore publish failed, kept for a publish-only retry. */
    private class Kept(val projectId: String, val file: File, val warning: String?)
    @Volatile private var kept: Kept? = null

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    val isBusy: Boolean get() = _state.value.inProgress

    /**
     * Why the foreground service could not protect the running export ("ExceptionClass: message"),
     * or null. Shown under the Export button; the full trace is in errors.log (Diagnostics).
     */
    private val _serviceProblem = MutableStateFlow<String?>(null)
    val serviceProblem: StateFlow<String?> = _serviceProblem.asStateFlow()

    /** True while [ExportService] is in the foreground. */
    @Volatile var serviceForeground = false
        private set

    /** elapsedRealtime of the last ExportService.start, for the refusal diagnostics. */
    @Volatile var serviceRequestedAtMs = 0L

    fun reportServiceProblem(reason: String) {
        _serviceProblem.value = reason
    }

    fun reportServiceRunning() {
        serviceForeground = true
        _serviceProblem.value = null
    }

    fun reportServiceStopped() {
        serviceForeground = false
    }

    /**
     * MainActivity.onResume: if an export runs without the foreground service (it was refused),
     * ask again now that the app is in front, which is when Android allows it.
     */
    @MainThread
    fun retryServiceIfUnprotected() {
        if (!::appContext.isInitialized || !isBusy || serviceForeground) return
        if (_serviceProblem.value == null) return
        Log.i(TAG, "retrying the export service now that the app is in front")
        ExportService.start(appContext)
    }

    /**
     * Starts an export of [project] and the foreground service that guards it. If the last
     * export of this project failed only at the save step, the kept video is saved instead.
     */
    @MainThread
    fun start(project: Project, photosDir: File) {
        if (isBusy || transformer != null) return
        projectId = project.id
        projectName = project.name
        _serviceProblem.value = null
        ExportNotifications.clearResult(appContext)
        val k = kept
        if (k != null && k.projectId == project.id && k.file.isFile) {
            kept = null
            Log.i(TAG, "retrying publish of ${k.file.name}")
            publish(k.file, k.warning)
        } else {
            discardKept()
            if (!encode(project, photosDir)) return
        }
        ExportService.start(appContext)
    }

    /** False when the export failed before it started (state is then Failed). */
    private fun encode(project: Project, photosDir: File): Boolean {
        val built = try {
            CompositionBuilder.build(project, photosDir)
        } catch (e: Exception) {
            _state.value = ExportState.Failed("Could not build the video: ${describe(e)}", ErrorLog.record(appContext, "export build", e))
            return false
        }
        val composition = built.composition
        val warning = if (built.skipped > 0) {
            "${built.skipped} photo(s) were missing and left out of the video."
        } else null
        val out = File(appContext.cacheDir, "export-${System.currentTimeMillis()}.mp4")
        tempFile = out
        Log.i(TAG, "export start: ${project.photos.size} photos, ${project.aspect}, " +
            "${project.transition} ${project.transitionDurationMs} ms -> ${out.path}")

        // Explicit H.264 VBR bitrate from the shared table (see ExportEstimate for why).
        val bitrate = ExportEstimate.bitrateBps(project.aspect)
        val encoderFactory = DefaultEncoderFactory.Builder(appContext)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(bitrate)
                    .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    .build(),
            )
            .build()
        lateinit var t: Transformer
        t = Transformer.Builder(appContext)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (transformer !== t) return // cancelled or replaced; the temp file is gone
                    Log.i(TAG, "export done: ${exportResult.width}x${exportResult.height} " +
                        "frames=${exportResult.videoFrameCount} durMs=${exportResult.approximateDurationMs} " +
                        "bytes=${exportResult.fileSizeBytes} avgVideoBitrate=${exportResult.averageVideoBitrate} " +
                        "requested=$bitrate encoder=${exportResult.videoEncoderName}")
                    finishTransformer()
                    publish(out, warning)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    if (transformer !== t) return
                    Log.e(TAG, "export failed", exportException)
                    finishTransformer()
                    deleteTemp(out)
                    finishWith(ExportState.Failed(
                        "Export failed (${exportException.errorCodeName}): ${describe(exportException)}",
                        ErrorLog.record(appContext, "export failed", exportException),
                    ))
                }
            })
            .build()
        transformer = t
        _state.value = ExportState.Running(null)
        try {
            t.start(composition, out.path)
        } catch (e: Exception) {
            Log.e(TAG, "transformer start failed", e)
            finishTransformer()
            deleteTemp(out)
            _state.value = ExportState.Failed("Export could not start: ${describe(e)}", ErrorLog.record(appContext, "export start", e))
            return false
        }
        pollJob = scope.launch {
            val holder = ProgressHolder()
            while (isActive) {
                val tr = transformer ?: break
                val ps = tr.getProgress(holder)
                if (_state.value is ExportState.Running) {
                    _state.value = ExportState.Running(
                        if (ps == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null,
                    )
                }
                delay(POLL_MS)
            }
        }
        return true
    }

    /** Stops a running encode. With [failure] the export ends as Failed (e.g. a system time limit). */
    @MainThread
    fun cancel(failure: String? = null) {
        val t = transformer ?: return
        t.cancel()
        finishTransformer()
        tempFile?.let { deleteTemp(it) }
        if (failure != null) finishWith(ExportState.Failed(failure)) else _state.value = ExportState.Cancelled
        Log.i(TAG, "export cancelled${failure?.let { ": $it" } ?: ""}")
    }

    /** Back to Idle (the project changed). Drops a kept video, which no longer matches. */
    @MainThread
    fun reset() {
        if (transformer != null || isBusy) return
        discardKept()
        _state.value = ExportState.Idle
    }

    /**
     * Launch housekeeping (blocking, call off the main thread): deletes export temp files and
     * pending MediaStore entries left when an earlier process was killed mid-export.
     */
    fun cleanupStale() {
        if (isBusy) return
        val keep = setOfNotNull(tempFile?.path, kept?.file?.path)
        appContext.cacheDir.listFiles { f -> f.name.startsWith("export-") && f.name.endsWith(".mp4") }
            ?.filter { it.path !in keep }
            ?.forEach { it.delete() }
        try {
            val n = MediaStorePublisher.deleteStalePending(appContext)
            if (n > 0) Log.i(TAG, "deleted $n stale pending MediaStore entries")
        } catch (e: Exception) {
            Log.w(TAG, "pending MediaStore cleanup failed", e)
        }
    }

    private fun finishTransformer() {
        pollJob?.cancel()
        pollJob = null
        transformer = null
    }

    private fun deleteTemp(file: File) {
        file.delete()
        if (tempFile == file) tempFile = null
    }

    private fun discardKept() {
        val k = kept ?: return
        kept = null
        k.file.delete()
    }

    /**
     * Ends an export that began in the service's lifetime. The result notification is posted
     * here, not by the service, so it appears even if the service has already stopped (e.g. an
     * FGS timeout during Saving, which cannot abort the publish).
     */
    private fun finishWith(end: ExportState) {
        _state.value = end
        when (end) {
            is ExportState.Done -> ExportNotifications.showDone(appContext, end)
            is ExportState.Failed -> ExportNotifications.showFailed(appContext, end)
            else -> {}
        }
    }

    private fun publish(file: File, warning: String?) {
        _state.value = ExportState.Saving
        val owner = projectId
        scope.launch {
            try {
                val size = file.length()
                val (uri, name) = withContext(Dispatchers.IO) { MediaStorePublisher.publish(appContext, file) }
                withContext(Dispatchers.IO) { file.delete() }
                if (tempFile == file) tempFile = null
                finishWith(ExportState.Done(uri, name, warning, size))
                Log.i(TAG, "published $uri")
            } catch (e: Exception) {
                Log.e(TAG, "publish failed", e)
                if (tempFile == file) tempFile = null
                if (owner != null) kept = Kept(owner, file, warning) else file.delete()
                finishWith(ExportState.Failed(
                    "Video was made but could not be saved to Movies: ${describe(e)}",
                    ErrorLog.record(appContext, "export publish", e),
                    keptVideo = owner != null,
                ))
            }
        }
    }

    fun describe(e: Throwable): String {
        val parts = ArrayList<String>()
        var c: Throwable? = e
        var depth = 0
        while (c != null && depth < 4) {
            parts += "${c.javaClass.simpleName}: ${c.message ?: "no message"}"
            c = c.cause
            depth++
        }
        return parts.joinToString(" / caused by ")
    }
}
