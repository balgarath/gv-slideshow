package com.geovisual.kenburns.core.render

import com.geovisual.kenburns.core.model.FramingRect
import com.geovisual.kenburns.core.timeline.FrameState

/** Four floats, uploaded as a GLSL vec4. */
data class Vec4(val x: Float, val y: Float, val z: Float, val w: Float) {
    fun toArray(): FloatArray = floatArrayOf(x, y, z, w)
}

/**
 * Uniform values for one frame of the Ken Burns shader.
 *
 * A rect uniform is (offsetU, offsetV, scaleU, scaleV). The fragment shader samples at
 * `offset + vTex * scale`, where vTex is the output position in [0,1]^2 with v = 0 at the
 * BOTTOM of the output frame (GL convention).
 *
 * The two textures have opposite vertical orientation:
 * - The input frame (current photo) comes out of Media3's bitmap input stage, which mirrors Y,
 *   so the image top sits at v = 1 (verified in Media3 1.11.1 DefaultShaderProgram).
 * - The next-photo texture we upload ourselves with GLUtils.texImage2D has bitmap row 0
 *   (image top) at v = 0.
 * Both flips are folded into the offsets/scales so the shader uses one formula.
 */
data class ShaderUniforms(
    val currentRect: Vec4,
    val nextRect: Vec4,
    /** Weight of the next photo, 0..1. */
    val alpha: Float,
    /** Fade to black, 0..1. */
    val dip: Float,
    /** True when the next-photo texture must be bound and is visible. */
    val hasNext: Boolean,
    /**
     * Output x (0 = left) where the current photo ends and the next begins. 1 means the whole frame
     * is the current photo (no slide); the edge is hard, no blending.
     */
    val split: Float = 1f,
    /** Added to the output x before applying [currentRect] (push slide moves the crop left). */
    val currentShift: Float = 0f,
    /** Added to the output x before applying [nextRect]. */
    val nextShift: Float = 0f,
)

object ShaderUniformMath {

    /** Input-frame sampling rect: image top at v = 1. */
    fun currentRectUv(r: FramingRect): Vec4 =
        Vec4(r.left.toFloat(), (1.0 - r.bottom).toFloat(), r.w.toFloat(), r.h.toFloat())

    /** Self-uploaded bitmap sampling rect: image top at v = 0. */
    fun nextRectUv(r: FramingRect): Vec4 =
        Vec4(r.left.toFloat(), r.bottom.toFloat(), r.w.toFloat(), (-r.h).toFloat())

    fun from(state: FrameState): ShaderUniforms {
        val next = state.rectNext
        val alpha = if (next == null) 0.0 else state.alpha.coerceIn(0.0, 1.0)
        val slide = if (next == null) 0.0 else state.slide.coerceIn(0.0, 1.0)
        return ShaderUniforms(
            currentRect = currentRectUv(state.rectCurrent),
            // With no next photo the shader still samples uNext (bound to the input texture),
            // but alpha is 0 so the value never shows.
            nextRect = if (next == null) currentRectUv(state.rectCurrent) else nextRectUv(next),
            alpha = alpha.toFloat(),
            dip = state.dipBlack.coerceIn(0.0, 1.0).toFloat(),
            hasNext = next != null && (alpha > 0.0 || slide > 0.0),
            split = (1.0 - slide).toFloat(),
            // Shifts are slide-only. Outside a slide the shader samples each crop unshifted
            // (crossfade, dip and cut all read the next crop at x + 0).
            currentShift = if (slide > 0.0) slide.toFloat() else 0f,
            nextShift = if (slide > 0.0) (-(1.0 - slide)).toFloat() else 0f,
        )
    }

    /** Which photo a fragment at output x shows, and the x it samples inside that photo's crop. */
    data class SlideSample(val next: Boolean, val cropX: Float)

    /** CPU twin of the fragment shader's slide branch (tested; the shader mirrors it). */
    fun slideSample(u: ShaderUniforms, x: Float): SlideSample =
        if (x < u.split) SlideSample(false, x + u.currentShift) else SlideSample(true, x + u.nextShift)
}

/**
 * Maps the presentationTimeUs Media3 hands to a per-item effect onto item-local time.
 *
 * Whether those timestamps are item-local or composition-global is not documented. Reading the
 * 1.11.1 source suggests global (VideoEncoderGraphInput adds the cumulative media item offset),
 * but this is unverified on device, so the mode is decided from the first frame:
 * - first pts within [toleranceUs] of [itemStartUs] (and the item does not start at 0): GLOBAL
 * - first pts within [toleranceUs] of 0: LOCAL
 * - anything else: ANCHORED, treating the first frame as local time 0 (covers an unknown
 *   extra base offset).
 */
class ItemClock(val itemStartUs: Long, val toleranceUs: Long = DEFAULT_TOLERANCE_US) {
    enum class Mode { LOCAL, GLOBAL, ANCHORED }

    var mode: Mode? = null
        private set
    var offsetUs: Long = 0
        private set
    var firstPtsUs: Long = Long.MIN_VALUE
        private set

    fun localUs(ptsUs: Long): Long {
        if (mode == null) {
            firstPtsUs = ptsUs
            when {
                itemStartUs > 0 && kotlin.math.abs(ptsUs - itemStartUs) <= toleranceUs -> {
                    mode = Mode.GLOBAL; offsetUs = itemStartUs
                }
                kotlin.math.abs(ptsUs) <= toleranceUs -> {
                    mode = Mode.LOCAL; offsetUs = 0
                }
                else -> {
                    mode = Mode.ANCHORED; offsetUs = ptsUs
                }
            }
        }
        return ptsUs - offsetUs
    }

    companion object {
        /** About three frames at 60 fps; far below the shortest item. */
        const val DEFAULT_TOLERANCE_US = 50_000L
    }
}
