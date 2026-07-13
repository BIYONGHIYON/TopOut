package com.topout.cam.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.topout.cam.detection.PersonClimbAnalyzer
import com.topout.cam.model.CameraCapabilities
import com.topout.cam.model.RecordingFrameRate
import com.topout.cam.model.RecordingResolution
import com.topout.cam.model.RecordingStopNotice
import com.topout.cam.model.TopOutConfig
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Test-only camera replacement.
 *
 * A selected MOV/MP4 file is shown as an endlessly looping camera preview. During a test recording,
 * playback restarts at 0 and continues looping until the user taps stop. Every decoded frame is fed
 * to the real motion + MediaPipe pipeline, and the output is re-muxed as a continuous looped MP4 so
 * review timestamps stay aligned with the simulated camera timeline.
 */
class MockVideoEngine(
    private val context: Context,
    private val analyzer: PersonClimbAnalyzer
) {
    data class FinalizedRecording(
        val file: File,
        val durationSeconds: Double,
        val notice: RecordingStopNotice? = null
    )

    data class SourceInfo(
        val uri: Uri,
        val displayName: String,
        val durationSeconds: Double,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val extractionInFlight = AtomicBoolean(false)
    private val finalizeInFlight = AtomicBoolean(false)
    private val retrieverLock = Any()

    private val player = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_ALL
        playWhenReady = true
        volume = 0f
    }

    private var playerView: PlayerView? = null
    private var previewRenderingEnabled = true
    private var sourceInfo: SourceInfo? = null
    private var retriever: MediaMetadataRetriever? = null
    private var firstFrame: Bitmap? = null
    private var calibrating = false
    private var recording = false
    private var recordingStartedAtNanos = 0L
    private var analysisTickerRunning = false
    private var targetWidthOverHeight = 9f / 16f

    var onRecordingStarted: (() -> Unit)? = null
    var onRecordingProgress: ((Double) -> Unit)? = null
    var onRecordingFinalized: ((Result<FinalizedRecording>) -> Unit)? = null

    suspend fun loadBundledSourceIfPresent(): SourceInfo? {
        val available = runCatching {
            context.assets.list(BUNDLED_ASSET_DIRECTORY)?.contains(BUNDLED_ASSET_FILENAME) == true
        }.getOrDefault(false)
        if (!available) return null

        val destination = File(context.cacheDir, BUNDLED_ASSET_FILENAME)
        context.assets.open("$BUNDLED_ASSET_DIRECTORY/$BUNDLED_ASSET_FILENAME").use { input ->
            destination.outputStream().buffered().use { output ->
                input.copyTo(output)
            }
        }
        return setSource(Uri.fromFile(destination), BUNDLED_ASSET_FILENAME)
    }

    suspend fun setSource(uri: Uri, preferredName: String? = null): SourceInfo =
        suspendCancellableCoroutine { continuation ->
            analysisExecutor.execute {
                var newRetriever: MediaMetadataRetriever? = null
                try {
                    val preparedRetriever = MediaMetadataRetriever().apply {
                        setDataSource(context, uri)
                    }
                    newRetriever = preparedRetriever
                    val durationMs = preparedRetriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.coerceAtLeast(1L)
                        ?: throw IllegalArgumentException("동영상 길이를 읽지 못했습니다.")
                    val width = preparedRetriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull()
                        ?.coerceAtLeast(1)
                        ?: 1
                    val height = preparedRetriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull()
                        ?.coerceAtLeast(1)
                        ?: 1
                    val rotation = preparedRetriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull()
                        ?: 0
                    val name = preferredName ?: queryDisplayName(uri) ?: "test.MOV"
                    val info = SourceInfo(
                        uri = uri,
                        displayName = name,
                        durationSeconds = durationMs / 1_000.0,
                        width = width,
                        height = height,
                        rotationDegrees = rotation
                    )
                    val initialFrame = frameAt(
                        activeRetriever = preparedRetriever,
                        info = info,
                        timeUs = 0L,
                        maxLongSide = TopOutConfig.CaptureMaxLongSide
                    ) ?: throw IllegalArgumentException("동영상의 첫 프레임을 읽지 못했습니다.")

                    synchronized(retrieverLock) {
                        retriever?.release()
                        retriever = preparedRetriever
                        newRetriever = null
                        firstFrame?.recycle()
                        firstFrame = initialFrame
                        sourceInfo = info
                    }

                    mainHandler.post {
                        player.setMediaItem(MediaItem.fromUri(uri))
                        player.repeatMode = Player.REPEAT_MODE_ALL
                        player.prepare()
                        player.seekTo(0L)
                        player.playWhenReady = true
                        attachPlayerIfNeeded()
                        if (continuation.isActive) continuation.resume(info)
                    }
                } catch (error: Throwable) {
                    runCatching { newRetriever?.release() }
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }

    fun bind(view: PlayerView, widthOverHeight: Float): CameraCapabilities {
        playerView = view
        targetWidthOverHeight = widthOverHeight.coerceAtLeast(0.01f)
        attachPlayerIfNeeded()
        analysisExecutor.execute {
            synchronized(retrieverLock) {
                val activeRetriever = retriever
                val info = sourceInfo
                if (activeRetriever != null && info != null) {
                    val refreshed = frameAt(
                        activeRetriever,
                        info,
                        0L,
                        TopOutConfig.CaptureMaxLongSide
                    )
                    if (refreshed != null) {
                        firstFrame?.recycle()
                        firstFrame = refreshed
                    }
                }
            }
        }
        sourceInfo?.let {
            player.repeatMode = Player.REPEAT_MODE_ALL
            if (player.playbackState == Player.STATE_IDLE) {
                player.setMediaItem(MediaItem.fromUri(it.uri))
                player.prepare()
            }
            if (!recording && !calibrating) {
                player.playWhenReady = true
            }
        }
        return CameraCapabilities(
            supportedFrameRates = mapOf(
                RecordingResolution.FHD to RecordingFrameRate.entries.toSet(),
                RecordingResolution.UHD to RecordingFrameRate.entries.toSet()
            ),
            minZoomRatio = 1f,
            maxZoomRatio = 1f,
            lensZoomRatios = listOf(1f)
        )
    }

    fun isAnalysisAvailable(): Boolean = sourceInfo != null

    fun beginCalibration() {
        if (sourceInfo == null) return
        recording = false
        calibrating = true
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.pause()
        player.seekTo(0L)
        startAnalysisTicker()
    }

    fun cancelCalibration() {
        calibrating = false
        recording = false
        stopAnalysisTicker()
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.seekTo(0L)
        player.playWhenReady = true
    }

    fun startRecording(includeAudio: Boolean = true) {
        if (sourceInfo == null || recording) {
            if (sourceInfo == null) {
                onRecordingFinalized?.invoke(
                    Result.failure(IllegalStateException("번들 테스트 영상을 불러오지 못했습니다."))
                )
            }
            return
        }
        finalizeInFlight.set(false)
        calibrating = false
        recording = true
        recordingStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.seekTo(0L)
        player.playWhenReady = true
        startAnalysisTicker()
        onRecordingStarted?.invoke()
    }

    fun stopRecording(notice: RecordingStopNotice? = null) {
        if (!recording && !calibrating) return
        finalizeRecording(notice)
    }

    fun setPreviewRenderingEnabled(enabled: Boolean): Boolean {
        previewRenderingEnabled = enabled
        attachPlayerIfNeeded()
        return true
    }

    fun setZoom(zoom: Float) = Unit
    fun updateTargetRotation(rotation: Int) = Unit

    /** Returns the source's first frame so selection, calibration and looped recording align. */
    suspend fun captureCurrentFrame(maxLongSide: Int): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            analysisExecutor.execute {
                try {
                    val result = synchronized(retrieverLock) {
                        val activeRetriever = retriever ?: return@synchronized null
                        val info = sourceInfo ?: return@synchronized null
                        frameAt(activeRetriever, info, 0L, maxLongSide)
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }

    fun unbind() {
        player.pause()
        playerView?.player = null
        playerView = null
        calibrating = false
        recording = false
        stopAnalysisTicker()
    }

    fun executeOnAnalysisThread(block: () -> Unit) {
        analysisExecutor.execute(block)
    }

    fun release() {
        stopAnalysisTicker()
        mainHandler.post { player.release() }
        analysisExecutor.execute {
            synchronized(retrieverLock) {
                retriever?.release()
                retriever = null
                firstFrame?.recycle()
                firstFrame = null
                sourceInfo = null
            }
        }
        analysisExecutor.shutdown()
    }

    private fun attachPlayerIfNeeded() {
        playerView?.player = if (previewRenderingEnabled) player else null
    }

    private fun startAnalysisTicker() {
        if (analysisTickerRunning) return
        analysisTickerRunning = true
        mainHandler.post(analysisTicker)
    }

    private fun stopAnalysisTicker() {
        analysisTickerRunning = false
        mainHandler.removeCallbacks(analysisTicker)
    }

    private val analysisTicker = object : Runnable {
        override fun run() {
            if (!analysisTickerRunning) return
            val shouldAnalyze = calibrating || recording
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val elapsedNanos = if (recording) {
                max(0L, nowNanos - recordingStartedAtNanos)
            } else {
                0L
            }
            val sourceDurationMs = max(
                1L,
                ((sourceInfo?.durationSeconds ?: 0.001) * 1_000.0).toLong()
            )
            val mediaPositionMs = if (recording) {
                (elapsedNanos / 1_000_000L) % sourceDurationMs
            } else {
                0L
            }
            if (shouldAnalyze && extractionInFlight.compareAndSet(false, true)) {
                analysisExecutor.execute {
                    var frame: Bitmap? = null
                    try {
                        frame = synchronized(retrieverLock) {
                            if (calibrating) {
                                firstFrame?.copy(Bitmap.Config.ARGB_8888, false)
                            } else {
                                val activeRetriever = retriever
                                val info = sourceInfo
                                if (activeRetriever == null || info == null) null
                                else frameAt(
                                    activeRetriever,
                                    info,
                                    mediaPositionMs * 1_000L,
                                    MOCK_ANALYSIS_LONG_SIDE
                                )
                            }
                        }
                        frame?.let { analyzer.analyzeBitmap(it, nowNanos) }
                    } catch (_: RuntimeException) {
                        // MediaMetadataRetriever and native vision code can reject an individual
                        // frame. Drop that frame instead of allowing the executor exception to
                        // terminate the app process.
                    } finally {
                        frame?.takeIf { !it.isRecycled }?.recycle()
                        extractionInFlight.set(false)
                    }
                }
            }
            if (recording) {
                onRecordingProgress?.invoke(elapsedNanos / 1_000_000_000.0)
            }
            mainHandler.postDelayed(this, ANALYSIS_INTERVAL_MILLIS)
        }
    }

    private fun finalizeRecording(notice: RecordingStopNotice?) {
        if (!finalizeInFlight.compareAndSet(false, true)) return
        val info = sourceInfo
        recording = false
        calibrating = false
        stopAnalysisTicker()
        player.pause()
        if (info == null) {
            onRecordingFinalized?.invoke(
                Result.failure(IllegalStateException("Mock 동영상 소스가 없습니다."))
            )
            return
        }

        val recordedDurationSeconds = max(
            0.1,
            (SystemClock.elapsedRealtimeNanos() - recordingStartedAtNanos) / 1_000_000_000.0
        )
        analysisExecutor.execute {
            val result = runCatching {
                val output = File(context.cacheDir, "topout_mock_${System.currentTimeMillis()}.mp4")
                createLoopedRecording(
                    sourceUri = info.uri,
                    output = output,
                    targetDurationUs = (recordedDurationSeconds * 1_000_000.0).toLong()
                )
                check(output.length() > 0L) { "Mock 결과 파일이 생성되지 않았습니다." }
                FinalizedRecording(output, recordedDurationSeconds, notice)
            }.recoverCatching {
                val sourceFile = info.uri.path?.let(::File)
                    ?.takeIf { it.exists() && it.length() > 0L }
                    ?: throw IllegalStateException("Mock 결과 파일을 준비하지 못했습니다.")
                val fallback = File(
                    context.cacheDir,
                    "topout_mock_fallback_${System.currentTimeMillis()}.mov"
                )
                sourceFile.copyTo(fallback, overwrite = true)
                FinalizedRecording(
                    file = fallback,
                    durationSeconds = min(recordedDurationSeconds, info.durationSeconds),
                    notice = notice
                )
            }
            mainHandler.post { onRecordingFinalized?.invoke(result) }
        }
    }

    /** Re-muxes only the video track repeatedly, so mock playback and review remain silent. */
    private fun createLoopedRecording(
        sourceUri: Uri,
        output: File,
        targetDurationUs: Long
    ) {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(
            output.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        var muxerStarted = false
        try {
            extractor.setDataSource(context, sourceUri, null)
            check(extractor.trackCount > 0) { "동영상 트랙을 찾지 못했습니다." }

            var sourceVideoTrack = -1
            var videoFormat: MediaFormat? = null
            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    sourceVideoTrack = trackIndex
                    videoFormat = format
                    break
                }
            }
            check(sourceVideoTrack >= 0 && videoFormat != null) { "비디오 트랙을 찾지 못했습니다." }

            val activeVideoFormat = requireNotNull(videoFormat)
            val outputVideoTrack = muxer.addTrack(activeVideoFormat)
            extractor.selectTrack(sourceVideoTrack)

            val rotation = synchronized(retrieverLock) {
                retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull()
            }
            if (rotation != null && rotation % 360 != 0) muxer.setOrientationHint(rotation)

            muxer.start()
            muxerStarted = true

            val configuredDurationUs = if (activeVideoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                activeVideoFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            val sourceDurationUs = max(
                1L,
                if (configuredDurationUs > 0L) configuredDurationUs else {
                    (sourceInfo?.durationSeconds?.times(1_000_000.0) ?: 1.0).toLong()
                }
            )
            val maximumInputSize = if (activeVideoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                max(1_048_576, activeVideoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            } else {
                16 * 1_048_576
            }
            val buffer = ByteBuffer.allocateDirect(maximumInputSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var loopOffsetUs = 0L

            while (loopOffsetUs < targetDurationUs) {
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                var firstSourceTimeUs = -1L
                var wroteSample = false

                while (true) {
                    val sourceTrackIndex = extractor.sampleTrackIndex
                    if (sourceTrackIndex < 0) break
                    if (sourceTrackIndex != sourceVideoTrack) {
                        if (!extractor.advance()) break
                        continue
                    }

                    val sourceTimeUs = extractor.sampleTime
                    if (sourceTimeUs < 0L) break
                    if (firstSourceTimeUs < 0L) firstSourceTimeUs = sourceTimeUs
                    val normalizedSourceTimeUs = max(0L, sourceTimeUs - firstSourceTimeUs)
                    val outputTimeUs = loopOffsetUs + normalizedSourceTimeUs
                    if (outputTimeUs >= targetDurationUs) break

                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    bufferInfo.set(
                        0,
                        sampleSize,
                        outputTimeUs,
                        extractor.sampleFlags
                    )
                    muxer.writeSampleData(outputVideoTrack, buffer, bufferInfo)
                    wroteSample = true
                    if (!extractor.advance()) break
                }

                check(wroteSample) { "비디오 샘플을 읽지 못했습니다." }
                loopOffsetUs += sourceDurationUs
            }
        } finally {
            extractor.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
            if (!output.exists() || output.length() == 0L) output.delete()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) null
                    else cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                }
        }.getOrNull()
    }

    private fun frameAt(
        activeRetriever: MediaMetadataRetriever,
        info: SourceInfo,
        timeUs: Long,
        maxLongSide: Int
    ): Bitmap? {
        val safeLongSide = max(64, maxLongSide)
        val rawScale = min(
            1f,
            safeLongSide.toFloat() / max(info.width, info.height).coerceAtLeast(1)
        )
        val decodeWidth = max(1, (info.width * rawScale).roundToInt())
        val decodeHeight = max(1, (info.height * rawScale).roundToInt())
        val safeTimeUs = timeUs.coerceIn(
            0L,
            max(0L, (info.durationSeconds * 1_000_000.0).toLong() - 1L)
        )

        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activeRetriever.getScaledFrameAtTime(
                safeTimeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                decodeWidth,
                decodeHeight
            )
        } else {
            val original = activeRetriever.getFrameAtTime(
                safeTimeUs,
                MediaMetadataRetriever.OPTION_CLOSEST
            ) ?: return null
            if (original.width == decodeWidth && original.height == decodeHeight) {
                original
            } else {
                Bitmap.createScaledBitmap(original, decodeWidth, decodeHeight, true).also {
                    if (it !== original) original.recycle()
                }
            }
        } ?: return null

        return transformFrame(
            source = decoded,
            rotationDegrees = info.rotationDegrees,
            widthOverHeight = targetWidthOverHeight,
            maxLongSide = safeLongSide
        )
    }

    private fun transformFrame(
        source: Bitmap,
        rotationDegrees: Int,
        widthOverHeight: Float,
        maxLongSide: Int
    ): Bitmap {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        var working = if (normalizedRotation == 0) {
            source
        } else {
            Bitmap.createBitmap(
                source,
                0,
                0,
                source.width,
                source.height,
                Matrix().apply { postRotate(normalizedRotation.toFloat()) },
                true
            ).also { if (it !== source) source.recycle() }
        }

        val targetRatio = widthOverHeight.coerceAtLeast(0.01f)
        val currentRatio = working.width.toFloat() / working.height.coerceAtLeast(1)
        val cropWidth: Int
        val cropHeight: Int
        if (currentRatio > targetRatio) {
            cropHeight = working.height
            cropWidth = max(1, (cropHeight * targetRatio).roundToInt()).coerceAtMost(working.width)
        } else {
            cropWidth = working.width
            cropHeight = max(1, (cropWidth / targetRatio).roundToInt()).coerceAtMost(working.height)
        }
        if (cropWidth != working.width || cropHeight != working.height) {
            val cropped = Bitmap.createBitmap(
                working,
                (working.width - cropWidth) / 2,
                (working.height - cropHeight) / 2,
                cropWidth,
                cropHeight
            )
            if (cropped !== working) working.recycle()
            working = cropped
        }

        val scale = min(
            1f,
            maxLongSide.toFloat() / max(working.width, working.height).coerceAtLeast(1)
        )
        val outputWidth = max(1, (working.width * scale).roundToInt())
        val outputHeight = max(1, (working.height * scale).roundToInt())
        if (outputWidth == working.width && outputHeight == working.height) return working

        return Bitmap.createScaledBitmap(working, outputWidth, outputHeight, true).also {
            if (it !== working) working.recycle()
        }
    }

    private companion object {
        const val BUNDLED_ASSET_DIRECTORY = "mock"
        const val BUNDLED_ASSET_FILENAME = "test.MOV"
        const val ANALYSIS_INTERVAL_MILLIS = 250L
        const val MOCK_ANALYSIS_LONG_SIDE = 512
    }
}
