package com.geovisual.kenburns.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.geovisual.kenburns.core.export.ExportEstimate
import com.geovisual.kenburns.core.model.FramingRect
import com.geovisual.kenburns.core.model.Project
import com.geovisual.kenburns.core.render.ShaderUniformMath
import com.geovisual.kenburns.core.timeline.Timeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_LIVE_BITMAPS = 3

private fun formatClock(ms: Double): String = ExportEstimate.formatLength(ms.toLong())

/**
 * Whole-project preview on a Canvas. Uses Timeline.frameState and ShaderUniformMath, the same
 * functions the export shader gets its values from, so timing, crops and transitions match.
 * Not pixel-identical to the GL export.
 */
@Composable
fun PreviewScreen(project: Project, onBack: () -> Unit) {
    val timeline = remember(project) { Timeline(project) }
    val total = timeline.totalMs.toDouble()
    val photosDir = remember(project.id) { AppModel.photosDir(project.id) }
    var playing by remember { mutableStateOf(true) }
    val posState = remember { mutableDoubleStateOf(0.0) }
    var pos by posState
    val images = remember { mutableStateMapOf<Int, ImageBitmap>() }

    BackHandler { onBack() }

    val current = if (project.photos.isEmpty()) 0 else timeline.itemAt(pos.toLong())

    // Keep at most current, next and the one after (wrapping for the loop); drop references to the rest and let GC reclaim them.
    LaunchedEffect(current) {
        val n = project.photos.size
        if (n == 0) return@LaunchedEffect
        val want = linkedSetOf(current, (current + 1) % n, (current + 2) % n)
        val drop = images.keys.filter { it !in want }
        drop.forEach { images.remove(it) }
        for (k in want) {
            if (images.containsKey(k)) continue
            val bmp = withContext(Dispatchers.IO) { decodeForCanvas(File(photosDir, project.photos[k].fileName)) }
            if (bmp != null) images[k] = bmp
        }
    }
    DisposableEffect(Unit) {
        onDispose { images.clear() }
    }

    // Cancelled when the screen leaves composition or playing stops.
    LaunchedEffect(playing, total) {
        if (!playing || total <= 0.0) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            var p = posState.doubleValue + (now - last) / 1_000_000.0
            last = now
            if (p >= total) p %= total
            posState.doubleValue = p
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton("Timeline", onBack)
            Text("Preview", color = Teal, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
        }
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
            if (project.photos.isEmpty()) return@Canvas
            val f = fit(size.width, size.height, project.aspect.w.toFloat(), project.aspect.h.toFloat())
            val i = timeline.itemAt(pos.toLong())
            val t = (pos - timeline.startMs(i)).coerceAtLeast(0.0)
            drawSlideshowFrame(f, images[i], images[i + 1], timeline.frameState(i, t))
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { playing = !playing }) {
                Canvas(Modifier.size(14.dp)) {
                    if (playing) {
                        // Pause: two downward-pointing triangles side by side would read as arrows, so a circle, like the editor's stop.
                        drawCircle(Color.White)
                    } else {
                        drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, size.height / 2f); lineTo(0f, size.height); close() }, Color.White)
                    }
                }
            }
            Slider(
                value = pos.toFloat().coerceIn(0f, total.toFloat().coerceAtLeast(1f)),
                onValueChange = { playing = false; pos = it.toDouble() },
                valueRange = 0f..total.toFloat().coerceAtLeast(1f),
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Teal),
            )
            Text("${formatClock(pos)} / ${formatClock(total)}", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** One output frame: current crop, then the outgoing transition, using the export's uniform math. */
private fun DrawScope.drawSlideshowFrame(
    f: Fit, cur: ImageBitmap?, next: ImageBitmap?, state: com.geovisual.kenburns.core.timeline.FrameState,
) {
    val u = ShaderUniformMath.from(state)
    clipRect(f.x, f.y, f.x + f.w, f.y + f.h) {
        drawRect(Color.Black, Offset(f.x, f.y), androidx.compose.ui.geometry.Size(f.w, f.h))
        val nextRect = state.rectNext
        if (u.split < 1f && nextRect != null) {
            // Push slide: hard seam at split, each side shows its own shifted crop.
            val seam = f.x + u.split * f.w
            if (cur != null) clipRect(f.x, f.y, seam, f.y + f.h) { drawCrop(cur, state.rectCurrent, f, u.currentShift, 1f) }
            if (next != null) clipRect(seam, f.y, f.x + f.w, f.y + f.h) { drawCrop(next, nextRect, f, u.nextShift, 1f) }
        } else {
            if (cur != null) drawCrop(cur, state.rectCurrent, f, u.currentShift, 1f)
            if (next != null && nextRect != null && u.alpha > 0f) drawCrop(next, nextRect, f, u.nextShift, u.alpha)
        }
        if (u.dip > 0f) drawRect(Color.Black.copy(alpha = u.dip), Offset(f.x, f.y), androidx.compose.ui.geometry.Size(f.w, f.h))
    }
}

/** Draws [rect] of [img] filling the frame, shifted left by [shift] frame widths (the push slide). */
private fun DrawScope.drawCrop(img: ImageBitmap, rect: FramingRect, f: Fit, shift: Float, alpha: Float) {
    val s = f.w / (rect.w.toFloat() * img.width)
    withTransform({
        translate(f.x - shift * f.w, f.y)
        scale(s, s, Offset.Zero)
        translate(-(rect.left.toFloat() * img.width), -(rect.top.toFloat() * img.height))
    }) { drawImage(img, alpha = alpha, filterQuality = FilterQuality.Medium) }
}
