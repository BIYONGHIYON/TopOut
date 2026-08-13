package com.topout.cam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topout.cam.model.TopOutConfig
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

private enum class TrimDragTarget { LOWER, UPPER, PLAYHEAD }
private enum class TimelineGestureMode { PENDING, DRAGGING }

/**
 * Two-handle trim slider with playhead scrubbing and a selected-range zoom control.
 *
 * - The zoom slider always zooms toward the selected range's midpoint.
 * - The maximum zoom is limited dynamically so both trim handles remain visible with margin.
 * - Timeline tick spacing expands and gains subdivisions as the viewport is magnified.
 * - All emitted values remain absolute video times; zoom only changes the display mapping.
 */
@Composable
fun PrecisionTrimSlider(
    durationSeconds: Double,
    lowerValue: Double,
    upperValue: Double,
    playheadValue: Double,
    enabled: Boolean,
    onRangeChanged: (Double, Double, Boolean) -> Unit,
    onPlayheadChanged: (Double) -> Unit,
    onTrackingChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val latestLower by rememberUpdatedState(lowerValue)
    val latestUpper by rememberUpdatedState(upperValue)
    val latestPlayhead by rememberUpdatedState(playheadValue)
    val latestRangeChanged by rememberUpdatedState(onRangeChanged)
    val latestPlayheadChanged by rememberUpdatedState(onPlayheadChanged)
    val latestTrackingChanged by rememberUpdatedState(onTrackingChanged)

    val safeDuration = durationSeconds.coerceAtLeast(0.01)

    var timelineSize by remember { mutableStateOf(IntSize.Zero) }
    var trackingTarget by remember { mutableStateOf<TrimDragTarget?>(null) }
    var zoomTracking by remember { mutableStateOf(false) }
    var displayLower by remember { mutableDoubleStateOf(lowerValue) }
    var displayUpper by remember { mutableDoubleStateOf(upperValue) }
    var displayPlayhead by remember { mutableDoubleStateOf(playheadValue) }
    var bubbleValue by remember { mutableDoubleStateOf(0.0) }
    var fineMode by remember { mutableStateOf(false) }
    var viewportStart by remember(durationSeconds) { mutableDoubleStateOf(0.0) }
    var viewportSpan by remember(durationSeconds) { mutableDoubleStateOf(safeDuration) }

    fun maximumZoomForSelection(lower: Double, upper: Double): Double =
        TimelineZoomMath.maximumZoomFactor(
            durationSeconds = safeDuration,
            lowerSeconds = lower,
            upperSeconds = upper,
            minimumSelectionSeconds = TopOutConfig.MinimumTrimRangeSeconds
        )

    fun setViewportCenteredOnSelection(zoom: Double, lower: Double, upper: Double) {
        val viewport = TimelineZoomMath.viewportForSelectedRange(
            durationSeconds = safeDuration,
            lowerSeconds = lower,
            upperSeconds = upper,
            requestedZoomFactor = zoom,
            minimumSelectionSeconds = TopOutConfig.MinimumTrimRangeSeconds
        )
        viewportSpan = viewport.spanSeconds
        viewportStart = viewport.startSeconds
    }

    fun updateSelectedRangeZoom(zoom: Double) {
        if (!zoomTracking) {
            zoomTracking = true
            latestTrackingChanged(true)
        }
        setViewportCenteredOnSelection(zoom, latestLower, latestUpper)
    }

    fun finishSelectedRangeZoom() {
        if (!zoomTracking) return
        zoomTracking = false
        latestTrackingChanged(false)
    }

    fun recenterViewportPreservingZoom(lower: Double, upper: Double) {
        val viewport = TimelineZoomMath.viewportPreservingZoom(
            durationSeconds = safeDuration,
            lowerSeconds = lower,
            upperSeconds = upper,
            currentSpanSeconds = viewportSpan,
            minimumSelectionSeconds = TopOutConfig.MinimumTrimRangeSeconds
        )
        viewportSpan = viewport.spanSeconds
        viewportStart = viewport.startSeconds
    }

    val maximumZoomFactor = maximumZoomForSelection(lowerValue, upperValue)
    val zoomFactor = (safeDuration / viewportSpan.coerceAtLeast(0.01)).coerceAtLeast(1.0)
    val sliderMaximumZoomFactor = TimelineZoomMath.stableMaximumZoomFactor(
        allowedMaximum = maximumZoomFactor,
        currentZoom = zoomFactor
    )
    val viewportEnd = viewportStart + viewportSpan

    LaunchedEffect(lowerValue, upperValue, playheadValue, trackingTarget, zoomTracking) {
        if (trackingTarget == null && !zoomTracking) {
            displayLower = lowerValue
            displayUpper = upperValue
            displayPlayhead = playheadValue
            recenterViewportPreservingZoom(lowerValue, upperValue)
        }
    }

    LaunchedEffect(durationSeconds) {
        viewportStart = 0.0
        viewportSpan = safeDuration
    }

    // Keep the generous touch target while shrinking only the visible controls.
    val thumbRadiusPx = with(density) { 12.dp.toPx() }
    val hitRadiusPx = with(density) { 34.dp.toPx() }
    val dragStartPx = with(density) { 4.dp.toPx() }
    val horizontalInset = thumbRadiusPx
    val trackWidthPx = max(1f, timelineSize.width - horizontalInset * 2f)
    val trackWidthDp = trackWidthPx / density.density

    fun isVisible(value: Double): Boolean = value >= viewportStart && value <= viewportEnd

    fun xFor(value: Double): Float {
        val ratio = ((value - viewportStart) / viewportSpan).toFloat().coerceIn(0f, 1f)
        return horizontalInset + ratio * trackWidthPx
    }

    fun valueFor(x: Float): Double {
        val ratio = ((x - horizontalInset) / trackWidthPx).coerceIn(0f, 1f)
        return viewportStart + viewportSpan * ratio
    }

    fun clamp(value: Double, target: TrimDragTarget, lower: Double, upper: Double): Double = when (target) {
        TrimDragTarget.LOWER -> value.coerceIn(0.0, max(0.0, upper - TopOutConfig.MinimumTrimRangeSeconds))
        TrimDragTarget.UPPER -> value.coerceIn(min(safeDuration, lower + TopOutConfig.MinimumTrimRangeSeconds), safeDuration)
        TrimDragTarget.PLAYHEAD -> value.coerceIn(lower, upper)
    }

    fun sensitivity(speed: Float): Float {
        val secondsPerPoint = viewportSpan / max(1f, trackWidthDp)
        val minimumGain = if (secondsPerPoint <= 0.0) 0.03f else {
            (TopOutConfig.FineAdjustmentStep / secondsPerPoint).toFloat().coerceIn(0.008f, 0.08f)
        }
        fun interpolate(value: Float, low: Float, high: Float, lowGain: Float, highGain: Float): Float {
            val progress = ((value - low) / max(1f, high - low)).coerceIn(0f, 1f)
            return lowGain + (highGain - lowGain) * progress
        }
        return when {
            speed <= 18f -> minimumGain
            speed <= 35f -> interpolate(speed, 18f, 35f, minimumGain, 0.10f)
            speed <= 60f -> interpolate(speed, 35f, 60f, 0.10f, 0.45f)
            speed <= 110f -> interpolate(speed, 60f, 110f, 0.45f, 0.85f)
            speed <= 170f -> interpolate(speed, 110f, 170f, 0.85f, 1f)
            else -> 1f
        }
    }

    fun formatTimelineTime(seconds: Double): String {
        val totalTenths = (seconds.coerceAtLeast(0.0) * 10.0).roundToInt()
        val minutes = totalTenths / 600
        val wholeSeconds = (totalTenths / 10) % 60
        val tenths = totalTenths % 10
        return if (minutes > 0) "%d:%02d.%d".format(minutes, wholeSeconds, tenths)
        else "%d.%d초".format(wholeSeconds, tenths)
    }

    fun niceStep(raw: Double): Double {
        if (raw <= 0.0) return 1.0
        val magnitude = 10.0.pow(floor(log10(raw)))
        val normalized = raw / magnitude
        val rounded = when {
            normalized <= 1.0 -> 1.0
            normalized <= 2.0 -> 2.0
            normalized <= 5.0 -> 5.0
            else -> 10.0
        }
        return rounded * magnitude
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { timelineSize = it }
                .pointerInput(enabled, durationSeconds, timelineSize, viewportStart, viewportSpan) {
                    if (!enabled || safeDuration <= 0.0 || timelineSize.width <= 0) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                        var lower = latestLower.coerceIn(0.0, safeDuration)
                        var upper = latestUpper.coerceIn(lower, safeDuration)
                        var playhead = latestPlayhead.coerceIn(lower, upper)

                        val lowerVisible = isVisible(lower)
                        val upperVisible = isVisible(upper)
                        val playheadVisible = isVisible(playhead)
                        val lowerDistance = if (lowerVisible) abs(down.position.x - xFor(lower)) else Float.POSITIVE_INFINITY
                        val upperDistance = if (upperVisible) abs(down.position.x - xFor(upper)) else Float.POSITIVE_INFINITY
                        val playheadDistance = if (playheadVisible) abs(down.position.x - xFor(playhead)) else Float.POSITIVE_INFINITY
                        val nearestDistance = min(lowerDistance, min(upperDistance, playheadDistance))
                        val nearAny = nearestDistance <= hitRadiusPx
                        val tappedValue = valueFor(down.position.x)
                        val target = when {
                            nearAny && lowerDistance <= upperDistance && lowerDistance <= playheadDistance -> TrimDragTarget.LOWER
                            nearAny && upperDistance <= playheadDistance -> TrimDragTarget.UPPER
                            nearAny -> TrimDragTarget.PLAYHEAD
                            tappedValue < lower -> TrimDragTarget.LOWER
                            tappedValue > upper -> TrimDragTarget.UPPER
                            else -> TrimDragTarget.PLAYHEAD
                        }

                        var rawValue = if (nearAny) {
                            when (target) {
                                TrimDragTarget.LOWER -> lower
                                TrimDragTarget.UPPER -> upper
                                TrimDragTarget.PLAYHEAD -> playhead
                            }
                        } else {
                            clamp(tappedValue, target, lower, upper)
                        }

                        fun publish(value: Double) {
                            bubbleValue = value
                            when (target) {
                                TrimDragTarget.LOWER -> {
                                    lower = value
                                    playhead = lower
                                    displayLower = lower
                                    displayUpper = upper
                                    displayPlayhead = playhead
                                    latestRangeChanged(lower, upper, true)
                                }
                                TrimDragTarget.UPPER -> {
                                    upper = value
                                    playhead = upper
                                    displayLower = lower
                                    displayUpper = upper
                                    displayPlayhead = playhead
                                    latestRangeChanged(lower, upper, false)
                                }
                                TrimDragTarget.PLAYHEAD -> {
                                    playhead = value
                                    displayPlayhead = playhead
                                    latestPlayheadChanged(playhead)
                                }
                            }
                        }

                        var mode = TimelineGestureMode.PENDING
                        var trackingNotified = false
                        var lastX = down.position.x
                        var lastTime = down.uptimeMillis
                        var smoothedSpeed = 0f
                        var slowDuration = 0.0

                        fun beginTracking() {
                            if (!trackingNotified) {
                                latestTrackingChanged(true)
                                trackingNotified = true
                            }
                        }

                        fun beginDrag() {
                            beginTracking()
                            trackingTarget = target
                            mode = TimelineGestureMode.DRAGGING
                            fineMode = false
                            if (!nearAny) publish(rawValue) else bubbleValue = rawValue
                        }

                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                            if (change == null || !change.pressed) {
                                if (mode == TimelineGestureMode.PENDING) {
                                    beginTracking()
                                    trackingTarget = target
                                    publish(rawValue)
                                }
                                break
                            }

                            val totalDelta = change.position.x - down.position.x
                            if (mode == TimelineGestureMode.PENDING && abs(totalDelta) >= dragStartPx) {
                                beginDrag()
                            }

                            if (mode == TimelineGestureMode.DRAGGING) {
                                val now = change.uptimeMillis
                                val dt = max(1.0 / 240.0, (now - lastTime) / 1000.0)
                                val deltaPx = change.position.x - lastX
                                val speedPoints = abs(deltaPx / density.density) / dt.toFloat()
                                smoothedSpeed = if (smoothedSpeed == 0f) speedPoints
                                else smoothedSpeed * 0.62f + speedPoints * 0.38f
                                slowDuration = if (smoothedSpeed <= TopOutConfig.FineAdjustmentEnterSpeed) slowDuration + dt else 0.0
                                val nextFine = if (fineMode) {
                                    smoothedSpeed <= TopOutConfig.FineAdjustmentExitSpeed
                                } else {
                                    slowDuration >= TopOutConfig.FineAdjustmentActivationSeconds
                                }
                                if (nextFine && !fineMode) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                fineMode = nextFine

                                val secondsPerPoint = viewportSpan / max(1f, trackWidthDp)
                                rawValue = clamp(
                                    rawValue + (deltaPx / density.density) * secondsPerPoint * sensitivity(smoothedSpeed),
                                    target,
                                    lower,
                                    upper
                                )
                                val shown = if (fineMode) {
                                    round(rawValue / TopOutConfig.FineAdjustmentStep) * TopOutConfig.FineAdjustmentStep
                                } else rawValue
                                publish(clamp(shown, target, lower, upper))
                                lastX = change.position.x
                                lastTime = now
                                change.consume()
                            }
                        }

                        trackingTarget = null
                        fineMode = false
                        recenterViewportPreservingZoom(lower, upper)
                        if (trackingNotified) latestTrackingChanged(false)
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val centerY = size.height * 0.54f
                val baseMajorStep = niceStep(safeDuration / 8.0)
                val majorSpacingPx = (baseMajorStep / viewportSpan * trackWidthPx).toFloat()
                val subdivisions = when {
                    majorSpacingPx >= 180.dp.toPx() -> 10
                    majorSpacingPx >= 90.dp.toPx() -> 5
                    majorSpacingPx >= 48.dp.toPx() -> 2
                    else -> 1
                }
                val tickStep = baseMajorStep / subdivisions
                var tick = ceil(max(0.0, viewportStart) / tickStep) * tickStep
                val tickEnd = min(safeDuration, viewportEnd)
                var tickGuard = 0
                while (tick <= tickEnd + tickStep * 0.001 && tickGuard < 500) {
                    val majorIndex = tick / baseMajorStep
                    val isMajor = abs(majorIndex - round(majorIndex)) < 0.001
                    val height = if (isMajor) 7.dp.toPx() else 3.dp.toPx()
                    val x = xFor(tick)
                    drawLine(
                        color = Color.White.copy(alpha = if (isMajor) 0.32f else 0.16f),
                        start = Offset(x, centerY - height),
                        end = Offset(x, centerY + height),
                        strokeWidth = if (isMajor) 1.2.dp.toPx() else 0.8.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    tick += tickStep
                    tickGuard++
                }

                drawLine(
                    color = Color(0xFFA6A6AA),
                    start = Offset(horizontalInset, centerY),
                    end = Offset(size.width - horizontalInset, centerY),
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round
                )

                val selectedVisibleStart = max(displayLower, viewportStart)
                val selectedVisibleEnd = min(displayUpper, viewportEnd)
                if (selectedVisibleEnd >= selectedVisibleStart) {
                    drawLine(
                        color = Color(0xFF0A84FF),
                        start = Offset(xFor(selectedVisibleStart), centerY),
                        end = Offset(xFor(selectedVisibleEnd), centerY),
                        strokeWidth = 6.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                if (isVisible(displayPlayhead)) {
                    val playX = xFor(displayPlayhead)
                    drawLine(
                        color = Color.White,
                        start = Offset(playX, centerY - 12.dp.toPx()),
                        end = Offset(playX, centerY + 12.dp.toPx()),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                if (isVisible(displayLower)) {
                    drawCircle(Color.White, radius = thumbRadiusPx, center = Offset(xFor(displayLower), centerY))
                }
                if (isVisible(displayUpper)) {
                    drawCircle(Color.White, radius = thumbRadiusPx, center = Offset(xFor(displayUpper), centerY))
                }
            }

            if (zoomFactor > 1.01) {
                Text(
                    text = formatTimelineTime(viewportStart),
                    modifier = Modifier.align(Alignment.BottomStart),
                    color = Color.White.copy(alpha = 0.68f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    maxLines = 1
                )
                Text(
                    text = formatTimelineTime(viewportEnd),
                    modifier = Modifier.align(Alignment.BottomEnd),
                    color = Color.White.copy(alpha = 0.68f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    maxLines = 1
                )
            }

            if (trackingTarget != null) {
                val bubbleWidthPx = with(density) { 92.dp.roundToPx() }
                val bubbleHalfPx = bubbleWidthPx / 2
                val bubbleX = xFor(bubbleValue).roundToInt()
                Text(
                    text = "%.1f초".format(bubbleValue),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            IntOffset(
                                (bubbleX - bubbleHalfPx).coerceIn(0, max(0, timelineSize.width - bubbleWidthPx)),
                                0
                            )
                        }
                        .widthIn(min = 72.dp, max = 92.dp)
                        .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(9.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
        }

        // Keep the labels positioned relative to the compact zoom slider. The label row is
        // allocated above the slider and then lifted slightly inside that shared container, so
        // neither the title nor the current magnification can be covered by the slider thumb.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = (-6).dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .offset(y = (-3).dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "선택된 구간 확대",
                    color = Color.White.copy(alpha = if (enabled) 0.82f else 0.40f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "%.1f×".format(zoomFactor),
                    color = Color.White.copy(alpha = if (enabled) 0.68f else 0.34f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            val zoomSliderEnabled = enabled && sliderMaximumZoomFactor > 1.01
            val zoomSliderMaximum = sliderMaximumZoomFactor.coerceAtLeast(1.0001)
            TrackTappableZoomSlider(
                value = zoomFactor,
                maximumValue = zoomSliderMaximum,
                enabled = zoomSliderEnabled,
                onValueChange = ::updateSelectedRangeZoom,
                onValueChangeFinished = ::finishSelectedRangeZoom,
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .height(36.dp)
            )
        }
    }
}
