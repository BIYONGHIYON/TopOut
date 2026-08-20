package com.topout.cam.detection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.topout.cam.model.TopOutConfig

internal data class MoveNetCropRegion(
    val xMin: Float,
    val yMin: Float,
    val width: Float,
    val height: Float
)

/** Converts MoveNet's [1, 1, 17, 3] (y, x, score) output back to source-image space. */
internal object MoveNetPoseOutputParser {
    fun initialCropRegion(imageWidth: Int, imageHeight: Int): MoveNetCropRegion {
        require(imageWidth > 0 && imageHeight > 0)
        return if (imageWidth > imageHeight) {
            val cropHeight = imageWidth.toFloat() / imageHeight
            MoveNetCropRegion(
                xMin = 0f,
                yMin = (imageHeight / 2f - imageWidth / 2f) / imageHeight,
                width = 1f,
                height = cropHeight
            )
        } else {
            val cropWidth = imageHeight.toFloat() / imageWidth
            MoveNetCropRegion(
                xMin = (imageWidth / 2f - imageHeight / 2f) / imageWidth,
                yMin = 0f,
                width = cropWidth,
                height = 1f
            )
        }
    }

    fun parse(
        values: FloatArray,
        cropRegion: MoveNetCropRegion,
        minimumBoxScore: Float = 0.10f
    ): PoseResult? {
        require(values.size >= PoseLandmark.COUNT * 3) {
            "MoveNet 출력은 최소 ${PoseLandmark.COUNT * 3}개의 float 값을 포함해야 합니다."
        }

        val keypoints = LinkedHashMap<Int, PoseKeypoint>(PoseLandmark.COUNT)
        var scoreSum = 0f
        var reliablePointCount = 0
        var reliableMinX = 1f
        var reliableMinY = 1f
        var reliableMaxX = 0f
        var reliableMaxY = 0f
        for (index in 0 until PoseLandmark.COUNT) {
            val offset = index * 3
            val modelY = values[offset]
            val modelX = values[offset + 1]
            val score = values[offset + 2].coerceIn(0f, 1f)
            val position = Offset(
                x = (cropRegion.xMin + modelX * cropRegion.width).coerceIn(0f, 1f),
                y = (cropRegion.yMin + modelY * cropRegion.height).coerceIn(0f, 1f)
            )
            keypoints[index] = PoseKeypoint(
                position = position,
                confidence = score
            )
            scoreSum += score
            if (score >= minimumBoxScore) {
                reliablePointCount++
                reliableMinX = minOf(reliableMinX, position.x)
                reliableMinY = minOf(reliableMinY, position.y)
                reliableMaxX = maxOf(reliableMaxX, position.x)
                reliableMaxY = maxOf(reliableMaxY, position.y)
            }
        }

        if (reliablePointCount == 0) return null
        val margin = 0.025f
        val left = (reliableMinX - margin).coerceIn(0f, 1f)
        val top = (reliableMinY - margin).coerceIn(0f, 1f)
        val right = (reliableMaxX + margin).coerceIn(left, 1f)
        val bottom = (reliableMaxY + margin).coerceIn(top, 1f)
        return PoseResult(
            boundingBox = Rect(left, top, right, bottom),
            confidence = scoreSum / PoseLandmark.COUNT,
            keypoints = keypoints
        )
    }

    /** Builds the next 1.5x joint-bounds crop once enough torso evidence is visible. */
    fun nextCropRegion(
        pose: PoseResult,
        imageWidth: Int,
        imageHeight: Int,
        minimumKeypointScore: Float = 0.20f
    ): MoveNetCropRegion {
        require(imageWidth > 0 && imageHeight > 0)
        if (!torsoVisible(pose, minimumKeypointScore)) {
            return initialCropRegion(imageWidth, imageHeight)
        }

        var reliablePointCount = 0
        var reliableMinX = 1f
        var reliableMinY = 1f
        var reliableMaxX = 0f
        var reliableMaxY = 0f
        for (keypoint in pose.keypoints.values) {
            if (keypoint.confidence < minimumKeypointScore) continue
            reliablePointCount++
            reliableMinX = minOf(reliableMinX, keypoint.position.x)
            reliableMinY = minOf(reliableMinY, keypoint.position.y)
            reliableMaxX = maxOf(reliableMaxX, keypoint.position.x)
            reliableMaxY = maxOf(reliableMaxY, keypoint.position.y)
        }
        if (reliablePointCount == 0) return initialCropRegion(imageWidth, imageHeight)
        return MoveNetCropPlanner.squareAroundBounds(
            left = reliableMinX * imageWidth,
            top = reliableMinY * imageHeight,
            right = reliableMaxX * imageWidth,
            bottom = reliableMaxY * imageHeight,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            expansionScale = TopOutConfig.MoveNetTrackingCropExpansionScale
        ) ?: initialCropRegion(imageWidth, imageHeight)
    }

    private fun torsoVisible(pose: PoseResult, minimumScore: Float): Boolean {
        val hipVisible = pose.keypoints.getValue(PoseLandmark.LEFT_HIP).confidence > minimumScore ||
            pose.keypoints.getValue(PoseLandmark.RIGHT_HIP).confidence > minimumScore
        val shoulderVisible = pose.keypoints.getValue(PoseLandmark.LEFT_SHOULDER).confidence > minimumScore ||
            pose.keypoints.getValue(PoseLandmark.RIGHT_SHOULDER).confidence > minimumScore
        return hipVisible && shoulderVisible
    }
}
