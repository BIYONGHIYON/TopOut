package com.topout.cam.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.topout.cam.model.HoldMask
import com.topout.cam.model.TopOutConfig
import kotlin.math.max
import kotlin.math.min

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

@Composable
internal fun RecordingMatLineOverlay(matLine: List<Offset>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (matLine.size < 2) return@Canvas
        val path = Path()
        matLine.forEachIndexed { index, point ->
            val x = point.x.coerceIn(0f, 1f) * size.width
            val y = point.y.coerceIn(0f, 1f) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // 읽기 전용 경계선은 촬영을 방해하지 않도록 반투명하게 표시한다.
        drawPath(
            path,
            Color.Black.copy(alpha = 0.28f),
            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path,
            Color(0xFF168BFF).copy(alpha = 0.48f),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
internal fun HoldMaskOverlay(mask: HoldMask, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val contour = mask.normalizedContour.ifEmpty {
            listOf(
                Offset(mask.center.x - mask.radiusX, mask.center.y),
                Offset(mask.center.x, mask.center.y - mask.radiusY),
                Offset(mask.center.x + mask.radiusX, mask.center.y),
                Offset(mask.center.x, mask.center.y + mask.radiusY)
            )
        }
        if (contour.size < 2) return@Canvas

        val path = Path()
        contour.forEachIndexed { index, point ->
            val x = point.x.coerceIn(0f, 1f) * size.width
            val y = point.y.coerceIn(0f, 1f) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        // iOS와 같은 투명 내부, 굵은 빨간 선, 얇은 흰 선을 사용한다.
        val minimumSide = min(size.width, size.height)
        val redWidth = max(TopOutConfig.MinRedLineWidth, minimumSide * TopOutConfig.RedLineRatio)
        val whiteWidth = max(TopOutConfig.MinWhiteLineWidth, minimumSide * TopOutConfig.WhiteLineRatio)
        drawPath(
            path = path,
            color = Color.Red,
            style = Stroke(width = redWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path = path,
            color = Color.White,
            style = Stroke(width = whiteWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

private const val LENS_TRANSITION_FADE_MILLIS = 150
