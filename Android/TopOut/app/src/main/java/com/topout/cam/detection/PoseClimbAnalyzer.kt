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
 * Two-mode climbing detector.
 *
 * Initial MOTION search and active climbing run at 2 fps. Once a reliable pose is acquired but
 * the climb has not started, analysis runs at 4 fps; pose-loss recovery also runs at 4 fps. MOTION
 * compares the current analysis frame with the immediately previous frame, builds a quarter-frame
 * square ROI around changed pixels, and runs MoveNet inside that ROI. POSE follows the reliable
 * person's joints. A single unreliable pose switches back to MOTION; a reliable pose switches back
 * to POSE.
 */
class PoseClimbAnalyzer(
    context: Context,
    private val onEvent: (MotionEvent) -> Unit
) : ImageAnalysis.Analyzer {
    private enum class TrackingMode { NONE, MOTION, POSE }

    private data class SmallFrame(val width: Int, val height: Int, val luma: ByteArray)
    private data class FrameGeometry(
        val viewport: Rect,
        val rotation: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val sourceWidth: Int,
        val sourceHeight: Int
    )
    private data class ReliablePose(val points: List<Offset>)
    private data class FrameCaptureRequest(
        val maxLongSide: Int,
        val ready: CompletableDeferred<Unit>,
        val bitmap: AtomicReference<Bitmap?> = AtomicReference(null),
        val framesToSkip: Int
    )

    private val frameCaptureRequest = AtomicReference<FrameCaptureRequest?>(null)
    private val poseDetector = MoveNetThunderPoseDetector(context)
    private val motionLandmarkTypes = intArrayOf(
        PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
        PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
        PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
        PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
        PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE
    )

    @Volatile private var active = false
    @Volatile private var trackingMode = TrackingMode.NONE
    private var isClimbing = false
    private var hasInferenceCrop = false
    private var hasSeenReliablePose = false

    private var matLine: List<Offset> = emptyList()
    private var topBox: Rect = Rect.Zero
    private var finishReference: MotionFinishReference = MotionFinishReference.CLOSEST_TOP_HOLD

    private var analysisOriginTimestampNanos: Long? = null
    private var recordingOriginTimestampNanos: Long? = null
    private var recordingStartPending = false
    private var lastAnalyzedTimestampNanos: Long? = null
    private var previousMotionFrame: SmallFrame? = null

    private var lastPoseSeenSeconds: Double? = null
    private var lastLowestAboveMat: Boolean? = null
    private var activeStartSeconds: Double? = null
    private var activeBestTopHoldSeconds: Double? = null
    private var activeBestTopHoldDistance: Float? = null
    private var activeHighestPoseSeconds: Double? = null
    private var activeHighestPoseY: Float? = null
    private var isPostClimbTracking = false
    private var postClimbConsecutivePoseMisses = 0
    private var rgbScratchPixels = IntArray(0)
    // Analyzer callbacks are serialized by CameraX's single-thread executor. Two buffers preserve
    // the immediately previous frame while the next frame is filled, avoiding a new ByteArray at
    // every 2/4-fps analysis tick.
    private var lumaScratchFrames: Array<ByteArray?> = arrayOfNulls(2)
    private var nextLumaScratchIndex = 0

    suspend fun captureNextFrame(
        maxLongSide: Int = TopOutConfig.CaptureMaxLongSide,
        minimumFramesAfterRequest: Int = 0
    ): Bitmap? {
        val request = FrameCaptureRequest(
            maxLongSide = max(1, maxLongSide),
            ready = CompletableDeferred(),
            framesToSkip = max(0, minimumFramesAfterRequest)
        )
        frameCaptureRequest.getAndSet(request)?.ready?.complete(Unit)
        var bitmap: Bitmap? = null
        try {
            withTimeoutOrNull(1_800L) { request.ready.await() }
            bitmap = request.bitmap.getAndSet(null)
            return bitmap
        } finally {
            if (bitmap == null) {
                // Also runs when the owning UI coroutine is canceled before its timeout. If the
                // analyzer finishes conversion later, ready.complete() fails and recycles it.
                request.ready.cancel()
                request.bitmap.getAndSet(null)?.recycle()
            }
            frameCaptureRequest.get()?.let { pending ->
                if (pending.ready === request.ready) {
                    frameCaptureRequest.compareAndSet(pending, null)
                }
            }
        }
    }

    @Synchronized
    fun configure(
        matLine: List<Offset>,
        topBox: Rect?,
        finishReference: MotionFinishReference
    ) {
        this.matLine = matLine.sortedBy { it.x }
        this.topBox = topBox?.clamped01() ?: Rect.Zero
        this.finishReference = finishReference
        resetStateLocked(keepConfiguration = true)
    }

    @Synchronized
    fun resetForRecording() {
        resetStateLocked(keepConfiguration = true)
    }

    @Synchronized
    fun markRecordingStarted() {
        resetStateLocked(keepConfiguration = true)
        active = true
        trackingMode = TrackingMode.MOTION
        recordingStartPending = true
    }

    @Synchronized
    fun stop(): ClipRange? {
        val open = openClipRangeLocked()
        active = false
        resetStateLocked(keepConfiguration = true)
        return open
    }

    override fun analyze(image: ImageProxy) {
        var cachedGeometry: FrameGeometry? = null
        fun geometryForFrame(): FrameGeometry = cachedGeometry ?: frameGeometry(image).also {
            cachedGeometry = it
        }

        frameCaptureRequest.get()?.let { request ->
            if (request.framesToSkip > 0) {
                frameCaptureRequest.compareAndSet(
                    request,
                    request.copy(framesToSkip = request.framesToSkip - 1)
                )
            } else if (frameCaptureRequest.compareAndSet(request, null)) {
                val bitmap = runCatching {
                    imageProxyToBitmap(image, request.maxLongSide, geometryForFrame())
                }.getOrNull()
                request.bitmap.set(bitmap)
                if (!request.ready.complete(Unit)) {
                    request.bitmap.getAndSet(null)?.recycle()
                }
            }
        }

        val enabled = active && matLine.isNotEmpty() &&
            (finishReference == MotionFinishReference.HIGHEST_POSE_POINT || topBox != Rect.Zero)
        if (!enabled) {
            image.close()
            return
        }

        val timestamp = image.imageInfo.timestamp
        synchronized(this) {
            if (analysisOriginTimestampNanos == null) analysisOriginTimestampNanos = timestamp
            if (recordingStartPending) {
                recordingOriginTimestampNanos = timestamp
                recordingStartPending = false
            }
        }

        val previousTimestamp = lastAnalyzedTimestampNanos
        if (previousTimestamp != null) {
            val elapsed = (timestamp - previousTimestamp) / 1_000_000_000.0
            if (elapsed >= 0.0 && elapsed < currentAnalysisIntervalSeconds()) {
                image.close()
                return
            }
        }
        lastAnalyzedTimestampNanos = timestamp

        val geometry = geometryForFrame()
        val currentFrame = makeSmallFrame(image, geometry)
        if (currentFrame == null) {
            image.close()
            return
        }
        val previousFrame = synchronized(this) { previousMotionFrame }
        val seconds = recordingSeconds(timestamp)

        try {
            processAnalysisFrame(
                image = image,
                geometry = geometry,
                currentFrame = currentFrame,
                previousFrame = previousFrame,
                seconds = seconds
            )
        } catch (_: Exception) {
            handlePoseMiss(seconds, currentFrame, previousFrame)
        } finally {
            synchronized(this) {
                previousMotionFrame = currentFrame
                nextLumaScratchIndex = (nextLumaScratchIndex + 1) % lumaScratchFrames.size
            }
            image.close()
        }
    }

    private fun processAnalysisFrame(
        image: ImageProxy,
        geometry: FrameGeometry,
        currentFrame: SmallFrame,
        previousFrame: SmallFrame?,
        seconds: Double
    ) {
        val mode = synchronized(this) { trackingMode }
        if (mode == TrackingMode.NONE) return

        if (mode == TrackingMode.MOTION) {
            val crop = motionCropForCurrentContext(currentFrame, previousFrame)
            if (crop != null) {
                poseDetector.primeTrackingCrop(crop)
                synchronized(this) { hasInferenceCrop = true }
            }
            if (!synchronized(this) { hasInferenceCrop }) return
        }

        val pose = poseDetector.detectTrackedYuv(
            image = image,
            viewportInDisplay = AndroidRectF(
                geometry.viewport.left,
                geometry.viewport.top,
                geometry.viewport.right,
                geometry.viewport.bottom
            ),
            sourceWidth = geometry.sourceWidth,
            sourceHeight = geometry.sourceHeight,
            updateTrackingRegion = mode == TrackingMode.POSE,
            retainCropOnMissing = true
        )
        val reliable = pose?.let(::reliablePose)
        if (pose == null || reliable == null) {
            handlePoseMiss(seconds, currentFrame, previousFrame)
        } else {
            handleReliablePose(
                pose = pose,
                reliable = reliable,
                seconds = seconds,
                imageWidth = geometry.sourceWidth,
                imageHeight = geometry.sourceHeight
            )
        }
    }

    /**
     * Initial search is restricted to the edited mat-line region. After any reliable person has
     * been seen, the same immediate-frame motion crop may move across the whole frame for recovery.
     */
    private fun motionCropForCurrentContext(
        current: SmallFrame,
        previous: SmallFrame?
    ): MoveNetCropRegion? {
        if (previous == null || current.width != previous.width || current.height != previous.height) {
            return null
        }
        val restrictToMatSearch = synchronized(this) { !hasSeenReliablePose }
        if (restrictToMatSearch) {
            val rawSignal = cvSearchRegionDiffScore(current, previous)
            val adjustedSignal = max(0f, rawSignal - TopOutConfig.MotionBaseDiffMargin)
            if (adjustedSignal < TopOutConfig.MotionCVRegionActiveThreshold) return null
            val bounds = motionSearchBounds(current.width, current.height)
            return MotionSeedCropFinder.find(
                width = current.width,
                height = current.height,
                currentLuma = current.luma,
                previousLuma = previous.luma,
                activeTopByColumn = bounds.first,
                activeBottomExclusiveByColumn = bounds.second
            )
        }
        return MotionSeedCropFinder.find(
            width = current.width,
            height = current.height,
            currentLuma = current.luma,
            previousLuma = previous.luma
        )
    }

    /** Builds one compact reliable-pose representation instead of repeated Map/List conversions. */
    private fun reliablePose(pose: PoseResult): ReliablePose? {
        val points = ArrayList<Offset>(motionLandmarkTypes.size)
        var shoulderCount = 0
        var hipCount = 0
        var elbowCount = 0
        var kneeCount = 0
        for (type in motionLandmarkTypes) {
            val keypoint = pose.keypoints[type] ?: continue
            if (keypoint.confidence < TopOutConfig.PoseConfidence) continue
            val point = Offset(
                keypoint.position.x.coerceIn(0f, 1f),
                keypoint.position.y.coerceIn(0f, 1f)
            )
            points += point
            when (type) {
                PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER -> shoulderCount++
                PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP -> hipCount++
                PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW -> elbowCount++
                PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE -> kneeCount++
            }
        }
        if (!PoseHumanGate.isValid(
                shoulderCount = shoulderCount,
                hipCount = hipCount,
                elbowCount = elbowCount,
                kneeCount = kneeCount
            )
        ) return null
        return ReliablePose(points)
    }

    /** A reliable human pose always returns the detector to joint-derived tracking. */
    @Synchronized
    private fun handleReliablePose(
        pose: PoseResult,
        reliable: ReliablePose,
        seconds: Double,
        imageWidth: Int,
        imageHeight: Int
    ) {
        trackingMode = TrackingMode.POSE
        hasInferenceCrop = true
        hasSeenReliablePose = true
        poseDetector.primeTracking(pose, imageWidth, imageHeight)
        lastPoseSeenSeconds = seconds
        postClimbConsecutivePoseMisses = 0

        val lowest = reliable.points.maxByOrNull { it.y } ?: return
        val lowestAboveStartBoundary = isAboveStartBoundary(lowest)
        val lowestAtOrAboveFinishBoundary = isAtOrAboveFinishBoundary(lowest)

        if (!isClimbing) {
            val shouldStart = PoseStartRule.shouldStart(
                lowestAboveMat = lowestAboveStartBoundary,
                previousLowestAboveMat = lastLowestAboveMat
            )
            lastLowestAboveMat = lowestAboveStartBoundary
            if (!shouldStart) return

            isClimbing = true
            isPostClimbTracking = false
            activeStartSeconds = seconds
            resetFinishReferenceTrackingLocked()
            onEvent(MotionEvent.Start(seconds))
            updateFinishReferenceMoment(reliable.points, seconds)
            return
        }

        updateFinishReferenceMoment(reliable.points, seconds)
        lastLowestAboveMat = lowestAboveStartBoundary
        if (lowestAtOrAboveFinishBoundary) return

        activeStartSeconds ?: return
        val rawEnd = bestFinishReferenceSeconds() ?: seconds
        onEvent(MotionEvent.Finish(rawEnd))
        continueTrackingAfterClimbLocked(seconds)
    }

    /**
     * One unreliable pose always hands ROI placement back to immediate-frame motion. During a climb
     * this recovery may continue for six seconds. After a normal finish, visible pose tracking may
     * continue without a time limit; two consecutive misses reset to initial mat-region motion search.
     */
    private fun handlePoseMiss(
        seconds: Double,
        currentFrame: SmallFrame?,
        previousFrame: SmallFrame?
    ) {
        synchronized(this) {
            if (trackingMode == TrackingMode.NONE) return
            trackingMode = TrackingMode.MOTION

            val recoveryCrop = if (currentFrame != null) {
                motionCropForCurrentContext(currentFrame, previousFrame)
            } else null
            if (recoveryCrop != null) {
                poseDetector.primeTrackingCrop(recoveryCrop)
                hasInferenceCrop = true
            }

            if (isClimbing) {
                val reference = lastPoseSeenSeconds ?: seconds.also { lastPoseSeenSeconds = it }
                if (seconds - reference < TopOutConfig.ClimbingPoseMissingTimeoutSeconds) return

                val open = openClipRangeLocked()
                if (open != null) onEvent(MotionEvent.Finish(open.endSeconds))
                else onEvent(MotionEvent.Retry)
                returnToInitialMotionSearchLocked()
                return
            }

            if (isPostClimbTracking) {
                postClimbConsecutivePoseMisses++
                if (postClimbConsecutivePoseMisses >= 2) {
                    returnToInitialMotionSearchLocked()
                }
            }
        }
    }

    private fun continueTrackingAfterClimbLocked(seconds: Double) {
        isClimbing = false
        trackingMode = TrackingMode.POSE
        isPostClimbTracking = true
        postClimbConsecutivePoseMisses = 0
        lastPoseSeenSeconds = seconds
        lastLowestAboveMat = false
        activeStartSeconds = null
        resetFinishReferenceTrackingLocked()
    }

    private fun returnToInitialMotionSearchLocked() {
        trackingMode = TrackingMode.MOTION
        isClimbing = false
        hasInferenceCrop = false
        hasSeenReliablePose = false
        poseDetector.resetTracking()
        previousMotionFrame = null
        lastPoseSeenSeconds = null
        lastLowestAboveMat = null
        isPostClimbTracking = false
        postClimbConsecutivePoseMisses = 0
        activeStartSeconds = null
        resetFinishReferenceTrackingLocked()
    }

    private fun openClipRangeLocked(): ClipRange? {
        if (!isClimbing) return null
        val start = activeStartSeconds ?: return null
        val finishBase = bestFinishReferenceSeconds() ?: lastPoseSeenSeconds ?: return null
        return if (finishBase > start) ClipRange(start, finishBase) else null
    }

    /** Points and the mat line share the selected CameraX ViewPort's normalized video space. */
    private fun isAboveStartBoundary(point: Offset): Boolean {
        val startBoundary = (lineY(point.x) - TopOutConfig.PoseStartAboveMatVideoHeightRatio)
            .coerceAtLeast(0f)
        return point.y <= startBoundary
    }

    /** The finish line sits above the mat line by a fraction of the selected video's height. */
    private fun isAtOrAboveFinishBoundary(point: Offset): Boolean {
        val finishBoundary = (lineY(point.x) - TopOutConfig.PoseFinishAboveMatVideoHeightRatio)
            .coerceAtLeast(0f)
        return point.y <= finishBoundary
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

    private fun motionSearchBounds(width: Int, height: Int): Pair<IntArray, IntArray> {
        val top = IntArray(width)
        val bottom = IntArray(width)
        val regionHeight = TopOutConfig.MotionCVRegionAboveMatHeightRatio.coerceIn(0.02f, 1f)
        for (x in 0 until width) {
            val xNorm = (x + 0.5f) / width
            val boundary = lineY(xNorm)
            top[x] = floor(max(0f, boundary - regionHeight) * height)
                .toInt()
                .coerceIn(0, height - 1)
            bottom[x] = ceil(boundary * height)
                .toInt()
                .coerceIn(top[x] + 1, height)
        }
        return top to bottom
    }

    /**
     * Compares only the region above the edited mat line, up to 40% of the visible frame height.
     * The reference is the immediately previous throttled analysis frame, so all motion
     * search and pose-recovery paths share the same comparison rule.
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

    /** Converts a YUV_420_888 frame to an oriented RGB bitmap using the shared ViewPort crop. */
    private fun imageProxyToBitmap(
        image: ImageProxy,
        maxLongSide: Int,
        geometry: FrameGeometry
    ): Bitmap? {
        if (image.width <= 0 || image.height <= 0 || image.planes.size < 3) return null
        val rotation = geometry.rotation
        val crop = geometry.viewport
        val cropWidth = max(1f, crop.width * geometry.displayWidth)
        val cropHeight = max(1f, crop.height * geometry.displayHeight)
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
    private fun makeSmallFrame(image: ImageProxy, geometry: FrameGeometry): SmallFrame? {
        val plane = image.planes.firstOrNull() ?: return null
        val crop = geometry.viewport
        val rotation = geometry.rotation
        val cropPixelWidth = max(1f, crop.width * geometry.displayWidth)
        val cropPixelHeight = max(1f, crop.height * geometry.displayHeight)
        val outputWidth = max(32, TopOutConfig.MotionFrameWidth)
        val outputHeight = max(32, (outputWidth * cropPixelHeight / cropPixelWidth).roundToInt())
        val requiredBytes = outputWidth * outputHeight
        val output = synchronized(this) {
            lumaScratchFrames[nextLumaScratchIndex]
                ?.takeIf { it.size == requiredBytes }
                ?: ByteArray(requiredBytes).also {
                    lumaScratchFrames[nextLumaScratchIndex] = it
                }
        }
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

    /** Computes the shared CameraX ViewPort geometry once for all work on this ImageProxy. */
    private fun frameGeometry(image: ImageProxy): FrameGeometry {
        val rotation = normalizedRotation(image.imageInfo.rotationDegrees)
        val raw = image.cropRect
        val left = raw.left.toFloat() / image.width
        val top = raw.top.toFloat() / image.height
        val right = raw.right.toFloat() / image.width
        val bottom = raw.bottom.toFloat() / image.height
        val viewport = when (rotation) {
            90 -> Rect(1f - bottom, left, 1f - top, right)
            180 -> Rect(1f - right, 1f - bottom, 1f - left, 1f - top)
            270 -> Rect(top, 1f - right, bottom, 1f - left)
            else -> Rect(left, top, right, bottom)
        }.clamped01()
        val displayWidth = if (rotation % 180 == 0) image.width else image.height
        val displayHeight = if (rotation % 180 == 0) image.height else image.width
        return FrameGeometry(
            viewport = viewport,
            rotation = rotation,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            sourceWidth = max(1, (viewport.width * displayWidth).roundToInt()),
            sourceHeight = max(1, (viewport.height * displayHeight).roundToInt())
        )
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

    /**
     * 4 fps is used only while a reliable person is tracked before the next climb starts, or while
     * MOTION is recovering a previously seen pose. Initial motion search and active climbing stay
     * at 2 fps.
     */
    private fun currentAnalysisIntervalSeconds(): Double = synchronized(this) {
        val preStartPoseTracking = trackingMode == TrackingMode.POSE && !isClimbing
        val poseLossRecovery = trackingMode == TrackingMode.MOTION && hasSeenReliablePose
        if (preStartPoseTracking || poseLossRecovery) {
            TopOutConfig.FastAnalysisIntervalSeconds
        } else {
            TopOutConfig.AnalysisIntervalSeconds
        }
    }

    private fun recordingSeconds(timestampNanos: Long): Double {
        val origin = recordingOriginTimestampNanos ?: analysisOriginTimestampNanos ?: timestampNanos
        return max(0.0, (timestampNanos - origin) / 1_000_000_000.0)
    }

    @Synchronized
    private fun resetStateLocked(keepConfiguration: Boolean) {
        active = false
        trackingMode = TrackingMode.NONE
        isClimbing = false
        hasInferenceCrop = false
        hasSeenReliablePose = false
        analysisOriginTimestampNanos = null
        recordingOriginTimestampNanos = null
        recordingStartPending = false
        lastAnalyzedTimestampNanos = null
        previousMotionFrame = null
        lastPoseSeenSeconds = null
        lastLowestAboveMat = null
        activeStartSeconds = null
        isPostClimbTracking = false
        postClimbConsecutivePoseMisses = 0
        resetFinishReferenceTrackingLocked()
        poseDetector.resetTracking()
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
        frameCaptureRequest.getAndSet(null)?.let { request ->
            request.ready.cancel()
            request.bitmap.getAndSet(null)?.recycle()
        }
        synchronized(this) {
            previousMotionFrame = null
            rgbScratchPixels = IntArray(0)
            lumaScratchFrames = arrayOfNulls(2)
            nextLumaScratchIndex = 0
        }
        poseDetector.close()
    }

}
