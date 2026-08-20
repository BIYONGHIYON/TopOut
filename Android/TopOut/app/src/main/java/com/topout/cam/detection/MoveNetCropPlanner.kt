package com.topout.cam.detection

import kotlin.math.max

/** Builds a pixel-square MoveNet crop around source-space bounds without distorting the image. */
internal object MoveNetCropPlanner {
    fun squareAroundBounds(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        imageWidth: Int,
        imageHeight: Int,
        expansionScale: Float
    ): MoveNetCropRegion? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
        val boundsWidth = right - left
        val boundsHeight = bottom - top
        if (boundsWidth <= 0f || boundsHeight <= 0f) return null

        val maximumSide = max(imageWidth, imageHeight).toFloat()
        val safeExpansionScale = expansionScale.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
        val side = (max(boundsWidth, boundsHeight) * safeExpansionScale)
            .coerceIn(1f, maximumSide)
        val centerX = (left + right) * 0.5f
        val centerY = (top + bottom) * 0.5f
        val cropLeft = positionedStart(centerX, side, imageWidth.toFloat())
        val cropTop = positionedStart(centerY, side, imageHeight.toFloat())

        return MoveNetCropRegion(
            xMin = cropLeft / imageWidth,
            yMin = cropTop / imageHeight,
            width = side / imageWidth,
            height = side / imageHeight
        )
    }

    /** Keep the square inside an axis when possible and use symmetric padding only when necessary. */
    private fun positionedStart(center: Float, side: Float, axisLength: Float): Float {
        val proposed = center - side * 0.5f
        return if (side <= axisLength) {
            proposed.coerceIn(0f, axisLength - side)
        } else {
            proposed.coerceIn(axisLength - side, 0f)
        }
    }
}
