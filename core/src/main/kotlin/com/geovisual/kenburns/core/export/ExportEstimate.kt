package com.geovisual.kenburns.core.export

import com.geovisual.kenburns.core.model.AspectRatio
import java.util.Locale

/**
 * Export bitrate table and the size estimate built from it. Single source of truth: `Exporter`
 * passes [bitrateBps] to Media3's `VideoEncoderSettings.setBitrate` (H.264, VBR), and the
 * estimate multiplies the same number by the length.
 *
 * Why explicit: with no requested bitrate, Media3 1.11.1 `DefaultEncoderFactory` falls back to the
 * Kush Gauge `w * h * fps * 0.07 * 2`, and on device it used 30 fps there although the output is
 * 60 fps (a 1080x1080 export measured 4.89 Mbps, the gauge's 30 fps value, with 1416 frames over
 * 23.60 s). That halved the per-frame bitrate and made the old estimate twice the real size.
 * Values: 20 Mbps for 1080x1920 and 1920x1080 at 60 fps, 12 Mbps for 1080x1080, a little above
 * the 60 fps gauge (17.4 and 9.8 Mbps). VBR means the real file can come in somewhat smaller.
 */
object ExportEstimate {
    const val FRAME_RATE = 60

    /** Requested encoder bitrate, bits per second. */
    fun bitrateBps(aspect: AspectRatio): Int = when (aspect) {
        AspectRatio.V9x16, AspectRatio.H16x9 -> 20_000_000
        AspectRatio.S1x1 -> 12_000_000
    }

    /** Video-only (no audio track), MP4 container overhead ignored (well under 1%). */
    fun sizeBytes(aspect: AspectRatio, durationMs: Long): Long =
        bitrateBps(aspect).toLong() * durationMs / 8000L

    /** "m:ss" for a length. */
    fun formatLength(durationMs: Long): String {
        val s = (durationMs + 500) / 1000
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    /** Actual file size, "63 MB" or "1.2 GB" (no "~"), for comparing with [formatSize]. */
    fun formatActualSize(bytes: Long): String {
        val mb = bytes / 1_000_000.0
        return if (mb >= 1000.0) String.format(Locale.US, "%.1f GB", mb / 1000.0)
        else String.format(Locale.US, "%d MB", Math.round(mb))
    }

    /** "~12 MB" or "~1.2 GB"; empty length gives "~0 MB". */
    fun formatSize(bytes: Long): String {
        val mb = bytes / 1_000_000.0
        return if (mb >= 1000.0) String.format(Locale.US, "~%.1f GB", mb / 1000.0)
        else String.format(Locale.US, "~%d MB", Math.round(mb))
    }
}
