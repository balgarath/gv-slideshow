package com.geovisual.kenburns.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.util.Log
import android.net.Uri
import com.geovisual.kenburns.core.imports.ImportLogic
import com.geovisual.kenburns.core.math.FramingRandomizer
import com.geovisual.kenburns.core.model.AspectRatio
import com.geovisual.kenburns.core.model.FaceBox
import com.geovisual.kenburns.core.model.PhotoItem
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class PhotoImporter(private val context: Context) {

    private companion object {
        const val TAG = "PhotoImporter"
        const val MIN_FACE_SIZE = 0.04f
        const val DETECT_TIMEOUT_MS = 8000L
    }

    data class Result(val items: List<PhotoItem>, val failedUris: List<Uri>)

    fun photosDir(projectId: String): File =
        File(context.filesDir, ImportLogic.photosDirPath(projectId)).also { it.mkdirs() }

    /**
     * Decodes each URI at <= 2560 long edge (EXIF rotation applied by ImageDecoder), writes a
     * JPEG q90 into the project photos dir, and returns PhotoItems with randomized framings
     * in the same order as [uris]. Bad URIs are reported in [Result.failedUris], not thrown.
     */
    suspend fun import(
        projectId: String,
        uris: List<Uri>,
        aspect: AspectRatio,
        seed: Long = System.nanoTime(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        val dir = photosDir(projectId)
        val randomizer = FramingRandomizer(seed)
        val items = ArrayList<PhotoItem>(uris.size)
        val failed = ArrayList<Uri>()
        withDetector { detector -> importAll(uris, dir, detector, aspect, randomizer, items, failed, onProgress) }
        Result(items, failed)
    }

    /**
     * PhotoItems for JPEGs already in the photos dir that the project does not list (photo
     * recovery). Same face-aware framing as an import; size comes from the file. [files] pairs a
     * photo id with its file. Files that do not decode are skipped and left where they are.
     */
    suspend fun adopt(
        files: List<Pair<String, File>>,
        aspect: AspectRatio,
        seed: Long = System.nanoTime(),
    ): List<PhotoItem> = withContext(Dispatchers.IO) {
        val randomizer = FramingRandomizer(seed)
        val items = ArrayList<PhotoItem>(files.size)
        withDetector { detector ->
            for ((id, file) in files) {
                try {
                    val bitmap = BitmapFactory.decodeFile(file.path) ?: continue
                    try {
                        val faces = detectFaces(detector, bitmap)
                        items += ImportLogic.newPhotoItem(id, null, bitmap.width, bitmap.height, aspect, randomizer, faces)
                            .copy(fileName = file.name)
                    } finally {
                        bitmap.recycle()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Includes OutOfMemoryError: one huge file is skipped, recovery goes on.
                    Log.w(TAG, "could not adopt ${file.name}", e)
                }
            }
        }
        items
    }

    /** Face detection is optional: any failure creating or closing the detector means no faces. */
    private inline fun <T> withDetector(block: (FaceDetector?) -> T): T {
        val detector: FaceDetector? = try {
            FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .setMinFaceSize(MIN_FACE_SIZE)
                    .build(),
            )
        } catch (e: Throwable) {
            Log.w(TAG, "face detector unavailable, importing without faces", e)
            ErrorLog.record(context, "face detector unavailable (import continues without faces)", e)
            null
        }
        try {
            return block(detector)
        } finally {
            try { detector?.close() } catch (e: Throwable) { Log.w(TAG, "detector close failed", e) }
        }
    }

    private suspend fun importAll(
        uris: List<Uri>, dir: File, detector: FaceDetector?, aspect: AspectRatio, randomizer: FramingRandomizer,
        items: MutableList<PhotoItem>, failed: MutableList<Uri>, onProgress: (Int, Int) -> Unit,
    ) {
        uris.forEachIndexed { index, uri ->
            val id = UUID.randomUUID().toString()
            val file = File(dir, ImportLogic.photoFileName(id))
            try {
                val stored = decodeAndStore(uri, file, detector)
                items += ImportLogic.newPhotoItem(
                    id, uri.toString(), stored.width, stored.height, aspect, randomizer, stored.faces,
                )
            } catch (e: CancellationException) {
                file.delete()
                throw e
            } catch (e: Exception) {
                file.delete()
                failed += uri
            }
            onProgress(index + 1, uris.size)
        }
    }

    private class Stored(val width: Int, val height: Int, val faces: List<FaceBox>?)

    /** Face boxes as fractions of the bitmap, or null if detection failed. Never throws except on cancel. */
    private suspend fun detectFaces(detector: FaceDetector?, bitmap: Bitmap): List<FaceBox>? = if (detector == null) null else try {
        val w = bitmap.width.toDouble(); val h = bitmap.height.toDouble()
        // Bitmap is already upright (EXIF baked in by ImageDecoder), so rotation is 0.
        withTimeoutOrNull(DETECT_TIMEOUT_MS) { detector.process(InputImage.fromBitmap(bitmap, 0)).await() }?.mapNotNull { face ->
            val b = face.boundingBox
            val l = (b.left / w).coerceIn(0.0, 1.0); val t = (b.top / h).coerceIn(0.0, 1.0)
            val r = (b.right / w).coerceIn(0.0, 1.0); val bt = (b.bottom / h).coerceIn(0.0, 1.0)
            if (r - l > 0.0 && bt - t > 0.0) FaceBox(l, t, r - l, bt - t) else null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "face detection failed, framing without faces", e)
        null
    }

    /** Writes the downscaled JPEG and runs face detection on the same bitmap. */
    private suspend fun decodeAndStore(uri: Uri, out: File, detector: FaceDetector?): Stored {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val target = ImportLogic.targetSize(info.size.width, info.size.height)
            decoder.setTargetSize(target.width, target.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            val tmp = File(out.parentFile, out.name + ".tmp")
            FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, ImportLogic.JPEG_QUALITY, it) }
            if (!tmp.renameTo(out)) throw java.io.IOException("rename failed for ${out.name}")
            return Stored(bitmap.width, bitmap.height, detectFaces(detector, bitmap))
        } finally {
            bitmap.recycle()
        }
    }
}
