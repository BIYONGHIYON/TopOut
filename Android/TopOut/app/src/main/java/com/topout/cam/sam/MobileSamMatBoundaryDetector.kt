package com.topout.cam.sam

import androidx.compose.ui.geometry.Offset
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max

/**
 * Converts MobileSAM mat candidates into the editable TopOut mat line.
 *
 * The automatic rule is intentionally small:
 * 1. MobileSAM is prompted at centre y=70%, 80%, 90%, and 95%.
 * 2. Keep the prompt-connected mask only when it spans at least 40% of the image width and its
 *    highest pixel stays in the lower half of the image.
 * 3. Reject a candidate when its fitted upper boundary tilts more than 15 degrees.
 * 4. Pick the valid mask whose upper edge is highest on screen.
 * 5. Convert that upper edge to the editable two-point line. The shared ViewModel then applies the
 *    detected boundary directly in the original captured-image coordinate system.
 *
 * No straightness, area, consensus, or multi-prompt scoring rule is used here.
 */
internal object MobileSamMatBoundaryDetector {
    data class PromptResult(
        val promptY: Float,
        val masks: List<SamBinaryMask>
    )

    data class SamBinaryMask(
        val width: Int,
        val height: Int,
        val visibleWidth: Int,
        val visibleHeight: Int,
        val positiveX: Float,
        val values: BooleanArray
    )

    private data class Candidate(
        val topY: Float,
        val horizontalSpan: Float,
        val line: List<Offset>
    )

    private data class Component(
        val topByX: IntArray,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int
    )

    private data class BoundaryPoint(val x: Float, val y: Float)

    private const val MIN_HORIZONTAL_SPAN = 0.40f
    private const val MIN_TOP_Y = 0.50f
    private const val MAX_BOUNDARY_ANGLE_DEGREES = 15f
    private const val LEFT_X = 0.06f
    private const val RIGHT_X = 0.94f

    val defaultLine: List<Offset>
        get() = listOf(Offset(LEFT_X, 0.75f), Offset(RIGHT_X, 0.75f))

    /** Convenience overload for tests and already-materialized callers. */
    fun select(results: Iterable<PromptResult>): List<Offset>? = select(results.asSequence())

    /** Returns the highest valid upper boundary while consuming prompt results one at a time. */
    fun select(results: Sequence<PromptResult>): List<Offset>? {
        val comparator = compareBy<Candidate> { it.topY }
            .thenByDescending { it.horizontalSpan }
        return results
            .asSequence()
            .flatMap { result ->
                result.masks.asSequence().mapNotNull { mask ->
                    makeCandidate(mask = mask, promptY = result.promptY)
                }
            }
            .minWithOrNull(comparator)
            ?.line
    }

    private fun makeCandidate(mask: SamBinaryMask, promptY: Float): Candidate? {
        val width = mask.width
        val height = mask.height
        val visibleWidth = mask.visibleWidth.coerceIn(1, width)
        val visibleHeight = mask.visibleHeight.coerceIn(1, height)
        if (width <= 0 || height <= 0 || mask.values.size < width * height) return null

        val seedX = (mask.positiveX.coerceIn(0f, 1f) * visibleWidth)
            .toInt()
            .coerceIn(0, visibleWidth - 1)
        val seedY = (promptY.coerceIn(0f, 1f) * visibleHeight)
            .toInt()
            .coerceIn(0, visibleHeight - 1)
        val seed = nearestPositive(
            source = mask.values,
            width = width,
            visibleWidth = visibleWidth,
            visibleHeight = visibleHeight,
            originX = seedX,
            originY = seedY,
            maxRadius = 12
        ) ?: return null

        val component = connectedComponent(
            source = mask.values,
            width = width,
            visibleWidth = visibleWidth,
            visibleHeight = visibleHeight,
            seed = seed
        ) ?: return null

        val horizontalSpan = (component.maxX - component.minX + 1).toFloat() / visibleWidth
        if (horizontalSpan < MIN_HORIZONTAL_SPAN) return null

        val topY = component.minY.toFloat() / visibleHeight
        if (topY < MIN_TOP_Y) return null

        val boundary = extractUpperBoundary(
            component = component,
            visibleWidth = visibleWidth,
            visibleHeight = visibleHeight
        )
        if (boundary.size < 2) return null

        val fit = fitLine(boundary) ?: return null
        val pixelSlope = fit.first * visibleHeight.toFloat() / visibleWidth.toFloat()
        val angleDegrees = Math.toDegrees(atan(abs(pixelSlope)).toDouble()).toFloat()
        if (angleDegrees > MAX_BOUNDARY_ANGLE_DEGREES) return null

        val leftY = (fit.first * LEFT_X + fit.second).coerceIn(MIN_TOP_Y, 1f)
        val rightY = (fit.first * RIGHT_X + fit.second).coerceIn(MIN_TOP_Y, 1f)

        return Candidate(
            topY = topY,
            horizontalSpan = horizontalSpan,
            line = listOf(Offset(LEFT_X, leftY), Offset(RIGHT_X, rightY))
        )
    }

