package com.geovisual.kenburns.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.geovisual.kenburns.io.ErrorLog
import com.geovisual.kenburns.core.model.AspectRatio
import com.geovisual.kenburns.core.model.Project
import java.text.DateFormat
import java.util.Date

/** Launch screen: every saved project, newest edit first. */
@Composable
fun ProjectListScreen(onOpen: (Project) -> Unit) {
    val projects = AppModel.projects
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var overflow by remember { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TriangleUp(Teal, 18.dp)
                Text("GV-Slideshow", color = Teal, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { overflow = true }) {
                        Text("\u22EE", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleLarge)
                    }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        DropdownMenuItem(text = { Text("Diagnostics") }, onClick = { overflow = false; showDiagnostics = true })
                    }
                }
            }
            notice?.let { Text(it, color = ErrorColor) }

            if (projects.isEmpty() && AppModel.listLoaded) {
                EmptyState("No videos yet.", "Tap New video to start.", Modifier.weight(1f))
            } else {
                Text("Press and hold a project to rename or delete it.", color = Muted, style = MaterialTheme.typography.bodySmall)
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
                ) {
                    items(projects, key = { it.id }) { p ->
                        ProjectCard(
                            project = p,
                            accent = Accents[projects.indexOf(p).coerceAtLeast(0) % Accents.size],
                            onOpen = { notice = null; onOpen(p) },
                            onRename = { renameId = p.id },
                            recoverable = AppModel.recoverable[p.id] ?: 0,
                            onRecover = {
                                if (AppModel.importing || AppModel.isBusy(p.id)) notice = "Wait for the current import or export to finish, then try again."
                                else { notice = null; AppModel.recoverPhotos(p.id) }
                            },
                            onDelete = {
                                if (AppModel.isBusy(p.id)) notice = "\"${p.name}\" is importing or exporting. Wait for it to finish."
                                else deleteId = p.id
                            },
                        )
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { showCreate = true },
            containerColor = Purple,
            contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Text("New video")
        }
    }

    if (showDiagnostics) DiagnosticsDialog(onDismiss = { showDiagnostics = false })

    if (showCreate) {
        NameDialog(
            title = "New video",
            confirmLabel = "Create",
            initialName = "",
            showAspect = true,
            onDismiss = { showCreate = false },
            onConfirm = { name, aspect ->
                showCreate = false
                onOpen(AppModel.createProject(name, aspect))
            },
        )
    }
    renameId?.let { id ->
        val p = projects.firstOrNull { it.id == id }
        if (p == null) renameId = null else NameDialog(
            title = "Rename",
            confirmLabel = "Save",
            initialName = p.name,
            showAspect = false,
            onDismiss = { renameId = null },
            onConfirm = { name, _ -> renameId = null; AppModel.renameProject(id, name) },
        )
    }
    deleteId?.let { id ->
        val p = projects.firstOrNull { it.id == id }
        if (p == null) deleteId = null else AlertDialog(
            onDismissRequest = { deleteId = null },
            containerColor = Surface,
            title = { Text("Delete \"${p.name}\"?", color = MaterialTheme.colorScheme.onBackground) },
            text = { Text("This removes the project and its ${p.photos.size} photo copies from the app. Your originals and exported videos stay.", color = Muted) },
            confirmButton = {
                Button(onClick = {
                    deleteId = null
                    if (!AppModel.deleteProject(id)) notice = "\"${p.name}\" is busy and was not deleted."
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel", color = MaterialTheme.colorScheme.onBackground) } },
        )
    }
}

/** Shows filesDir/errors.log with Copy and Clear, so failures can be sent without adb. */
@Composable
private fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var log by remember { mutableStateOf(ErrorLog.read(context)) }
    var copied by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // Newest entries are at the bottom; start there.
    LaunchedEffect(log, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Diagnostics", color = Teal) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (log.isBlank()) "No errors recorded." else "errors.log, newest at the bottom." + if (copied) " Copied." else "",
                    color = Muted, style = MaterialTheme.typography.bodySmall,
                )
                if (log.isNotBlank()) {
                    Text(
                        log,
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(scroll),
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = log.isNotBlank(), onClick = { clipboard.setText(AnnotatedString(log)); copied = true }) { Text("Copy", color = Teal) }
                TextButton(enabled = log.isNotBlank(), onClick = { ErrorLog.clear(context); log = ""; copied = false }) { Text("Clear", color = Teal) }
                Button(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: Project, accent: Color, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit,
    recoverable: Int, onRecover: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .background(Surface, RoundedCornerShape(14.dp))
                .border(BorderStroke(1.dp, accent.copy(alpha = 0.35f)), RoundedCornerShape(14.dp))
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val first = project.photos.firstOrNull()
            if (first != null) {
                Thumbnail(AppModel.photosDir(project.id), first, Modifier.size(64.dp))
            } else {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { TriangleUp(accent, 28.dp) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(project.name, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    "${project.photos.size} ${if (project.photos.size == 1) "photo" else "photos"}  |  " +
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(project.modifiedAt)),
                    color = Muted, style = MaterialTheme.typography.bodySmall,
                )
                if (AppModel.importing && AppModel.importingProjectId == project.id) {
                    Text(AppModel.importStatus ?: "Working", color = Teal, style = MaterialTheme.typography.bodySmall)
                } else if (recoverable > 0) {
                    Text("${photoCount(recoverable)} can be recovered. Press and hold.", color = Teal, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                aspectShort(project.aspect), color = accent, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.border(BorderStroke(1.dp, accent), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (recoverable > 0) {
                DropdownMenuItem(text = { Text("Recover ${photoCount(recoverable)}") }, onClick = { menu = false; onRecover() })
            }
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    showAspect: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, AspectRatio) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var aspect by rememberSaveable { mutableStateOf(AspectRatio.V9x16) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(title, color = MaterialTheme.colorScheme.onBackground) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Teal, focusedLabelColor = Teal, cursorColor = Teal,
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )
                if (showAspect) {
                    SectionLabel("Format")
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (a in AspectRatio.entries) {
                            ChoiceChip(aspectLabel(a), aspect == a, enabled = true) { aspect = a }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(name, aspect) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onBackground) } },
    )
}

internal fun photoCount(n: Int) = "$n ${if (n == 1) "photo" else "photos"}"
