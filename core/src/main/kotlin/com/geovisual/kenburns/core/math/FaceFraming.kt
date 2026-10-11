package com.geovisual.kenburns.core.math

import com.geovisual.kenburns.core.model.AspectRatio
import com.geovisual.kenburns.core.model.FaceBox
import kotlin.math.max
import kotlin.math.min

/** Axis-aligned box in normalized image space. */
data class NormBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val w: Double get() = right - left
    val h: Double get() = bottom - top
}

/** The framing a set of faces asks for; see [FaceFraming.plan]. */
data class FacePlan(
    /** The box every face-aware rect must fully contain. */
    val contain: NormBox,
    /** Zoom of the tight rect: the highest zoom that still contains [contain], capped at the max zoom. */
    val tightZoom: Double,
)

object FaceFraming {
    /** Margin added on each side of the face union, as a fraction of the union size. */
    const val MARGIN = 0.5
    /** Extra room above the faces (boxes sit on the forehead, hair is outside them). */
    const val HEADROOM = 0.25
    /** Margin used when the full margin leaves too little room for visible motion. */
    const val HARD_MARGIN = 0.1
    /** Smallest tight zoom that guarantees visible motion on zoom alone (existing rule is 1.12). */
    const val MIN_TIGHT_ZOOM = 1.121

    /**
     * Zoom of the smallest valid crop (output aspect, inside the image) that contains [box].
     * Below 1.0 means the box does not fit even the full base crop.
     */
    fun fitZoom(box: NormBox, imageW: Int, imageH: Int, aspect: AspectRatio): Double {
        val (bw, bh) = KenBurnsMath.baseSize(imageW, imageH, aspect)
        val cropW = max(box.w, box.h * bw / bh)
        return bw / cropW
    }

    private fun union(faces: List<FaceBox>): NormBox = NormBox(
        faces.minOf { it.left }, faces.minOf { it.top }, faces.maxOf { it.right }, faces.maxOf { it.bottom },
    )

    private fun expand(b: NormBox, margin: Double, headroom: Double, clamp: Boolean = true): NormBox {
        val e = NormBox(
            b.left - margin * b.w, b.top - (margin + headroom) * b.h,
            b.right + margin * b.w, b.bottom + margin * b.h,
        )
        return if (!clamp) e else NormBox(
            e.left.coerceAtLeast(0.0), e.top.coerceAtLeast(0.0), e.right.coerceAtMost(1.0), e.bottom.coerceAtMost(1.0),
        )
    }

    /** Drops unusable boxes and clamps the rest into the image. */
    private fun clean(faces: List<FaceBox>): List<FaceBox> = faces.mapNotNull { f ->
        if (!(f.left.isFinite() && f.top.isFinite() && f.w.isFinite() && f.h.isFinite())) return@mapNotNull null
        val l = f.left.coerceIn(0.0, 1.0); val t = f.top.coerceIn(0.0, 1.0)
        val r = f.right.coerceIn(0.0, 1.0); val b = f.bottom.coerceIn(0.0, 1.0)
        if (r - l <= 1e-6 || b - t <= 1e-6) null else FaceBox(l, t, r - l, b - t)
    }

    /**
     * Decides what to frame, trying in order: the whole group at 50% margin, the whole group at
     * 10% margin, then the biggest single face at 50% and 10%. A candidate must fit the full base
     * crop judged before clamping to the image (so edge faces do not hide how spread out the group
     * is) and leave a tight zoom of at least [MIN_TIGHT_ZOOM] after clamping. Returns null when
     * there are no usable faces or nothing leaves room to zoom (the caller then frames as without
     * faces).
     */
    fun plan(faces: List<FaceBox>?, imageW: Int, imageH: Int, aspect: AspectRatio): FacePlan? {
        val usable = clean(faces ?: return null)
        if (usable.isEmpty()) return null
        val targets = listOf(union(usable), union(listOf(usable.maxBy { it.w * it.h })))
        for (target in targets) {
            for ((margin, headroom) in listOf(MARGIN to HEADROOM, HARD_MARGIN to HEADROOM / 2)) {
                if (fitZoom(expand(target, margin, headroom, clamp = false), imageW, imageH, aspect) < 1.0) continue
                val box = expand(target, margin, headroom)
                val fit = fitZoom(box, imageW, imageH, aspect)
                if (fit >= MIN_TIGHT_ZOOM) return FacePlan(box, min(fit, FramingRandomizer.ZOOM_MAX))
            }
        }
        return null
    }

    /** Rect at [zoom], centered on [box] and clamped in bounds. Contains [box] when zoom <= its fit zoom. */
    fun rectAround(box: NormBox, zoom: Double, imageW: Int, imageH: Int, aspect: AspectRatio) =
        KenBurnsMath.rectForZoom(
            imageW, imageH, aspect, max(1.0, zoom), (box.left + box.right) / 2, (box.top + box.bottom) / 2,
        )
}
