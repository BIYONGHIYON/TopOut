package com.topout.cam.detection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class MoveNetCropRegion(
    val xMin: Float,
    val yMin: Float,
    val width: Float,
    val height: Float
)

/** Converts MoveNet's [1, 1, 17, 3] (y, x, score) output back to source-image space. */
internal object MoveNetPoseOutputParser {
    private val torsoLandmarks = intArrayOf(
        PoseLandmark.LEFT_SHOULDER,
        PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.LEFT_HIP,
        PoseLandmark.RIGHT_HIP
    )

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
        for (index in 0 until PoseLandmark.COUNT) {
            val offset = index * 3
            val modelY = values[offset]
            val modelX = values[offset + 1]
            val score = values[offset + 2].coerceIn(0f, 1f)
            keypoints[index] = PoseKeypoint(
                position = Offset(
                    x = (cropRegion.xMin + modelX * cropRegion.width).coerceIn(0f, 1f),
                    y = (cropRegion.yMin + modelY * cropRegion.height).coerceIn(0f, 1f)
                ),
                confidence = score
            )
            scoreSum += score
        }

        val reliable = keypoints.values.filter { it.confidence >= minimumBoxScore }
        if (reliable.isEmpty()) return null
        val margin = 0.025f
        val left = (reliable.minOf { it.position.x } - margin).coerceIn(0f, 1f)
        val top = (reliable.minOf { it.position.y } - margin).coerceIn(0f, 1f)
        val right = (reliable.maxOf { it.position.x } + margin).coerceIn(left, 1f)
        val bottom = (reliable.maxOf { it.position.y } + margin).coerceIn(top, 1f)
        return PoseResult(
            boundingBox = Rect(left, top, right, bottom),
            confidence = scoreSum / PoseLandmark.COUNT,
            keypoints = keypoints
        )
    }

    /** Implements the smart-crop policy from TensorFlow's official MoveNet video tutorial. */
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

        val leftHip = pose.keypoints.getValue(PoseLandmark.LEFT_HIP).position
        val rightHip = pose.keypoints.getValue(PoseLandmark.RIGHT_HIP).position
        val centerX = (leftHip.x + rightHip.x) * 0.5f * imageWidth
        val centerY = (leftHip.y + rightHip.y) * 0.5f * imageHeight

        var maxTorsoX = 0f
        var maxTorsoY = 0f
        torsoLandmarks.forEach { index ->
            val point = pose.keypoints.getValue(index).position
            maxTorsoX = max(maxTorsoX, abs(centerX - point.x * imageWidth))
            maxTorsoY = max(maxTorsoY, abs(centerY - point.y * imageHeight))
        }

        var maxBodyX = 0f
        var maxBodyY = 0f
        pose.keypoints.values.forEach { keypoint ->
            if (keypoint.confidence < minimumKeypointScore) return@forEach
            maxBodyX = max(maxBodyX, abs(centerX - keypoint.position.x * imageWidth))
            maxBodyY = max(maxBodyY, abs(centerY - keypoint.position.y * imageHeight))
        }

        var cropHalf = max(
            max(maxTorsoX * 1.9f, maxTorsoY * 1.9f),
            max(maxBodyX * 1.2f, maxBodyY * 1.2f)
        )
        cropHalf = min(
            cropHalf,
            max(max(centerX, imageWidth - centerX), max(centerY, imageHeight - centerY))
        )
        val cropLength = cropHalf * 2f
        if (!cropLength.isFinite() || cropLength <= 1f || cropLength > max(imageWidth, imageHeight)) {
            return initialCropRegion(imageWidth, imageHeight)
        }

        return MoveNetCropRegion(
            xMin = (centerX - cropHalf) / imageWidth,
            yMin = (centerY - cropHalf) / imageHeight,
            width = cropLength / imageWidth,
            height = cropLength / imageHeight
        )
    }

    private fun torsoVisible(pose: PoseResult, minimumScore: Float): Boolean {
        val hipVisible = pose.keypoints.getValue(PoseLandmark.LEFT_HIP).confidence > minimumScore ||
            pose.keypoints.getValue(PoseLandmark.RIGHT_HIP).confidence > minimumScore
        val shoulderVisible = pose.keypoints.getValue(PoseLandmark.LEFT_SHOULDER).confidence > minimumScore ||
            pose.keypoints.getValue(PoseLandmark.RIGHT_SHOULDER).confidence > minimumScore
        return hipVisible && shoulderVisible
    }
}
