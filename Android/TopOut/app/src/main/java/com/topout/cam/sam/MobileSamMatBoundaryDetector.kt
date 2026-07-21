package com.topout.cam.sam

import androidx.compose.ui.geometry.Offset
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Converts automatic MobileSAM masks into the editable TopOut mat line.
 *
 * The detector assumes only that the mat normally contains the centre prompt at 80%, 85%, or 90%
 * of the visible camera frame. It rejects boundaries above 50%, excessively tilted lines, narrow
 * objects, and masks that do not continue below their proposed upper edge.
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
        val leftY: Float,
        val rightY: Float,
        val centerY: Float,
        val slope: Float,
        val score: Float,
        val binCoverage: Float,
        val horizontalSpan: Float
    )

    private data class FitPoint(val x: Float, val y: Float, val weight: Float)

    private data class Component(
        val indices: IntArray,
        val map: BooleanArray,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int
    )

    private const val MIN_BOUNDARY_Y = 0.50f
    private const val MAX_BOUNDARY_Y = 1.00f
    private const val MAX_ENDPOINT_DELTA = 0.14f
    private const val LEFT_X = 0.06f
    private const val RIGHT_X = 0.94f
    private const val BIN_COUNT = 24

    val defaultLine: List<Offset>
        get() = listOf(Offset(LEFT_X, 0.75f), Offset(RIGHT_X, 0.75f))

    /** Returns a strong candidate from one prompt level, or a medium candidate for consensus. */
    fun evaluateStage(result: PromptResult, strict: Boolean): StageEvaluation {
        val candidates = result.masks.mapNotNull { makeCandidate(it, result.promptY) }
        val combined = combine(candidates)
        return when {
            combined == null -> StageEvaluation.None
            isStrong(combined, strict) -> StageEvaluation.Strong(line(combined))
            isMedium(combined) -> StageEvaluation.Medium(combined.toPublic())
            else -> StageEvaluation.None
        }
    }

    fun consensus(candidates: List<PublicCandidate>): List<Offset>? {
        if (candidates.size < 2) return null
        val internal = candidates.map { it.toInternal() }.sortedByDescending { it.score }
        for (anchor in internal) {
            val agreeing = internal.filter {
                abs(it.centerY - anchor.centerY) <= 0.04f && abs(it.slope - anchor.slope) <= 0.10f
            }
            if (agreeing.size < 2) continue
            val combined = combine(agreeing) ?: continue
            if (isMedium(combined)) return line(combined)
        }
        return null
    }

    sealed interface StageEvaluation {
        data class Strong(val line: List<Offset>) : StageEvaluation
        data class Medium(val candidate: PublicCandidate) : StageEvaluation
        data object None : StageEvaluation
    }

    data class PublicCandidate(
        val leftY: Float,
        val rightY: Float,
        val centerY: Float,
        val slope: Float,
        val score: Float,
        val binCoverage: Float,
        val horizontalSpan: Float
    )

    private fun Candidate.toPublic() = PublicCandidate(
        leftY, rightY, centerY, slope, score, binCoverage, horizontalSpan
    )

    private fun PublicCandidate.toInternal() = Candidate(
        leftY, rightY, centerY, slope, score, binCoverage, horizontalSpan
    )

    private fun isStrong(candidate: Candidate, strict: Boolean): Boolean {
        if (!geometryValid(candidate)) return false
        return if (strict) {
            candidate.score >= 4.8f && candidate.binCoverage >= 0.55f && candidate.horizontalSpan >= 0.55f
        } else {
            candidate.score >= 4.25f && candidate.binCoverage >= 0.42f && candidate.horizontalSpan >= 0.45f
        }
    }

    private fun isMedium(candidate: Candidate): Boolean = geometryValid(candidate) &&
        candidate.score >= 3.75f && candidate.binCoverage >= 0.34f && candidate.horizontalSpan >= 0.38f

    private fun geometryValid(candidate: Candidate): Boolean =
        candidate.leftY in MIN_BOUNDARY_Y..MAX_BOUNDARY_Y &&
            candidate.rightY in MIN_BOUNDARY_Y..MAX_BOUNDARY_Y &&
            candidate.centerY in MIN_BOUNDARY_Y..MAX_BOUNDARY_Y &&
            abs(candidate.rightY - candidate.leftY) <= MAX_ENDPOINT_DELTA

    private fun line(candidate: Candidate): List<Offset> {
        if (!geometryValid(candidate)) return defaultLine
        return listOf(
            Offset(LEFT_X, candidate.leftY.coerceIn(MIN_BOUNDARY_Y, MAX_BOUNDARY_Y)),
            Offset(RIGHT_X, candidate.rightY.coerceIn(MIN_BOUNDARY_Y, MAX_BOUNDARY_Y))
        )
    }

    private fun makeCandidate(mask: SamBinaryMask, promptY: Float): Candidate? {
        val width = mask.width
        val visibleWidth = mask.visibleWidth.coerceIn(1, width)
        val visibleHeight = mask.visibleHeight.coerceIn(1, mask.height)
        if (mask.values.size < width * mask.height) return null

        val seedX = (mask.positiveX * visibleWidth).toInt().coerceIn(0, visibleWidth - 1)
        val seedY = (promptY * visibleHeight).toInt().coerceIn(0, visibleHeight - 1)
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
        )
        if (component.indices.isEmpty()) return null

        val visibleArea = max(1f, (visibleWidth * visibleHeight).toFloat())
        val areaRatio = component.indices.size / visibleArea
        if (areaRatio !in 0.035f..0.82f) return null

        val horizontalSpan = (component.maxX - component.minX + 1).toFloat() / visibleWidth
        val verticalSpan = (component.maxY - component.minY + 1).toFloat() / visibleHeight
        if (horizontalSpan < 0.28f || verticalSpan < 0.08f) return null

        val boundaryPoints = extractBoundaryPoints(
            componentMap = component.map,
            width = width,
            visibleWidth = visibleWidth,
            visibleHeight = visibleHeight,
            binCount = BIN_COUNT
        )
        if (boundaryPoints.size < 7) return null

        val initial = weightedFit(boundaryPoints) ?: return null
        val residuals = boundaryPoints.map { abs(it.y - (initial.first * it.x + initial.second)) }
        val medianResidual = median(residuals)
        val tolerance = max(0.018f, medianResidual * 2.8f)
        val inliers = boundaryPoints.zip(residuals).filter { it.second <= tolerance }.map { it.first }
        if (inliers.size < 7) return null

        val fit = weightedFit(inliers) ?: initial
        val leftY = fit.first * LEFT_X + fit.second
        val rightY = fit.first * RIGHT_X + fit.second
        val centerY = (leftY + rightY) * 0.5f
        val provisional = Candidate(
            leftY = leftY,
            rightY = rightY,
            centerY = centerY,
            slope = fit.first,
            score = 0f,
            binCoverage = inliers.size.toFloat() / BIN_COUNT,
            horizontalSpan = horizontalSpan
        )
        if (!geometryValid(provisional)) return null

        // The prompt should lie on, or slightly below, the segmented mat region.
        if (centerY > promptY + 0.035f) return null

        val lowerOccupancy = lowerCentralOccupancy(
            componentMap = component.map,
            width = width,
            visibleWidth = visibleWidth,
            visibleHeight = visibleHeight,
            boundaryY = centerY
        )
        val bottomReach = (component.maxY + 1).toFloat() / visibleHeight
        val depthBelowBoundary = max(0f, bottomReach - centerY)

        var score = 0f
        score += 2.8f * horizontalSpan
        score += 2.4f * provisional.binCoverage
        score += 1.8f * lowerOccupancy
        score += 1.1f * min(depthBelowBoundary / 0.25f, 1f)
        score += 0.6f * min(areaRatio / 0.30f, 1f)
        score -= 1.4f * min(medianResidual / 0.05f, 1f)
        if (horizontalSpan < 0.45f) score -= 0.8f
        if (score < 3.15f) return null

        return provisional.copy(score = score)
    }

    private fun combine(candidates: List<Candidate>): Candidate? {
        val best = candidates.maxByOrNull { it.score } ?: return null
        val compatible = candidates.filter {
            abs(it.centerY - best.centerY) <= 0.045f &&
                abs(it.slope - best.slope) <= 0.14f &&
                it.score >= best.score - 1.15f
        }
        if (compatible.isEmpty()) return best

        val weighted = compatible.map { candidate ->
            val weight = max(0.1f, candidate.score) *
                max(0.25f, candidate.binCoverage) * max(0.25f, candidate.horizontalSpan)
            candidate to weight
        }
        val total = weighted.sumOf { it.second.toDouble() }.toFloat()
        if (total <= 0f) return best
        val leftY = weighted.sumOf { (it.first.leftY * it.second).toDouble() }.toFloat() / total
        val rightY = weighted.sumOf { (it.first.rightY * it.second).toDouble() }.toFloat() / total
        return Candidate(
            leftY = leftY,
            rightY = rightY,
            centerY = (leftY + rightY) * 0.5f,
            slope = (rightY - leftY) / (RIGHT_X - LEFT_X),
            score = best.score,
            binCoverage = best.binCoverage,
            horizontalSpan = best.horizontalSpan
        )
    }

    private fun extractBoundaryPoints(
        componentMap: BooleanArray,
        width: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        binCount: Int
    ): List<FitPoint> {
        val points = ArrayList<FitPoint>(binCount)
        for (bin in 0 until binCount) {
            val startX = max(0, (bin.toFloat() / binCount * visibleWidth).toInt())
            val endX = min(
                visibleWidth,
                max(startX + 1, ((bin + 1f) / binCount * visibleWidth).toInt())
            )
            val columnTops = ArrayList<Int>(endX - startX)
            for (x in startX until endX) {
                stableColumnTop(componentMap, width, x, visibleHeight)?.let(columnTops::add)
            }
            if (columnTops.size < max(1, (endX - startX) / 3)) continue
            columnTops.sort()
            val index = min(
                columnTops.lastIndex,
                max(0, ((columnTops.size - 1) * 0.28f).toInt())
            )
            val normalizedY = columnTops[index].toFloat() / visibleHeight
            if (normalizedY !in MIN_BOUNDARY_Y..MAX_BOUNDARY_Y) continue
            val centerX = (startX + endX) * 0.5f / visibleWidth
            val weight = columnTops.size.toFloat() / max(1, endX - startX)
            points += FitPoint(centerX, normalizedY, max(weight, 0.15f))
        }
        return points
    }

    private fun stableColumnTop(
        componentMap: BooleanArray,
        width: Int,
        x: Int,
        visibleHeight: Int
    ): Int? {
        val runWindow = 7
        val required = 4
        if (visibleHeight < runWindow) return null
        for (y in 0..(visibleHeight - runWindow)) {
            var count = 0
            for (offset in 0 until runWindow) {
                if (componentMap[(y + offset) * width + x]) count++
            }
            if (count >= required) return y
        }
        return null
    }

    private fun lowerCentralOccupancy(
        componentMap: BooleanArray,
        width: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        boundaryY: Float
    ): Float {
        val x0 = (visibleWidth * 0.34f).toInt().coerceIn(0, visibleWidth - 1)
        val x1 = (visibleWidth * 0.66f).toInt().coerceIn(x0 + 1, visibleWidth)
        val y0Ratio = (boundaryY + 0.025f).coerceIn(0.55f, 0.94f)
        val y1Ratio = max(y0Ratio + 0.10f, boundaryY + 0.16f).coerceAtMost(0.99f)
        val y0 = (visibleHeight * y0Ratio).toInt().coerceIn(0, visibleHeight - 1)
        val y1 = (visibleHeight * y1Ratio).toInt().coerceIn(y0 + 1, visibleHeight)
        var positive = 0
        var total = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                total++
                if (componentMap[y * width + x]) positive++
            }
        }
        return if (total > 0) positive.toFloat() / total else 0f
    }

    private fun nearestPositive(
        source: BooleanArray,
        width: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        originX: Int,
        originY: Int,
        maxRadius: Int
    ): Int? {
        val origin = originY * width + originX
        if (source.getOrElse(origin) { false }) return origin
        for (radius in 1..maxRadius) {
            val minX = max(0, originX - radius)
            val maxX = min(visibleWidth - 1, originX + radius)
            val minY = max(0, originY - radius)
            val maxY = min(visibleHeight - 1, originY + radius)
            var bestIndex: Int? = null
            var bestDistance = Int.MAX_VALUE
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    if (x != minX && x != maxX && y != minY && y != maxY) continue
                    val index = y * width + x
                    if (!source.getOrElse(index) { false }) continue
                    val distance = abs(x - originX) + abs(y - originY)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        bestIndex = index
                    }
                }
            }
            if (bestIndex != null) return bestIndex
        }
        return null
    }

    private fun connectedComponent(
        source: BooleanArray,
        width: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        seed: Int
    ): Component {
        val visited = BooleanArray(source.size)
        val componentMap = BooleanArray(source.size)
        val indices = IntArrayBuilder()
        val queue = ArrayDeque<Int>()
        visited[seed] = true
        queue.add(seed)
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            indices.add(current)
            componentMap[current] = true
            val x = current % width
            val y = current / width
            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until visibleWidth || ny !in 0 until visibleHeight) continue
                    val next = ny * width + nx
                    if (!visited[next] && source[next]) {
                        visited[next] = true
                        queue.add(next)
                    }
                }
            }
        }
        val result = indices.toArray()
        return if (result.isEmpty()) {
            Component(result, componentMap, 0, -1, 0, -1)
        } else {
            Component(result, componentMap, minX, maxX, minY, maxY)
        }
    }

    private fun weightedFit(points: List<FitPoint>): Pair<Float, Float>? {
        if (points.size < 2) return null
        var sw = 0f
        var sx = 0f
        var sy = 0f
        var sxx = 0f
        var sxy = 0f
        for (point in points) {
            sw += point.weight
            sx += point.weight * point.x
            sy += point.weight * point.y
            sxx += point.weight * point.x * point.x
            sxy += point.weight * point.x * point.y
        }
        val denominator = sw * sxx - sx * sx
        if (sw <= 0f || abs(denominator) <= 0.000001f) return null
        val slope = (sw * sxy - sx * sy) / denominator
        val intercept = (sy - slope * sx) / sw
        return slope to intercept
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) * 0.5f else sorted[middle]
    }

    private class IntArrayBuilder(initialCapacity: Int = 512) {
        private var storage = IntArray(initialCapacity)
        private var size = 0
        fun add(value: Int) {
            if (size == storage.size) storage = storage.copyOf(storage.size * 2)
            storage[size++] = value
        }
        fun toArray(): IntArray = storage.copyOf(size)
    }
}
