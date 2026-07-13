package com.topout.cam.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.topout.cam.model.HoldMask
import com.topout.cam.model.TopOutConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** UIScrollView-backed Zoom.swift behaviour reproduced with one coordinate space. */
@Composable
fun SelectionEditor(
    bitmap: Bitmap,
    matLine: List<Offset>,
    holdMask: HoldMask?,
    onTapHold: (Offset, Float) -> Unit,
    onMatPointChanged: (Int, Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val latestMatLine by rememberUpdatedState(matLine)
    val latestTap by rememberUpdatedState(onTapHold)
    val latestMatChanged by rememberUpdatedState(onMatPointChanged)
    val imageBitmap: ImageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    var viewport by remember { mutableStateOf(Size.Zero) }
    var zoomScale by remember(bitmap) { mutableFloatStateOf(1f) }
    var translation by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val animationScope = rememberCoroutineScope()
    var settleZoomJob by remember(bitmap) { mutableStateOf<Job?>(null) }

    fun baseImageRect(): Rect {
        if (viewport.width <= 0f || viewport.height <= 0f || bitmap.width <= 0 || bitmap.height <= 0) {
            return Rect.Zero
        }
        val imageAspect = bitmap.width.toFloat() / bitmap.height
        val viewportAspect = viewport.width / viewport.height
        return if (viewportAspect > imageAspect) {
            val height = viewport.height
            val width = height * imageAspect
            Rect((viewport.width - width) / 2f, 0f, (viewport.width + width) / 2f, height)
        } else {
            val width = viewport.width
            val height = width / imageAspect
            Rect(0f, (viewport.height - height) / 2f, width, (viewport.height + height) / 2f)
        }
    }

    fun viewportCenter() = Offset(viewport.width / 2f, viewport.height / 2f)

    fun clampedTranslation(candidate: Offset, newScale: Float): Offset {
        val rect = baseImageRect()
        val maxX = max(0f, (rect.width * newScale - viewport.width) / 2f)
        val maxY = max(0f, (rect.height * newScale - viewport.height) / 2f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    fun screenPoint(normalized: Offset): Offset {
        val rect = baseImageRect()
        val base = Offset(rect.left + normalized.x * rect.width, rect.top + normalized.y * rect.height)
        val center = viewportCenter()
        return center + (base - center) * zoomScale + translation
    }

    fun normalizedPoint(screen: Offset): Offset? {
        val rect = baseImageRect()
        if (rect == Rect.Zero) return null
        val center = viewportCenter()
        val base = center + (screen - center - translation) / zoomScale
        if (base.x < rect.left || base.x > rect.right || base.y < rect.top || base.y > rect.bottom) return null
        return Offset(
            ((base.x - rect.left) / rect.width).coerceIn(0f, 1f),
            ((base.y - rect.top) / rect.height).coerceIn(0f, 1f)
        )
    }

    val touchSlop = with(density) { 8f * this.density }
    val matHitRadius = with(density) { 32f * this.density }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged {
                viewport = Size(it.width.toFloat(), it.height.toFloat())
                translation = clampedTranslation(translation, zoomScale)
            }
            .pointerInput(bitmap, viewport) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // A new touch takes control immediately and cancels any in-flight 1x settle animation.
                    settleZoomJob?.cancel()
                    settleZoomJob = null
                    val initialMat = latestMatLine
                    var draggedMatIndex: Int? = initialMat.indices.minByOrNull { index ->
                        val delta = screenPoint(initialMat[index]) - down.position
                        sqrt(delta.x * delta.x + delta.y * delta.y)
                    }?.takeIf { index ->
                        val delta = screenPoint(initialMat[index]) - down.position
                        sqrt(delta.x * delta.x + delta.y * delta.y) <= matHitRadius
                    }
                    var moved = false
                    var maxPointerCount = 1
                    var accumulatedDistance = 0f
                    var lastCentroid = down.position

                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Main)
                        val pressed = event.changes.filter { it.pressed }
                        maxPointerCount = max(maxPointerCount, pressed.size)
                        if (pressed.isEmpty()) break

                        val activeMatIndex = draggedMatIndex
                        if (activeMatIndex != null && pressed.size == 1) {
                            val position = pressed.first().position
                            val delta = position - lastCentroid
                            accumulatedDistance += sqrt((delta.x * delta.x + delta.y * delta.y).toDouble()).toFloat()
                            if (accumulatedDistance > touchSlop) moved = true
                            normalizedPoint(position)?.let { point ->
                                val fixedX = latestMatLine.getOrNull(activeMatIndex)?.x
                                    ?: initialMat.getOrNull(activeMatIndex)?.x
                                    ?: point.x
                                // MatLineView.swift keeps the auto-detected x coordinate and allows
                                // vertical correction only.
                                latestMatChanged(activeMatIndex, Offset(fixedX, point.y.coerceIn(0f, 1f)))
                            }
                            lastCentroid = position
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        if (pressed.size > 1) draggedMatIndex = null
                        val currentCentroid = event.calculateCentroid(useCurrent = true)
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val previousCentroid = currentCentroid - panChange
                        val panDistance = sqrt(panChange.x * panChange.x + panChange.y * panChange.y)
                        accumulatedDistance += panDistance + abs(zoomChange - 1f) * 100f
                        if (accumulatedDistance > touchSlop || pressed.size > 1) moved = true

                        if (moved) {
                            val oldScale = zoomScale
                            val requestedScale = oldScale * zoomChange
                            // Match UIScrollView's rubber-band feel: allow only a small visual
                            // undershoot below 1x while the fingers are down. The editor always
                            // springs back to its legal 1x minimum when the gesture ends.
                            val newScale = if (requestedScale < 1f) {
                                (1f - (1f - requestedScale) * 0.22f).coerceIn(0.90f, 1f)
                            } else {
                                requestedScale.coerceAtMost(TopOutConfig.SelectionMaxZoom)
                            }
                            val center = viewportCenter()
                            // Resolve the image point under the PREVIOUS centroid, then keep that same
                            // point under the CURRENT centroid. Using current + pan double-counts panning.
                            val contentUnderPreviousCentroid =
                                center + (previousCentroid - center - translation) / oldScale
                            val anchored = currentCentroid - center -
                                (contentUnderPreviousCentroid - center) * newScale
                            translation = clampedTranslation(anchored, newScale)
                            zoomScale = newScale
                            event.changes.forEach { it.consume() }
                        }
                        lastCentroid = currentCentroid
                    }

                    if (!moved && maxPointerCount == 1 && draggedMatIndex == null) {
                        normalizedPoint(down.position)?.let { latestTap(it, zoomScale) }
                    }

                    if (zoomScale < 1f) {
                        val settleFrom = zoomScale
                        // awaitEachGesture uses a restricted suspension scope, so the spring animation
                        // must run in the composable's regular coroutine scope.
                        settleZoomJob?.cancel()
                        settleZoomJob = animationScope.launch {
                            animate(
                                initialValue = settleFrom,
                                targetValue = 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            ) { value, _ ->
                                zoomScale = value
                                translation = clampedTranslation(translation, value)
                            }
                            zoomScale = 1f
                            translation = Offset.Zero
                        }
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val rect = baseImageRect()
            if (rect == Rect.Zero) return@Canvas
            val center = viewportCenter()
            withTransform({
                translate(translation.x, translation.y)
                scale(zoomScale, zoomScale, pivot = center)
            }) {
                drawImage(
                    image = imageBitmap,
                    dstOffset = androidx.compose.ui.unit.IntOffset(rect.left.toInt(), rect.top.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(rect.width.toInt(), rect.height.toInt())
                )

                if (matLine.size >= 2) {
                    val path = Path()
                    matLine.forEachIndexed { index, point ->
                        val x = rect.left + point.x * rect.width
                        val y = rect.top + point.y * rect.height
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path,
                        Color.Black.copy(alpha = 0.55f),
                        style = Stroke(width = 5f / zoomScale * density.density, cap = StrokeCap.Round)
                    )
                    drawPath(
                        path,
                        Color(0xFF168BFF),
                        style = Stroke(width = 3f / zoomScale * density.density, cap = StrokeCap.Round)
                    )
                }

                holdMask?.normalizedContour?.takeIf { it.size >= 2 }?.let { contour ->
                    val path = Path()
                    contour.forEachIndexed { index, point ->
                        val x = rect.left + point.x * rect.width
                        val y = rect.top + point.y * rect.height
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    // iOS renders the outline into the source image before UIScrollView zooming.
                    // Convert those source-pixel widths into this fitted image rect and let the
                    // surrounding zoom transform scale them naturally.
                    val sourceToViewport = min(
                        rect.width / max(1f, imageBitmap.width.toFloat()),
                        rect.height / max(1f, imageBitmap.height.toFloat())
                    )
                    val sourceMinSide = min(imageBitmap.width, imageBitmap.height).toFloat()
                    val redWidth = max(
                        TopOutConfig.MinRedLineWidth,
                        sourceMinSide * TopOutConfig.RedLineRatio
                    ) * sourceToViewport
                    val whiteWidth = max(
                        TopOutConfig.MinWhiteLineWidth,
                        sourceMinSide * TopOutConfig.WhiteLineRatio
                    ) * sourceToViewport
                    drawPath(path, Color.Red, style = Stroke(width = redWidth, cap = StrokeCap.Round))
                    drawPath(path, Color.White, style = Stroke(width = whiteWidth, cap = StrokeCap.Round))
                }
            }

            matLine.forEach { point ->
                val screen = screenPoint(point)
                drawCircle(Color.White, radius = 10f * density.density, center = screen)
                drawCircle(Color(0xFF168BFF), radius = 8f * density.density, center = screen)
            }
        }
    }
}
