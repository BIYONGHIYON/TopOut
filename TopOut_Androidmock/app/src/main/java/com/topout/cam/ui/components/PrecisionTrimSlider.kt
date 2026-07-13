package com.topout.cam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

private enum class TrimDragTarget { LOWER, UPPER, PLAYHEAD }

/** Direct port of ReviewVC.TrimRangeSlider, with stable pointer tracking across recompositions. */
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

    var size by remember { mutableStateOf(IntSize.Zero) }
    var trackingTarget by remember { mutableStateOf<TrimDragTarget?>(null) }
    var displayLower by remember { mutableDoubleStateOf(lowerValue) }
    var displayUpper by remember { mutableDoubleStateOf(upperValue) }
    var displayPlayhead by remember { mutableDoubleStateOf(playheadValue) }
    var bubbleValue by remember { mutableDoubleStateOf(0.0) }
    var fineMode by remember { mutableStateOf(false) }

    LaunchedEffect(lowerValue, upperValue, playheadValue, trackingTarget) {
        if (trackingTarget == null) {
            displayLower = lowerValue
            displayUpper = upperValue
            displayPlayhead = playheadValue
        }
    }

    // Keep the generous touch target while shrinking only the visible controls.
    val thumbRadiusPx = with(density) { 10.dp.toPx() }
    val hitRadiusPx = with(density) { 30.dp.toPx() }
    val horizontalInset = thumbRadiusPx
    val trackWidthPx = max(1f, size.width - horizontalInset * 2f)
    val trackWidthDp = trackWidthPx / density.density

    fun xFor(value: Double): Float {
        val ratio = if (durationSeconds <= 0.0) 0f else (value / durationSeconds).toFloat().coerceIn(0f, 1f)
        return horizontalInset + ratio * trackWidthPx
    }

    fun valueFor(x: Float): Double {
        val ratio = ((x - horizontalInset) / trackWidthPx).coerceIn(0f, 1f)
        return durationSeconds * ratio
    }

    fun clamp(value: Double, target: TrimDragTarget, lower: Double, upper: Double): Double = when (target) {
        TrimDragTarget.LOWER -> value.coerceIn(0.0, max(0.0, upper - TopOutConfig.MinimumTrimRangeSeconds))
        TrimDragTarget.UPPER -> value.coerceIn(min(durationSeconds, lower + TopOutConfig.MinimumTrimRangeSeconds), durationSeconds)
        TrimDragTarget.PLAYHEAD -> value.coerceIn(lower, upper)
    }

    fun sensitivity(speed: Float): Float {
        val secondsPerPoint = durationSeconds / max(1f, trackWidthDp)
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

    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(enabled, durationSeconds, size) {
                if (!enabled || durationSeconds <= 0.0 || size.width <= 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    var lower = latestLower.coerceIn(0.0, durationSeconds)
                    var upper = latestUpper.coerceIn(lower, durationSeconds)
                    var playhead = latestPlayhead.coerceIn(lower, upper)

                    val lowerDistance = abs(down.position.x - xFor(lower))
                    val upperDistance = abs(down.position.x - xFor(upper))
                    val playheadDistance = abs(down.position.x - xFor(playhead))
                    val nearAny = min(lowerDistance, min(upperDistance, playheadDistance)) <= hitRadiusPx
                    val target = when {
                        nearAny && lowerDistance <= upperDistance && lowerDistance <= playheadDistance -> TrimDragTarget.LOWER
                        nearAny && upperDistance <= playheadDistance -> TrimDragTarget.UPPER
                        nearAny -> TrimDragTarget.PLAYHEAD
                        down.position.x < xFor(lower) -> TrimDragTarget.LOWER
                        down.position.x > xFor(upper) -> TrimDragTarget.UPPER
                        else -> TrimDragTarget.PLAYHEAD
                    }
                    trackingTarget = target
                    latestTrackingChanged(true)

                    var rawValue = if (nearAny) {
                        when (target) {
                            TrimDragTarget.LOWER -> lower
                            TrimDragTarget.UPPER -> upper
                            TrimDragTarget.PLAYHEAD -> playhead
                        }
                    } else {
                        clamp(valueFor(down.position.x), target, lower, upper)
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

                    if (!nearAny) publish(rawValue)
                    else bubbleValue = rawValue

                    var lastX = down.position.x
                    var lastTime = down.uptimeMillis
                    var smoothedSpeed = 0f
                    var slowDuration = 0.0
                    fineMode = false

                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                        if (change == null || !change.pressed) break
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

                        val secondsPerPoint = durationSeconds / max(1f, trackWidthDp)
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

                    trackingTarget = null
                    fineMode = false
                    latestTrackingChanged(false)
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val centerY = size.height * 0.64f
            val lowerX = xFor(displayLower)
            val upperX = xFor(displayUpper)
            val playX = xFor(displayPlayhead.coerceIn(displayLower, displayUpper))
            drawLine(
                color = Color(0xFFA6A6AA),
                start = Offset(horizontalInset, centerY),
                end = Offset(size.width - horizontalInset, centerY),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color(0xFF0A84FF),
                start = Offset(lowerX, centerY),
                end = Offset(upperX, centerY),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.White,
                start = Offset(playX, centerY - 12.dp.toPx()),
                end = Offset(playX, centerY + 12.dp.toPx()),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(Color.White, radius = thumbRadiusPx, center = Offset(lowerX, centerY))
            drawCircle(Color.White, radius = thumbRadiusPx, center = Offset(upperX, centerY))
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
                            (bubbleX - bubbleHalfPx).coerceIn(0, max(0, size.width - bubbleWidthPx)),
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
}
