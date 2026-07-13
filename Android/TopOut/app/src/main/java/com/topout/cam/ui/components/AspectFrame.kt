package com.topout.cam.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier

/** Centers the active recording frame like RecordingAspectRatio.displayRect(in:) on iOS. */
@Composable
fun AspectFrame(
    widthOverHeight: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val availableRatio = constraints.maxWidth.toFloat() / constraints.maxHeight.coerceAtLeast(1)
        val frameModifier = if (availableRatio > widthOverHeight) {
            Modifier.fillMaxHeight().aspectRatio(widthOverHeight, matchHeightConstraintsFirst = true)
        } else {
            Modifier.fillMaxWidth().aspectRatio(widthOverHeight)
        }
        Box(frameModifier.clipToBounds(), contentAlignment = Alignment.Center) { content() }
    }
}
