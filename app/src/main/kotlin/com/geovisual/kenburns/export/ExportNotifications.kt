package com.geovisual.kenburns.export

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.geovisual.kenburns.core.export.ExportEstimate
import com.geovisual.kenburns.MainActivity
import com.geovisual.kenburns.R

/** Export notifications: the foreground progress one and the saved / failed result. */
object ExportNotifications {
    const val ID_PROGRESS = 1001
    private const val ID_RESULT = 1002
    private const val CHANNEL_PROGRESS = "export_progress"
    private const val CHANNEL_RESULT = "export_result"

    /** MainActivity extra: open this project's timeline. */
    const val EXTRA_PROJECT_ID = "com.geovisual.kenburns.extra.PROJECT_ID"

    private const val PREFS = "export"
    private const val KEY_ASKED = "notification_permission_asked"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Export progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while a video is exporting"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Export finished", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Video saved or export failed"
            },
        )
    }

    fun progress(context: Context, state: ExportState): Notification {
        val title = Exporter.projectName.ifBlank { "Video" }.let { "Exporting $it" }
        val b = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_export)
            .setContentTitle(title)
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        when (state) {
            is ExportState.Running -> {
                val pct = state.percent
                b.setContentText(if (pct != null) "$pct%" else "Starting")
                    .setProgress(100, pct ?: 0, pct == null)
                    .addAction(0, "Cancel", ExportService.cancelIntent(context))
            }
            ExportState.Saving -> b.setContentText("Saving to ${MediaStorePublisher.RELATIVE_DIR}").setProgress(100, 100, false)
            else -> b.setContentText("Finishing")
        }
        return b.build()
    }

    /** Bare progress notification, used only if [progress] fails to build. */
    fun minimal(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Exporting video")
            .setOngoing(true)
            .setSilent(true)
            .build()

    fun showDone(context: Context, done: ExportState.Done) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(done.uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(
            context, 2, view, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "Saved. ${ExportEstimate.formatActualSize(done.sizeBytes)}\n${done.fileName} in ${MediaStorePublisher.RELATIVE_DIR}" + (done.warning?.let { "\n$it" } ?: "")
        notify(
            context,
            NotificationCompat.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_stat_export)
                .setContentTitle("Video saved")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun showFailed(context: Context, failed: ExportState.Failed) {
        notify(
            context,
            NotificationCompat.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_stat_export)
                .setContentTitle("Export failed")
                .setContentText(failed.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(failed.message))
                .setContentIntent(openAppIntent(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun clearResult(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_RESULT)
    }

    /** True once per install, before the first export: POST_NOTIFICATIONS is a runtime permission on 33+. */
    fun shouldAskPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    fun markPermissionAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
    }

    private fun notify(context: Context, n: Notification) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            context.getSystemService(NotificationManager::class.java).notify(ID_RESULT, n)
        } catch (e: SecurityException) {
            Log.w(Exporter.TAG, "result notification refused", e)
        }
    }

    /** Opens the app on the exporting project's timeline. */
    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        Exporter.projectId?.let { intent.putExtra(EXTRA_PROJECT_ID, it) }
        return PendingIntent.getActivity(
            context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
