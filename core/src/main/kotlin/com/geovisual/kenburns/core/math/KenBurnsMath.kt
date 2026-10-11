package com.geovisual.kenburns.core.math

import com.geovisual.kenburns.core.model.AspectRatio
import com.geovisual.kenburns.core.model.FramingRect
import com.geovisual.kenburns.core.model.PhotoItem
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

object KenBurnsMath {

    /** Normalized (w, h) of the zoom=1 crop: largest in-bounds crop with the output aspect. */
    fun baseSize(imageW: Int, imageH: Int, aspect: AspectRatio): Pair<Double, Double> {
        require(imageW > 0 && imageH > 0) { "bad image size ${imageW}x$imageH" }
        val a = aspect.ratio
        val basePxW = min(imageW.toDouble(), imageH * a)
        val basePxH = basePxW / a
        return Pair(basePxW / imageW, basePxH / imageH)
    }

    /** Zoom factor (>= 1 for valid rects) of [rect] on an image of this size. */
    fun zoomOf(rect: FramingRect, imageW: Int, imageH: Int, aspect: AspectRatio): Double =
        baseSize(imageW, imageH, aspect).first / rect.w

    /** Builds an in-bounds, aspect-correct rect for [zoom] (>= 1), centered at (cx,cy) then clamped. */
    fun rectForZoom(
        imageW: Int, imageH: Int, aspect: AspectRatio,
        zoom: Double, cx: Double, cy: Double,
    ): FramingRect {
        require(zoom >= 1.0) { "zoom must be >= 1, got $zoom" }
        val (bw, bh) = baseSize(imageW, imageH, aspect)
        return clampToBounds(FramingRect(cx, cy, bw / zoom, bh / zoom))
    }

    /** Pulls the rect center so the rect stays inside [0,1]^2. Size is shrunk only if it exceeds 1. */
    fun clampToBounds(r: FramingRect): FramingRect {
        val w = min(r.w, 1.0)
        val h = min(r.h, 1.0)
        return FramingRect(
            cx = r.cx.coerceIn(w / 2, 1.0 - w / 2),
            cy = r.cy.coerceIn(h / 2, 1.0 - h / 2),
            w = w,
            h = h,
        )
    }

    /**
     * Crop rect at motion time [motionTimeMs] of a motion lasting [motionDurationMs].
     * Size interpolates in log space (constant perceived zoom rate; since the base crop is
     * constant this equals log-space zoom interpolation), center interpolates linearly, and the
     * result is clamped in bounds. This single function feeds both preview and shader.
     */
    fun rectAt(
        photo: PhotoItem,
        motionTimeMs: Double,
        motionDurationMs: Double = photo.durationMs.toDouble(),
    ): FramingRect {
        val raw = if (motionDurationMs <= 0.0) 1.0 else motionTimeMs / motionDurationMs
        val p = Easings.apply(photo.easing, raw)
        return interpolate(photo.startRect, photo.endRect, p)
    }

    fun rectAt(photo: PhotoItem, tMs: Long): FramingRect = rectAt(photo, tMs.toDouble())

    fun interpolate(s: FramingRect, e: FramingRect, p: Double): FramingRect {
        val w = exp(lerp(ln(s.w), ln(e.w), p))
        val h = exp(lerp(ln(s.h), ln(e.h), p))
        return clampToBounds(FramingRect(lerp(s.cx, e.cx, p), lerp(s.cy, e.cy, p), w, h))
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
}
