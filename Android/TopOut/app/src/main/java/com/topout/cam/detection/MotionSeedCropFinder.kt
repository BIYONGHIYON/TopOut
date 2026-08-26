package com.topout.cam.detection

import com.topout.cam.model.TopOutConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Finds a pixel-square crop 1.5x the selected immediate-frame motion bounds.
 *
 * The frame is divided into roughly square grid cells. Cells with the highest changed-pixel ratios
 * form the target set. The crop keeps its pixel aspect ratio and may use padding beyond the shorter
 * frame axis when that is the only way to preserve the requested context.
 */
internal object MotionSeedCropFinder {
    fun find(
        width: Int,
        height: Int,
        currentLuma: ByteArray,
        previousLuma: ByteArray,
        gridColumns: Int = TopOutConfig.MotionSeedGridColumns,
        cropExpansionScale: Float = TopOutConfig.MotionSeedCropExpansionScale,
        maximumSelectedCells: Int = TopOutConfig.MotionSeedMaximumSelectedCells,
        relativeCellThreshold: Float = TopOutConfig.MotionSeedRelativeCellThreshold,
        minimumCellChangedRatio: Float = TopOutConfig.MotionSeedMinimumCellChangedRatio,
        pixelDifferenceThreshold: Int = TopOutConfig.MotionPixelDifference,
        minimumOverallChangedRatio: Float? = null,
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
        val cellScores = FloatArray(columns * rows)
        var overallChanged = 0
        var overallTotal = 0

        for (row in 0 until rows) {
            val top = row * height / rows
            val bottom = max(top + 1, (row + 1) * height / rows).coerceAtMost(height)
            for (column in 0 until columns) {
                val left = column * width / columns
                val right = max(left + 1, (column + 1) * width / columns).coerceAtMost(width)
                var changed = 0
                var total = 0
                for (x in left until right) {
                    val activeTop = activeTopByColumn?.get(x)?.coerceIn(0, height) ?: 0
                    val activeBottom = activeBottomExclusiveByColumn
                        ?.get(x)
                        ?.coerceIn(activeTop, height)
                        ?: height
                    val sampleTop = max(top, activeTop)
                    val sampleBottom = minOf(bottom, activeBottom)
                    for (y in sampleTop until sampleBottom) {
                        val index = y * width + x
                        val current = currentLuma[index].toInt() and 0xFF
                        val previous = previousLuma[index].toInt() and 0xFF
                        if (abs(current - previous) >= pixelDifferenceThreshold) changed++
                        total++
                    }
                }
                overallChanged += changed
                overallTotal += total
                cellScores[row * columns + column] =
                    if (total > 0) changed.toFloat() / total else 0f
            }
        }

        minimumOverallChangedRatio
            ?.takeIf { it.isFinite() }
            ?.coerceIn(0f, 1f)
            ?.let { minimum ->
                val ratio = if (overallTotal > 0) overallChanged.toFloat() / overallTotal else 0f
                if (ratio < minimum) return null
            }

        var strongestIndex = 0
        for (index in 1 until cellScores.size) {
            if (cellScores[index] > cellScores[strongestIndex]) strongestIndex = index
        }
        val strongestScore = cellScores[strongestIndex]
        val minimumScore = minimumCellChangedRatio.coerceIn(0f, 1f)
        if (strongestScore < minimumScore) return null
        val cutoff = max(
            minimumScore,
            strongestScore * relativeCellThreshold.coerceIn(0f, 1f)
        )
        val selectionLimit = maximumSelectedCells.coerceIn(1, cellScores.size)
        val selectedIndices = IntArray(selectionLimit)
        var selectedCount = 0
        for (index in cellScores.indices) {
            val score = cellScores[index]
            if (score < cutoff) continue

            // 매 분석마다 객체를 만들지 않고 강한 격자만 행 우선 순서로 유지한다.
            var insertionIndex = 0
            while (
                insertionIndex < selectedCount &&
                cellScores[selectedIndices[insertionIndex]] >= score
            ) {
                insertionIndex++
            }
            if (insertionIndex >= selectionLimit) continue

            val newCount = minOf(selectedCount + 1, selectionLimit)
            for (shiftIndex in newCount - 1 downTo insertionIndex + 1) {
                selectedIndices[shiftIndex] = selectedIndices[shiftIndex - 1]
            }
            selectedIndices[insertionIndex] = index
            selectedCount = newCount
        }
        if (selectedCount == 0) {
            selectedIndices[0] = strongestIndex
            selectedCount = 1
        }

        var selectedLeft = width
        var selectedTop = height
        var selectedRight = 0
        var selectedBottom = 0
        for (selectionIndex in 0 until selectedCount) {
            val cellIndex = selectedIndices[selectionIndex]
            val row = cellIndex / columns
            val column = cellIndex % columns
            val cellLeft = column * width / columns
            val cellTop = row * height / rows
            val cellRight = max(cellLeft + 1, (column + 1) * width / columns).coerceAtMost(width)
            val cellBottom = max(cellTop + 1, (row + 1) * height / rows).coerceAtMost(height)
            selectedLeft = minOf(selectedLeft, cellLeft)
            selectedTop = minOf(selectedTop, cellTop)
            selectedRight = maxOf(selectedRight, cellRight)
            selectedBottom = maxOf(selectedBottom, cellBottom)
        }
        return MoveNetCropPlanner.squareAroundBounds(
            left = selectedLeft.toFloat(),
            top = selectedTop.toFloat(),
            right = selectedRight.toFloat(),
            bottom = selectedBottom.toFloat(),
            imageWidth = width,
            imageHeight = height,
            expansionScale = cropExpansionScale
        )
    }
}
