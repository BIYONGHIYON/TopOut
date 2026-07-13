package com.topout.cam.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

sealed interface MotionEvent {
    data class Start(val seconds: Double) : MotionEvent
    data class Finish(val seconds: Double) : MotionEvent
    data object Retry : MotionEvent
}

enum class MotionFinishReference {
    CLOSEST_TOP_HOLD,
    HIGHEST_POSE_POINT
}

/**
 * Mock-only automatic clip analyzer.
 *
 * IDLE flow:
 * 1) Compare consecutive low-resolution full frames at 4 fps.
 * 2) When meaningful change is found, run a person-only EfficientDet-Lite2 detector at 2 fps.
 * 3) If no person is found for one second, return to frame-change detection.
 * 4) Once a person is found, use only the person's bounding box and edited mat line to determine
 *    climb start/end. No pose landmarks are evaluated.
 */
class PersonClimbAnalyzer(
    context: Context,
    private val onEvent: (MotionEvent) -> Unit
) {
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

    private val appContext = context.applicationContext

    @Volatile
    private var active = false

    @Volatile
    private var state = State.IDLE

    private var matLine: List<Offset> = emptyList()
    private var topBox: Rect = Rect.Zero
    private var finishReference: MotionFinishReference = MotionFinishReference.CLOSEST_TOP_HOLD
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
    private var consecutiveAboveFrames = 0
    private var consecutiveBelowFrames = 0
    private var activeStartSeconds: Double? = null
    private var activeBestTopHoldSeconds: Double? = null
    private var activeBestTopHoldDistance: Float? = null
    private var activeHighestPersonSeconds: Double? = null
    private var activeHighestPersonY: Float? = null

    private var detector: ObjectDetector? = null
    private var lastMediaPipeTimestampMs = -1L
    @Volatile private var processingDetector = false

    @Synchronized
    fun configure(
        matLine: List<Offset>,
        topBox: Rect?,
        finishReference: MotionFinishReference,
        referenceImage: Bitmap?,
        horizontalBinCount: Int = TopOutConfig.MatBoundaryBins
    ) {
        this.matLine = matLine.sortedBy { it.x }
        this.topBox = topBox?.clamped01() ?: Rect.Zero
        this.finishReference = finishReference
        // Keep the named parameter for ViewModel compatibility; full-frame motion no longer uses bins.
        @Suppress("UNUSED_VARIABLE")
        val ignoredHorizontalBinCount = horizontalBinCount
        referenceMotionFrame = referenceImage?.let(::smallFrameFromBitmap)
        resetStateLocked(keepConfiguration = true)
    }

    @Synchronized
    fun resetForRecording() {
        resetStateLocked(keepConfiguration = true)
        active = true
        state = State.CALIBRATING
        previousMotionFrame = referenceMotionFrame
    }

    @Synchronized
    fun markRecordingStarted() {
        recordingStartPending = true
        recordingOriginTimestampNanos = null
        lastDetectorTimestampNanos = null
        detectorSearchStartedSeconds = null
        lastPersonSeenSeconds = null
        trackedPerson = null
        resetCrossingCountersLocked()
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

    /** Receives decoded frames from MockVideoEngine. Calls are serialized on its analysis thread. */
    fun analyzeBitmap(bitmap: Bitmap, timestampNanos: Long) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        if (!active || matLine.isEmpty()) return
        if (finishReference == MotionFinishReference.CLOSEST_TOP_HOLD && topBox == Rect.Zero) return

        synchronized(this) {
            if (analysisOriginTimestampNanos == null) analysisOriginTimestampNanos = timestampNanos
            if (recordingStartPending) {
                recordingOriginTimestampNanos = timestampNanos
                recordingStartPending = false
                lastMotionTimestampNanos = null
                lastDetectorTimestampNanos = null
            }
        }

        when (state) {
            State.CALIBRATING -> analyzeCalibrationFrame(bitmap, timestampNanos)
            State.WAITING_FOR_CHANGE -> analyzeMotionFrame(bitmap, timestampNanos)
            State.DETECTING_PERSON,
            State.TRACKING_PERSON,
            State.CLIMBING -> analyzePersonFrame(bitmap, timestampNanos)
            State.IDLE -> Unit
        }
    }

    private fun analyzeCalibrationFrame(bitmap: Bitmap, timestampNanos: Long) {
        if (!canRunMotionAnalysis(timestampNanos)) return
        val frame = smallFrameFromBitmap(bitmap) ?: return
        synchronized(this) {
            if (calibrationStartedTimestampNanos == null) {
                calibrationStartedTimestampNanos = timestampNanos
            }
            previousMotionFrame = frame
            val started = calibrationStartedTimestampNanos ?: timestampNanos
            if ((timestampNanos - started) / 1_000_000_000.0 >= TopOutConfig.MotionCountdownSeconds) {
                state = State.WAITING_FOR_CHANGE
                calibrationStartedTimestampNanos = null
                lastMotionTimestampNanos = null
            }
        }
    }

    private fun analyzeMotionFrame(bitmap: Bitmap, timestampNanos: Long) {
        if (!canRunMotionAnalysis(timestampNanos)) return
        val frame = smallFrameFromBitmap(bitmap) ?: return
        val previous = synchronized(this) {
            previousMotionFrame.also { previousMotionFrame = frame }
        }
        if (previous == null || previous.width != frame.width || previous.height != frame.height) return

        if (changedPixelRatio(previous, frame) >= TopOutConfig.FullFrameMotionChangedPixelRatio) {
            val seconds = recordingSeconds(timestampNanos)
            synchronized(this) {
                state = State.DETECTING_PERSON
                detectorSearchStartedSeconds = seconds
                lastPersonSeenSeconds = null
                trackedPerson = null
                lastDetectorTimestampNanos = null
                resetCrossingCountersLocked()
            }
            analyzePersonFrame(bitmap, timestampNanos)
        }
    }

    private fun analyzePersonFrame(bitmap: Bitmap, timestampNanos: Long) {
        if (!canRunPersonDetector(timestampNanos)) return
        val seconds = recordingSeconds(timestampNanos)
        if (processingDetector) return

        processingDetector = true
        var detectorBitmap: Bitmap? = null
        try {
            detectorBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
            val activeBitmap = detectorBitmap ?: return
            val mpImage = BitmapImageBuilder(activeBitmap).build()
            try {
                val activeDetector = requireDetector()
                val result = activeDetector.detectForVideo(
                    mpImage,
                    nextMediaPipeTimestampMs(timestampNanos)
                )
                handleDetectionResult(
                    result = result,
                    seconds = seconds,
                    inputWidth = activeBitmap.width,
                    inputHeight = activeBitmap.height
                )
            } finally {
                mpImage.close()
            }
        } catch (_: RuntimeException) {
            // A malformed frame must not terminate the mock app. Recreate the CPU detector
            // for the next eligible frame instead of keeping a potentially invalid instance.
            closeDetector()
            lastMediaPipeTimestampMs = -1L
            handlePersonMissing(seconds)
        } finally {
            detectorBitmap
                ?.takeIf { it !== bitmap && !it.isRecycled }
                ?.recycle()
            processingDetector = false
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
            .setCategoryAllowlist(listOf("person"))
            .setMaxResults(TopOutConfig.PersonDetectorMaxResults)
            .build()
        return ObjectDetector.createFromOptions(appContext, options)
    }

    private fun requireDetector(): ObjectDetector {
        detector?.let { return it }
        return createCpuDetector().also { detector = it }
    }

    private fun closeDetector() {
        detector?.let { runCatching { it.close() } }
        detector = null
    }

    @Synchronized
    private fun nextMediaPipeTimestampMs(timestampNanos: Long): Long {
        val sourceTimestampMs = timestampNanos / 1_000_000L
        val next = max(sourceTimestampMs, lastMediaPipeTimestampMs + 1L)
        lastMediaPipeTimestampMs = next
        return next
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
            if (!categoryName.equals("person", ignoreCase = true)) return@mapNotNull null
            val raw = detection.boundingBox()
            if (inputWidth <= 0 || inputHeight <= 0) return@mapNotNull null
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
        // Use the current detector box directly. TopOut only needs the person location,
        // so do not delay a crossing decision with a multi-frame median window.
        val currentPerson = selected

        if (state == State.DETECTING_PERSON) {
            state = State.TRACKING_PERSON
            detectorSearchStartedSeconds = null
            resetCrossingCountersLocked()
        }

        val aboveMat = isPersonAboveMat(currentPerson)
        if (aboveMat) {
            consecutiveAboveFrames += 1
            consecutiveBelowFrames = 0
        } else {
            consecutiveBelowFrames += 1
            consecutiveAboveFrames = 0
        }

        when (state) {
            State.TRACKING_PERSON -> {
                if (consecutiveAboveFrames < TopOutConfig.PersonCrossingConfirmationFrames) return
                val adjustedStart = max(0.0, seconds - TopOutConfig.ClipBoundaryPaddingSeconds)
                state = State.CLIMBING
                activeStartSeconds = adjustedStart
                resetFinishReferenceTrackingLocked()
                onEvent(MotionEvent.Start(adjustedStart))
                updateFinishReferenceMoment(currentPerson, seconds)
            }

            State.CLIMBING -> {
                updateFinishReferenceMoment(currentPerson, seconds)
                if (consecutiveBelowFrames < TopOutConfig.PersonCrossingConfirmationFrames) return
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
                if (open != null) onEvent(MotionEvent.Finish(open.endSeconds))
                else onEvent(MotionEvent.Retry)
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
            val distance = normalizedCenterDistance(previous, best)
            if (intersectionOverUnion(previous, best) >= 0.01f || distance <= 0.42f) return best
        }
        return candidates.maxByOrNull(::initialCandidateScore)
    }

    private fun initialCandidateScore(box: PersonBox): Float {
        val matY = lineY(box.centerX)
        val matCloseness = (1f - abs(box.bottom - matY) / 0.45f).coerceIn(0f, 1f)
        return box.score * 0.45f + sqrt(box.area.coerceAtLeast(0f)) * 0.30f + matCloseness * 0.25f
    }

    private fun associationScore(previous: PersonBox, current: PersonBox): Float {
        val overlap = intersectionOverUnion(previous, current)
        val proximity = (1f - normalizedCenterDistance(previous, current)).coerceIn(0f, 1f)
        val areaSimilarity = if (previous.area <= 0f || current.area <= 0f) 0f else {
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
        sqrt((a.centerX - b.centerX) * (a.centerX - b.centerX) +
            (a.centerY - b.centerY) * (a.centerY - b.centerY)) / sqrt(2f)

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

            MotionFinishReference.HIGHEST_POSE_POINT -> {
                if (activeHighestPersonY == null || box.top < (activeHighestPersonY ?: Float.MAX_VALUE)) {
                    activeHighestPersonY = box.top
                    activeHighestPersonSeconds = seconds
                }
            }
        }
    }

    private fun bestFinishReferenceSeconds(): Double? = when (finishReference) {
        MotionFinishReference.CLOSEST_TOP_HOLD -> activeBestTopHoldSeconds
        MotionFinishReference.HIGHEST_POSE_POINT -> activeHighestPersonSeconds
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
        resetCrossingCountersLocked()
        resetFinishReferenceTrackingLocked()
    }

    private fun canRunMotionAnalysis(timestampNanos: Long): Boolean {
        val intervalNanos = (1_000_000_000.0 / TopOutConfig.FullFrameMotionFps).toLong()
        val previous = lastMotionTimestampNanos
        if (previous != null && timestampNanos - previous < intervalNanos) return false
        lastMotionTimestampNanos = timestampNanos
        return true
    }

    private fun canRunPersonDetector(timestampNanos: Long): Boolean {
        val intervalNanos = (1_000_000_000.0 / TopOutConfig.PersonDetectorFps).toLong()
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
            val a = previous.luma[index].toInt() and 0xFF
            val b = current.luma[index].toInt() and 0xFF
            if (abs(a - b) >= TopOutConfig.MotionPixelDifference) changed += 1
        }
        return changed.toFloat() / count
    }

    private fun smallFrameFromBitmap(bitmap: Bitmap): SmallFrame? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val outputWidth = max(32, TopOutConfig.MotionFrameWidth)
        val outputHeight = max(32, (outputWidth * bitmap.height.toFloat() / bitmap.width).roundToInt())
        val output = ByteArray(outputWidth * outputHeight)
        for (y in 0 until outputHeight) {
            val sourceY = min(bitmap.height - 1, ((y + 0.5f) * bitmap.height / outputHeight).toInt())
            for (x in 0 until outputWidth) {
                val sourceX = min(bitmap.width - 1, ((x + 0.5f) * bitmap.width / outputWidth).toInt())
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
        return max(0.0, (timestampNanos - origin) / 1_000_000_000.0)
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

    private fun resetCrossingCountersLocked() {
        consecutiveAboveFrames = 0
        consecutiveBelowFrames = 0
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
        processingDetector = false
        resetCrossingCountersLocked()
        resetFinishReferenceTrackingLocked()
        if (!keepConfiguration) {
            matLine = emptyList()
            topBox = Rect.Zero
            finishReference = MotionFinishReference.CLOSEST_TOP_HOLD
            referenceMotionFrame = null
        }
    }

    private fun Rect.clamped01(): Rect {
        val left = this.left.coerceIn(0f, 1f)
        val top = this.top.coerceIn(0f, 1f)
        val right = this.right.coerceIn(left, 1f)
        val bottom = this.bottom.coerceIn(top, 1f)
        return Rect(left, top, right, bottom)
    }

    fun close() {
        closeDetector()
    }
}
