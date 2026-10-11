package com.geovisual.kenburns.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.geovisual.kenburns.core.render.ItemClock
import com.geovisual.kenburns.core.render.ShaderUniformMath
import com.geovisual.kenburns.core.render.ShaderUniforms
import com.geovisual.kenburns.core.timeline.Timeline

/** Everything one item's effect needs. Immutable; [timeline] is shared by all items. */
data class ItemRenderSpec(
    val timeline: Timeline,
    val index: Int,
    /** Composition-global start of this item, handed over for timestamp handling. */
    val startUs: Long,
    val durationUs: Long,
    /** Project-local JPEG of photo index+1, or null when there is no crossfade out of this item. */
    val nextPhotoPath: String?,
    val outputWidth: Int,
    val outputHeight: Int,
)

/** Per-item effect: Ken Burns crop of the current photo plus the outgoing transition. */
@OptIn(UnstableApi::class)
class KenBurnsEffect(private val spec: ItemRenderSpec) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        KenBurnsShaderProgram(spec, useHdr)
}

/**
 * Samples the input frame (photo i) at the Ken Burns rect, blends in photo i+1 from a texture
 * we upload ourselves during the crossfade tail, and applies dip to black. Output size is fixed
 * to the project output size by [configure].
 */
@OptIn(UnstableApi::class)
class KenBurnsShaderProgram(
    private val spec: ItemRenderSpec,
    useHdr: Boolean,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private val glProgram: GlProgram
    private val clock = ItemClock(spec.startUs)
    private var nextTexId = NO_TEXTURE
    private var minFilter = C.TEXTURE_MIN_FILTER_LINEAR_MIPMAP_LINEAR
    private var frameCount = 0
    private var lastPtsUs = C.TIME_UNSET

    init {
        try {
            glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            glProgram.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        Log.i(TAG_TS, "item=${spec.index} configure input=${inputWidth}x$inputHeight " +
            "output=${spec.outputWidth}x${spec.outputHeight}")
        return Size(spec.outputWidth, spec.outputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val localUs = clock.localUs(presentationTimeUs)
        logTimestamp(presentationTimeUs, localUs)
        frameCount++
        lastPtsUs = presentationTimeUs

        val durationMs = spec.durationUs / 1000.0
        val tMs = (localUs / 1000.0).coerceIn(0.0, durationMs)
        val u = ShaderUniformMath.from(spec.timeline.frameState(spec.index, tMs))

        try {
            val nextTex = if (u.hasNext) ensureNextTexture(presentationTimeUs) else inputTexId
            try {
                draw(inputTexId, nextTex, u)
            } catch (e: GlUtil.GlException) {
                if (minFilter != C.TEXTURE_MIN_FILTER_LINEAR) {
                    // Mipmapped sampling failed on this GPU; fall back to plain bilinear for good.
                    Log.w(TAG, "mipmap sampling failed, falling back to linear", e)
                    minFilter = C.TEXTURE_MIN_FILTER_LINEAR
                    draw(inputTexId, nextTex, u)
                } else {
                    throw e
                }
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun draw(inputTexId: Int, nextTex: Int, u: ShaderUniforms) {
        // With no next photo, uNext aliases the input; skip the mip chain regeneration for it.
        val nextFilter = if (u.hasNext) minFilter else C.TEXTURE_MIN_FILTER_LINEAR
        glProgram.use()
        glProgram.setSamplerTexIdUniform("uCurrent", inputTexId, /* texUnitIndex= */ 0, minFilter)
        glProgram.setSamplerTexIdUniform("uNext", nextTex, /* texUnitIndex= */ 1, nextFilter)
        glProgram.setFloatsUniform("uCurrentRect", u.currentRect.toArray())
        glProgram.setFloatsUniform("uNextRect", u.nextRect.toArray())
        glProgram.setFloatUniform("uAlpha", u.alpha)
        glProgram.setFloatUniform("uDip", u.dip)
        glProgram.setFloatUniform("uSplit", u.split)
        glProgram.setFloatUniform("uCurrentShift", u.currentShift)
        glProgram.setFloatUniform("uNextShift", u.nextShift)
        glProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
        GlUtil.checkGlError()
    }

    /** Decodes the next photo once, uploads it, and frees the bitmap straight away. */
    private fun ensureNextTexture(ptsUs: Long): Int {
        if (nextTexId != NO_TEXTURE) return nextTexId
        val path = spec.nextPhotoPath
            ?: throw VideoFrameProcessingException("item ${spec.index}: crossfade without next photo", ptsUs)
        val bitmap = decodeBounded(path)
            ?: throw VideoFrameProcessingException("item ${spec.index}: could not decode $path", ptsUs)
        try {
            nextTexId = GlUtil.createTexture(bitmap)
            Log.i(TAG, "item=${spec.index} uploaded next texture ${bitmap.width}x${bitmap.height}")
        } finally {
            bitmap.recycle()
        }
        return nextTexId
    }

    private fun logTimestamp(ptsUs: Long, localUs: Long) {
        if (frameCount < 3 || frameCount % 60 == 0) {
            Log.i(TAG_TS, "item=${spec.index} frame=$frameCount pts=$ptsUs startUs=${spec.startUs} " +
                "mode=${clock.mode} local=$localUs")
        }
    }

    override fun release() {
        Log.i(TAG_TS, "item=${spec.index} release frames=$frameCount firstPts=${clock.firstPtsUs} " +
            "lastPts=$lastPtsUs startUs=${spec.startUs} durUs=${spec.durationUs} mode=${clock.mode}")
        super.release()
        try {
            glProgram.delete()
            if (nextTexId != NO_TEXTURE) {
                GlUtil.deleteTexture(nextTexId)
                nextTexId = NO_TEXTURE
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        const val TAG = "KenBurnsGl"
        const val TAG_TS = "KenBurnsTs"
        private const val NO_TEXTURE = -1

        private fun decodeBounded(path: String): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > GlUtil.MAX_BITMAP_DECODING_SIZE) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeFile(path, opts)
        }

        private const val VERTEX_SHADER = """
attribute vec4 aFramePosition;
varying vec2 vTex;
void main() {
  gl_Position = aFramePosition;
  vTex = aFramePosition.xy * 0.5 + 0.5;
}
"""

        // Both samplers are always read so no uniform gets optimized away; with uAlpha = 0 the
        // mix returns the current photo exactly.
        private const val FRAGMENT_SHADER = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uCurrent;
uniform sampler2D uNext;
uniform vec4 uCurrentRect;
uniform vec4 uNextRect;
uniform float uAlpha;
uniform float uDip;
uniform float uSplit;
uniform float uCurrentShift;
uniform float uNextShift;
varying vec2 vTex;
void main() {
  // Push slide: hard seam at uSplit; each side samples its own crop, shifted along x. Each
  // texture keeps its own rect, so the vertical flip difference between them stays folded in.
  vec2 cp = vec2(vTex.x + uCurrentShift, vTex.y);
  vec2 np = vec2(vTex.x + uNextShift, vTex.y);
  vec4 c = texture2D(uCurrent, uCurrentRect.xy + cp * uCurrentRect.zw);
  vec4 n = texture2D(uNext, uNextRect.xy + np * uNextRect.zw);
  vec3 side = vTex.x < uSplit ? c.rgb : n.rgb;
  vec3 rgb = mix(side, n.rgb, uAlpha) * (1.0 - uDip);
  gl_FragColor = vec4(rgb, 1.0);
}
"""
    }
}
