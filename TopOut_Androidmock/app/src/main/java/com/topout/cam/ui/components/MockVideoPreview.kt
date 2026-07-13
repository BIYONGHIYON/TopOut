package com.topout.cam.ui.components

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** Preview surface for the test-only looping MOV input. */
@Composable
fun MockVideoPreview(
    modifier: Modifier = Modifier,
    onPreviewReady: (PlayerView) -> Unit,
    onPreviewDisposed: (() -> Unit)? = null,
    previewViewChanged: (PlayerView) -> Unit = {}
) {
    val latestOnPreviewReady = rememberUpdatedState(onPreviewReady)
    val latestPreviewViewChanged = rememberUpdatedState(previewViewChanged)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(Color.BLACK)
                keepScreenOn = true
                latestPreviewViewChanged.value(this)
                latestOnPreviewReady.value(this)
            }
        },
        update = {
            latestPreviewViewChanged.value(it)
        }
    )

    DisposableEffect(Unit) {
        onDispose { onPreviewDisposed?.invoke() }
    }
}
