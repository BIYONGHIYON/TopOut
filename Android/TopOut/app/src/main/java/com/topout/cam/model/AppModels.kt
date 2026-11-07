package com.topout.cam.model

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.topout.cam.localization.TopOutStrings
import com.topout.cam.localization.tr
import kotlin.math.max
import kotlin.math.min

/** UI stages mirror HoldVC's camera, mat-line selection, recording and review modes. */
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
    val subtitleKey: String,
    val portraitWidthOverHeight: Float
) {
    STORY_9_16("9:16", "16:9", "settings.story_reels", 9f / 16f),
    FEED_4_5("4:5", "5:4", "settings.instagram_feed", 4f / 5f),
    PORTRAIT_3_4("3:4", "4:3", "settings.portrait_post", 3f / 4f),
    SQUARE_1_1("1:1", "1:1", "settings.square_post", 1f);

    val subtitle: String get() = tr(subtitleKey)

    fun title(isLandscape: Boolean): String = if (isLandscape) landscapeTitle else portraitTitle

    fun widthOverHeight(isLandscape: Boolean): Float =
        if (isLandscape) 1f / portraitWidthOverHeight else portraitWidthOverHeight

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
    val automaticStopNotice: RecordingStopNotice? = null,
    /** 탑홀드 마스킹을 켠 녹화는 결과 화면에서 멈춘 프레임의 탑홀드를 탭해 마스크를 지정한다. */
    val topHoldSelectionEnabled: Boolean = false,
    /** 마스크 비트맵을 영상과 같은 크기로 다시 만들 때 쓰는 표시 크기. */
    val overlayWidth: Int = 0,
    val overlayHeight: Int = 0
)

/** Exact review selection used to keep save progress/results isolated per detected clip. */
data class GalleryClipSelection(
    val startSeconds: Double,
    val endSeconds: Double,
    val includeOverlay: Boolean
) {
    fun matches(other: GalleryClipSelection, toleranceSeconds: Double = 0.01): Boolean =
        kotlin.math.abs(startSeconds - other.startSeconds) < toleranceSeconds &&
            kotlin.math.abs(endSeconds - other.endSeconds) < toleranceSeconds &&
            includeOverlay == other.includeOverlay
}

/** A clip that was written to MediaStore and successfully reopened from the gallery. */
data class SavedGalleryVideo(
    val uri: Uri,
    val startSeconds: Double,
    val endSeconds: Double,
    val includeOverlay: Boolean
) {
    val selection: GalleryClipSelection
        get() = GalleryClipSelection(startSeconds, endSeconds, includeOverlay)
}

