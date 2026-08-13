package com.topout.cam.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** A continuous slider whose entire track supports immediate tap-and-drag input. */
@Composable
internal fun TrackTappableZoomSlider(
    value: Double,
    maximumValue: Double,
    enabled: Boolean,
    onValueChange: (Double) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val safeMaximum = maximumValue.coerceAtLeast(1.0001)
    val latestMaximum by rememberUpdatedState(safeMaximum)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

    Box(
        modifier = modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial
                )
                val trackInsetPx = 10.dp.toPx().toDouble()

                fun updateFromPosition(x: Float) {
                    latestOnValueChange(
                        TimelineZoomMath.valueForTrackPosition(
                            positionPx = x.toDouble(),
                            widthPx = size.width.toDouble(),
                            minimumValue = 1.0,
                            maximumValue = latestMaximum,
                            horizontalInsetPx = trackInsetPx
                        )
                    )
                }

                try {
                    updateFromPosition(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        updateFromPosition(change.position.x)
                        change.consume()
                    }
                } finally {
                    latestOnValueChangeFinished()
                }
            }
        }
    ) {
        Slider(
            value = value.toFloat().coerceIn(1f, safeMaximum.toFloat()),
            onValueChange = { latestOnValueChange(it.toDouble()) },
            onValueChangeFinished = latestOnValueChangeFinished,
            enabled = enabled,
            valueRange = 1f..safeMaximum.toFloat(),
            modifier = Modifier.fillMaxSize()
        )
    }
}
