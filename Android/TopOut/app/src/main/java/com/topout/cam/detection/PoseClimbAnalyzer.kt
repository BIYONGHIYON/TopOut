package com.topout.cam.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF as AndroidRectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.topout.cam.model.ClipRange
import com.topout.cam.model.TopOutConfig
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

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
 * Direct Android port of MotionDetector.swift.
 *
 * The detector intentionally uses two stages, matching iOS:
 * 1) rolling luma difference in the 40%-high region above the edited mat line to wake pose tracking;
 * 2) MoveNet Thunder FP16 tracking every 0.5 s to determine the raw start/end and either the closest
 *    top-hold moment or the highest pose point, matching iOS MotionDetector.FinishReference.
 * Review padding is deliberately deferred until recording finalization.
 */
class PoseClimbAnalyzer(
    context: Context,
    private val onEvent: (MotionEvent) -> Unit
) : ImageAnalysis.Analyzer {
    private enum class State { IDLE, CALIBRATING, WAITING, TRACKING, CLIMBING }

    private data class SmallFrame(val width: Int, val height: Int, val luma: ByteArray)
    private data class CalibrationSample(val energy: Float)
    private data class Calibration(val regionBaseline: Float)
    private data class FrameCaptureRequest(
        val maxLongSide: Int,
        val result: CompletableDeferred<Bitmap?>
    )

    private val frameCaptureRequest = AtomicReference<FrameCaptureRequest?>(null)
    /**
     * MoveNet remains on the full frame until three consecutive pose frames show human-scale motion.
     * This prevents static climbing holds from locking the detector into a false smart-crop region.
     */
    private val poseDetector = MoveNetThunderPoseDetector(context)
    private val smartCropMotionGate = PoseSmartCropMotionGate(
        requiredFrames = TopOutConfig.SmartCropMotionVerificationFrames,
        minimumMovedLandmarks = TopOutConfig.SmartCropMinimumMovedLandmarks,
        minimumMovementRatio = TopOutConfig.SmartCropMinimumMovementRatio
    )

    private val motionLandmarkTypes = intArrayOf(
        PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
        PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
        PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
        PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
        PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE
    )


    @Volatile private var active = false
    @Volatile private var state = State.IDLE
    private var matLine: List<Offset> = emptyList()
    private var topBox: Rect = Rect.Zero
    private var finishReference: MotionFinishReference = MotionFinishReference.CLOSEST_TOP_HOLD
    private val cvFrameHistory = ArrayDeque<SmallFrame>()

    private var analysisOriginTimestampNanos: Long? = null
    private var recordingOriginTimestampNanos: Long? = null
    private var recordingStartPending = false
    private var lastAnalyzedTimestampNanos: Long? = null
    private var motionBoostUntilTimestampNanos: Long? = null
    private var calibrationStartedTimestampNanos: Long? = null
    private val calibrationSamples = mutableListOf<CalibrationSample>()
    private var calibration: Calibration? = null

    private var lastPoseTimestampNanos: Long? = null
    private var lastPoseSeenSeconds: Double? = null
    private var poseTrackingStartedSeconds: Double? = null
    private var lastLowestAboveMat: Boolean? = null
    private var activeStartSeconds: Double? = null
    private var activeBestTopHoldSeconds: Double? = null
    private var activeBestTopHoldDistance: Float? = null
    private var activeHighestPoseSeconds: Double? = null
    private var activeHighestPoseY: Float? = null
    private var smartCropEnabled = false
    private var smartCropConfirmationFrames = 0
    private var smartCropConsecutiveMissingFrames = 0
    private var rgbScratchPixels = IntArray(0)

    /**
     * Returns the next ImageAnalysis frame in the shared CameraX ViewPort. Android uses this as
     * the primary selection frame so the source follows iOS VideoFrameSink's camera-buffer path.
     */
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
        referenceImage: Bitmap?,
        horizontalBinCount: Int = TopOutConfig.MatBoundaryBins
    ) {
        this.matLine = matLine.sortedBy { it.x }
        this.topBox = topBox?.clamped01() ?: Rect.Zero
        this.finishReference = finishReference
        // The rolling CV gate uses the edited mat line directly and no longer samples a narrow
        // strip on or below the boundary. referenceImage/horizontalBinCount remain in the API for
        // call-site compatibility with earlier v1.2 builds.
        resetStateLocked(keepConfiguration = true)
    }

    /** Countdown frames are used for the same three-second baseline calibration as iOS. */
    @Synchronized
    fun resetForRecording() {
        resetStateLocked(keepConfiguration = true)
        active = true
        state = State.CALIBRATING
    }

    /**
     * CameraX emits this at the actual file-recording start. Detection state and countdown
     * calibration are retained, while all clip timestamps are re-based to the encoded video.
     */
    @Synchronized
    fun markRecordingStarted() {
        recordingStartPending = true
        recordingOriginTimestampNanos = null
        lastPoseSeenSeconds = null
        poseTrackingStartedSeconds = null
        activeStartSeconds = null
        resetFinishReferenceTrackingLocked()
        lastLowestAboveMat = null
        resetSmartCropVerificationLocked()
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
            val bitmap = runCatching { imageProxyToBitmap(image, request.maxLongSide) }.getOrNull()
            request.result.complete(bitmap)
        }

        val motionAnalysisEnabled = active && matLine.isNotEmpty() &&
            (finishReference == MotionFinishReference.HIGHEST_POSE_POINT || topBox != Rect.Zero)
        if (!motionAnalysisEnabled) {
            image.close()
            return
        }

        val timestamp = image.imageInfo.timestamp
        synchronized(this) {
            if (analysisOriginTimestampNanos == null) analysisOriginTimestampNanos = timestamp
            if (recordingStartPending) {
                recordingOriginTimestampNanos = timestamp
                recordingStartPending = false
                lastPoseTimestampNanos = null
            }
        }

        if (state == State.TRACKING || state == State.CLIMBING) {
            if (canAnalyzePose(timestamp, TopOutConfig.PoseIntervalSeconds)) {
                runPose(image, recordingSeconds(timestamp))
            } else {
                image.close()
            }
            return
        }

        val interval = currentAnalysisInterval(timestamp)
        val previous = lastAnalyzedTimestampNanos
        if (previous != null && (timestamp - previous) / 1_000_000_000.0 >= 0.0 &&
            (timestamp - previous) / 1_000_000_000.0 < interval
        ) {
            image.close()
            return
        }
        lastAnalyzedTimestampNanos = timestamp

        val frame = makeSmallFrame(image)
        if (frame == null) {
            image.close()
            return
        }
        val comparisonFrame = rollingCvReference(frame)

        when (state) {
            State.CALIBRATING -> {
                collectCalibration(frame, comparisonFrame, timestamp)
                image.close()
            }

            State.WAITING -> {
                val base = comparisonFrame
                val calibrationSnapshot = calibration
                if (base != null && calibrationSnapshot != null &&
                    base.width == frame.width && base.height == frame.height
                ) {
                    val rawSignal = cvSearchRegionDiffScore(frame, base)
                    val adjustedSignal = max(
                        0f,
                        rawSignal - calibrationSnapshot.regionBaseline - TopOutConfig.MotionBaseDiffMargin
                    )
                    if (adjustedSignal >= TopOutConfig.MotionCVRegionActiveThreshold) {
                        motionBoostUntilTimestampNanos = timestamp +
                            (TopOutConfig.MotionAnalysisBoostSeconds * 1_000_000_000L).toLong()
                        enterPoseTracking(timestamp)
                        if (canAnalyzePose(timestamp, TopOutConfig.PoseStartVerificationIntervalSeconds)) {
                            runPose(image, recordingSeconds(timestamp))
                            return
                        }
                    }
                }
                image.close()
            }

            else -> image.close()
        }
    }

    private fun currentAnalysisInterval(timestamp: Long): Double {
        val fps = when (state) {
            State.CALIBRATING -> TopOutConfig.MotionAnalysisCalibrationFps
            State.WAITING -> if ((motionBoostUntilTimestampNanos ?: Long.MIN_VALUE) >= timestamp) {
                TopOutConfig.MotionAnalysisBoostFps
            } else TopOutConfig.MotionAnalysisIdleFps
            else -> TopOutConfig.MotionAnalysisIdleFps
        }
        return 1.0 / max(1.0, fps)
    }

    @Synchronized
    private fun collectCalibration(frame: SmallFrame, referenceFrame: SmallFrame?, timestamp: Long) {
        if (calibrationStartedTimestampNanos == null) calibrationStartedTimestampNanos = timestamp
        if (referenceFrame != null && referenceFrame.width == frame.width && referenceFrame.height == frame.height) {
            calibrationSamples += CalibrationSample(cvSearchRegionDiffScore(frame, referenceFrame))
        }

        val started = calibrationStartedTimestampNanos ?: timestamp
        if ((timestamp - started) / 1_000_000_000.0 < TopOutConfig.MotionCountdownSeconds) return
        if (calibrationSamples.isEmpty()) return
        finishCalibrationLocked()
    }

    private fun finishCalibrationLocked() {
        val samples = calibrationSamples.toList()
        if (samples.isEmpty()) return
        val energies = samples.map { it.energy }
        val median = median(energies)
        val mad = median(energies.map { abs(it - median) })
        val threshold = median + max(
            TopOutConfig.MotionCalibrationMinOutlierDelta,
            TopOutConfig.MotionCalibrationMadFactor * max(mad, 0.0001f)
        )
        val accepted = samples.filter { it.energy <= threshold }
        val used = if (accepted.isEmpty()) samples else accepted
        calibration = Calibration(regionBaseline = median(used.map { it.energy }))
        state = State.WAITING
        calibrationStartedTimestampNanos = null
        calibrationSamples.clear()
    }

    @Synchronized
    private fun enterPoseTracking(timestamp: Long) {
        cvFrameHistory.clear()
        resetSmartCropVerificationLocked()
        state = State.TRACKING
        poseTrackingStartedSeconds = recordingSeconds(timestamp)
        lastPoseSeenSeconds = null
        lastLowestAboveMat = null
        activeStartSeconds = null
        resetFinishReferenceTrackingLocked()
    }

    private fun canAnalyzePose(timestamp: Long, intervalSeconds: Double): Boolean {
        val previous = lastPoseTimestampNanos
        if (previous != null && (timestamp - previous) / 1_000_000_000.0 < intervalSeconds) return false
        lastPoseTimestampNanos = timestamp
        return true
    }

    /**
     * Runs pose conversion and inference on CameraEngine's single analysis executor.
     *
     * ImageAnalysis uses STRATEGY_KEEP_ONLY_LATEST. Keeping this analyzer callback occupied until
     * inference completes means CameraX retains at most one newer frame and replaces it as fresher
     * frames arrive. No per-frame Runnable, Bitmap, or ImageProxy queue can accumulate, and the next
     * callback receives the freshest available frame instead of an old queued frame.
     */
    private fun runPose(
        image: ImageProxy,
        seconds: Double
    ) {
        var poseBitmap: Bitmap? = null
        try {
            val smartCropActive = synchronized(this) { smartCropEnabled }
            if (smartCropActive) {
                // Once a real moving person has unlocked smart crop, skip the 512px RGB Bitmap.
                // Sample the tracked region directly from CameraX YUV into MoveNet's 256x256 input.
                val viewport = cropRectInDisplay(image)
                val rotation = normalizedRotation(image.imageInfo.rotationDegrees)
                val displayWidth = if (rotation % 180 == 0) image.width else image.height
                val displayHeight = if (rotation % 180 == 0) image.height else image.width
                val sourceWidth = max(1, (viewport.width * displayWidth).roundToInt())
                val sourceHeight = max(1, (viewport.height * displayHeight).roundToInt())
                val detection = runCatching {
                    poseDetector.detectTrackedYuv(
                        image = image,
                        viewportInDisplay = AndroidRectF(
                            viewport.left, viewport.top, viewport.right, viewport.bottom
                        ),
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight
                    )
                }
                detection.onSuccess { pose ->
                    if (pose != null) {
                        handlePose(pose, seconds, sourceWidth, sourceHeight)
                    } else {
                        handlePoseMissing(seconds)
                    }
                }.onFailure {
                    handlePoseMissing(seconds)
                }
            } else {
                // Preserve the 512px full-frame path only for initial person discovery and the
                // three-frame motion verification that protects against static-hold false locks.
                poseBitmap = imageProxyToBitmap(image, TopOutConfig.PoseInputMaxLongSide)
                val bitmap = poseBitmap
                if (bitmap == null) {
                    handlePoseMissing(seconds)
                    return
                }
                val detection = runCatching { poseDetector.detect(bitmap, false) }
                detection.onSuccess { pose ->
                    if (pose != null) {
                        handlePose(pose, seconds, bitmap.width, bitmap.height)
                    } else {
                        handlePoseMissing(seconds)
                    }
                }.onFailure {
                    handlePoseMissing(seconds)
                }
            }
        } catch (_: Throwable) {
            handlePoseMissing(seconds)
        } finally {
            poseBitmap?.recycle()
            image.close()
        }
    }

    @Synchronized
    private fun handlePose(
        pose: PoseResult,
        seconds: Double,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val landmarks = reliableLandmarks(pose, motionLandmarkTypes)
        val humanValid = PoseHumanGate.isValid(landmarks.keys)
        if (!humanValid) {
            smartCropMotionGate.reset()
            // A single cropped-frame miss can happen during fast movement or immediately after the
            // crop changes. Keep the crop for one miss, but require post-unlock confirmation to
            // restart from zero. Revoke the crop only after two consecutive misses while TRACKING.
            // During an active climb, retain the existing no-pose grace period for occlusions.
            if (state == State.TRACKING && smartCropEnabled) {
                smartCropConfirmationFrames = 0
                smartCropConsecutiveMissingFrames += 1
                if (smartCropConsecutiveMissingFrames >= TopOutConfig.SmartCropConsecutiveMissingFramesBeforeReset) {
                    resetSmartCropVerificationLocked()
                }
            }
            handlePoseMissing(seconds)
            return
        }

        if (state == State.TRACKING && smartCropEnabled) {
            smartCropConsecutiveMissingFrames = 0
        }

        val observation = if (!smartCropEnabled) smartCropMotionGate.observe(landmarks) else null
        val unlockedThisFrame = observation?.shouldUnlock == true
        if (unlockedThisFrame) {
            smartCropEnabled = true
            smartCropConfirmationFrames = 0
            smartCropConsecutiveMissingFrames = 0
            poseDetector.primeTracking(pose, imageWidth, imageHeight)
        }
        val points = landmarks.values.distinctBy {
            ((it.x * 10_000).roundToInt() shl 16) xor (it.y * 10_000).roundToInt()
        }

        lastPoseSeenSeconds = seconds
        poseTrackingStartedSeconds = null

        // A pose-like arrangement of static holds may pass the relaxed body-joint gate. It must
        // never start a climb until the three-frame human-motion gate has unlocked smart crop and
        // the cropped pose has been human-valid for two additional consecutive frames.
        // Keep lastLowestAboveMat untouched while unverified so the first confirmed pose can still
        // become the start boundary even when it is already above the mat.
        if (state == State.TRACKING) {
            if (!smartCropEnabled) return
            if (unlockedThisFrame) return
            smartCropConfirmationFrames += 1
            if (smartCropConfirmationFrames < TopOutConfig.SmartCropPostUnlockConfirmationFrames) return
        }

        val lowest = points.maxByOrNull { it.y } ?: return
        val lowestAboveMat = isAboveMatLine(lowest)

        when (state) {
            State.TRACKING -> {
                // Only a motion-verified person can become a crossing candidate. The first verified
                // pose may start immediately when its lowest reliable point is already above the mat.
                val crossedUp = PoseStartRule.shouldStart(
                    lowestAboveMat = lowestAboveMat,
                    previousLowestAboveMat = lastLowestAboveMat,
                    humanMotionVerified = smartCropEnabled
                )
                lastLowestAboveMat = lowestAboveMat
                if (!crossedUp) return

                // Store only the original detection boundary while recording. Review padding is
                // added once, after short-range cleanup and merging at recording finalization.
                state = State.CLIMBING
                activeStartSeconds = seconds
                resetFinishReferenceTrackingLocked()
                onEvent(MotionEvent.Start(seconds))
                updateFinishReferenceMoment(points, seconds)
            }
            State.CLIMBING -> {
                updateFinishReferenceMoment(points, seconds)
                lastLowestAboveMat = lowestAboveMat
                if (lowestAboveMat) return

                val start = activeStartSeconds ?: return
                val endBase = bestFinishReferenceSeconds() ?: seconds
                val rawEnd = max(start + 0.1, endBase)
                onEvent(MotionEvent.Finish(rawEnd))
                resetForNextStartSearchLocked()
            }
            else -> Unit
        }
    }

    @Synchronized
    private fun handlePoseMissing(seconds: Double) {
        if (!smartCropEnabled) smartCropMotionGate.reset()
        if (lastPoseSeenSeconds == null && poseTrackingStartedSeconds == null) {
            poseTrackingStartedSeconds = seconds
            return
        }
        val reference = lastPoseSeenSeconds ?: poseTrackingStartedSeconds ?: seconds
        val missingAge = seconds - reference
        if (missingAge < TopOutConfig.PoseNoPoseRetrySeconds) return

        if (state == State.CLIMBING) {
            val open = openClipRangeLocked()
            if (open != null) onEvent(MotionEvent.Finish(open.endSeconds)) else onEvent(MotionEvent.Retry)
            resetForNextStartSearchLocked()
        } else {
            returnToCvSearchLocked()
        }
    }

    private fun resetForNextStartSearchLocked() {
        resetSmartCropVerificationLocked()
        state = State.TRACKING
        lastPoseTimestampNanos = null
        lastPoseSeenSeconds = null
        poseTrackingStartedSeconds = null
        lastLowestAboveMat = null
        activeStartSeconds = null
        resetFinishReferenceTrackingLocked()
    }

    private fun returnToCvSearchLocked() {
        cvFrameHistory.clear()
        resetSmartCropVerificationLocked()
        state = State.WAITING
        lastPoseTimestampNanos = null
        lastPoseSeenSeconds = null
        poseTrackingStartedSeconds = null
        lastLowestAboveMat = null
        activeStartSeconds = null
        resetFinishReferenceTrackingLocked()
    }

    private fun resetSmartCropVerificationLocked() {
        smartCropEnabled = false
        smartCropConfirmationFrames = 0
        smartCropConsecutiveMissingFrames = 0
        smartCropMotionGate.reset()
        poseDetector.resetTracking()
    }

    private fun openClipRangeLocked(): ClipRange? {
        if (state != State.CLIMBING) return null
        val start = activeStartSeconds ?: return null
        val finishBase = bestFinishReferenceSeconds() ?: lastPoseSeenSeconds ?: return null
        return if (finishBase > start) ClipRange(start, finishBase) else null
    }

    private fun reliableLandmarks(
        pose: PoseResult,
        landmarkTypes: IntArray
    ): Map<Int, Offset> {
        val points = LinkedHashMap<Int, Offset>(landmarkTypes.size)
        for (type: Int in landmarkTypes) {
            val keypoint = pose.keypoints[type] ?: continue
            if (keypoint.confidence < TopOutConfig.PoseConfidence) continue
            points[type] = Offset(
                keypoint.position.x.coerceIn(0f, 1f),
                keypoint.position.y.coerceIn(0f, 1f)
            )
        }
        return points
    }


    private fun isAboveMatLine(point: Offset): Boolean {
        val stricterBoundary = max(
            0f,
            lineY(point.x) - max(0.02f, TopOutConfig.MotionMatBandHeightRatio)
        )
        return point.y <= stricterBoundary + TopOutConfig.PoseMatTolerance
    }

    private fun updateFinishReferenceMoment(points: List<Offset>, seconds: Double) {
        when (finishReference) {
            MotionFinishReference.CLOSEST_TOP_HOLD -> {
                if (topBox == Rect.Zero) return
                val distance = points.minOfOrNull { pointDistanceToRect(it, topBox) } ?: return
                if (activeBestTopHoldDistance == null || distance < (activeBestTopHoldDistance ?: Float.MAX_VALUE)) {
                    activeBestTopHoldDistance = distance
                    activeBestTopHoldSeconds = seconds
                }
            }
            MotionFinishReference.HIGHEST_POSE_POINT -> {
                val highest = points.minByOrNull { it.y } ?: return
                if (activeHighestPoseY == null || highest.y < (activeHighestPoseY ?: Float.MAX_VALUE)) {
                    activeHighestPoseY = highest.y
                    activeHighestPoseSeconds = seconds
                }
            }
        }
    }

    private fun bestFinishReferenceSeconds(): Double? = when (finishReference) {
        MotionFinishReference.CLOSEST_TOP_HOLD -> activeBestTopHoldSeconds
        MotionFinishReference.HIGHEST_POSE_POINT -> activeHighestPoseSeconds
    }

    private fun resetFinishReferenceTrackingLocked() {
        activeBestTopHoldSeconds = null
        activeBestTopHoldDistance = null
        activeHighestPoseSeconds = null
        activeHighestPoseY = null
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

    /**
     * Compares only the region above the edited mat line, up to 40% of the visible frame height.
     * The reference is exactly three throttled CV-analysis frames earlier, not the frozen selection
     * image, so lighting drift and camera auto-exposure do not accumulate into a false trigger.
     */
    private fun cvSearchRegionDiffScore(frame: SmallFrame, base: SmallFrame): Float {
        if (frame.width != base.width || frame.height != base.height) return 0f
        val width = frame.width
        val height = frame.height
        val regionHeight = TopOutConfig.MotionCVRegionAboveMatHeightRatio.coerceIn(0.02f, 1f)
        var changed = 0
        var total = 0
        for (x in 0 until width) {
            val xNorm = (x + 0.5f) / width
            val boundary = lineY(xNorm)
            val yStartNorm = max(0f, boundary - regionHeight)
            val yEndNorm = boundary
            if (yEndNorm <= yStartNorm) continue
            val y0 = floor(yStartNorm * height).toInt().coerceIn(0, height - 1)
            val y1 = ceil(yEndNorm * height).toInt().coerceIn(y0 + 1, height)
            for (y in y0 until y1) {
                val index = y * width + x
                val current = frame.luma[index].toInt() and 0xFF
                val previous = base.luma[index].toInt() and 0xFF
                if (abs(current - previous) >= TopOutConfig.MotionPixelDifference) changed++
                total++
            }
        }
        return if (total > 0) changed.toFloat() / total else 0f
    }

    private fun rollingCvReference(frame: SmallFrame): SmallFrame? {
        val lag = max(1, TopOutConfig.MotionCVComparisonFrameLag)
        val latest = cvFrameHistory.peekLast()
        if (latest != null && (latest.width != frame.width || latest.height != frame.height)) {
            cvFrameHistory.clear()
        }
        if (cvFrameHistory.size < lag) {
            cvFrameHistory.addLast(frame)
            return null
        }
        val reference = cvFrameHistory.removeFirst()
        cvFrameHistory.addLast(frame)
        return reference
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
        val requiredPixels = outputWidth * outputHeight
        if (rgbScratchPixels.size < requiredPixels) rgbScratchPixels = IntArray(requiredPixels)
        val pixels = rgbScratchPixels

        fun valueAt(buffer: java.nio.ByteBuffer, index: Int, fallback: Int): Int =
            if (index in 0 until buffer.limit()) buffer.get(index).toInt() and 0xFF else fallback

        for (y in 0 until outputHeight) {
            val displayY = crop.top + crop.height * (y + 0.5f) / outputHeight
            for (x in 0 until outputWidth) {
                val displayX = crop.left + crop.width * (x + 0.5f) / outputWidth
                val raw = displayNormalizedToRawPixelPacked(
                    displayX, displayY, image.width, image.height, rotation
                )
                val rawX = (raw ushr 32).toInt()
                val rawY = raw.toInt()
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

    /** Produces the same oriented/cropped luma coordinate space shown by PreviewView. */
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
                val raw = displayNormalizedToRawPixelPacked(
                    displayX, displayY, image.width, image.height, rotation
                )
                val rawX = (raw ushr 32).toInt()
                val rawY = raw.toInt()
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
        val outputHeight = max(32, (outputWidth * bitmap.height.toFloat() / bitmap.width).roundToInt())
        val output = ByteArray(outputWidth * outputHeight)
        for (y in 0 until outputHeight) {
            val sourceY = min(bitmap.height - 1, ((y + 0.5f) * bitmap.height / outputHeight).toInt())
            for (x in 0 until outputWidth) {
                val sourceX = min(bitmap.width - 1, ((x + 0.5f) * bitmap.width / outputWidth).toInt())
                val color = bitmap.getPixel(sourceX, sourceY)
                val luma = (Color.red(color) * 0.2126f + Color.green(color) * 0.7152f +
                    Color.blue(color) * 0.0722f).roundToInt().coerceIn(0, 255)
                output[y * outputWidth + x] = luma.toByte()
            }
        }
        return SmallFrame(outputWidth, outputHeight, output)
    }

    /** CameraX's ViewPort cropRect is raw-sensor oriented; this converts it to display space. */
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
            displayed.minOf { it.x }, displayed.minOf { it.y },
            displayed.maxOf { it.x }, displayed.maxOf { it.y }
        ).clamped01()
    }

    private fun rawNormalizedToDisplay(point: Offset, rotation: Int): Offset = when (rotation) {
        90 -> Offset(1f - point.y, point.x)
        180 -> Offset(1f - point.x, 1f - point.y)
        270 -> Offset(point.y, 1f - point.x)
        else -> point
    }

    /** Packs raw x in the high 32 bits and raw y in the low 32 bits to avoid per-pixel Pair allocations. */
    private fun displayNormalizedToRawPixelPacked(
        displayX: Float,
        displayY: Float,
        rawWidth: Int,
        rawHeight: Int,
        rotation: Int
    ): Long {
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
        val pixelX = (rawX * (rawWidth - 1)).roundToInt().coerceIn(0, rawWidth - 1)
        val pixelY = (rawY * (rawHeight - 1)).roundToInt().coerceIn(0, rawHeight - 1)
        return (pixelX.toLong() shl 32) or (pixelY.toLong() and 0xFFFF_FFFFL)
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

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) * 0.5f else sorted[middle]
    }

    @Synchronized
    private fun resetStateLocked(keepConfiguration: Boolean) {
        active = false
        state = State.IDLE
        analysisOriginTimestampNanos = null
        recordingOriginTimestampNanos = null
        recordingStartPending = false
        lastAnalyzedTimestampNanos = null
        motionBoostUntilTimestampNanos = null
        calibrationStartedTimestampNanos = null
        calibrationSamples.clear()
        calibration = null
        cvFrameHistory.clear()
        lastPoseTimestampNanos = null
        lastPoseSeenSeconds = null
        poseTrackingStartedSeconds = null
        lastLowestAboveMat = null
        activeStartSeconds = null
        resetFinishReferenceTrackingLocked()
        resetSmartCropVerificationLocked()
        if (!keepConfiguration) {
            matLine = emptyList()
            topBox = Rect.Zero
            finishReference = MotionFinishReference.CLOSEST_TOP_HOLD
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
        poseDetector.close()
    }

}