data class RecordingStopNotice(val title: String, val message: String) {
    companion object {
        val Background: RecordingStopNotice
            get() = RecordingStopNotice(tr("notice.background_title"), tr("notice.background_message"))
        val CaptureInterruption: RecordingStopNotice
            get() = RecordingStopNotice(tr("notice.interruption_title"), tr("notice.interruption_message"))
        val SessionError: RecordingStopNotice
            get() = RecordingStopNotice(tr("notice.camera_error_title"), tr("notice.camera_error_message"))
        fun lowStorage(availableBytes: Long?): RecordingStopNotice {
            val available = availableBytes?.let { tr("notice.available_storage", formatBytes(it)) }.orEmpty()
            return RecordingStopNotice(
                tr("notice.low_storage_title"),
                tr("notice.low_storage_message", available)
            )
        }

        private fun formatBytes(bytes: Long): String {
            val gb = bytes / 1_073_741_824.0
            return if (gb >= 1) {
                String.format(TopOutStrings.currentLocale(), "%.1fGB", gb)
            } else {
                String.format(TopOutStrings.currentLocale(), "%.0fMB", bytes / 1_048_576.0)
            }
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
    /** Gesture segment starts that actually require a camera route change after the first value. */
    val lensZoomRatios: List<Float> = listOf(TopOutConfig.MinZoom, 1f)
) {
    fun supports(resolution: RecordingResolution, frameRate: RecordingFrameRate): Boolean =
        supportedFrameRates[resolution]?.contains(frameRate) == true
}

enum class LensTransitionPhase {
    IDLE,
    AWAITING_GESTURE_END,
    REBINDING,
    FADING
}

data class LensTransitionUiState(
    val id: Long? = null,
    val phase: LensTransitionPhase = LensTransitionPhase.IDLE,
    /** Captured once when a physical-camera transition starts. */
    val frame: Bitmap? = null,
    /** Tracks the live pinch ratio for the captured frame; zoom-out stays at 1x. */
    val frameScale: Float = 1f,
    /** Changes after every completion/cancellation so an active detector rebases exactly once. */
    val rebaseToken: Long = 0L
) {
    val inProgress: Boolean
        get() = phase == LensTransitionPhase.AWAITING_GESTURE_END ||
            phase == LensTransitionPhase.REBINDING

    val awaitingGestureEnd: Boolean
        get() = phase == LensTransitionPhase.AWAITING_GESTURE_END

    val fading: Boolean
        get() = phase == LensTransitionPhase.FADING
}

data class TopOutUiState(
    val stage: CameraStage = CameraStage.PREVIEW,
    val recordingState: RecordingState = RecordingState.IDLE,
    val settings: AppSettings = AppSettings(),
    val capabilities: CameraCapabilities = CameraCapabilities(),
    val matLine: List<Offset> = emptyList(),
    val zoomFactor: Float = TopOutConfig.MinZoom,
    val lensTransition: LensTransitionUiState = LensTransitionUiState(),
    val recordingElapsedSeconds: Double = 0.0,
    val recordingCountdown: Int? = null,
    val livePreviewEnabled: Boolean = true,
    val statusText: String = tr("camera.preview_mat"),
    val detectedRanges: List<ClipRange> = emptyList(),
    val recordedVideo: RecordedVideo? = null,
    val settingsVisible: Boolean = false,
    /** Mirrors HoldVC.isBusy while capturing the Ready frame or running MobileSAM. */
    val isBusy: Boolean = false,
    val saving: Boolean = false,
    val savingStatus: String? = null,
    /** 결과 화면 탑홀드 지정 중이거나 방금 끝난 결과 안내. */
    val reviewTopHoldAnalyzing: Boolean = false,
    val reviewTopHoldStatus: String? = null,
    /** Selection that owns [savingStatus], so messages never leak into another detected clip. */
    val savingStatusSelection: GalleryClipSelection? = null,
    /** Every clip whose MediaStore row and contents were reopened successfully. */
    val savedGalleryVideos: List<SavedGalleryVideo> = emptyList(),
    val errorMessage: String? = null,
    val launchVisible: Boolean = true,
    /** True only after CameraX has bound and PreviewView reports a real streaming surface. */
    val cameraReady: Boolean = false,
    val isLandscape: Boolean = false,
    /** CameraX target rotation frozen at Ready so large-screen window rotations cannot move FOV. */
    val cameraTargetRotation: Int = 0
) {
    /** 녹화 중 실시간 영상 설정이 켜져 있으면 true. */
    val livePreviewVisible: Boolean
        get() = livePreviewEnabled
}

/** Values are a direct port of Config.swift. */
object TopOutConfig {
    const val SamSize = 1024
    const val MaskSize = 256
    val SamMean = floatArrayOf(123.675f, 116.28f, 103.53f)
    val SamStd = floatArrayOf(58.395f, 57.12f, 57.375f)

    // 탑홀드 선택 규칙은 sam/HoldOutlineExtractor.Tuning에 있다.
    /** 최소 표시 원 지름: 짧은 변의 3.75%, 22px 이상, 짧은 변의 6% 이하. */
    const val FallbackHoldMaskDiameterRatio = 0.0375f
    const val FallbackHoldMaskMinDiameter = 22f
    const val FallbackHoldMaskMaxDiameterRatio = 0.06f
    const val TopHoldAnalysisTimeoutMillis = 5_000L

    // iOS와 같이 선택 화면은 5배, 카메라는 8배로 제한한다.
    const val MinZoom = 0.5f
    const val SelectionMinZoom = 1f
    const val SelectionMaxZoom = 5f
    const val CameraMaxZoom = 8f
    const val CaptureMaxLongSide = 1600

    const val RedLineRatio = 0.016f
    const val WhiteLineRatio = 0.006f
    const val MinRedLineWidth = 8f
    const val MinWhiteLineWidth = 2.5f

    const val MatDefaultY = 0.75f
    const val MatBoundaryAnalysisTimeoutMillis = 5_000L
    const val ReviewEndPreviewLeadSeconds = 3.0
    /** The lowest reliable joint must clear the mat line by 6% of the selected video height. */
    const val PoseStartAboveMatVideoHeightRatio = 0.06f
    /** Finish after the joint drops below a boundary 4% above the line in selected-video space. */
    const val PoseFinishAboveMatVideoHeightRatio = 0.04f
    /** Guarded starts need three lower-body joints including at least one ankle. */
    const val PoseStartTorsoSideEdgeMarginRatio = 0.10f
    const val PoseStartMinimumLowerBodyPointsWhenGuarded = 3
    const val PoseStartMinimumAnklePointsWhenGuarded = 1

    /** Initial motion search and active climbing remain at 2 fps. */
    const val MotionFrameWidth = 160
    const val MotionCVRegionAboveMatHeightRatio = 0.40f
    /** With the 2.5% base margin, motion now wakes at roughly 4.5% raw changed pixels. */
    const val MotionCVRegionActiveThreshold = 0.02f
    const val MotionPixelDifference = 20
    const val RecordingCountdownSeconds = 3.0
    const val AutomaticRecordingStartDelayMillis = 60_000L
    const val AutomaticRecordingWarningLeadMillis = 5_000L
    const val MotionBaseDiffMargin = 0.025f

    const val AnalysisIntervalSeconds = 0.5
    /** Pre-start pose tracking and pose-loss recovery run at 4 fps. */
    const val FastAnalysisIntervalSeconds = 0.25
    const val MoveNetPoseInputSize = 256
    const val MoveNetCropKeypointConfidence = 0.15f
    const val MotionSeedGridColumns = 8
    const val MotionSeedCropExpansionScale = 1.5f
    const val MoveNetTrackingCropExpansionScale = 1.5f
    const val MotionSeedMaximumSelectedCells = 8
    const val MotionSeedRelativeCellThreshold = 0.45f
    const val MotionSeedMinimumCellChangedRatio = 0.02f
    const val ClimbingPoseMissingTimeoutSeconds = 6.0
    const val ClipStartPaddingSeconds = 5.0
    const val ClipEndPaddingSeconds = 3.0
    /** Minimum MoveNet confidence for a joint to participate in human validation and climb logic. */
    const val PoseConfidence = 0.25f
    const val PoseMinimumShoulders = 1
    const val PoseMinimumHips = 1
    const val PoseMinimumElbows = 1
    const val PoseMinimumKnees = 1
    /** Human validation passes when at least 3 of the 4 required joint groups are present. */
    const val PoseMinimumSatisfiedGroups = 3
    /** 믿을 만한 관절이 화면 긴 변의 이 비율보다 좁게 몰리면 홀드 위 가짜 뼈대로 보고 버린다. */
    const val PoseMinimumSkeletonSpanRatio = 0.05f
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
