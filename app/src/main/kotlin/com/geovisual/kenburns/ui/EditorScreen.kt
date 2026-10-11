@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.geovisual.kenburns.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geovisual.kenburns.core.editor.FramingGeometry
import com.geovisual.kenburns.core.editor.FramingGesture
import com.geovisual.kenburns.core.editor.FramingGestureState
import com.geovisual.kenburns.core.editor.FramingTarget
import com.geovisual.kenburns.core.math.Easings
import com.geovisual.kenburns.core.math.KenBurnsMath
import com.geovisual.kenburns.core.model.AspectRatio
import com.geovisual.kenburns.core.model.Easing
import com.geovisual.kenburns.core.model.FramingRect
import com.geovisual.kenburns.core.model.PhotoItem
import com.geovisual.kenburns.core.model.Project
import com.geovisual.kenburns.core.model.TransitionType
import com.geovisual.kenburns.core.timeline.Timeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToLong

private const val MIN_DURATION_MS = 2000L
private const val MAX_DURATION_MS = 10000L
private const val TOUCH_SLOP_DP = 30f
private const val PREVIEW_HOLD_MS = 500.0

/** Where content of a given aspect lands inside a canvas: letterboxed, centered. */
internal data class Fit(val x: Float, val y: Float, val w: Float, val h: Float)

/** Decodes near canvas size (about 1080 px on the long side); coordinates are normalized so size is irrelevant. */
internal fun decodeForCanvas(file: File): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1080) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
}

internal fun fit(canvasW: Float, canvasH: Float, contentW: Float, contentH: Float): Fit {
    if (canvasW <= 0f || canvasH <= 0f) return Fit(0f, 0f, 1f, 1f)
    val s = min(canvasW / contentW, canvasH / contentH)
    val w = contentW * s; val h = contentH * s
    return Fit((canvasW - w) / 2f, (canvasH - h) / 2f, w, h)
}

