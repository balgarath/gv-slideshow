package com.geovisual.kenburns.ui.components

import android.database.ContentObserver
import android.graphics.Matrix
import android.graphics.SweepGradient
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.geovisual.kenburns.ui.Purple

private val Violet = Color(0xFFA66BFF)
private val PinkGlow = Color(0xFFFF5CCF)

/** Room reserved around the button so the glow is not clipped by the parent. */
private val GlowPad = 10.dp
private val CornerRadius = 26.dp

/**
 * Purple button with a glowing outline.
 *
 * - [progress] null and [enabled]: a violet and pink band travels around the edge.
 * - [progress] set: the outline is a progress ring that fills clockwise from the top.
 * - not [enabled] and no progress: dim static outline.
 * With "remove animations" on (animator duration scale 0) the band is replaced by a static glow.
 * Animation state is read only in the draw phase, so nothing recomposes per frame.
 */
@Composable
fun GlowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 0..1 shows the progress ring; null shows the idle ring. */
    progress: Float? = null,
    /** Busy without a known progress: travelling band, button not clickable. */
    busy: Boolean = false,
) {
    val reduceMotion = rememberReduceMotion()
    val travelling = !reduceMotion && progress == null && (enabled || busy)
    val angle = rememberSweepAngle(travelling)
    val lit = enabled || busy || progress != null

    Box(
        modifier.drawWithCache {
            val pad = GlowPad.toPx()
            val rect = Rect(pad, pad, size.width - pad, size.height - pad)
            val radius = minOf(CornerRadius.toPx(), rect.height / 2f, rect.width / 2f)
            val path = ringPath(rect, radius)
            val measure = PathMeasure().apply { setPath(path, false) }
            val length = measure.length
            val segment = Path()
            val cx = size.width / 2f
            val cy = size.height / 2f
            val matrix = Matrix()
            val dim = Violet.copy(alpha = if (lit) 0.35f else 0.18f)
            val core = 2.5.dp.toPx()

            // Static ring: violet and pink alternating around the whole edge.
            val ringBrush = ShaderBrush(
                SweepGradient(
                    cx, cy,
                    intArrayOf(Violet.toArgb(), PinkGlow.toArgb(), Violet.toArgb(), PinkGlow.toArgb(), Violet.toArgb()),
                    floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
                ),
            )
            val bandColors = intArrayOf(
                Violet.copy(alpha = 0f).toArgb(), Violet.toArgb(), PinkGlow.toArgb(),
                Violet.copy(alpha = 0f).toArgb(), Violet.copy(alpha = 0f).toArgb(),
            )
            val bandPositions = floatArrayOf(0f, 0.18f, 0.34f, 0.55f, 1f)
            // Progress arc starts at the top (path start) and ends pink at the head.
            val progressColors = intArrayOf(Violet.toArgb(), PinkGlow.toArgb(), Violet.toArgb())
            val progressPositions = floatArrayOf(0f, 0.5f, 1f)

            onDrawWithContent {
                drawContent()
                fun glow(brush: Brush, p: Path) {
                    // Soft halo from stacked, wider and fainter strokes.
                    drawPath(p, brush, 0.07f, Stroke(14.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(p, brush, 0.14f, Stroke(9.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(p, brush, 0.30f, Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(p, brush, 1f, Stroke(core, cap = StrokeCap.Round))
                }
                drawPath(path, dim, style = Stroke(core))
                val pr = progress
                when {
                    pr != null -> {
                        val f = pr.coerceIn(0f, 1f)
                        if (f > 0f) {
                            segment.rewind()
                            measure.getSegment(0f, length * f, segment, true)
                            // Gradient follows the arc: path start is the top, so rotate -90 degrees.
                            matrix.setRotate(-90f, cx, cy)
                            val shader = SweepGradient(cx, cy, progressColors, progressPositions)
                            shader.setLocalMatrix(matrix)
                            glow(ShaderBrush(shader), segment)
                        }
                    }
                    !lit -> {}
                    travelling -> {
                        matrix.setRotate(angle.value, cx, cy)
                        val shader = SweepGradient(cx, cy, bandColors, bandPositions)
                        shader.setLocalMatrix(matrix)
                        glow(ShaderBrush(shader), path)
                    }
                    else -> glow(ringBrush, path)
                }
            }
        }.padding(GlowPad),
        propagateMinConstraints = true,
    ) {
        Button(
            onClick = onClick,
            enabled = enabled && progress == null && !busy,
            shape = RoundedCornerShape(CornerRadius),
            colors = ButtonDefaults.buttonColors(
                containerColor = Purple,
                contentColor = Color.White,
                disabledContainerColor = if (lit) Purple else Purple.copy(alpha = 0.4f),
                disabledContentColor = Color.White.copy(alpha = if (lit) 0.9f else 0.5f),
            ),
        ) { Text(text) }
    }
}

private fun ringPath(r: Rect, radius: Float): Path = Path().apply {
    val d = radius * 2
    moveTo(r.center.x, r.top)
    lineTo(r.right - radius, r.top)
    arcTo(Rect(r.right - d, r.top, r.right, r.top + d), -90f, 90f, false)
    lineTo(r.right, r.bottom - radius)
    arcTo(Rect(r.right - d, r.bottom - d, r.right, r.bottom), 0f, 90f, false)
    lineTo(r.left + radius, r.bottom)
    arcTo(Rect(r.left, r.bottom - d, r.left + d, r.bottom), 90f, 90f, false)
    lineTo(r.left, r.top + radius)
    arcTo(Rect(r.left, r.top, r.left + d, r.top + d), 180f, 90f, false)
    close()
}

/** One infinite transition per button, and none while the band is not travelling. */
@Composable
private fun rememberSweepAngle(travelling: Boolean): State<Float> {
    if (!travelling) return remember { mutableStateOf(0f) }
    val transition = rememberInfiniteTransition(label = "glow")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "glowAngle",
    )
}

/** True when the system animator duration scale is 0 ("remove animations"). Tracks live changes. */
@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    var reduce by remember { mutableStateOf(readReduce(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduce = readReduce(context) }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        reduce = readReduce(context)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return reduce
}

private fun readReduce(context: android.content.Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
