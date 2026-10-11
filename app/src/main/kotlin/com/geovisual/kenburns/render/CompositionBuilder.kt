package com.geovisual.kenburns.render

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.geovisual.kenburns.core.export.ExportEstimate
import com.geovisual.kenburns.core.model.Project
import com.geovisual.kenburns.core.timeline.Timeline
import com.geovisual.kenburns.core.timeline.keepingPhotos
import java.io.File

/**
 * Builds the Transformer composition for a project (design section 5): one video sequence of
 * image items in project order, each with its own duration, 60 fps, and one [KenBurnsEffect]
 * that knows the item's global start time.
 */
@OptIn(UnstableApi::class)
object CompositionBuilder {
    const val FRAME_RATE = ExportEstimate.FRAME_RATE

    /** [skipped] counts listed photos whose file was missing and were left out. */
    class Built(val composition: Composition, val skipped: Int)

    /** Missing photo files are skipped (not fatal); fails only when none are left. */
    fun build(listed: Project, photosDir: File): Built {
        // keepingPhotos hands a dropped photo's boundary override to the kept photo before it.
        val project = listed.keepingPhotos { File(photosDir, it.fileName).isFile }
        val skipped = listed.photos.size - project.photos.size
        require(project.photos.isNotEmpty()) { if (skipped > 0) "none of the photo files could be found" else "no photos" }
        val timeline = Timeline(project)
        val items = project.photos.mapIndexed { i, photo ->
            val file = File(photosDir, photo.fileName)
            // Per-boundary type comes from the Timeline, which applies PhotoItem.transitionOverride.
            val nextPath = if (timeline.needsNextPhoto(i)) {
                File(photosDir, project.photos[i + 1].fileName).path
            } else null
            val spec = ItemRenderSpec(
                timeline = timeline,
                index = i,
                startUs = timeline.startMs(i) * 1000,
                durationUs = photo.durationMs * 1000,
                nextPhotoPath = nextPath,
                outputWidth = project.aspect.w,
                outputHeight = project.aspect.h,
            )
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.fromFile(file))
                .setMimeType(MimeTypes.IMAGE_JPEG)
                // Required: DefaultAssetLoaderFactory only routes to the image loader when set.
                .setImageDurationMs(photo.durationMs)
                .build()
            EditedMediaItem.Builder(mediaItem)
                .setDurationUs(photo.durationMs * 1000)
                .setFrameRate(FRAME_RATE)
                .setEffects(Effects(emptyList(), listOf(KenBurnsEffect(spec))))
                .build()
        }
        return Built(Composition.Builder(EditedMediaItemSequence.withVideoFrom(items)).build(), skipped)
    }
}
