package com.topout.cam.model

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** UI stages mirror HoldVC's camera, selection, recording and review modes. */
enum class CameraStage { PREVIEW, SELECT, RECORDING, REVIEW }
enum class RecordingState { IDLE, STARTING, RECORDING, FINALIZING, EXPORTING }

enum class RecordingResolution(val title: String) {
    FHD("FHD"), UHD("4K");

    fun portraitSize(): IntSize = when (this) {
        FHD -> IntSize(1080, 1920)
        UHD -> IntSize(2160, 3840)
    }

    fun estimatedBitrate(fps: RecordingFrameRate, aspectRatio: RecordingAspectRatio): Long {
        val base = when (this to fps) {
            FHD to RecordingFrameRate.FPS30 -> 8_000_000L
            FHD to RecordingFrameRate.FPS60 -> 14_000_000L
            UHD to RecordingFrameRate.FPS30 -> 45_000_000L
            else -> 70_000_000L
        }
        return (base * aspectRatio.pixelScaleComparedToStory).toLong()
    }
}

enum class RecordingFrameRate(val fps: Int, val title: String) {
    FPS30(30, "30fps"), FPS60(60, "60fps")
}

enum class RecordingAspectRatio(
    val portraitTitle: String,
    val landscapeTitle: String,
    val subtitle: String,
    val portraitWidthOverHeight: Float
) {
    STORY_9_16("9:16", "16:9", "스토리·릴스", 9f / 16f),
    FEED_4_5("4:5", "5:4", "인스타 피드", 4f / 5f),
    PORTRAIT_3_4("3:4", "4:3", "세로 게시물", 3f / 4f),
    SQUARE_1_1("1:1", "1:1", "정사각 게시물", 1f);

    fun title(isLandscape: Boolean): String = if (isLandscape) landscapeTitle else portraitTitle

    fun widthOverHeight(isLandscape: Boolean): Float =
        if (isLandscape) 1f / portraitWidthOverHeight else portraitWidthOverHeight

    fun adaptiveHorizontalBinCount(baseCount: Int, isLandscape: Boolean): Int {
        val base = max(1, baseCount)
        val ratio = widthOverHeight(isLandscape) / max(portraitWidthOverHeight, 0.0001f)
        return max(base, ceil(base * ratio).toInt())
    }

    /** Exact integer ratio used by CameraX ViewPort; avoids OEM rounding of custom float ratios. */
    fun viewPortRatio(isLandscape: Boolean): IntSize {
        val portrait = when (this) {
            STORY_9_16 -> IntSize(9, 16)
            FEED_4_5 -> IntSize(4, 5)
            PORTRAIT_3_4 -> IntSize(3, 4)
            SQUARE_1_1 -> IntSize(1, 1)
        }
        return if (isLandscape) IntSize(portrait.height, portrait.width) else portrait
    }

    val pixelScaleComparedToStory: Double
        get() {
            val heightComparedToStory = when (this) {
                STORY_9_16 -> 1.0
                FEED_4_5 -> (5.0 / 4.0) / (16.0 / 9.0)
                PORTRAIT_3_4 -> (4.0 / 3.0) / (16.0 / 9.0)
                SQUARE_1_1 -> 1.0 / (16.0 / 9.0)
            }
            return heightComparedToStory.coerceIn(0.55, 1.0)
        }

    /** Exact encoded dimensions used by the Swift recorder. */
    fun outputSize(resolution: RecordingResolution, isLandscape: Boolean): IntSize {
        val base = resolution.portraitSize()
        val width = base.width.toFloat()
        val height = min(width / portraitWidthOverHeight, base.height.toFloat())
        val portrait = IntSize(width.toInt().toEven(), height.toInt().toEven())
        return if (isLandscape && portrait.height > portrait.width) {
            IntSize(portrait.height, portrait.width)
        } else portrait
    }

}

data class IntSize(val width: Int, val height: Int)
private fun Int.toEven(): Int {
    val rounded = coerceAtLeast(2)
    return rounded - (rounded and 1)
}

data class AppSettings(
    val resolution: RecordingResolution = RecordingResolution.FHD,
    val frameRate: RecordingFrameRate = RecordingFrameRate.FPS60,
    val aspectRatio: RecordingAspectRatio = RecordingAspectRatio.STORY_9_16,
    val autoClipDetectionEnabled: Boolean = true,
    val topHoldMaskingEnabled: Boolean = false,
    val helpEnabled: Boolean = true,
    /** Mirrors iOS developer mode: true keeps the camera visible while recording. */
    val livePreviewDuringRecordingEnabled: Boolean = false
) {
    /** iOS only requires a top-hold selection when top-hold masking is enabled. */
    val requiresTopHoldSelection: Boolean
        get() = topHoldMaskingEnabled

    /** The four feature switches are independent in the completed iOS app. */
    fun normalized(): AppSettings = this
}

