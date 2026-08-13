package com.topout.cam.ui.components

import kotlin.math.max
import kotlin.math.min

internal data class TimelineViewport(
    val zoomFactor: Double,
    val startSeconds: Double,
    val spanSeconds: Double
) {
    val endSeconds: Double get() = startSeconds + spanSeconds
}

internal object TimelineZoomMath {
    fun stableMaximumZoomFactor(
        allowedMaximum: Double,
        currentZoom: Double,
        tolerance: Double = 0.0001
    ): Double = if (currentZoom > allowedMaximum + tolerance) currentZoom else allowedMaximum

    fun valueForTrackPosition(
        positionPx: Double,
        widthPx: Double,
        minimumValue: Double,
        maximumValue: Double,
        horizontalInsetPx: Double
    ): Double {
        val lower = min(minimumValue, maximumValue)
        val upper = max(minimumValue, maximumValue)
        val safeInset = horizontalInsetPx.coerceAtLeast(0.0)
        val usableWidth = (widthPx - safeInset * 2.0).coerceAtLeast(1.0)
        val progress = ((positionPx - safeInset) / usableWidth).coerceIn(0.0, 1.0)
        return lower + (upper - lower) * progress
    }

    fun minimumViewportSpan(
        durationSeconds: Double,
        lowerSeconds: Double,
        upperSeconds: Double,
        minimumSelectionSeconds: Double,
        maximumZoomFactor: Double = 12.0,
        minimumWindowSeconds: Double = 2.0,
        handleMarginSeconds: Double = 0.35,
        handleMarginRatio: Double = 0.16
    ): Double {
        val duration = durationSeconds.coerceAtLeast(0.01)
        val lower = lowerSeconds.coerceIn(0.0, duration)
        val upper = upperSeconds.coerceIn(lower, duration)
        val selectionSpan = max(minimumSelectionSeconds, upper - lower)
        val handleMargin = max(handleMarginSeconds, selectionSpan * handleMarginRatio)
        return min(
            duration,
            max(
                max(minimumWindowSeconds, duration / maximumZoomFactor),
                selectionSpan + handleMargin * 2.0
            )
        )
    }

    fun maximumZoomFactor(
        durationSeconds: Double,
        lowerSeconds: Double,
        upperSeconds: Double,
        minimumSelectionSeconds: Double
    ): Double {
        val duration = durationSeconds.coerceAtLeast(0.01)
        return (duration / minimumViewportSpan(
            durationSeconds = duration,
            lowerSeconds = lowerSeconds,
            upperSeconds = upperSeconds,
            minimumSelectionSeconds = minimumSelectionSeconds
        )).coerceAtLeast(1.0)
    }

    fun viewportForSelectedRange(
        durationSeconds: Double,
        lowerSeconds: Double,
        upperSeconds: Double,
        requestedZoomFactor: Double,
        minimumSelectionSeconds: Double
    ): TimelineViewport {
        val duration = durationSeconds.coerceAtLeast(0.01)
        val lower = lowerSeconds.coerceIn(0.0, duration)
        val upper = upperSeconds.coerceIn(lower, duration)
        val minimumSpan = minimumViewportSpan(
            durationSeconds = duration,
            lowerSeconds = lower,
            upperSeconds = upper,
            minimumSelectionSeconds = minimumSelectionSeconds
        )
        val maximumZoom = (duration / minimumSpan).coerceAtLeast(1.0)
        val zoom = requestedZoomFactor.coerceIn(1.0, maximumZoom)
        val span = (duration / zoom).coerceIn(minimumSpan, duration)
        val center = (lower + upper) * 0.5
        val start = (center - span * 0.5).coerceIn(0.0, max(0.0, duration - span))
        return TimelineViewport(zoomFactor = duration / span, startSeconds = start, spanSeconds = span)
    }
    fun viewportPreservingZoom(
        durationSeconds: Double,
        lowerSeconds: Double,
        upperSeconds: Double,
        currentSpanSeconds: Double,
        minimumSelectionSeconds: Double
    ): TimelineViewport {
        val duration = durationSeconds.coerceAtLeast(0.01)
        val lower = lowerSeconds.coerceIn(0.0, duration)
        val upper = upperSeconds.coerceIn(lower, duration)
        val currentSpan = currentSpanSeconds.coerceIn(0.01, duration)
        val selectedSpan = max(minimumSelectionSeconds, upper - lower)

        if (selectedSpan > currentSpan + 0.0001) {
            return viewportForSelectedRange(
                durationSeconds = duration,
                lowerSeconds = lower,
                upperSeconds = upper,
                requestedZoomFactor = duration / currentSpan,
                minimumSelectionSeconds = minimumSelectionSeconds
            )
        }

        val center = (lower + upper) * 0.5
        val start = (center - currentSpan * 0.5)
            .coerceIn(0.0, max(0.0, duration - currentSpan))
        return TimelineViewport(
            zoomFactor = duration / currentSpan,
            startSeconds = start,
            spanSeconds = currentSpan
        )
    }

}
