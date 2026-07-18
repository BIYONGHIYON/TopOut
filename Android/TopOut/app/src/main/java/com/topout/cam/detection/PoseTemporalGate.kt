package com.topout.cam.detection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.sqrt

/** Pure temporal checks used by the analyzer and unit tests. */
internal object PoseTemporalGate {
    fun movedLandmarkCount(
        referenceBox: Rect,
        referenceLandmarks: Map<Int, Offset>,
        currentBox: Rect,
        currentLandmarks: Map<Int, Offset>,
        minimumBoxDiagonalRatio: Float
    ): Int {
        val referenceDiagonal = boxDiagonal(referenceBox)
        val currentDiagonal = boxDiagonal(currentBox)
        val threshold = maxOf(referenceDiagonal, currentDiagonal) * minimumBoxDiagonalRatio
        if (threshold <= 0f) return 0

        return currentLandmarks.count { (type, current) ->
            val reference = referenceLandmarks[type] ?: return@count false
            val dx = current.x - reference.x
            val dy = current.y - reference.y
            sqrt(dx * dx + dy * dy) >= threshold
        }
    }

    fun canUseRelaxedMaintenance(
        seconds: Double,
        lastStrictPoseSeconds: Double?,
        maximumRelaxedSeconds: Double
    ): Boolean {
        val reference = lastStrictPoseSeconds ?: return false
        return seconds - reference <= maximumRelaxedSeconds
    }

    private fun boxDiagonal(box: Rect): Float {
        val width = (box.right - box.left).coerceAtLeast(0f)
        val height = (box.bottom - box.top).coerceAtLeast(0f)
        return sqrt(width * width + height * height)
    }
}
