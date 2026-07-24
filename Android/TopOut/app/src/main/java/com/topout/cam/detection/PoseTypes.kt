package com.topout.cam.detection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

internal data class PoseKeypoint(
    val position: Offset,
    val confidence: Float
)

internal data class PoseResult(
    val boundingBox: Rect,
    val confidence: Float,
    val keypoints: Map<Int, PoseKeypoint>
)

/** COCO 17-keypoint indices emitted by MoveNet SinglePose. */
object PoseLandmark {
    const val LEFT_SHOULDER = 5
    const val RIGHT_SHOULDER = 6
    const val LEFT_ELBOW = 7
    const val RIGHT_ELBOW = 8
    const val LEFT_WRIST = 9
    const val RIGHT_WRIST = 10
    const val LEFT_HIP = 11
    const val RIGHT_HIP = 12
    const val LEFT_KNEE = 13
    const val RIGHT_KNEE = 14
    const val LEFT_ANKLE = 15
    const val RIGHT_ANKLE = 16
    const val COUNT = 17
}
