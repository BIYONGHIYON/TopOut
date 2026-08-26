package com.topout.cam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.topout.cam.model.HoldMask
import com.topout.cam.model.TopOutConfig
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Transparent editor drawn directly over the live CameraX preview.
 *
 * The Ready-time bitmap is never shown. The camera lens/zoom is frozen after Ready, mat endpoints
 * remain in normalized preview coordinates, and every top-hold tap is resolved against a freshly
 * captured live frame by the ViewModel.
 */
@Composable
fun SelectionEditor(
    matLine: List<Offset>,
    holdMask: HoldMask?,
    onTapHold: (Offset) -> Unit,
    onMatPointChanged: (Int, Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val latestMatLine by rememberUpdatedState(matLine)
    val latestTap by rememberUpdatedState(onTapHold)
    val latestMatChanged by rememberUpdatedState(onMatPointChanged)
    var viewport by remember { mutableStateOf(Size.Zero) }

    fun screenPoint(normalized: Offset): Offset = Offset(
        normalized.x.coerceIn(0f, 1f) * viewport.width,
        normalized.y.coerceIn(0f, 1f) * viewport.height
    )

    fun normalizedPoint(screen: Offset): Offset? {
        if (viewport.width <= 0f || viewport.height <= 0f) return null
        if (screen.x !in 0f..viewport.width || screen.y !in 0f..viewport.height) return null
        return Offset(
            (screen.x / viewport.width).coerceIn(0f, 1f),
            (screen.y / viewport.height).coerceIn(0f, 1f)
        )
    }

    val touchSlop = with(density) { 8f * this.density }
    val matHitRadius = with(density) { 32f * this.density }

    Box(
        modifier = modifier
            .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(viewport) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                    val initialMat = latestMatLine
                    val draggedMatIndex = initialMat.indices.minByOrNull { index ->
                        val delta = screenPoint(initialMat[index]) - down.position
                        sqrt(delta.x * delta.x + delta.y * delta.y)
                    }?.takeIf { index ->
                        val delta = screenPoint(initialMat[index]) - down.position
                        sqrt(delta.x * delta.x + delta.y * delta.y) <= matHitRadius
                    }

                    var moved = false
                    var maxPointerCount = 1
                    var accumulatedDistance = 0f
                    var lastPointer = down.position

                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Main)
                        val pressed = event.changes.filter { it.pressed }
                        maxPointerCount = max(maxPointerCount, pressed.size)
                        if (pressed.isEmpty()) break

                        // 선택 중 두 손가락 입력을 소비해 카메라 화각 변경을 막는다.
                        if (pressed.size >= 2) {
                            moved = true
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        val position = pressed.first().position
                        val delta = position - lastPointer
                        accumulatedDistance += sqrt(delta.x * delta.x + delta.y * delta.y)
                        if (accumulatedDistance > touchSlop) moved = true

                        if (draggedMatIndex != null) {
                            normalizedPoint(position)?.let { point ->
                                val fixedX = latestMatLine.getOrNull(draggedMatIndex)?.x
                                    ?: initialMat.getOrNull(draggedMatIndex)?.x
                                    ?: point.x
                                latestMatChanged(draggedMatIndex, Offset(fixedX, point.y))
                            }
                            event.changes.forEach { it.consume() }
                        }
                        lastPointer = position
                    }

                    if (!moved && maxPointerCount == 1 && draggedMatIndex == null) {
                        normalizedPoint(down.position)?.let(latestTap)
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (matLine.size >= 2) {
                val path = Path()
                matLine.forEachIndexed { index, point ->
                    val x = point.x.coerceIn(0f, 1f) * size.width
                    val y = point.y.coerceIn(0f, 1f) * size.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path,
                    Color.Black.copy(alpha = 0.55f),
                    style = Stroke(width = 5f * density.density, cap = StrokeCap.Round)
                )
                drawPath(
                    path,
                    Color(0xFF168BFF),
                    style = Stroke(width = 3f * density.density, cap = StrokeCap.Round)
                )
            }

            holdMask?.normalizedContour?.takeIf { it.size >= 2 }?.let { contour ->
                val path = Path()
                contour.forEachIndexed { index, point ->
                    val x = point.x.coerceIn(0f, 1f) * size.width
                    val y = point.y.coerceIn(0f, 1f) * size.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                val minimumSide = min(size.width, size.height)
                val redWidth = max(TopOutConfig.MinRedLineWidth, minimumSide * TopOutConfig.RedLineRatio)
                val whiteWidth = max(TopOutConfig.MinWhiteLineWidth, minimumSide * TopOutConfig.WhiteLineRatio)
                drawPath(path, Color.Red, style = Stroke(width = redWidth, cap = StrokeCap.Round))
                drawPath(path, Color.White, style = Stroke(width = whiteWidth, cap = StrokeCap.Round))
            }

            matLine.forEach { point ->
                val center = Offset(
                    point.x.coerceIn(0f, 1f) * size.width,
                    point.y.coerceIn(0f, 1f) * size.height
                )
                drawCircle(Color.White, radius = 10f * density.density, center = center)
                drawCircle(Color(0xFF168BFF), radius = 8f * density.density, center = center)
            }
        }
    }
}
