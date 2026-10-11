package com.geovisual.kenburns.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.geovisual.kenburns.core.export.ExportEstimate
import com.geovisual.kenburns.core.model.AspectRatio
import com.geovisual.kenburns.core.model.PhotoItem
import com.geovisual.kenburns.core.model.TransitionType
import com.geovisual.kenburns.export.ExportState
import com.geovisual.kenburns.export.MediaStorePublisher
import com.geovisual.kenburns.ui.components.GlowButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

val ErrorColor = Color(0xFFFF8A65)
val Muted = Color(0xFF9A9AA6)

@Composable
fun ExportPanel(
    state: ExportState,
    canExport: Boolean,
    onExport: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (android.net.Uri) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            is ExportState.Running -> {
                val pct = state.percent
                // The button outline is the progress indicator; null percent keeps the travelling band.
                GlowButton(
                    text = if (pct != null) "Exporting $pct%" else "Starting export",
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    progress = pct?.let { it / 100f },
                    busy = pct == null,
                )
                Text("You can leave the app. Progress also shows in the notification.", color = Muted)
                Button(onClick = onCancel) { Text("Cancel") }
            }
            ExportState.Saving -> {
                GlowButton(text = "Saving to ${MediaStorePublisher.RELATIVE_DIR}", onClick = {}, modifier = Modifier.fillMaxWidth(), progress = 1f)
            }
            is ExportState.Done -> {
                Text("Saved. ${ExportEstimate.formatActualSize(state.sizeBytes)}", color = Teal)
                Text("${state.fileName} in ${MediaStorePublisher.RELATIVE_DIR}", color = Muted)
                state.warning?.let { Text(it, color = ErrorColor) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpen(state.uri) }) { Text("Open video") }
                    GlowButton(text = "Export again", onClick = onExport, enabled = canExport)
                }
            }
            is ExportState.Failed -> {
                SelectionContainer { Text(state.message, color = ErrorColor) }
                state.details?.let { CopyDetailsButton(it) }
                if (state.keptVideo) Text("The video itself was made. Try again only saves it.", color = Muted)
                GlowButton(text = "Try again", onClick = onExport, modifier = Modifier.fillMaxWidth(), enabled = canExport)
            }
            ExportState.Cancelled -> {
                Text("Export cancelled", color = Muted)
                GlowButton(text = "Export", onClick = onExport, modifier = Modifier.fillMaxWidth(), enabled = canExport)
            }
            ExportState.Idle -> {
                GlowButton(text = "Export", onClick = onExport, modifier = Modifier.fillMaxWidth(), enabled = canExport)
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, color = Teal, style = MaterialTheme.typography.titleSmall)
}

/** Selectable chip; the selected one gets a teal outline, teal label and a triangle marker. */
@Composable
fun ChoiceChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { TriangleMarker(Teal) }
        } else null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.background,
            labelColor = MaterialTheme.colorScheme.onBackground,
            selectedContainerColor = Teal.copy(alpha = 0.12f),
            selectedLabelColor = Teal,
        ),
        border = BorderStroke(1.dp, if (selected) Teal else Muted.copy(alpha = 0.5f)),
    )
}

@Composable
fun TriangleMarker(color: Color) {
    Canvas(Modifier.size(10.dp)) {
        val p = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, size.height / 2f)
            lineTo(0f, size.height)
            close()
        }
        drawPath(p, color)
    }
}

fun aspectLabel(a: AspectRatio) = when (a) {
    AspectRatio.V9x16 -> "9:16 tall"
    AspectRatio.H16x9 -> "16:9 wide"
    AspectRatio.S1x1 -> "1:1 square"
}

fun transitionLabel(t: TransitionType) = when (t) {
    TransitionType.CUT -> "Cut"
    TransitionType.CROSSFADE -> "Crossfade"
    TransitionType.DIP_TO_BLACK -> "Dip to black"
    TransitionType.SLIDE -> "Slide"
}

/** Short label for the marker on a timeline thumbnail whose transition differs from the default. */
fun transitionShort(t: TransitionType) = when (t) {
    TransitionType.CUT -> "Cut"
    TransitionType.CROSSFADE -> "Fade"
    TransitionType.DIP_TO_BLACK -> "Dip"
    TransitionType.SLIDE -> "Slide"
}

fun formatSeconds(ms: Long) = String.format(Locale.US, "%.1f s", ms / 1000.0)

@Composable
fun Thumbnail(dir: File, photo: PhotoItem, modifier: Modifier = Modifier) {
    val bmp by produceState<ImageBitmap?>(null, photo.id) {
        value = withContext(Dispatchers.IO) { decodeThumb(File(dir, photo.fileName)) }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Surface)) {
        bmp?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun decodeThumb(file: File): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 360 && bounds.outHeight / (sample * 2) >= 240) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val b: Bitmap = BitmapFactory.decodeFile(file.path, opts) ?: return null
    return b.asImageBitmap()
}

/** Back button with a left-pointing triangle, used at the top of every sub screen. */
@Composable
fun BackButton(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Canvas(Modifier.size(width = 8.dp, height = 12.dp)) {
            drawPath(
                Path().apply { moveTo(size.width, 0f); lineTo(0f, size.height / 2f); lineTo(size.width, size.height); close() },
                Teal,
            )
        }
        Text(label, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Centered empty state: triangle graphic, a title and one line of direction. */
@Composable
fun EmptyState(title: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TriangleCluster()
        Text(title, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(hint, color = Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Small filled triangle pointing up, used as a bullet and for empty states. */
@Composable
fun TriangleUp(color: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val p = Path().apply {
            moveTo(this@Canvas.size.width / 2f, 0f)
            lineTo(this@Canvas.size.width, this@Canvas.size.height)
            lineTo(0f, this@Canvas.size.height)
            close()
        }
        drawPath(p, color)
    }
}

/** Three overlapping triangles in the accent colors; empty-state graphic. */
@Composable
fun TriangleCluster(modifier: Modifier = Modifier) {
    Canvas(modifier.size(96.dp)) {
        fun tri(cx: Float, top: Float, w: Float, h: Float, c: Color) {
            val p = Path().apply {
                moveTo(cx, top); lineTo(cx + w / 2, top + h); lineTo(cx - w / 2, top + h); close()
            }
            drawPath(p, c.copy(alpha = 0.85f))
        }
        tri(size.width * 0.50f, 0f, size.width * 0.55f, size.height * 0.62f, Teal)
        tri(size.width * 0.28f, size.height * 0.38f, size.width * 0.50f, size.height * 0.58f, Orange)
        tri(size.width * 0.72f, size.height * 0.38f, size.width * 0.50f, size.height * 0.58f, Pink)
    }
}

fun aspectShort(a: AspectRatio) = when (a) {
    AspectRatio.V9x16 -> "9:16"
    AspectRatio.H16x9 -> "16:9"
    AspectRatio.S1x1 -> "1:1"
}

/** Copies [details] (a full stack trace) to the clipboard. */
@Composable
fun CopyDetailsButton(details: String) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    androidx.compose.material3.TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(details)) }) {
        Text("Copy details", color = Teal)
    }
}
