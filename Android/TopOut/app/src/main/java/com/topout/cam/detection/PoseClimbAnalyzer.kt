package com.topout.cam.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
import java.util.concurrent.Executors
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
 * 1) luma difference around the edited mat line to cheaply wake pose tracking;
 * 2) MoveNet Lightning FP16 tracking every 0.5 s to determine the raw start/end and either the closest
 *    top-hold moment or the highest pose point, matching iOS MotionDetector.FinishReference.
 * Review padding is deliberately deferred until recording finalization.
 */
class PoseClimbAnalyzer(
    context: Context,
    private val onEvent: (MotionEvent) -> Unit
) : ImageAnalysis.Analyzer {
    private enum class State { IDLE, CALIBRATING, WAITING, TRACKING, CLIMBING }
    private enum class BandKind { BELOW, UPPER }

    private data class SmallFrame(val width: Int, val height: Int, val luma: ByteArray)
    private data class BandBin(val x0: Float, val x1: Float)
    private data class CalibrationSample(val frame: SmallFrame, val energy: Float)
    private data class Calibration(val belowBaseline: FloatArray, val upperBaseline: FloatArray)
    private data class RunScore(val indices: List<Int>, val energy: Float)
    private data class BoundaryMotion(val indices: List<Int>, val energy: Float)
    private data class FrameCaptureRequest(
        val maxLongSide: Int,
        val result: CompletableDeferred<Bitmap?>
    )

    private val frameCaptureRequest = AtomicReference<FrameCaptureRequest?>(null)
    /**
     * MoveNet keeps the previous smart-crop region throughout TRACKING and CLIMBING. This is important
     * for distant, back-facing climbers because full-frame inference alone can make the person too small.
     */
    private val poseDetector = MoveNetLightningPoseDetector(context)
    private val poseExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "topout-movenet-fp16-pose").apply { isDaemon = true }
    }

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
    private var horizontalBinCount = TopOutConfig.MatBoundaryBins
    private var belowBins: List<BandBin> = emptyList()
    private var upperBins: List<BandBin> = emptyList()
    private var captureBaseFrame: SmallFrame? = null

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
    @Volatile private var processingPose = false
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
        this.horizontalBinCount = max(1, horizontalBinCount)
        belowBins = buildBandBins()
        upperBins = buildBandBins()
        captureBaseFrame = referenceImage?.let(::smallFrameFromBitmap)
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
        if (captureBaseFrame == null) captureBaseFrame = frame

        when (state) {
            State.CALIBRATING -> {
                collectCalibration(frame, timestamp)
                image.close()
            }

            State.WAITING -> {
                val base = captureBaseFrame
                val calibrationSnapshot = calibration
                if (base != null && calibrationSnapshot != null &&
                    base.width == frame.width && base.height == frame.height
                ) {
                    val below = adjustedBandSignals(
                        frame, base, belowBins, BandKind.BELOW, calibrationSnapshot.belowBaseline
                    )
                    val upper = adjustedBandSignals(
                        frame, base, upperBins, BandKind.UPPER, calibrationSnapshot.upperBaseline
                    )
                    val motion = classifyBoundaryMotion(below, upper)
                    if (motion != null) {
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
    private fun collectCalibration(frame: SmallFrame, timestamp: Long) {
        if (calibrationStartedTimestampNanos == null) calibrationStartedTimestampNanos = timestamp
        val base = captureBaseFrame
        val energy = if (base != null && base.width == frame.width && base.height == frame.height) {
            belowBins.sumOf { baseDiffScore(frame, base, it, BandKind.BELOW).toDouble() }.toFloat() +
                upperBins.sumOf { baseDiffScore(frame, base, it, BandKind.UPPER).toDouble() }.toFloat()
        } else 0f
        calibrationSamples += CalibrationSample(frame, energy)

        val started = calibrationStartedTimestampNanos ?: timestamp
        if ((timestamp - started) / 1_000_000_000.0 >= TopOutConfig.MotionCountdownSeconds) {
            finishCalibrationLocked()
        }
    }

    private fun finishCalibrationLocked() {
        val base = captureBaseFrame
        if (base == null) {
            state = State.WAITING
            return
        }
        val samples = calibrationSamples.toList()
        val energies = samples.map { it.energy }
        val median = median(energies)
        val mad = median(energies.map { abs(it - median) })
        val threshold = median + max(
            TopOutConfig.MotionCalibrationMinOutlierDelta,
            TopOutConfig.MotionCalibrationMadFactor * max(mad, 0.0001f)
        )
        val accepted = samples.filter { it.energy <= threshold }
        val used = if (accepted.isEmpty()) samples else accepted
        calibration = Calibration(
            belowBaseline = makeBaseline(used.map { it.frame }, base, belowBins, BandKind.BELOW),
            upperBaseline = makeBaseline(used.map { it.frame }, base, upperBins, BandKind.UPPER)
        )
        state = State.WAITING
        calibrationStartedTimestampNanos = null
        calibrationSamples.clear()
    }

    @Synchronized
    private fun enterPoseTracking(timestamp: Long) {
        poseDetector.resetTracking()
        state = State.TRACKING
        poseTrackingStartedSeconds = recordingSeconds(timestamp)
        lastPoseSeenSeconds = null
        lastLowestAboveMat = null
        activeStartSeconds = null
        resetFinishReferenceTrackingLocked()
    }

    private fun canAnalyzePose(timestamp: Long, intervalSeconds: Double): Boolean {
        if (processingPose) return false
        val previous = lastPoseTimestampNanos
        if (previous != null && (timestamp - previous) / 1_000_000_000.0 < intervalSeconds) return false
        lastPoseTimestampNanos = timestamp
        return true
    }

    private fun runPose(
        image: ImageProxy,
        seconds: Double
    ) {
        if (processingPose) {
            image.close()
            return
        }

        processingPose = true
        val bitmapConversion = runCatching {
            imageProxyToBitmap(image, TopOutConfig.PoseInputMaxLongSide)
        }
        image.close()

        val poseBitmap = bitmapConversion.getOrNull()
        if (poseBitmap == null) {
            processingPose = false
            handlePoseMissing(seconds)
            return
        }

        val useTrackingAssociation = synchronized(this) { state == State.TRACKING || state == State.CLIMBING }
        runCatching {
            poseExecutor.execute {
                try {
                    val detection = runCatching {
                        poseDetector.detect(poseBitmap, useTrackingAssociation)
                    }
                    detection.onSuccess { pose ->
                        if (pose != null) handlePose(pose, seconds) else handlePoseMissing(seconds)
                    }.onFailure { _ ->
                        handlePoseMissing(seconds)
                    }
                } finally {
                    poseBitmap.recycle()
                    processingPose = false
                }
            }
        }.onFailure { _ ->
            poseBitmap.recycle()
            processingPose = false
            handlePoseMissing(seconds)
        }
    }

    @Synchronized
    private fun handlePose(
        pose: PoseResult,
        seconds: Double
    ) {
        val landmarks = reliableLandmarks(pose, motionLandmarkTypes)
        if (!PoseHumanGate.isValid(landmarks.keys)) {
            handlePoseMissing(seconds)
            return
        }
        val points = landmarks.values.distinctBy {
            ((it.x * 10_000).roundToInt() shl 16) xor (it.y * 10_000).roundToInt()
        }

        lastPoseSeenSeconds = seconds
        poseTrackingStartedSeconds = null
        val lowest = points.maxByOrNull { it.y } ?: return
        val lowestAboveMat = isAboveMatLine(lowest)

        when (state) {
            State.TRACKING -> {
                // The first valid pose after CV motion is also a crossing candidate. If its
                // lowest reliable point is already above the mat, START is emitted immediately.
                val crossedUp = PoseStartRule.shouldStart(lowestAboveMat, lastLowestAboveMat)
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
        poseDetector.resetTracking()
        state = State.TRACKING
        lastPoseTimestampNanos = null
        lastPoseSeenSeconds = null
        poseTrackingStartedSeconds = null
        lastLowestAboveMat = null
        activeStartSeconds = null
        resetFinishReferenceTrackingLocked()
    }

    private fun returnToCvSearchLocked() {
        poseDetector.resetTracking()
        state = State.WAITING
        lastPoseTimestampNanos = null
        lastPoseSeenSeconds = null
        poseTrackingStartedSeconds = null
        lastLowestAboveMat = null
        activeStartSeconds = null
        resetFinishReferenceTrackingLocked()
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

    private fun adjustedBandSignals(
        frame: SmallFrame,
        base: SmallFrame,
        bins: List<BandBin>,
        kind: BandKind,
        baseline: FloatArray
    ): FloatArray = FloatArray(bins.size) { index ->
        val raw = baseDiffScore(frame, base, bins[index], kind)
        max(0f, raw - baseline.getOrElse(index) { 0f } - TopOutConfig.MotionBaseDiffMargin)
    }

    private fun classifyBoundaryMotion(below: FloatArray, upper: FloatArray): BoundaryMotion? {
        val count = min(below.size, upper.size)
        if (count <= 0) return null
        val combined = FloatArray(count) { max(below[it], upper[it]) }
        val activeIndices = combined.indices.filter { combined[it] >= TopOutConfig.MotionBinThreshold }
        val runs = scoredRuns(activeIndices, combined)
        if (runs.isEmpty()) return null

        val primary = runs
            .filter { it.indices.size in 1..TopOutConfig.MotionBoundaryPrimaryMaxBins }
            .sortedWith(runComparator)
            .firstOrNull() ?: return null
        val second = runs.filter { it.indices != primary.indices }.sortedWith(runComparator).firstOrNull()
            ?: return BoundaryMotion(primary.indices, primary.energy)

        val secondIsSmallNoise = second.indices.size <= TopOutConfig.MotionBoundaryNoiseMaxBins &&
            second.energy <= primary.energy * TopOutConfig.MotionBoundaryNoiseEnergyRatio
        if (secondIsSmallNoise) return BoundaryMotion(primary.indices, primary.energy)

        val primaryDominates = primary.energy >= second.energy * TopOutConfig.MotionBoundaryDominanceRatio ||
            primary.energy - second.energy >= TopOutConfig.MotionBoundaryDominanceMargin
        return if (primaryDominates) BoundaryMotion(primary.indices, primary.energy) else null
    }

    private val runComparator = Comparator<RunScore> { left, right ->
        when {
            left.energy != right.energy -> right.energy.compareTo(left.energy)
            left.indices.size != right.indices.size -> right.indices.size.compareTo(left.indices.size)
            else -> (left.indices.firstOrNull() ?: 0).compareTo(right.indices.firstOrNull() ?: 0)
        }
    }

    private fun scoredRuns(indices: List<Int>, values: FloatArray): List<RunScore> {
        if (indices.isEmpty()) return emptyList()
        val sorted = indices.distinct().sorted()
        val result = mutableListOf<RunScore>()
        var current = mutableListOf<Int>()
        var energy = 0f
        fun flush() {
            if (current.isNotEmpty()) result += RunScore(current.toList(), energy)
            current = mutableListOf()
            energy = 0f
        }
        for (index in sorted) {
            if (index !in values.indices) continue
            if (current.isNotEmpty() && index != current.last() + 1) flush()
            current += index
            energy += values[index]
        }
        flush()
        return result
    }

    private fun makeBaseline(
        frames: List<SmallFrame>,
        base: SmallFrame,
        bins: List<BandBin>,
        kind: BandKind
    ): FloatArray = FloatArray(bins.size) { index ->
        median(frames.map { baseDiffScore(it, base, bins[index], kind) })
    }

    private fun baseDiffScore(frame: SmallFrame, base: SmallFrame, bin: BandBin, kind: BandKind): Float {
        if (frame.width != base.width || frame.height != base.height) return 0f
        val width = frame.width
        val height = frame.height
        val x0 = floor(bin.x0 * width).toInt().coerceIn(0, width - 1)
        val x1 = ceil(bin.x1 * width).toInt().coerceIn(x0 + 1, width)
        val bandHeight = max(0.02f, TopOutConfig.MotionMatBandHeightRatio)
        var changed = 0
        var total = 0

        for (x in x0 until x1) {
            val xNorm = (x + 0.5f) / width
            val boundary = lineY(xNorm)
            val yStart = when (kind) {
                BandKind.BELOW -> boundary
                BandKind.UPPER -> max(0f, boundary - bandHeight)
            }
            val yEnd = when (kind) {
                BandKind.BELOW -> min(1f, boundary + bandHeight)
                BandKind.UPPER -> boundary
            }
            if (yEnd <= yStart) continue
            val y0 = floor(yStart * height).toInt().coerceIn(0, height - 1)
            val y1 = ceil(yEnd * height).toInt().coerceIn(y0 + 1, height)
            for (y in y0 until y1) {
                val index = y * width + x
                if (abs((frame.luma[index].toInt() and 0xFF) - (base.luma[index].toInt() and 0xFF)) >=
                    TopOutConfig.MotionPixelDifference
                ) changed++
                total++
            }
        }
        return if (total > 0) changed.toFloat() / total else 0f
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

    private fun buildBandBins(): List<BandBin> = (0 until max(1, horizontalBinCount)).map { index ->
        BandBin(index.toFloat() / horizontalBinCount, (index + 1f) / horizontalBinCount)
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
        lastPoseTimestampNanos = null
        lastPoseSeenSeconds = null
        poseTrackingStartedSeconds = null
        lastLowestAboveMat = null
        activeStartSeconds = null
        resetFinishReferenceTrackingLocked()
        poseDetector.resetTracking()
        processingPose = false
        if (!keepConfiguration) {
            matLine = emptyList()
            topBox = Rect.Zero
            finishReference = MotionFinishReference.CLOSEST_TOP_HOLD
            horizontalBinCount = TopOutConfig.MatBoundaryBins
            belowBins = emptyList()
            upperBins = emptyList()
            captureBaseFrame = null
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
        poseExecutor.shutdownNow()
        poseDetector.close()
    }


}