    /** Finds a nearby positive pixel because decoder output can be a few low-resolution pixels off. */
    private fun nearestPositive(
        source: BooleanArray,
        width: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        originX: Int,
        originY: Int,
        maxRadius: Int
    ): Int? {
        fun positiveAt(x: Int, y: Int): Int? {
            if (x !in 0 until visibleWidth || y !in 0 until visibleHeight) return null
            val index = y * width + x
            return index.takeIf { source[it] }
        }

        positiveAt(originX, originY)?.let { return it }
        for (radius in 1..maxRadius) {
            val left = originX - radius
            val right = originX + radius
            val top = originY - radius
            val bottom = originY + radius

            for (x in left..right) {
                positiveAt(x, top)?.let { return it }
                positiveAt(x, bottom)?.let { return it }
            }
            for (y in (top + 1) until bottom) {
                positiveAt(left, y)?.let { return it }
                positiveAt(right, y)?.let { return it }
            }
        }
        return null
    }

    private fun connectedComponent(
        source: BooleanArray,
        width: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        seed: Int
    ): Component? {
        if (seed !in source.indices || !source[seed]) return null

        val visited = BooleanArray(width * visibleHeight)
        val topByX = IntArray(visibleWidth) { Int.MAX_VALUE }
        val queue = ArrayDeque<Int>()
        queue.add(seed)
        visited[seed] = true

        var minX = visibleWidth
        var maxX = -1
        var minY = visibleHeight
        var maxY = -1

        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % width
            val y = index / width
            if (x !in 0 until visibleWidth || y !in 0 until visibleHeight || !source[index]) continue

            topByX[x] = minOf(topByX[x], y)
            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)

            fun enqueue(nx: Int, ny: Int) {
                if (nx !in 0 until visibleWidth || ny !in 0 until visibleHeight) return
                val next = ny * width + nx
                if (!visited[next] && source[next]) {
                    visited[next] = true
                    queue.add(next)
                }
            }

            enqueue(x - 1, y)
            enqueue(x + 1, y)
            enqueue(x, y - 1)
            enqueue(x, y + 1)
        }

        return if (maxX >= minX && maxY >= minY) {
            Component(topByX, minX, maxX, minY, maxY)
        } else {
            null
        }
    }

    /** Uses the per-column top pixel collected during flood fill as the upper boundary. */
    private fun extractUpperBoundary(
        component: Component,
        visibleWidth: Int,
        visibleHeight: Int
    ): List<BoundaryPoint> {
        val points = ArrayList<BoundaryPoint>(component.maxX - component.minX + 1)
        for (x in component.minX..component.maxX) {
            val top = component.topByX[x]
            if (top != Int.MAX_VALUE) {
                points += BoundaryPoint(
                    x = x.toFloat() / max(1, visibleWidth - 1),
                    y = top.toFloat() / max(1, visibleHeight - 1)
                )
            }
        }
        return points
    }

    /** Least-squares line used for the slope validity test and the editable two-point boundary. */
    private fun fitLine(points: List<BoundaryPoint>): Pair<Float, Float>? {
        if (points.size < 2) return null
        val meanX = points.sumOf { it.x.toDouble() }.toFloat() / points.size
        val meanY = points.sumOf { it.y.toDouble() }.toFloat() / points.size
        var numerator = 0f
        var denominator = 0f
        for (point in points) {
            val dx = point.x - meanX
            numerator += dx * (point.y - meanY)
            denominator += dx * dx
        }
        val slope = if (denominator > 1e-8f) numerator / denominator else 0f
        return slope to (meanY - slope * meanX)
    }
}
