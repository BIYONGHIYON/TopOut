package com.topout.cam.detection

import com.topout.cam.model.TopOutConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds a fixed quarter-frame, pixel-square crop around the strongest immediate-frame motion.
 *
 * The frame is divided into roughly square grid cells. Cells with the highest changed-pixel ratios
 * form the target set. If that set fits in the crop, the crop is positioned to contain it entirely;
 * otherwise a sliding window chooses the crop that captures the greatest weighted cell score.
 */
internal object MotionSeedCropFinder {
    private var weightScratch = FloatArray(0)
    private var integralScratch = FloatArray(0)

    private data class CellScore(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val score: Float
    )

    @Synchronized
    fun find(
        width: Int,
        height: Int,
        currentLuma: ByteArray,
        previousLuma: ByteArray,
        gridColumns: Int = TopOutConfig.MotionSeedGridColumns,
        cropAreaRatio: Float = TopOutConfig.MotionSeedCropAreaRatio,
        maximumSelectedCells: Int = TopOutConfig.MotionSeedMaximumSelectedCells,
        relativeCellThreshold: Float = TopOutConfig.MotionSeedRelativeCellThreshold,
        minimumCellChangedRatio: Float = TopOutConfig.MotionSeedMinimumCellChangedRatio,
        pixelDifferenceThreshold: Int = TopOutConfig.MotionPixelDifference,
        activeTopByColumn: IntArray? = null,
        activeBottomExclusiveByColumn: IntArray? = null
    ): MoveNetCropRegion? {
        if (width <= 0 || height <= 0) return null
        val expected = width * height
        if (currentLuma.size < expected || previousLuma.size < expected) return null
        if (activeTopByColumn != null && activeTopByColumn.size < width) return null
        if (activeBottomExclusiveByColumn != null && activeBottomExclusiveByColumn.size < width) return null

        val columns = gridColumns.coerceIn(2, width)
        val rows = (columns * height.toFloat() / width)
            .roundToInt()
            .coerceIn(2, height)
        val cells = ArrayList<CellScore>(columns * rows)

        for (row in 0 until rows) {
            val top = row * height / rows
            val bottom = max(top + 1, (row + 1) * height / rows).coerceAtMost(height)
            for (column in 0 until columns) {
                val left = column * width / columns
                val right = max(left + 1, (column + 1) * width / columns).coerceAtMost(width)
                var changed = 0
                var total = 0
                for (y in top until bottom) {
                    val rowOffset = y * width
                    for (x in left until right) {
                        val activeTop = activeTopByColumn?.get(x)?.coerceIn(0, height) ?: 0
                        val activeBottom = activeBottomExclusiveByColumn
                            ?.get(x)
                            ?.coerceIn(activeTop, height)
                            ?: height
                        if (y < activeTop || y >= activeBottom) continue
                        val index = rowOffset + x
                        val current = currentLuma[index].toInt() and 0xFF
                        val previous = previousLuma[index].toInt() and 0xFF
                        if (abs(current - previous) >= pixelDifferenceThreshold) changed++
                        total++
                    }
                }
                val score = if (total > 0) changed.toFloat() / total else 0f
                cells += CellScore(left, top, right, bottom, score)
            }
        }

        val sorted = cells.sortedByDescending { it.score }
        val strongest = sorted.firstOrNull() ?: return null
        if (strongest.score < minimumCellChangedRatio.coerceIn(0f, 1f)) return null
        val cutoff = max(
            minimumCellChangedRatio.coerceIn(0f, 1f),
            strongest.score * relativeCellThreshold.coerceIn(0f, 1f)
        )
        val selected = sorted
            .filter { it.score >= cutoff }
            .take(maximumSelectedCells.coerceAtLeast(1))
            .ifEmpty { listOf(strongest) }

        // Keep the model crop square in source pixels while targeting one quarter of frame area.
        val desiredSide = sqrt(
            width.toFloat() * height.toFloat() * cropAreaRatio.coerceIn(0.05f, 1f)
        )
        val side = desiredSide
            .roundToInt()
            .coerceIn(1, min(width, height))
        val maxLeft = width - side
        val maxTop = height - side

        val selectedLeft = selected.minOf { it.left }
        val selectedTop = selected.minOf { it.top }
        val selectedRight = selected.maxOf { it.right }
        val selectedBottom = selected.maxOf { it.bottom }

        val cropLeft: Int
        val cropTop: Int
        if (selectedRight - selectedLeft <= side && selectedBottom - selectedTop <= side) {
            cropLeft = ((selectedLeft + selectedRight - side) / 2).coerceIn(0, maxLeft)
            cropTop = ((selectedTop + selectedBottom - side) / 2).coerceIn(0, maxTop)
        } else {
            val weightMap = ensureWeightScratch(expected)
            weightMap.fill(0f, 0, expected)
            for (cell in selected) {
                val weight = max(cell.score, 0.0001f)
                for (y in cell.top until cell.bottom) {
                    val offset = y * width
                    for (x in cell.left until cell.right) {
                        weightMap[offset + x] = weight
                    }
                }
            }
            val integralSize = (width + 1) * (height + 1)
            val integral = ensureIntegralScratch(integralSize)
            integral.fill(0f, 0, integralSize)
            for (y in 0 until height) {
                var rowSum = 0f
                for (x in 0 until width) {
                    rowSum += weightMap[y * width + x]
                    val index = (y + 1) * (width + 1) + (x + 1)
                    integral[index] = integral[y * (width + 1) + (x + 1)] + rowSum
                }
            }

            var totalWeight = 0f
            var weightedX = 0f
            var weightedY = 0f
            for (cell in selected) {
                val weight = max(cell.score, 0.0001f)
                totalWeight += weight
                weightedX += (cell.left + cell.right) * 0.5f * weight
                weightedY += (cell.top + cell.bottom) * 0.5f * weight
            }
            val weightedCenterX = weightedX / max(totalWeight, 0.0001f)
            val weightedCenterY = weightedY / max(totalWeight, 0.0001f)

            var bestLeft = 0
            var bestTop = 0
            var bestScore = Float.NEGATIVE_INFINITY
            var bestDistance = Float.POSITIVE_INFINITY
            for (top in 0..maxTop) {
                val bottom = top + side
                for (left in 0..maxLeft) {
                    val right = left + side
                    val score = rectangleSum(integral, width + 1, left, top, right, bottom)
                    val centerX = left + side * 0.5f
                    val centerY = top + side * 0.5f
                    val dx = centerX - weightedCenterX
                    val dy = centerY - weightedCenterY
                    val distance = dx * dx + dy * dy
                    if (score > bestScore || (score == bestScore && distance < bestDistance)) {
                        bestScore = score
                        bestDistance = distance
                        bestLeft = left
                        bestTop = top
                    }
                }
            }
            cropLeft = bestLeft
            cropTop = bestTop
        }

        return MoveNetCropRegion(
            xMin = cropLeft.toFloat() / width,
            yMin = cropTop.toFloat() / height,
            width = side.toFloat() / width,
            height = side.toFloat() / height
        )
    }


    private fun ensureWeightScratch(size: Int): FloatArray {
        if (weightScratch.size < size) weightScratch = FloatArray(size)
        return weightScratch
    }

    private fun ensureIntegralScratch(size: Int): FloatArray {
        if (integralScratch.size < size) integralScratch = FloatArray(size)
        return integralScratch
    }

    private fun rectangleSum(
        integral: FloatArray,
        stride: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Float {
        val bottomRight = integral[bottom * stride + right]
        val topRight = integral[top * stride + right]
        val bottomLeft = integral[bottom * stride + left]
        val topLeft = integral[top * stride + left]
        return bottomRight - topRight - bottomLeft + topLeft
    }
}
