package com.topout.cam.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Media3 counterpart of ContinuousRecorder's fixed-size writer and review export. */
class VideoExporter(private val context: Context) {


    suspend fun exportToGallery(
        inputUri: Uri,
        startSeconds: Double,
        endSeconds: Double,
        overlayBitmap: Bitmap?,
        includeOverlay: Boolean,
        onProgress: (Int) -> Unit = {},
        onSavingToGallery: () -> Unit = {}
    ): Uri {
        require(endSeconds > startSeconds) { "끝점은 시작점보다 뒤여야 합니다." }
        val temp = File(context.cacheDir, "topout_export_${System.currentTimeMillis()}.mp4")
        temp.delete()

        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs((startSeconds * 1000).toLong())
            .setEndPositionMs((endSeconds * 1000).toLong())
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(inputUri)
            .setClippingConfiguration(clipping)
            .build()

        val effects = mutableListOf<Effect>()
        // Recorder output already uses the selected camera ViewPort. Export only trims or adds the hold mask.
        if (includeOverlay && overlayBitmap != null) {
            effects += OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(overlayBitmap)))
        }

        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), effects))
            .build()

        onProgress(0)
        transform(edited, temp)
        onSavingToGallery()
        return try {
            copyToMediaStore(temp)
        } finally {
            temp.delete()
        }
    }

    /** Returns the displayed width/height after applying the file rotation metadata. */
    fun videoDimensions(file: File): Pair<Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: return null
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (rotation % 180 == 0) width to height else height to width
        } finally {
            retriever.release()
        }
    }

    fun durationSeconds(file: File): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
        } finally {
            retriever.release()
        }
    }

    private suspend fun transform(item: EditedMediaItem, output: File) = suspendCancellableCoroutine { continuation ->
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    output.delete()
                    if (continuation.isActive) continuation.resumeWithException(exportException)
                }
            })
            .build()

        continuation.invokeOnCancellation {
            transformer.cancel()
            output.delete()
        }
        transformer.start(item, output.absolutePath)
    }

    private fun copyToMediaStore(source: File): Uri {
        val resolver = context.contentResolver
        val name = "TopOut_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TopOut")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: error("사진 앱 저장 위치를 만들지 못했습니다.")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            } ?: error("저장 파일을 열지 못했습니다.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
            }
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
