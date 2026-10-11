package com.geovisual.kenburns.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.geovisual.kenburns.export.ExportNotifications

/**
 * Returns the Export button action. Before the first export it asks for the notification
 * permission (progress and "Video saved" live there); the export starts whatever the answer.
 */
@Composable
fun rememberExportStarter(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        AppModel.startExport()
    }
    return remember(context, launcher) {
        {
            if (ExportNotifications.shouldAskPermission(context)) {
                ExportNotifications.markPermissionAsked(context)
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                AppModel.startExport()
            }
        }
    }
}