data class HoldMask(
    val center: Offset,
    val radiusX: Float,
    val radiusY: Float,
    val normalizedBounds: Rect,
    val normalizedContour: List<Offset>
)

data class ClipRange(
    val startSeconds: Double,
    val endSeconds: Double,
    /** Original detected start before review padding is added at recording finalization. */
    val unpaddedStartSeconds: Double = startSeconds,
    /** Original detected finish-reference time before review padding is added. */
    val unpaddedEndSeconds: Double = endSeconds
) {
    val durationSeconds: Double get() = (endSeconds - startSeconds).coerceAtLeast(0.0)
}

data class RecordedVideo(
    val uri: Uri,
    val durationSeconds: Double,
    val detectedRanges: List<ClipRange>,
    val defaultStartSeconds: Double,
    val defaultEndSeconds: Double,
    val overlayBitmap: Bitmap?,
    val aspectRatio: RecordingAspectRatio,
    val automaticStopNotice: RecordingStopNotice? = null
)

data class RecordingStopNotice(val title: String, val message: String) {
    companion object {
        val Background = RecordingStopNotice(
            "녹화가 자동 종료되었습니다",
            "앱이 백그라운드로 이동해 녹화를 안전하게 종료했습니다. 녹화된 영상은 결과 화면에서 확인할 수 있습니다."
        )
        val CaptureInterruption = RecordingStopNotice(
            "녹화가 중단되었습니다",
            "전화 수신 또는 다른 앱의 카메라·마이크 사용으로 촬영 장치가 중단되어 녹화를 안전하게 종료했습니다."
        )
        val SessionError = RecordingStopNotice(
            "카메라 오류로 녹화가 종료되었습니다",
            "카메라 세션 오류가 발생해 현재까지 촬영된 영상을 안전하게 마무리했습니다."
        )
        fun lowStorage(availableBytes: Long?): RecordingStopNotice {
            val available = availableBytes?.let { " 현재 사용 가능한 저장공간은 약 ${formatBytes(it)}입니다." }.orEmpty()
            return RecordingStopNotice(
                "저장공간이 부족합니다",
                "저장공간이 임박해 녹화를 자동 종료했습니다. 영상을 저장하기 전에 기기의 저장공간을 확보해주세요.$available"
            )
        }

        private fun formatBytes(bytes: Long): String {
            val gb = bytes / 1_073_741_824.0
            return if (gb >= 1) String.format("%.1fGB", gb) else String.format("%.0fMB", bytes / 1_048_576.0)
        }
    }
}

data class CameraCapabilities(
    val supportedFrameRates: Map<RecordingResolution, Set<RecordingFrameRate>> = mapOf(
        RecordingResolution.FHD to RecordingFrameRate.entries.toSet(),
        RecordingResolution.UHD to setOf(RecordingFrameRate.FPS30)
    ),
    val minZoomRatio: Float = TopOutConfig.MinZoom,
    val maxZoomRatio: Float = TopOutConfig.CameraMaxZoom,
    /** Native optical lens anchors, for example 0.5x, 1.0x and 3.0x. */
    val lensZoomRatios: List<Float> = listOf(TopOutConfig.MinZoom, 1f)
) {
    fun supports(resolution: RecordingResolution, frameRate: RecordingFrameRate): Boolean =
        supportedFrameRates[resolution]?.contains(frameRate) == true
}

data class TopOutUiState(
    val stage: CameraStage = CameraStage.PREVIEW,
    val recordingState: RecordingState = RecordingState.IDLE,
    val settings: AppSettings = AppSettings(),
    val capabilities: CameraCapabilities = CameraCapabilities(),
    val frozenFrame: Bitmap? = null,
    val matLine: List<Offset> = emptyList(),
    val holdMask: HoldMask? = null,
    val zoomFactor: Float = TopOutConfig.MinZoom,
    /** True until the newly bound physical lens is actually streaming at its boundary zoom. */
    val lensSwitchInProgress: Boolean = false,
    /** Increments after each completed lens switch so an active pinch can always rebase once. */
    val lensSwitchSequence: Long = 0L,
    val recordingElapsedSeconds: Double = 0.0,
    val recordingCountdown: Int? = null,
    val livePreviewEnabled: Boolean = true,
    val statusText: String = "벽과 매트 경계선이 보이게 맞춘 뒤 준비 완료",
    val detectedRanges: List<ClipRange> = emptyList(),
    val recordedVideo: RecordedVideo? = null,
    val settingsVisible: Boolean = false,
    /** Mirrors HoldVC.isBusy while capturing a frame or running MobileSAM. */
    val isBusy: Boolean = false,
    val saving: Boolean = false,
    val savingStatus: String? = null,
    val errorMessage: String? = null,
    val launchVisible: Boolean = true,
    val isLandscape: Boolean = false
)

