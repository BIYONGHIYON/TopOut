package com.topout.cam.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
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
import java.io.IOException
import com.topout.cam.localization.tr
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Media3 counterpart of ContinuousRecorder's fixed-size writer and review export. */
@OptIn(UnstableApi::class)
class VideoExporter(private val context: Context) {
    data class VideoMetadata(
        val durationSeconds: Double?,
        val displayedWidth: Int?,
        val displayedHeight: Int?
    )


    suspend fun exportToGallery(
        inputUri: Uri,
        startSeconds: Double,
        endSeconds: Double,
        overlayBitmap: Bitmap?,
        includeOverlay: Boolean,
        onSavingToGallery: () -> Unit = {}
    ): Uri {
        require(endSeconds > startSeconds) { tr("error.invalid_clip_range") }
        val temp = File(context.cacheDir, "topout_export_${System.currentTimeMillis()}.mp4")
        temp.delete()

        return try {
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs((startSeconds * 1000).toLong())
                .setEndPositionMs((endSeconds * 1000).toLong())
                .build()
            val mediaItem = MediaItem.Builder()
                .setUri(inputUri)
                .setClippingConfiguration(clipping)
                .build()

            val effects = mutableListOf<Effect>()
            // 촬영 화면비는 이미 적용되어 있으므로 내보내기에서는 자르기와 마스크만 처리한다.
            if (includeOverlay && overlayBitmap != null) {
                effects += OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(overlayBitmap)))
            }

            val edited = EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(emptyList(), effects))
                .build()

            transform(edited, temp)
            onSavingToGallery()
            withContext(Dispatchers.IO) { copyToMediaStore(temp) }
        } finally {
            temp.delete()
        }
    }

    /** Reads all review metadata in one IO-bound pass. A malformed partial recording is non-fatal. */
    suspend fun inspectVideo(file: File): VideoMetadata? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.div(1000.0)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val displayedSize = if (width != null && height != null) {
                if (rotation % 180 == 0) width to height else height to width
            } else null
            VideoMetadata(duration, displayedSize?.first, displayedSize?.second)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private suspend fun transform(item: EditedMediaItem, output: File) = suspendCancellableCoroutine { continuation ->
        val transformer = Transformer.Builder(context)
            // 첫 영상 프레임보다 오디오가 먼저 시작하지 않도록 출력 시작 시각을 맞춘다.
            .setEnsureFileStartsOnVideoFrameEnabled(true)
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

    private suspend fun copyToMediaStore(source: File): Uri {
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
        val uri = resolver.insert(collection, values) ?: error(tr("error.photo_destination"))
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(source).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error(tr("error.open_saved_file"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
            }
            verifySavedMediaStoreVideo(uri)
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
    /**
     * Treat a save as complete only after the MediaStore row is visible again and the stored
     * resource can actually be reopened. Playback later uses this exact content URI.
     */
    private fun verifySavedMediaStoreVideo(uri: Uri) {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.MIME_TYPE
        )
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) error(tr("error.find_saved_video"))
        } ?: error(tr("error.verify_saved_video"))

        resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            if (!descriptor.fileDescriptor.valid()) {
                throw IOException(tr("error.open_saved_file"))
            }
        } ?: error(tr("error.open_saved_file"))

        resolver.openInputStream(uri)?.use { input ->
            if (input.read() < 0) error(tr("error.read_saved_video"))
        } ?: error(tr("error.read_saved_video"))

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (duration <= 0L) error(tr("error.saved_video_metadata"))
        } finally {
            retriever.release()
        }
    }

}
