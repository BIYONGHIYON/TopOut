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
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.sqrt

/**
 * Transparent editor drawn directly over the live CameraX preview, before and during recording.
 *
 * Mat endpoints remain in normalized preview coordinates. Touches are shared with the preview
 * underneath so two-finger pinch zoom keeps working while the boundary is editable. 탑홀드는 결과
 * 화면에서만 고른다.
 */
@Composable
fun SelectionEditor(
    matLine: List<Offset>,
    onMatPointChanged: (Int, Offset) -> Unit,
    modifier: Modifier = Modifier,
    onMatDragChanged: (Boolean) -> Unit = {}
) {
    val density = LocalDensity.current
    val latestMatLine by rememberUpdatedState(matLine)
    val latestMatChanged by rememberUpdatedState(onMatPointChanged)
    val latestMatDragChanged by rememberUpdatedState(onMatDragChanged)
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

    val matHitRadius = with(density) { 32f * this.density }

    Box(
        modifier = modifier
            .then(SharePointerInputWithSiblingsElement)
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

                    if (draggedMatIndex == null) return@awaitEachGesture
                    latestMatDragChanged(true)
                    try {
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            // 두 손가락 입력은 아래 미리보기의 핀치 줌으로 넘긴다.
                            if (pressed.size >= 2) continue

                            normalizedPoint(pressed.first().position)?.let { point ->
                                val fixedX = latestMatLine.getOrNull(draggedMatIndex)?.x
                                    ?: initialMat.getOrNull(draggedMatIndex)?.x
                                    ?: point.x
                                latestMatChanged(draggedMatIndex, Offset(fixedX, point.y))
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } finally {
                        // 제스처가 취소돼도 드래그 종료를 알려, 녹화 중 고친 선이 분석에 반영되게 한다.
                        latestMatDragChanged(false)
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

/** 선택 레이어가 터치를 받아도 아래 카메라 미리보기가 같은 터치로 핀치 줌을 처리하게 한다. */
private object SharePointerInputWithSiblingsElement :
    ModifierNodeElement<SharePointerInputWithSiblingsNode>() {
    override fun create() = SharePointerInputWithSiblingsNode()
    override fun update(node: SharePointerInputWithSiblingsNode) = Unit
    override fun hashCode(): Int = javaClass.hashCode()
    override fun equals(other: Any?): Boolean = other === this
}

private class SharePointerInputWithSiblingsNode : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = Unit
    override fun onCancelPointerInput() = Unit
    override fun sharePointerInputWithSiblings(): Boolean = true
}
