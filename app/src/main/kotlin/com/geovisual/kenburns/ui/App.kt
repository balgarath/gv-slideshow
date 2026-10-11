package com.geovisual.kenburns.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.geovisual.kenburns.LaunchRequests

/** Two screens: the project list (launch) and the timeline of one project. */
@Composable
fun App() {
    // Survives rotation and process death. AppModel.open is always (re)loaded from disk by id.
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var previewing by rememberSaveable { mutableStateOf(false) }
    val open = AppModel.open

    LaunchedEffect(openId) {
        val id = openId
        if (id != null && AppModel.open?.id != id && !AppModel.openProject(id)) openId = null
    }

    // An export notification tap asks for its project's timeline.
    val requested = LaunchRequests.openProjectId
    LaunchedEffect(requested) {
        val id = requested ?: return@LaunchedEffect
        LaunchRequests.openProjectId = null
        if (openId == id && AppModel.open?.id == id) return@LaunchedEffect
        if (AppModel.open != null) AppModel.closeProject()
        if (AppModel.openProject(id)) openId = id else if (AppModel.open == null) openId = null
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (openId != null && open != null && open.id == openId) {
            val eid = editId
            if (eid != null && open.photos.any { it.id == eid }) {
                EditorScreen(project = open, photoId = eid, onSelectPhoto = { editId = it }, onBack = { editId = null })
            } else if (previewing && open.photos.isNotEmpty()) {
                PreviewScreen(project = open, onBack = { previewing = false })
            } else {
                TimelineScreen(
                    project = open, onEdit = { editId = it }, onPreview = { previewing = true },
                    onBack = { AppModel.closeProject(); editId = null; previewing = false; openId = null },
                )
            }
        } else if (openId == null) {
            // Only the id is taken from the list; the LaunchedEffect above loads the project from disk.
            ProjectListScreen(onOpen = { p -> openId = p.id })
        }
    }
}
