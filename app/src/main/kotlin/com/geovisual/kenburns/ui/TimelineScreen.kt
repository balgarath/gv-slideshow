@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.geovisual.kenburns.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.geovisual.kenburns.core.export.ExportEstimate
import com.geovisual.kenburns.core.model.AspectRatio
import com.geovisual.kenburns.core.model.Project
import com.geovisual.kenburns.core.model.TransitionType
import com.geovisual.kenburns.core.timeline.Timeline
import com.geovisual.kenburns.export.ExportState
import com.geovisual.kenburns.export.MediaStorePublisher
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/** Project home: settings, reorderable photo grid, add photos and export. */
@Composable
fun TimelineScreen(project: Project, onEdit: (String) -> Unit, onPreview: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val exportState by AppModel.exporter.state.collectAsState()
    val exportsThis = AppModel.exportProjectId == project.id
    val exporting = AppModel.exporterBusy()
    val importing = AppModel.importing
    val locked = importing || (exporting && exportsThis)
    val photosDir = remember(project.id) { AppModel.photosDir(project.id) }
    var openError by remember { mutableStateOf<String?>(null) }
    val startExport = rememberExportStarter()

    BackHandler { onBack() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) AppModel.importPhotos(uris)
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackButton("Projects", onBack)
        }
        Text(project.name, color = Teal, style = MaterialTheme.typography.headlineSmall, maxLines = 1)

        // Settings and export controls scroll on their own so the grid always keeps room.
        val maxHeader = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeader).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (project.photos.isNotEmpty()) {
                val totalMs = Timeline(project).totalMs
                Text(
                    "${project.photos.size} photos, ${project.aspect.w} x ${project.aspect.h} at 60 fps",
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Length ${ExportEstimate.formatLength(totalMs)}, ${ExportEstimate.formatSize(ExportEstimate.sizeBytes(project.aspect, totalMs))}",
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            SectionLabel("Format")
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (a in AspectRatio.entries) {
                    ChoiceChip(aspectLabel(a), project.aspect == a, enabled = !locked) { AppModel.setAspect(a) }
                }
            }
            SectionLabel("Transition")
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in TransitionType.entries) {
                    ChoiceChip(transitionLabel(t), project.transition == t, enabled = !locked) { AppModel.setTransition(t) }
                }
            }
            if (project.transition != TransitionType.CUT) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Length ${formatSeconds(project.transitionDurationMs)}", color = MaterialTheme.colorScheme.onBackground)
                    Slider(
                        value = project.transitionDurationMs.toFloat(),
                        onValueChange = { AppModel.setTransitionMs((it / 100f).toLong() * 100L) },
                        valueRange = 200f..2000f,
                        enabled = !locked,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Teal),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !locked, onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(if (project.photos.isEmpty()) "Pick photos" else "Add photos") }
                if (project.photos.isNotEmpty()) {
                    Button(enabled = !importing, onClick = onPreview) {
                        TriangleMarker(Color.White)
                        Text("Preview video", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            val recoverable = AppModel.recoverable[project.id] ?: 0
            if (recoverable > 0 && !locked) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${photoCount(recoverable)} in this project's storage ${if (recoverable == 1) "is" else "are"} missing from the timeline.",
                        color = Teal, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { AppModel.recoverPhotos(project.id) }) { Text("Recover ${photoCount(recoverable)}") }
                }
            }
            AppModel.importStatus?.let { Text(it, color = Muted) }
            AppModel.importDetails?.let { CopyDetailsButton(it) }
            AppModel.saveError?.let { Text(it, color = ErrorColor) }

            val otherBusy = exporting && !exportsThis
            ExportPanel(
                state = if (exportsThis) exportState else ExportState.Idle,
                canExport = project.photos.isNotEmpty() && !importing && !otherBusy,
                onExport = { openError = null; startExport() },
                onCancel = { AppModel.exporter.cancel() },
                onOpen = { uri ->
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "video/mp4")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        openError = "No app found to play the video. It is in ${MediaStorePublisher.RELATIVE_DIR}."
                    }
                },
            )
            if (otherBusy) Text("Another project is exporting. Wait for it to finish.", color = Muted)
            val serviceProblem by AppModel.exporter.serviceProblem.collectAsState()
            if (exportsThis && exporting) serviceProblem?.let {
                Text("Background export refused ($it). Keep the app open until it finishes. Details: Projects, menu, Diagnostics.", color = ErrorColor, style = MaterialTheme.typography.bodySmall)
            }
            openError?.let { Text(it, color = ErrorColor) }
        }

        if (project.photos.isEmpty()) {
            EmptyState("No photos yet", "Tap Pick photos to add some.", Modifier.weight(1f))
        } else {
            Text("Press and hold a photo, then drag to reorder. Tap to edit.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            PhotoGrid(project, photosDir, locked, Modifier.weight(1f), onEdit)
        }
    }
}

@Composable
private fun PhotoGrid(project: Project, photosDir: java.io.File, locked: Boolean, modifier: Modifier, onTap: (String) -> Unit) {
    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        AppModel.movePhoto(from.index, to.index)
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (project.aspect == AspectRatio.H16x9) 150.dp else 100.dp),
        state = gridState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(project.photos, key = { _, p -> p.id }) { index, photo ->
            ReorderableItem(reorderState, key = photo.id) { isDragging ->
                val accent = Accents[index % Accents.size]
                Box(
                    Modifier
                        .aspectRatio(project.aspect.ratio.toFloat())
                        .shadow(if (isDragging) 8.dp else 0.dp, RoundedCornerShape(10.dp))
                        .alpha(if (isDragging) 0.9f else 1f)
                        .border(BorderStroke(if (isDragging) 2.dp else 1.dp, if (isDragging) Teal else accent.copy(alpha = 0.5f)), RoundedCornerShape(10.dp))
                        .clickable(enabled = !locked) { onTap(photo.id) }
                        .then(
                            if (locked) Modifier else with(this@ReorderableItem) {
                                Modifier.longPressDraggableHandle()
                            },
                        ),
                ) {
                    Thumbnail(photosDir, photo, Modifier.fillMaxSize())
                    Row(
                        Modifier.align(Alignment.BottomStart).padding(6.dp)
                            .background(Background.copy(alpha = 0.7f), RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        TriangleUp(accent, 9.dp)
                        Text("${index + 1}  ${formatSeconds(photo.durationMs)}", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.labelSmall)
                    }
                    val override = photo.transitionOverride
                    if (override != null && index < project.photos.lastIndex) {
                        Row(
                            Modifier.align(Alignment.BottomEnd).padding(6.dp)
                                .background(Background.copy(alpha = 0.7f), RoundedCornerShape(50))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TriangleMarker(Teal)
                            Text(transitionShort(override), color = Teal, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
