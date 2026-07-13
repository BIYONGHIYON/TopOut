package com.topout.cam.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import com.topout.cam.model.ClipRange
import com.topout.cam.model.TopOutConfig
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

sealed interface MotionEvent {
    data class Start(val seconds: Double) : MotionEvent
    data class Finish(val seconds: Double) : MotionEvent
    data object Retry : MotionEvent
}

enum class MotionFinishReference {
    CLOSEST_TOP_HOLD,
    HIGHEST_PERSON_POINT
}

/**
 * Automatic clip analyzer optimized for TopOut's actual requirement: person location.
 *
 * The analyzer sleeps behind a low-cost full-frame luma comparison. Meaningful change wakes a
 * person-only EfficientDet-Lite2 detector. Once a person is found, detection continues regardless
 * of movement so a climber may pause or move slowly on the wall without losing the active track.
 *
 * Processing policy:
 * - full-frame change comparison: 4 fps;
 * - person detection and tracking: 2 fps;
 * - no person after a wake-up: return to change comparison after 1 second;
 * - tracked person missing: tolerate up to 5 seconds;
 * - CPU delegate only, avoiding device-specific OpenGL/GPU native crashes.
 */
class PersonClimbAnalyzer(
    context: Context,
    private val onEvent: (MotionEvent) -> Unit
) : ImageAnalysis.Analyzer {
    private enum class State {
        IDLE,
        CALIBRATING,
        WAITING_FOR_CHANGE,
        DETECTING_PERSON,
        TRACKING_PERSON,
        CLIMBING
    }

    private data class SmallFrame(
        val width: Int,
        val height: Int,
        val luma: ByteArray
    )

    private data class PersonBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val score: Float
    ) {
        val width: Float get() = max(0f, right - left)
        val height: Float get() = max(0f, bottom - top)
        val area: Float get() = width * height
        val centerX: Float get() = (left + right) * 0.5f
        val centerY: Float get() = (top + bottom) * 0.5f
        val topCenter: Offset get() = Offset(centerX, top)
    }

    private data class FrameCaptureRequest(
        val maxLongSide: Int,
        val result: CompletableDeferred<Bitmap?>
    )

    private val appContext = context.applicationContext
    private val frameCaptureRequest = AtomicReference<FrameCaptureRequest?>(null)

    @Volatile
    private var active = false

    @Volatile
    private var state = State.IDLE

    private var matLine: List<Offset> = emptyList()
    private var topBox: Rect = Rect.Zero
    private var finishReference = MotionFinishReference.HIGHEST_PERSON_POINT
    private var referenceMotionFrame: SmallFrame? = null
    private var previousMotionFrame: SmallFrame? = null

    private var analysisOriginTimestampNanos: Long? = null
    private var recordingOriginTimestampNanos: Long? = null
    private var recordingStartPending = false
    private var calibrationStartedTimestampNanos: Long? = null
    private var lastMotionTimestampNanos: Long? = null
    private var lastDetectorTimestampNanos: Long? = null
    private var detectorSearchStartedSeconds: Double? = null
    private var lastPersonSeenSeconds: Double? = null

    private var trackedPerson: PersonBox? = null
    private var activeStartSeconds: Double? = null
    private var activeBestTopHoldSeconds: Double? = null
    private var activeBestTopHoldDistance: Float? = null
    private var activeHighestPersonSeconds: Double? = null
    private var activeHighestPersonY: Float? = null

    private var detector: ObjectDetector? = null
    private var lastMediaPipeTimestampMs = -1L

    /** Returns the next frame in the same CameraX ViewPort used by preview, analysis, and recording. */
    suspend fun captureNextFrame(maxLongSide: Int = TopOutConfig.CaptureMaxLongSide): Bitmap? {
        val request = FrameCaptureRequest(
            maxLongSide = max(1, maxLongSide),
            result = CompletableDeferred()
        )
        frameCaptureRequest.getAndSet(request)?.result?.complete(null)
        val bitmap = withTimeoutOrNull(1_800L) { request.result.await() }
        frameCaptureRequest.compareAndSet(request, null)
        return bitmap
    }

    @Synchronized
    fun configure(
        matLine: List<Offset>,
        topBox: Rect?,
        finishReference: MotionFinishReference,
        referenceImage: Bitmap?
    ) {
        this.matLine = matLine.sortedBy { it.x }
        this.topBox = topBox?.clamped01() ?: Rect.Zero
        this.finishReference = finishReference
        referenceMotionFrame = referenceImage?.let(::smallFrameFromBitmap)
        resetStateLocked(keepConfiguration = true)
    }

    /** Uses the three-second recording countdown to establish a stable previous frame. */
    @Synchronized
    fun resetForRecording() {
        resetStateLocked(keepConfiguration = true)
        active = true
        state = State.CALIBRATING
        previousMotionFrame = referenceMotionFrame
    }

    /** Re-bases automatic-clip timestamps to the actual encoded recording start. */
    @Synchronized
    fun markRecordingStarted() {
        recordingStartPending = true
        recordingOriginTimestampNanos = null
        lastDetectorTimestampNanos = null
        detectorSearchStartedSeconds = null
        lastPersonSeenSeconds = null
        trackedPerson = null
        activeStartSeconds = null
        resetFinishReferenceTrackingLocked()
    }

    @Synchronized
    fun stop(): ClipRange? {
        val open = openClipRangeLocked()
        active = false
        resetStateLocked(keepConfiguration = true)
        return open
    }

    override fun analyze(image: ImageProxy) {
        frameCaptureRequest.getAndSet(null)?.let { request ->
            request.result.complete(
                runCatching { imageProxyToBitmap(image, request.maxLongSide) }.getOrNull()
            )
        }

        val analysisEnabled = active && matLine.isNotEmpty() &&
            (finishReference == MotionFinishReference.HIGHEST_PERSON_POINT || topBox != Rect.Zero)
        if (!analysisEnabled) {
            image.close()
            return
        }

        val timestampNanos = image.imageInfo.timestamp
        synchronized(this) {
            if (analysisOriginTimestampNanos == null) {
                analysisOriginTimestampNanos = timestampNanos
            }
            if (recordingStartPending) {
                recordingOriginTimestampNanos = timestampNanos
                recordingStartPending = false
                lastMotionTimestampNanos = null
                lastDetectorTimestampNanos = null
            }
        }

        when (state) {
            State.CALIBRATING -> analyzeCalibrationFrame(image, timestampNanos)
            State.WAITING_FOR_CHANGE -> analyzeMotionFrame(image, timestampNanos)
            State.DETECTING_PERSON,
            State.TRACKING_PERSON,
            State.CLIMBING -> analyzePersonFrame(image, timestampNanos)
            State.IDLE -> image.close()
        }
    }

    private fun analyzeCalibrationFrame(image: ImageProxy, timestampNanos: Long) {
        if (!canRunMotionAnalysis(timestampNanos)) {
            image.close()
            return
        }
        val frame = makeSmallFrame(image)
        image.close()
        if (frame == null) return

        synchronized(this) {
            if (calibrationStartedTimestampNanos == null) {
                calibrationStartedTimestampNanos = timestampNanos
            }
            previousMotionFrame = frame
            val started = calibrationStartedTimestampNanos ?: timestampNanos
            if ((timestampNanos - started) / NANOS_PER_SECOND >= TopOutConfig.MotionCountdownSeconds) {
                state = State.WAITING_FOR_CHANGE
                calibrationStartedTimestampNanos = null
                lastMotionTimestampNanos = null
            }
        }
    }

    private fun analyzeMotionFrame(image: ImageProxy, timestampNanos: Long) {
        if (!canRunMotionAnalysis(timestampNanos)) {
            image.close()
            return
        }
        val frame = makeSmallFrame(image)
        if (frame == null) {
            image.close()
            return
        }
        val previous = synchronized(this) {
            previousMotionFrame.also { previousMotionFrame = frame }
        }
        if (previous == null || previous.width != frame.width || previous.height != frame.height) {
            image.close()
            return
        }

        if (changedPixelRatio(previous, frame) < TopOutConfig.FullFrameMotionChangedPixelRatio) {
            image.close()
            return
        }

        val seconds = recordingSeconds(timestampNanos)
        synchronized(this) {
            state = State.DETECTING_PERSON
            detectorSearchStartedSeconds = seconds
            lastPersonSeenSeconds = null
            trackedPerson = null
            lastDetectorTimestampNanos = null
        }
        analyzePersonFrame(image, timestampNanos)
    }

    private fun analyzePersonFrame(image: ImageProxy, timestampNanos: Long) {
        if (!canRunPersonDetector(timestampNanos)) {
            image.close()
            return
        }

        val seconds = recordingSeconds(timestampNanos)
        val detectorBitmap = runCatching {
            imageProxyToBitmap(image, TopOutConfig.PersonDetectorInputMaxLongSide)
        }.getOrNull()
        image.close()

        if (detectorBitmap == null) {
            handlePersonMissing(seconds)
            return
        }

        val mpImage = BitmapImageBuilder(detectorBitmap).build()
        try {
            val result = requireDetector().detectForVideo(
                mpImage,
                nextMediaPipeTimestampMs(timestampNanos)
            )
            handleDetectionResult(
                result = result,
                seconds = seconds,
                inputWidth = detectorBitmap.width,
                inputHeight = detectorBitmap.height
            )
        } catch (_: RuntimeException) {
            closeDetector()
            lastMediaPipeTimestampMs = -1L
            handlePersonMissing(seconds)
        } finally {
            mpImage.close()
            detectorBitmap.recycle()
        }
    }

    private fun createCpuDetector(): ObjectDetector {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(TopOutConfig.PersonDetectorModelAssetPath)
            .setDelegate(Delegate.CPU)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setScoreThreshold(TopOutConfig.PersonDetectorScoreThreshold)
            .setCategoryAllowlist(listOf(PERSON_CATEGORY))
            .setMaxResults(TopOutConfig.PersonDetectorMaxResults)
            .build()
        return ObjectDetector.createFromOptions(appContext, options)
    }

    private fun requireDetector(): ObjectDetector =
        detector ?: createCpuDetector().also { detector = it }

    private fun closeDetector() {
        detector?.let { runCatching { it.close() } }
        detector = null
    }

    @Synchronized
    private fun nextMediaPipeTimestampMs(timestampNanos: Long): Long {
        val sourceTimestampMs = timestampNanos / 1_000_000L
        val nextTimestampMs = max(sourceTimestampMs, lastMediaPipeTimestampMs + 1L)
        lastMediaPipeTimestampMs = nextTimestampMs
        return nextTimestampMs
    }

    @Synchronized
    private fun handleDetectionResult(
        result: ObjectDetectorResult,
        seconds: Double,
        inputWidth: Int,
        inputHeight: Int
    ) {
        val candidates = result.detections().mapNotNull { detection ->
            val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
            val categoryName = category.categoryName().ifBlank { category.displayName() }
            if (!categoryName.equals(PERSON_CATEGORY, ignoreCase = true)) return@mapNotNull null
            if (inputWidth <= 0 || inputHeight <= 0) return@mapNotNull null

            val raw = detection.boundingBox()
            val left = (raw.left / inputWidth).coerceIn(0f, 1f)
            val top = (raw.top / inputHeight).coerceIn(0f, 1f)
            val right = (raw.right / inputWidth).coerceIn(left, 1f)
            val bottom = (raw.bottom / inputHeight).coerceIn(top, 1f)
            PersonBox(left, top, right, bottom, category.score())
                .takeIf { it.area >= TopOutConfig.PersonDetectorMinimumBoxAreaRatio }
        }

        val selected = selectPerson(candidates)
        if (selected == null) {
            handlePersonMissing(seconds)
            return
        }

        trackedPerson = selected
        lastPersonSeenSeconds = seconds

        if (state == State.DETECTING_PERSON) {
            state = State.TRACKING_PERSON
            detectorSearchStartedSeconds = null
        }

        when (state) {
            State.TRACKING_PERSON -> {
                if (!isPersonAboveMat(selected)) return
                val adjustedStart = max(0.0, seconds - TopOutConfig.ClipBoundaryPaddingSeconds)
                state = State.CLIMBING
                activeStartSeconds = adjustedStart
                resetFinishReferenceTrackingLocked()
                onEvent(MotionEvent.Start(adjustedStart))
                updateFinishReferenceMoment(selected, seconds)
            }

            State.CLIMBING -> {
                updateFinishReferenceMoment(selected, seconds)
                if (isPersonAboveMat(selected)) return

                val start = activeStartSeconds ?: return
                val endBase = bestFinishReferenceSeconds() ?: seconds
                val adjustedEnd = max(start + 0.1, endBase + TopOutConfig.ClipBoundaryPaddingSeconds)
                onEvent(MotionEvent.Finish(adjustedEnd))
                returnToMotionSearchLocked()
            }

            else -> Unit
        }
    }

    @Synchronized
    private fun handlePersonMissing(seconds: Double) {
        when (state) {
            State.DETECTING_PERSON -> {
                val started = detectorSearchStartedSeconds ?: seconds.also {
                    detectorSearchStartedSeconds = it
                }
                if (seconds - started >= TopOutConfig.PersonSearchWindowSeconds) {
                    returnToMotionSearchLocked()
                }
            }

            State.TRACKING_PERSON -> {
                val lastSeen = lastPersonSeenSeconds ?: detectorSearchStartedSeconds ?: seconds
                if (seconds - lastSeen >= TopOutConfig.PersonLostTimeoutSeconds) {
                    returnToMotionSearchLocked()
                }
            }

            State.CLIMBING -> {
                val lastSeen = lastPersonSeenSeconds ?: seconds
                if (seconds - lastSeen < TopOutConfig.PersonLostTimeoutSeconds) return

                val open = openClipRangeLocked()
                if (open != null) {
                    onEvent(MotionEvent.Finish(open.endSeconds))
                } else {
                    onEvent(MotionEvent.Retry)
                }
                returnToMotionSearchLocked()
            }

            else -> Unit
        }
    }

    private fun selectPerson(candidates: List<PersonBox>): PersonBox? {
        if (candidates.isEmpty()) return null
        val previous = trackedPerson
        if (previous != null) {
            val best = candidates.maxByOrNull { associationScore(previous, it) } ?: return null
            if (intersectionOverUnion(previous, best) >= 0.01f ||
                normalizedCenterDistance(previous, best) <= 0.42f
            ) {
                return best
            }
        }
        return candidates.maxByOrNull(::initialCandidateScore)
    }

    private fun initialCandidateScore(box: PersonBox): Float {
        val matY = lineY(box.centerX)
        val matCloseness = (1f - abs(box.bottom - matY) / 0.45f).coerceIn(0f, 1f)
        return box.score * 0.45f + sqrt(box.area) * 0.30f + matCloseness * 0.25f
    }

    private fun associationScore(previous: PersonBox, current: PersonBox): Float {
        val overlap = intersectionOverUnion(previous, current)
        val proximity = (1f - normalizedCenterDistance(previous, current)).coerceIn(0f, 1f)
        val areaSimilarity = if (previous.area <= 0f || current.area <= 0f) {
            0f
        } else {
            min(previous.area, current.area) / max(previous.area, current.area)
        }
        return overlap * 0.50f + proximity * 0.25f + areaSimilarity * 0.15f + current.score * 0.10f
    }

    private fun intersectionOverUnion(a: PersonBox, b: PersonBox): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = a.area + b.area - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun normalizedCenterDistance(a: PersonBox, b: PersonBox): Float =
        sqrt(
            (a.centerX - b.centerX) * (a.centerX - b.centerX) +
                (a.centerY - b.centerY) * (a.centerY - b.centerY)
        ) / sqrt(2f)

    private fun isPersonAboveMat(box: PersonBox): Boolean {
        val crossingBoundary = max(
            0f,
            lineY(box.centerX) - max(0.02f, TopOutConfig.PersonMatClearanceRatio)
        )
        return box.bottom <= crossingBoundary + TopOutConfig.PersonMatTolerance
    }

    private fun updateFinishReferenceMoment(box: PersonBox, seconds: Double) {
        when (finishReference) {
            MotionFinishReference.CLOSEST_TOP_HOLD -> {
                if (topBox == Rect.Zero) return
                val distance = pointDistanceToRect(box.topCenter, topBox)
                if (activeBestTopHoldDistance == null ||
                    distance < (activeBestTopHoldDistance ?: Float.MAX_VALUE)
                ) {
                    activeBestTopHoldDistance = distance
                    activeBestTopHoldSeconds = seconds
                }
            }

            MotionFinishReference.HIGHEST_PERSON_POINT -> {
                if (activeHighestPersonY == null ||
                    box.top < (activeHighestPersonY ?: Float.MAX_VALUE)
                ) {
                    activeHighestPersonY = box.top
                    activeHighestPersonSeconds = seconds
                }
            }
        }
    }

    private fun bestFinishReferenceSeconds(): Double? = when (finishReference) {
        MotionFinishReference.CLOSEST_TOP_HOLD -> activeBestTopHoldSeconds
        MotionFinishReference.HIGHEST_PERSON_POINT -> activeHighestPersonSeconds
    }

    @Synchronized
    private fun openClipRangeLocked(): ClipRange? {
        if (state != State.CLIMBING) return null
        val start = activeStartSeconds ?: return null
        val finishBase = bestFinishReferenceSeconds() ?: lastPersonSeenSeconds ?: return null
        val end = finishBase + TopOutConfig.ClipBoundaryPaddingSeconds
        return if (end > start) ClipRange(start, end) else null
    }

    @Synchronized
    private fun returnToMotionSearchLocked() {
        state = State.WAITING_FOR_CHANGE
        detectorSearchStartedSeconds = null
        lastPersonSeenSeconds = null
        trackedPerson = null
        lastDetectorTimestampNanos = null
        previousMotionFrame = null
        activeStartSeconds = null
        resetFinishReferenceTrackingLocked()
    }

    private fun canRunMotionAnalysis(timestampNanos: Long): Boolean {
        val intervalNanos = (NANOS_PER_SECOND / TopOutConfig.FullFrameMotionFps).toLong()
        val previous = lastMotionTimestampNanos
        if (previous != null && timestampNanos - previous < intervalNanos) return false
        lastMotionTimestampNanos = timestampNanos
        return true
    }

    private fun canRunPersonDetector(timestampNanos: Long): Boolean {
        val intervalNanos = (NANOS_PER_SECOND / TopOutConfig.PersonDetectorFps).toLong()
        val previous = lastDetectorTimestampNanos
        if (previous != null && timestampNanos - previous < intervalNanos) return false
        lastDetectorTimestampNanos = timestampNanos
        return true
    }

    private fun changedPixelRatio(previous: SmallFrame, current: SmallFrame): Float {
        if (previous.width != current.width || previous.height != current.height) return 1f
        val count = min(previous.luma.size, current.luma.size)
        if (count <= 0) return 0f

        var changed = 0
        for (index in 0 until count) {
            val oldLuma = previous.luma[index].toInt() and 0xFF
            val newLuma = current.luma[index].toInt() and 0xFF
            if (abs(oldLuma - newLuma) >= TopOutConfig.MotionPixelDifference) {
                changed += 1
            }
        }
        return changed.toFloat() / count
    }

    /** Produces an oriented/cropped luma frame in the exact CameraX ViewPort shown to the user. */
    private fun makeSmallFrame(image: ImageProxy): SmallFrame? {
        val plane = image.planes.firstOrNull() ?: return null
        val crop = cropRectInDisplay(image)
        val rotation = normalizedRotation(image.imageInfo.rotationDegrees)
        val displayWidth = if (rotation % 180 == 0) image.width else image.height
        val displayHeight = if (rotation % 180 == 0) image.height else image.width
        val cropPixelWidth = max(1f, crop.width * displayWidth)
        val cropPixelHeight = max(1f, crop.height * displayHeight)
        val outputWidth = max(32, TopOutConfig.MotionFrameWidth)
        val outputHeight = max(32, (outputWidth * cropPixelHeight / cropPixelWidth).roundToInt())
        val output = ByteArray(outputWidth * outputHeight)
        val buffer = plane.buffer.duplicate()
        val bufferBase = buffer.position()

        for (y in 0 until outputHeight) {
            val displayY = crop.top + crop.height * (y + 0.5f) / outputHeight
            for (x in 0 until outputWidth) {
                val displayX = crop.left + crop.width * (x + 0.5f) / outputWidth
                val (rawX, rawY) = displayNormalizedToRawPixel(
                    displayX,
                    displayY,
                    image.width,
                    image.height,
                    rotation
                )
                val index = bufferBase + rawY * plane.rowStride + rawX * plane.pixelStride
                output[y * outputWidth + x] =
                    if (index in bufferBase until buffer.limit()) buffer.get(index) else 0
            }
        }
        return SmallFrame(outputWidth, outputHeight, output)
    }

    private fun smallFrameFromBitmap(bitmap: Bitmap): SmallFrame? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val outputWidth = max(32, TopOutConfig.MotionFrameWidth)
        val outputHeight = max(
            32,
            (outputWidth * bitmap.height.toFloat() / bitmap.width).roundToInt()
        )
        val output = ByteArray(outputWidth * outputHeight)

        for (y in 0 until outputHeight) {
            val sourceY = min(
                bitmap.height - 1,
                ((y + 0.5f) * bitmap.height / outputHeight).toInt()
            )
            for (x in 0 until outputWidth) {
                val sourceX = min(
                    bitmap.width - 1,
                    ((x + 0.5f) * bitmap.width / outputWidth).toInt()
                )
                val color = bitmap.getPixel(sourceX, sourceY)
                val luma = (
                    Color.red(color) * 0.2126f +
                        Color.green(color) * 0.7152f +
                        Color.blue(color) * 0.0722f
                    ).roundToInt().coerceIn(0, 255)
                output[y * outputWidth + x] = luma.toByte()
            }
        }
        return SmallFrame(outputWidth, outputHeight, output)
    }

    /** Converts a YUV_420_888 frame to an oriented RGB bitmap using the shared ViewPort crop. */
    private fun imageProxyToBitmap(image: ImageProxy, maxLongSide: Int): Bitmap? {
        if (image.width <= 0 || image.height <= 0 || image.planes.size < 3) return null
        val rotation = normalizedRotation(image.imageInfo.rotationDegrees)
        val crop = cropRectInDisplay(image)
        val displayWidth = if (rotation % 180 == 0) image.width else image.height
        val displayHeight = if (rotation % 180 == 0) image.height else image.width
        val cropWidth = max(1f, crop.width * displayWidth)
        val cropHeight = max(1f, crop.height * displayHeight)
        val scale = min(1f, maxLongSide.toFloat() / max(cropWidth, cropHeight))
        val outputWidth = max(1, (cropWidth * scale).roundToInt())
        val outputHeight = max(1, (cropHeight * scale).roundToInt())

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val yBase = yBuffer.position()
        val uBase = uBuffer.position()
        val vBase = vBuffer.position()
        val pixels = IntArray(outputWidth * outputHeight)

        fun valueAt(buffer: java.nio.ByteBuffer, index: Int, fallback: Int): Int =
            if (index in 0 until buffer.limit()) buffer.get(index).toInt() and 0xFF else fallback

        for (y in 0 until outputHeight) {
            val displayY = crop.top + crop.height * (y + 0.5f) / outputHeight
            for (x in 0 until outputWidth) {
                val displayX = crop.left + crop.width * (x + 0.5f) / outputWidth
                val (rawX, rawY) = displayNormalizedToRawPixel(
                    displayX,
                    displayY,
                    image.width,
                    image.height,
                    rotation
                )
                val yIndex = yBase + rawY * yPlane.rowStride + rawX * yPlane.pixelStride
                val uvX = rawX / 2
                val uvY = rawY / 2
                val uIndex = uBase + uvY * uPlane.rowStride + uvX * uPlane.pixelStride
                val vIndex = vBase + uvY * vPlane.rowStride + uvX * vPlane.pixelStride

                val yy = valueAt(yBuffer, yIndex, 16)
                val uu = valueAt(uBuffer, uIndex, 128)
                val vv = valueAt(vBuffer, vIndex, 128)
                val c = max(0, yy - 16)
                val d = uu - 128
                val e = vv - 128
                val red = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
                val green = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                val blue = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
                pixels[y * outputWidth + x] = Color.rgb(red, green, blue)
            }
        }
        return Bitmap.createBitmap(pixels, outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
    }

    /** CameraX cropRect is sensor-oriented; this returns the normalized displayed crop. */
    private fun cropRectInDisplay(image: ImageProxy): Rect {
        val raw = image.cropRect
        val rawCorners = listOf(
            Offset(raw.left.toFloat() / image.width, raw.top.toFloat() / image.height),
            Offset(raw.right.toFloat() / image.width, raw.top.toFloat() / image.height),
            Offset(raw.left.toFloat() / image.width, raw.bottom.toFloat() / image.height),
            Offset(raw.right.toFloat() / image.width, raw.bottom.toFloat() / image.height)
        )
        val rotation = normalizedRotation(image.imageInfo.rotationDegrees)
        val displayed = rawCorners.map { rawNormalizedToDisplay(it, rotation) }
        return Rect(
            displayed.minOf { it.x },
            displayed.minOf { it.y },
            displayed.maxOf { it.x },
            displayed.maxOf { it.y }
        ).clamped01()
    }

    private fun rawNormalizedToDisplay(point: Offset, rotation: Int): Offset = when (rotation) {
        90 -> Offset(1f - point.y, point.x)
        180 -> Offset(1f - point.x, 1f - point.y)
        270 -> Offset(point.y, 1f - point.x)
        else -> point
    }

    private fun displayNormalizedToRawPixel(
        displayX: Float,
        displayY: Float,
        rawWidth: Int,
        rawHeight: Int,
        rotation: Int
    ): Pair<Int, Int> {
        val x = displayX.coerceIn(0f, 1f)
        val y = displayY.coerceIn(0f, 1f)
        val rawX: Float
        val rawY: Float
        when (rotation) {
            90 -> {
                rawX = y
                rawY = 1f - x
            }
            180 -> {
                rawX = 1f - x
                rawY = 1f - y
            }
            270 -> {
                rawX = 1f - y
                rawY = x
            }
            else -> {
                rawX = x
                rawY = y
            }
        }
        return (rawX * (rawWidth - 1)).roundToInt().coerceIn(0, rawWidth - 1) to
            (rawY * (rawHeight - 1)).roundToInt().coerceIn(0, rawHeight - 1)
    }

    private fun lineY(x: Float): Float {
        if (matLine.isEmpty()) return TopOutConfig.MatDefaultY
        if (matLine.size == 1) return matLine.first().y.coerceIn(0f, 1f)
        if (x <= matLine.first().x) return matLine.first().y.coerceIn(0f, 1f)
        if (x >= matLine.last().x) return matLine.last().y.coerceIn(0f, 1f)

        for (index in 0 until matLine.lastIndex) {
            val a = matLine[index]
            val b = matLine[index + 1]
            if (x in a.x..b.x) {
                val t = ((x - a.x) / max(0.0001f, b.x - a.x)).coerceIn(0f, 1f)
                return (a.y + (b.y - a.y) * t).coerceIn(0f, 1f)
            }
        }
        return matLine.last().y.coerceIn(0f, 1f)
    }

    private fun recordingSeconds(timestampNanos: Long): Double {
        val origin = recordingOriginTimestampNanos ?: analysisOriginTimestampNanos ?: timestampNanos
        return max(0.0, (timestampNanos - origin) / NANOS_PER_SECOND)
    }

    private fun pointDistanceToRect(point: Offset, rect: Rect): Float {
        val dx = when {
            point.x < rect.left -> rect.left - point.x
            point.x > rect.right -> point.x - rect.right
            else -> 0f
        }
        val dy = when {
            point.y < rect.top -> rect.top - point.y
            point.y > rect.bottom -> point.y - rect.bottom
            else -> 0f
        }
        return sqrt(dx * dx + dy * dy)
    }

    private fun resetFinishReferenceTrackingLocked() {
        activeBestTopHoldSeconds = null
        activeBestTopHoldDistance = null
        activeHighestPersonSeconds = null
        activeHighestPersonY = null
    }

    @Synchronized
    private fun resetStateLocked(keepConfiguration: Boolean) {
        active = false
        state = State.IDLE
        analysisOriginTimestampNanos = null
        recordingOriginTimestampNanos = null
        recordingStartPending = false
        calibrationStartedTimestampNanos = null
        lastMotionTimestampNanos = null
        lastDetectorTimestampNanos = null
        detectorSearchStartedSeconds = null
        lastPersonSeenSeconds = null
        previousMotionFrame = null
        trackedPerson = null
        activeStartSeconds = null
        resetFinishReferenceTrackingLocked()
        if (!keepConfiguration) {
            matLine = emptyList()
            topBox = Rect.Zero
            finishReference = MotionFinishReference.HIGHEST_PERSON_POINT
            referenceMotionFrame = null
        }
    }

    private fun normalizedRotation(value: Int): Int = ((value % 360) + 360) % 360

    private fun Rect.clamped01(): Rect {
        val left = this.left.coerceIn(0f, 1f)
        val top = this.top.coerceIn(0f, 1f)
        val right = this.right.coerceIn(left, 1f)
        val bottom = this.bottom.coerceIn(top, 1f)
        return Rect(left, top, right, bottom)
    }

    fun close() {
        frameCaptureRequest.getAndSet(null)?.result?.complete(null)
        closeDetector()
    }

    private companion object {
        const val PERSON_CATEGORY = "person"
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
