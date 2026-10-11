package com.geovisual.kenburns

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.geovisual.kenburns.export.ExportNotifications
import com.geovisual.kenburns.export.Exporter
import com.geovisual.kenburns.ui.App
import com.geovisual.kenburns.ui.AppModel
import com.geovisual.kenburns.ui.KenBurnsTheme

/** Navigation asked for from outside the UI (an export notification tap). Consumed by App(). */
object LaunchRequests {
    var openProjectId by mutableStateOf<String?>(null)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppModel.init(this)
        // A recreated activity gets its original intent again; only act on a fresh launch.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            KenBurnsTheme { App() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val id = intent?.getStringExtra(ExportNotifications.EXTRA_PROJECT_ID) ?: return
        intent.removeExtra(ExportNotifications.EXTRA_PROJECT_ID)
        LaunchRequests.openProjectId = id
    }

    override fun onResume() {
        super.onResume()
        Exporter.retryServiceIfUnprotected()
    }

    override fun onStop() {
        super.onStop()
        AppModel.flush()
    }
}