/** Values are a direct port of Config.swift. */
object TopOutConfig {
    const val SamSize = 1024
    const val MaskSize = 256
    val SamMean = floatArrayOf(123.675f, 116.28f, 103.53f)
    val SamStd = floatArrayOf(58.395f, 57.12f, 57.375f)

    const val HsvTolerance = 0.15f
    val LooseHsvTolerances = floatArrayOf(0.18f, 0.21f)
    const val WallSpanRatio = 0.25f
    const val TinyAreaRatio = 0.0006f
    val SearchRadii = listOf(0.01f, 0.02f, 0.03f)
    const val MaxCorrectionDecodes = 8
    const val ColorSampleRadius = 1
    const val FallbackHoldMaskDiameterRatio = 0.075f
    const val FallbackHoldMaskMinDiameter = 44f
    const val FallbackHoldMaskMaxDiameterRatio = 0.12f

    // Match the completed iOS app: selection is capped at 5x and camera raw zoom at 8x.
    const val MinZoom = 0.5f
    const val SelectionMinZoom = 1f
    const val SelectionMaxZoom = 5f
    const val CameraMaxZoom = 8f
    const val CaptureMaxLongSide = 1600

    const val TinyRoundMaxSpan = 6f
    const val TinyRoundMaxArea = 75
    const val RoundAspectLimit = 1.8f
    const val RoundedMaxAreaRatio = 0.0625f
    const val RoundedPaddingRatio = 0.10f
    const val RoundedCornerRatio = 0.48f
    const val RedLineRatio = 0.016f
    const val WhiteLineRatio = 0.006f
    const val MinRedLineWidth = 8f
    const val MinWhiteLineWidth = 2.5f
    const val ContourRawPointLimit = 160
    const val ContourPointLimit = 26
    const val ContourSmoothIterations = 2
    const val ContourMinPoints = 8

    const val MatDefaultY = 0.75f
    const val MatBoundaryBins = 10

    const val MotionAnalysisCalibrationFps = 8.0
    const val MotionAnalysisIdleFps = 6.0
    const val MotionAnalysisBoostFps = 12.0
    const val MotionAnalysisBoostSeconds = 1.2
    const val MotionFrameWidth = 160
    const val MotionCVComparisonFrameLag = 3
    const val MotionCVRegionAboveMatHeightRatio = 0.40f
    const val MotionCVRegionActiveThreshold = 0.04f
    const val MotionPixelDifference = 20
    const val MotionCountdownSeconds = 3.0
    const val MotionCalibrationMadFactor = 2.8f
    const val MotionCalibrationMinOutlierDelta = 0.035f
    const val MotionMatBandHeightRatio = 0.05f
    const val MotionBinThreshold = 0.08f
    const val MotionBaseDiffMargin = 0.025f
    const val MotionBoundaryPrimaryMaxBins = 3
    const val MotionBoundaryNoiseMaxBins = 2
    const val MotionBoundaryNoiseEnergyRatio = 0.55f
    const val MotionBoundaryDominanceRatio = 2.2f
    const val MotionBoundaryDominanceMargin = 0.10f

    const val PoseStartVerificationIntervalSeconds = 0.5
    const val PoseIntervalSeconds = 0.5
    const val PoseInputMaxLongSide = 384
    const val MoveNetPoseInputSize = 192
    const val MoveNetCropKeypointConfidence = 0.15f
    const val SmartCropMotionVerificationFrames = 3
    const val SmartCropMinimumMovedLandmarks = 3
    const val SmartCropMinimumMovementRatio = 0.05f
    const val SmartCropPostUnlockConfirmationFrames = 2
    const val SmartCropConsecutiveMissingFramesBeforeReset = 2
    // Retained for the dormant PicoDet/RTMPose fallback implementation.
    const val PosePersonConfidence = 0.25f
    const val PoseNmsIou = 0.45f
    const val PoseNoPoseRetrySeconds = 6.0
    const val ClipBoundaryPaddingSeconds = 3.0
    const val PoseConfidence = 0.25f
    const val PoseMinimumReliableLandmarks = 4
    const val PoseMinimumCoreLandmarks = 2
    const val PoseMatTolerance = 0.02f
    /** Raw detected climbs shorter than this are treated as short candidates before padding. */
    const val ShortClipMinimumDetectionSeconds = 2.0
    /** A short raw range may merge only when its raw gap to a neighbour is at most this value. */
    const val ShortClipMergeGapSeconds = 3.0

    const val FineAdjustmentStep = 0.1
    const val FineAdjustmentEnterSpeed = 35f
    const val FineAdjustmentExitSpeed = 55f
    const val FineAdjustmentActivationSeconds = 0.14
    const val MinimumTrimRangeSeconds = 0.1

    const val StorageCheckIntervalMillis = 60_000L
    const val MinimumStorageBytes = 1_073_741_824L
}
