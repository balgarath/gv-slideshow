package com.geovisual.kenburns.core.imports

import com.geovisual.kenburns.core.math.FramingRandomizer
import com.geovisual.kenburns.core.math.KenBurnsMath
import com.geovisual.kenburns.core.model.AspectRatio
import com.geovisual.kenburns.core.model.Easing
import com.geovisual.kenburns.core.model.FaceBox
import com.geovisual.kenburns.core.model.FramingRect
import com.geovisual.kenburns.core.model.PhotoItem
import kotlin.math.max
import kotlin.math.roundToInt

/** Pure bookkeeping for photo import; the Android decode lives in the app module. */
object ImportLogic {
    const val MAX_LONG_EDGE = 2560
    const val JPEG_QUALITY = 90

    data class Size(val width: Int, val height: Int)

    /** Scales to fit [maxEdge] on the long side, preserving aspect. Never upscales. */
    fun targetSize(width: Int, height: Int, maxEdge: Int = MAX_LONG_EDGE): Size {
        require(width > 0 && height > 0) { "bad size ${width}x$height" }
        val longEdge = max(width, height)
        if (longEdge <= maxEdge) return Size(width, height)
        val scale = maxEdge.toDouble() / longEdge
        return Size(
            (width * scale).roundToInt().coerceIn(1, maxEdge),
            (height * scale).roundToInt().coerceIn(1, maxEdge),
        )
    }

    fun photoFileName(photoId: String): String = "$photoId.jpg"

    /** Path relative to the app files dir. */
    fun photosDirPath(projectId: String): String = "projects/$projectId/photos"

    /**
     * Builds a PhotoItem with randomized framings for a stored copy of the given
     * post-rotation pixel size.
     */
    fun newPhotoItem(
        photoId: String,
        sourceUri: String?,
        storedWidth: Int,
        storedHeight: Int,
        aspect: AspectRatio,
        randomizer: FramingRandomizer,
        faces: List<FaceBox>? = null,
    ): PhotoItem {
        val f = randomizer.next(storedWidth, storedHeight, aspect, faces)
        return PhotoItem(
            id = photoId,
            fileName = photoFileName(photoId),
            sourceUri = sourceUri,
            imageWidth = storedWidth,
            imageHeight = storedHeight,
            startRect = f.start,
            endRect = f.end,
            durationMs = f.durationMs,
            easing = Easing.EASE_IN_OUT,
            faces = faces,
        )
    }

    /**
     * New random start/end framings (face-aware when [photo] has faces) for [photo] at a different output [aspect]. Duration,
     * easing and file bookkeeping are kept, so changing the aspect does not change timing.
     */
    fun reframe(photo: PhotoItem, aspect: AspectRatio, randomizer: FramingRandomizer): PhotoItem {
        val f = randomizer.next(photo.imageWidth, photo.imageHeight, aspect, photo.faces)
        return photo.copy(startRect = f.start, endRect = f.end)
    }

    /**
     * Converts [rect] (valid for [from]) to the same framing at output aspect [to]: same center and
     * same zoom level relative to the base crop, then clamped into the image. Hand-tuned framing
     * therefore survives an aspect change. Zoom below 1 is raised to 1.
     */
    fun convertRect(rect: FramingRect, imageW: Int, imageH: Int, from: AspectRatio, to: AspectRatio): FramingRect {
        val zoom = KenBurnsMath.zoomOf(rect, imageW, imageH, from).coerceAtLeast(1.0)
        return KenBurnsMath.rectForZoom(imageW, imageH, to, zoom, rect.cx, rect.cy)
    }

    /** Like [reframe] but keeps each rect's center and zoom level instead of re-randomizing. */
    fun convertAspect(photo: PhotoItem, from: AspectRatio, to: AspectRatio): PhotoItem = photo.copy(
        startRect = convertRect(photo.startRect, photo.imageWidth, photo.imageHeight, from, to),
        endRect = convertRect(photo.endRect, photo.imageWidth, photo.imageHeight, from, to),
    )
}