/** Per-photo editor: A/B framing boxes, duration, easing, preview, re-randomize, remove. */
@Composable
fun EditorScreen(project: Project, photoId: String, onSelectPhoto: (String) -> Unit, onBack: () -> Unit) {
    val index = project.photos.indexOfFirst { it.id == photoId }
    if (index < 0) { LaunchedEffect(Unit) { onBack() }; return }
    val photo = project.photos[index]
    val photosDir = remember(project.id) { AppModel.photosDir(project.id) }
    val geometry = remember(photo.id, project.aspect) { FramingGeometry(photo.imageWidth, photo.imageHeight, project.aspect) }
    var selectedBox by rememberSaveable(photo.id) { mutableStateOf(FramingTarget.A) }
    var gesture by remember(photo.id) { mutableStateOf(FramingGestureState(selected = selectedBox)) }
    LaunchedEffect(gesture.selected) { selectedBox = gesture.selected }
    var playing by remember(photo.id) { mutableStateOf(false) }
    var previewMs by remember(photo.id) { mutableStateOf<Double?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    val leave = { AppModel.flush(); onBack() }
    BackHandler { leave() }

    val image by produceState<ImageBitmap?>(null, photo.id) {
        value = withContext(Dispatchers.IO) { decodeForCanvas(File(photosDir, photo.fileName)) }
    }

    // Same timeline as export, so preview motion speed matches the video (hold plus crossfade lead-in).
    val motionMs = remember(project.photos, project.transition, project.transitionDurationMs, index) {
        Timeline(project).motionDurationMs(index)
    }
    val motionMsNow by rememberUpdatedState(motionMs)
    val latest by rememberUpdatedState(photo)
    LaunchedEffect(playing, photo.id) {
        if (!playing) { previewMs = null; return@LaunchedEffect }
        var origin = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val t = (now - origin) / 1_000_000.0
            val dur = motionMsNow
            previewMs = t.coerceAtMost(dur)
            if (t >= dur + PREVIEW_HOLD_MS) origin = now
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton("Photos", leave)
            Text(
                "Photo ${index + 1} of ${project.photos.size}", color = Teal,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            TextButton(enabled = index > 0, onClick = { onSelectPhoto(project.photos[index - 1].id) }) {
                Text("Prev", color = if (index > 0) MaterialTheme.colorScheme.onBackground else Muted)
            }
            TextButton(enabled = index < project.photos.lastIndex, onClick = { onSelectPhoto(project.photos[index + 1].id) }) {
                Text("Next", color = if (index < project.photos.lastIndex) MaterialTheme.colorScheme.onBackground else Muted)
            }
        }

        FramingCanvas(
            photo = photo, aspect = project.aspect, image = image, geometry = geometry,
            selected = gesture.selected, gestureState = gesture, previewMs = previewMs, motionMs = motionMs, playing = playing,
            onGesture = { gesture = it },
            onRect = { target, rect ->
                AppModel.updatePhoto(photo.id) {
                    if (target == FramingTarget.A) it.copy(startRect = rect) else it.copy(endRect = rect)
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
        )

        val maxControls = (LocalConfiguration.current.screenHeightDp * 0.42f).dp
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxControls).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PreviewButton(playing) { playing = !playing }
                AccentChip("Edit A (start)", Teal, gesture.selected == FramingTarget.A, !playing) {
                    gesture = gesture.copy(selected = FramingTarget.A)
                }
                AccentChip("Edit B (end)", Orange, gesture.selected == FramingTarget.B, !playing) {
                    gesture = gesture.copy(selected = FramingTarget.B)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Stays ${formatSeconds(photo.durationMs)}", color = MaterialTheme.colorScheme.onBackground)
                Slider(
                    value = photo.durationMs.toFloat(),
                    onValueChange = { v ->
                        val ms = ((v / 100f).roundToLong() * 100L).coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
                        if (ms != photo.durationMs) AppModel.updatePhoto(photo.id) { it.copy(durationMs = ms) }
                    },
                    valueRange = MIN_DURATION_MS.toFloat()..MAX_DURATION_MS.toFloat(),
                    steps = ((MAX_DURATION_MS - MIN_DURATION_MS) / 100L - 1).toInt(),
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Teal),
                )
            }
            if (kotlin.math.abs(motionMs - photo.durationMs) > 50.0) {
                Text(
                    "${formatSeconds(photo.durationMs)} on screen, motion ${formatSeconds(motionMs.roundToLong())} with fade",
                    color = Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
            SectionLabel("Easing")
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (e in Easing.entries) {
                    EasingChip(e, photo.easing == e, Modifier) {
                        AppModel.updatePhoto(photo.id) { it.copy(easing = e) }
                    }
                }
            }
            if (index < project.photos.lastIndex) {
                SectionLabel("Transition after this photo")
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ChoiceChip("Project default (${transitionLabel(project.transition)})", photo.transitionOverride == null, !playing) {
                        AppModel.updatePhoto(photo.id) { it.copy(transitionOverride = null) }
                    }
                    for (t in TransitionType.entries) {
                        ChoiceChip(transitionLabel(t), photo.transitionOverride == t, !playing) {
                            AppModel.updatePhoto(photo.id) { it.copy(transitionOverride = t) }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { playing = false; AppModel.rerandomize(photo.id) }) { Text("Re-randomize") }
                Button(onClick = { confirmRemove = true }) { Text("Remove photo") }
            }
            Text(
                "Drag a box to move it. Drag a corner to resize it. Re-randomize picks new boxes for this photo only.",
                color = Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        containerColor = Surface,
        title = { Text("Photo ${index + 1}", color = MaterialTheme.colorScheme.onBackground) },
        text = { Text("Remove this photo from the video? The copy in the app is deleted. Your original stays in Photos.", color = Muted) },
        confirmButton = {
            Button(onClick = {
                confirmRemove = false
                val next = project.photos.getOrNull(index + 1) ?: project.photos.getOrNull(index - 1)
                AppModel.removePhoto(photo.id)
                if (next != null) onSelectPhoto(next.id) else onBack()
            }) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep", color = MaterialTheme.colorScheme.onBackground) } },
    )
}

@Composable
private fun FramingCanvas(
    photo: PhotoItem,
    aspect: AspectRatio,
    image: ImageBitmap?,
    geometry: FramingGeometry,
    selected: FramingTarget,
    gestureState: FramingGestureState,
    previewMs: Double?,
    motionMs: Double,
    playing: Boolean,
    onGesture: (FramingGestureState) -> Unit,
    onRect: (FramingTarget, FramingRect) -> Unit,
    modifier: Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val photoNow by rememberUpdatedState(photo)
    val stateNow by rememberUpdatedState(gestureState)
    val playingNow by rememberUpdatedState(playing)

    Box(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(photo.id, size, geometry, density) {
                val slopPx = TOUCH_SLOP_DP * density.density
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (playingNow) return@awaitEachGesture
                    val f = fit(size.width.toFloat(), size.height.toFloat(), photoNow.imageWidth.toFloat(), photoNow.imageHeight.toFloat())
                    fun nx(p: Offset) = ((p.x - f.x) / f.w).toDouble()
                    fun ny(p: Offset) = ((p.y - f.y) / f.h).toDouble()
                    val st = FramingGesture.down(
                        stateNow, photoNow.startRect, photoNow.endRect,
                        nx(down.position), ny(down.position), (slopPx / f.w).toDouble(), (slopPx / f.h).toDouble(),
                    )
                    onGesture(st)
                    if (st.drag == null) return@awaitEachGesture
                    down.consume()
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            FramingGesture.move(st, geometry, nx(ch.position), ny(ch.position))?.let { onRect(it.target, it.rect) }
                            ch.consume()
                        }
                    } finally {
                        // Also runs when pointerInput restarts or is cancelled mid-drag.
                        onGesture(FramingGesture.up(st))
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val img = image ?: return@Canvas
            if (previewMs != null) drawPreview(img, photo, aspect, previewMs, motionMs)
            else drawEditor(img, photo, selected, gestureState, measurer)
        }
    }
}

/** The moving crop as the full view, letterboxed at the output aspect; same math as export. */
private fun DrawScope.drawPreview(img: ImageBitmap, photo: PhotoItem, aspect: AspectRatio, ms: Double, motionMs: Double) {
    val rect = KenBurnsMath.rectAt(photo, ms, motionMs)
    val f = fit(size.width, size.height, aspect.w.toFloat(), aspect.h.toFloat())
    val s = f.w / (rect.w.toFloat() * img.width)
    clipRect(f.x, f.y, f.x + f.w, f.y + f.h) {
        withTransform({
            translate(f.x, f.y)
            scale(s, s, Offset.Zero)
            translate(-(rect.left.toFloat() * img.width), -(rect.top.toFloat() * img.height))
        }) { drawImage(img, filterQuality = FilterQuality.Medium) }
    }
    val progress = (ms / motionMs).toFloat().coerceIn(0f, 1f)
    drawLine(Teal, Offset(f.x, f.y + f.h - 2f), Offset(f.x + f.w * progress, f.y + f.h - 2f), strokeWidth = 4f)
}

private fun DrawScope.drawEditor(
    img: ImageBitmap, photo: PhotoItem, selected: FramingTarget, gs: FramingGestureState,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val f = fit(size.width, size.height, photo.imageWidth.toFloat(), photo.imageHeight.toFloat())
    drawImage(
        img, dstOffset = androidx.compose.ui.unit.IntOffset(f.x.toInt(), f.y.toInt()),
        dstSize = IntSize(f.w.toInt(), f.h.toInt()), filterQuality = FilterQuality.Medium,
    )
    fun px(r: FramingRect) = Offset(f.x + r.cx.toFloat() * f.w, f.y + r.cy.toFloat() * f.h)
    // Motion path from the center of A to the center of B, arrowhead at B.
    val a = px(photo.startRect); val b = px(photo.endRect)
    val len = (b - a).getDistance()
    if (len > 24f) {
        drawLine(
            Color.White.copy(alpha = 0.55f), a, b, strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
        )
        val dir = (b - a) / len
        val n = Offset(-dir.y, dir.x)
        val tip = b; val base = b - dir * 22f
        drawPath(
            Path().apply { moveTo(tip.x, tip.y); lineTo(base.x + n.x * 9f, base.y + n.y * 9f); lineTo(base.x - n.x * 9f, base.y - n.y * 9f); close() },
            Color.White.copy(alpha = 0.7f),
        )
    }
    // Unselected first so the selected box draws on top.
    val order = if (selected == FramingTarget.A) listOf(FramingTarget.B, FramingTarget.A) else listOf(FramingTarget.A, FramingTarget.B)
    for (t in order) {
        val r = if (t == FramingTarget.A) photo.startRect else photo.endRect
        drawBox(f, r, if (t == FramingTarget.A) Teal else Orange, if (t == FramingTarget.A) "A" else "B", t == selected, measurer)
    }
}

private fun DrawScope.drawBox(
    f: Fit, r: FramingRect, color: Color, label: String, selected: Boolean,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val left = f.x + r.left.toFloat() * f.w; val top = f.y + r.top.toFloat() * f.h
    val right = f.x + r.right.toFloat() * f.w; val bottom = f.y + r.bottom.toFloat() * f.h
    val tl = Offset(left, top); val sz = Size(right - left, bottom - top)
    if (selected) drawRect(color.copy(alpha = 0.10f), tl, sz)
    drawRect(color, tl, sz, style = Stroke(width = if (selected) 3.dp.toPx() else 2.dp.toPx()))
    val leg = min(if (selected) 20.dp.toPx() else 14.dp.toPx(), min(sz.width, sz.height) * 0.4f)
    for ((cx, sx) in listOf(left to 1f, right to -1f)) for ((cy, sy) in listOf(top to 1f, bottom to -1f)) {
        drawPath(
            Path().apply { moveTo(cx, cy); lineTo(cx + sx * leg, cy); lineTo(cx, cy + sy * leg); close() },
            color,
        )
    }
    val text = measurer.measure(label, TextStyle(color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold))
    val c = Offset(left + leg + 12.dp.toPx(), top + 14.dp.toPx())
    drawCircle(Background.copy(alpha = 0.8f), radius = 11.dp.toPx(), center = c)
    drawText(text, topLeft = Offset(c.x - text.size.width / 2f, c.y - text.size.height / 2f))
}

@Composable
private fun PreviewButton(playing: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick) {
        Canvas(Modifier.size(12.dp)) {
            if (playing) drawCircle(Color.White) else drawPath(
                Path().apply { moveTo(0f, 0f); lineTo(size.width, size.height / 2f); lineTo(0f, size.height); close() },
                Color.White,
            )
        }
        Text(if (playing) "Stop" else "Preview", modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun AccentChip(label: String, accent: Color, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, enabled = enabled,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.background,
            labelColor = MaterialTheme.colorScheme.onBackground,
            selectedContainerColor = accent.copy(alpha = 0.14f),
            selectedLabelColor = accent,
        ),
        border = BorderStroke(1.dp, if (selected) accent else Muted.copy(alpha = 0.5f)),
    )
}

private fun easingLabel(e: Easing) = when (e) {
    Easing.LINEAR -> "Linear"
    Easing.EASE_IN_OUT -> "Smooth"
    Easing.EASE_IN -> "Ease in"
    Easing.EASE_OUT -> "Ease out"
}

@Composable
private fun EasingChip(easing: Easing, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint = if (selected) Teal else Muted
    FilterChip(
        selected = selected, onClick = onClick, modifier = modifier,
        label = { Text(easingLabel(easing), style = MaterialTheme.typography.labelMedium) },
        leadingIcon = { EasingGlyph(easing, tint) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.background,
            labelColor = MaterialTheme.colorScheme.onBackground,
            selectedContainerColor = Teal.copy(alpha = 0.12f),
            selectedLabelColor = Teal,
        ),
        border = BorderStroke(1.dp, if (selected) Teal else Muted.copy(alpha = 0.5f)),
    )
}

/** Tiny plot of the easing curve: progress over time, drawn from the real easing function. */
@Composable
private fun EasingGlyph(easing: Easing, color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val pad = 2.dp.toPx()
        val w = size.width - 2 * pad; val h = size.height - 2 * pad
        val path = Path()
        val n = 16
        for (i in 0..n) {
            val t = i / n.toDouble()
            val x = pad + w * t.toFloat(); val y = pad + h * (1f - Easings.apply(easing, t).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round))
    }
}
