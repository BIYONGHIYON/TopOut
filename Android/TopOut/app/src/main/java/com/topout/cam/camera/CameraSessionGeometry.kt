package com.topout.cam.camera

import android.util.Rational
import android.util.Size
import androidx.camera.core.ViewPort
import androidx.camera.view.PreviewView
import com.topout.cam.model.RecordingAspectRatio
import kotlin.math.max
import kotlin.math.round

/**
 * Requests an analysis stream whose displayed dimensions already match the selected frame.
 * CameraX may choose a nearby supported size, while the shared ViewPort still guarantees the
 * exact crop. Avoiding a fixed 16:9 request reduces OEM-specific custom-ratio artifacts.
 */
internal fun analysisTargetSize(
    aspectRatio: RecordingAspectRatio,
    isLandscape: Boolean,
    longSide: Int = 1280
): Size {
    val safeLongSide = max(320, longSide)
    val ratio = aspectRatio.widthOverHeight(isLandscape).coerceAtLeast(0.01f)
    fun even(value: Int): Int {
        val clamped = max(2, value)
        return clamped - (clamped and 1)
    }
    return if (ratio >= 1f) {
        Size(even(safeLongSide), even(round(safeLongSide / ratio).toInt()))
    } else {
        Size(even(round(safeLongSide * ratio).toInt()), even(safeLongSide))
    }
}

/** One source of truth for the frame visible in Preview, Analysis, and VideoCapture. */
internal fun selectedFrameViewPort(
    aspectRatio: RecordingAspectRatio,
    isLandscape: Boolean,
    targetRotation: Int
): ViewPort {
    val ratio = aspectRatio.viewPortRatio(isLandscape)
    return ViewPort.Builder(Rational(ratio.width, ratio.height), targetRotation)
        .setScaleType(ViewPort.FILL_CENTER)
        .build()
}

/** Prefer CPU analysis streams with the same orientation and ratio as the selected frame. */
internal fun recordingAnalysisTargetSizes(
    aspectRatio: RecordingAspectRatio,
    isLandscape: Boolean
): List<Size> = listOf(960, 640, 1280, 480, 320)
    .map { longSide -> analysisTargetSize(aspectRatio, isLandscape, longSide) }
    .distinct()

/**
 * Use PreviewView's measured ViewPort while its display rotation matches the capture session.
 * On an Android 16 large-screen retake the window may have rotated in Review even though we
 * intentionally restore the previous take's targetRotation; in that case PreviewView.viewPort
 * belongs to the new window rotation, so rebuild the same aspect crop with the frozen rotation.
 */
internal fun previewViewPort(
    previewView: PreviewView,
    aspectRatio: RecordingAspectRatio,
    isLandscape: Boolean,
    targetRotation: Int
): ViewPort {
    val displayRotation = previewView.display?.rotation
    return if (displayRotation == null || displayRotation == targetRotation) {
        previewView.viewPort
            ?: selectedFrameViewPort(aspectRatio, isLandscape, targetRotation)
    } else {
        selectedFrameViewPort(aspectRatio, isLandscape, targetRotation)
    }
}
