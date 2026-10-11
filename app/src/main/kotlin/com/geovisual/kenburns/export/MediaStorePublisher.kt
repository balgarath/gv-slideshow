package com.geovisual.kenburns.export

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Copies a finished export into [RELATIVE_DIR] (pending insert, no storage permission needed). */
object MediaStorePublisher {
    /** Single source of truth for the export folder and file name prefix. */
    const val RELATIVE_DIR = "Movies/GV-Slideshow"
    const val NAME_PREFIX = "GV-Slideshow_"

    // Names used by older builds; stale-pending cleanup still matches them.
    private const val LEGACY_RELATIVE_DIR = "Movies/KenBurns"
    private const val LEGACY_NAME_PREFIX = "KenBurns_"

    private val collection: Uri get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Blocking; call off the main thread. Returns the published uri and display name. */
    fun publish(context: Context, file: File): Pair<Uri, String> {
        val resolver = context.contentResolver
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "$NAME_PREFIX$stamp.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert returned null")
        try {
            val os = resolver.openOutputStream(uri) ?: error("could not open MediaStore output")
            os.use { o -> file.inputStream().use { it.copyTo(o) } }
            val updated = resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            if (updated == 0) error("MediaStore did not publish the video (IS_PENDING update changed 0 rows)")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri to name
    }

    /**
     * Deletes this app's still-pending entries in the export folder (current and legacy names), left when the process died
     * mid-copy. Blocking. Must not run while a publish is in flight. Returns the number deleted.
     */
    fun deleteStalePending(context: Context): Int {
        val resolver = context.contentResolver
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
                    "((${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?) OR " +
                    "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?))",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(
                    context.packageName,
                    "$RELATIVE_DIR%", "$NAME_PREFIX%",
                    "$LEGACY_RELATIVE_DIR%", "$LEGACY_NAME_PREFIX%",
                ),
            )
        }
        val ids = ArrayList<Long>()
        resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
            while (c.moveToNext()) ids += c.getLong(0)
        }
        var deleted = 0
        for (id in ids) deleted += resolver.delete(ContentUris.withAppendedId(collection, id), null, null)
        return deleted
    }
}
