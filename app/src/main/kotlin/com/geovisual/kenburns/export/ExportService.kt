package com.geovisual.kenburns.export

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.ServiceCompat
import com.geovisual.kenburns.io.ErrorLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and CPU) alive while [Exporter] works, and mirrors
 * [Exporter.state] in a notification with a progress bar and Cancel. It does not own the export:
 * the engine runs in the process either way, so if this service cannot start the export still
 * runs, just without background protection.
 *
 * Type: mediaProcessing on API 35+ (added in Android 15 for transcoding work), falling back to
 * dataSync if refused; dataSync on 33-34, where mediaProcessing does not exist. Both carry a 6 h per 24 h budget on Android 15+; on
 * timeout [onTimeout] stops the export.
 */
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastShown: ExportState? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Exporter.init(this)
        ExportNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            Exporter.cancel()
            if (watchJob?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        // Started with plain startService (see start()), so there is no startForeground contract.
        // If foreground is refused the export keeps running in-process and the service just ends;
        // MainActivity.onResume asks again the next time the app is in front.
        val refusal = goForeground()
        if (refusal != null) {
            Exporter.reportServiceProblem(refusal)
            Toast.makeText(this, "Background export refused: $refusal. Keep the app open.", Toast.LENGTH_LONG).show()
            if (watchJob?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        Exporter.reportServiceRunning()
        watch()
        return START_NOT_STICKY
    }

    /**
     * Enters the foreground. Null on success, else "ExceptionClass: message" of the last refusal.
     * Since Android 15, for the time-limited types (dataSync, mediaProcessing) the platform
     * re-checks at this call whether the app may start a foreground service right now (visible,
     * or within a few seconds of leaving), whichever start API was used. mediaProcessing is tried
     * first on API 35+, then dataSync, which has its own 6 h budget.
     */
    private fun goForeground(): String? {
        val state = Exporter.state.value
        val notification = try {
            ExportNotifications.progress(this, state)
        } catch (e: Exception) {
            // Not an FGS refusal: report it as what it is, then try a bare notification.
            Log.e(Exporter.TAG, "progress notification build failed", e)
            ErrorLog.record(this, "export service: building the progress notification failed\n${diagnostics(0)}", e)
            try {
                ExportNotifications.minimal(this)
            } catch (e2: Exception) {
                ErrorLog.record(this, "export service: minimal notification failed too", e2)
                return Exporter.describe(e)
            }
        }
        val types = if (Build.VERSION.SDK_INT >= 35) {
            listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
        var last: Exception? = null
        for (type in types) {
            try {
                // Platform call, not ServiceCompat.startForeground: androidx.core (1.18/1.19) masks
                // the type with the Android 14 set (0x40000FFF), which turns mediaProcessing
                // (0x2000) into 0, and Android refuses "type none" for targetSdk 34+.
                startForeground(ExportNotifications.ID_PROGRESS, notification, type)
                lastShown = state
                if (last != null) Log.w(Exporter.TAG, "foreground as ${typeName(type)} after a refusal")
                return null
            } catch (e: Exception) {
                // E.g. ForegroundServiceStartNotAllowedException (not allowed now, or the type's
                // time budget is used up), InvalidForegroundServiceTypeException, SecurityException.
                Log.e(Exporter.TAG, "startForeground(${typeName(type)}) refused", e)
                ErrorLog.record(this, "export service: startForeground(${typeName(type)}) refused\n${diagnostics(type)}", e)
                last = e
            }
        }
        return last?.let { "${it.javaClass.simpleName}: ${it.message ?: "no message"}" }
    }

    /** App state at the moment of a refusal, for errors.log. */
    private fun diagnostics(type: Int): String {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        val sinceRequest = Exporter.serviceRequestedAtMs.let { if (it > 0) SystemClock.elapsedRealtime() - it else -1 }
        return "  sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} device=${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "  type=${typeName(type)} importance=${info.importance} (100 foreground, 125 fg service, 200 visible, 300+ background)\n" +
            "  msSinceStartService=$sinceRequest state=${Exporter.state.value.javaClass.simpleName}"
    }

    private fun typeName(type: Int): String = when (type) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING -> "mediaProcessing"
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC -> "dataSync"
        0 -> "n/a"
        else -> "0x${Integer.toHexString(type)}"
    }

    private fun watch() {
        if (watchJob?.isActive == true) return
        acquireWakeLock()
        watchJob = scope.launch {
            val updates = launch {
                Exporter.state.collect { s -> if (s.inProgress) updateProgress(s) }
            }
            Exporter.state.first { !it.inProgress }
            updates.cancel()
            stopEverything() // the result notification is posted by Exporter
        }
    }

    private fun updateProgress(s: ExportState) {
        if (s == lastShown) return
        lastShown = s
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            getSystemService(NotificationManager::class.java)
                .notify(ExportNotifications.ID_PROGRESS, ExportNotifications.progress(this, s))
        } catch (e: Exception) {
            Log.w(Exporter.TAG, "progress notification update failed", e)
        }
    }

    private fun stopEverything() {
        Exporter.reportServiceStopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        lastShown = null
        stopSelf(lastStartId)
    }

    /** Android 15+: the mediaProcessing / dataSync budget ran out. Must stop within seconds. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(Exporter.TAG, "foreground service timeout (type $fgsType)")
        // Encoding: cancel() fails the export and Exporter posts the notification. Saving: cancel()
        // is a no-op and the short publish finishes in-process; Exporter posts its result too.
        Exporter.cancel("Android stopped the export because it ran too long.")
        watchJob?.cancel()
        stopEverything()
    }

    override fun onDestroy() {
        Exporter.reportServiceStopped()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    /** A foreground service alone does not stop the CPU from sleeping with the screen off. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GVSlideshow:export")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MAX_MS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val ACTION_START = "com.geovisual.kenburns.action.EXPORT_START"
        private const val ACTION_CANCEL = "com.geovisual.kenburns.action.EXPORT_CANCEL"
        private const val WAKE_LOCK_MAX_MS = 3 * 60 * 60 * 1000L

        /**
         * Called by [Exporter] once an export is in progress, from the foreground UI, and again
         * from MainActivity.onResume while an export runs without protection. Never throws.
         *
         * Plain startService, not startForegroundService. AOSP (ActiveServices, checked 2026-10-10)
         * re-evaluates "may this app start a foreground service now" inside startForeground for the
         * time-limited types on Android 15+, whichever start API was used, so startForegroundService
         * would not make startForeground more likely to succeed. It would add a contract: a service
         * brought down while still owing startForeground crashes the app.
         */
        fun start(context: Context) {
            Exporter.serviceRequestedAtMs = SystemClock.elapsedRealtime()
            try {
                context.startService(Intent(context, ExportService::class.java).setAction(ACTION_START))
            } catch (e: Exception) {
                Log.e(Exporter.TAG, "export service did not start; export continues in-process", e)
                ErrorLog.record(context, "export service: startService refused", e)
                Exporter.reportServiceProblem("${e.javaClass.simpleName}: ${e.message ?: "no message"}")
            }
        }

        fun cancelIntent(context: Context): PendingIntent = PendingIntent.getService(
            context, 3,
            Intent(context, ExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
