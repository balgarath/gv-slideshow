package com.geovisual.kenburns.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class AspectRatio(val w: Int, val h: Int) {
    V9x16(1080, 1920),
    H16x9(1920, 1080),
    S1x1(1080, 1080);

    /** Width over height of the output frame. */
    val ratio: Double get() = w.toDouble() / h.toDouble()
}

@Serializable
enum class Easing { LINEAR, EASE_IN_OUT, EASE_IN, EASE_OUT }

@Serializable
enum class TransitionType { CUT, CROSSFADE, DIP_TO_BLACK, SLIDE }

/**
 * Source crop rect in image space. cx,cy = center; w,h = size; all normalized to image
 * width/height. In pixels the rect's aspect equals the project output aspect; that invariant
 * is checked by [isValidFor] and guaranteed by the factories in KenBurnsMath.
 */
@Serializable
data class FramingRect(val cx: Double, val cy: Double, val w: Double, val h: Double) {
    init {
        require(cx.isFinite() && cy.isFinite() && w.isFinite() && h.isFinite()) { "non-finite rect" }
        require(w > 0.0 && h > 0.0) { "rect size must be positive: $w x $h" }
    }

    val left: Double get() = cx - w / 2
    val top: Double get() = cy - h / 2
    val right: Double get() = cx + w / 2
    val bottom: Double get() = cy + h / 2

    fun isInBounds(eps: Double = 1e-9): Boolean =
        w <= 1.0 + eps && h <= 1.0 + eps &&
            left >= -eps && top >= -eps && right <= 1.0 + eps && bottom <= 1.0 + eps

    /** True when the rect's pixel aspect on an imageW x imageH image matches [aspect]. */
    fun isValidFor(imageW: Int, imageH: Int, aspect: AspectRatio, eps: Double = 1e-6): Boolean {
        val pixelAspect = (w * imageW) / (h * imageH)
        return kotlin.math.abs(pixelAspect - aspect.ratio) <= eps * aspect.ratio
    }
}

/**
 * Detected face as a normalized rect in stored-image space: (left, top) is the top-left corner,
 * w and h the size, all in [0,1] relative to the stored image's width and height.
 */
@Serializable
data class FaceBox(val left: Double, val top: Double, val w: Double, val h: Double) {
    val right: Double get() = left + w
    val bottom: Double get() = top + h
}

@Serializable
data class PhotoItem(
    val id: String,
    val fileName: String,
    val sourceUri: String?,
    val imageWidth: Int,
    val imageHeight: Int,
    val startRect: FramingRect,
    val endRect: FramingRect,
    val durationMs: Long,
    val easing: Easing = Easing.EASE_IN_OUT,
    /** Boundary AFTER this photo; null = project default. */
    val transitionOverride: TransitionType? = null,
    /** null = detection not run or failed; empty = ran and found no faces. */
    val faces: List<FaceBox>? = null,
)

@Serializable
data class Project(
    val schemaVersion: Int = 2,
    val id: String,
    val name: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val aspect: AspectRatio,
    val transition: TransitionType = TransitionType.CROSSFADE,
    val transitionDurationMs: Long = 600,
    val photos: List<PhotoItem>,
)
