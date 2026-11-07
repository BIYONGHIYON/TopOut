package com.topout.cam.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale

@Composable
internal fun LensTransitionFrameOverlay(
    transitionId: Long?,
    frame: Bitmap?,
    scaleTarget: Float,
    fading: Boolean,
    onFadeFinished: (Long, Bitmap) -> Unit,
    modifier: Modifier = Modifier
) {
    if (transitionId == null || frame == null || frame.isRecycled) return

    val image = remember(transitionId, frame) { frame.asImageBitmap() }
    val alpha = remember(transitionId, frame) { Animatable(1f) }
    val liveScale = scaleTarget.takeIf { it.isFinite() && it >= 1f } ?: 1f
    LaunchedEffect(transitionId, frame, fading) {
        if (!fading) {
            alpha.snapTo(1f)
        } else {
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = LENS_TRANSITION_FADE_MILLIS)
            )
            onFadeFinished(transitionId, frame)
        }
    }

    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier.graphicsLayer {
            this.alpha = alpha.value
            // 정지 프레임은 애니메이션 지연 없이 현재 핀치 배율을 바로 따른다.
            scaleX = liveScale
            scaleY = liveScale
        }
    )
}

private const val LENS_TRANSITION_FADE_MILLIS = 150
