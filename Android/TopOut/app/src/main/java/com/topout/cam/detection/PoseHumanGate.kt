package com.topout.cam.detection

import com.google.mlkit.vision.pose.PoseLandmark
import com.topout.cam.model.TopOutConfig

/**
 * Rejects sparse landmark hallucinations before they can start or continue a climb.
 *
 * A frame is considered a human pose only when:
 * 1) enough reliable landmarks survive the confidence threshold; and
 * 2) the torso is represented by at least three shoulder/hip landmarks, including
 *    at least one shoulder and at least one hip.
 *
 * The gate intentionally does not enforce upright geometry because a climber can be
 * sideways or inverted on the wall.
 */
internal object PoseHumanGate {
    private val shoulderTypes = setOf(
        PoseLandmark.LEFT_SHOULDER,
        PoseLandmark.RIGHT_SHOULDER
    )
    private val hipTypes = setOf(
        PoseLandmark.LEFT_HIP,
        PoseLandmark.RIGHT_HIP
    )

    fun isValid(landmarkTypes: Set<Int>): Boolean {
        if (landmarkTypes.size < TopOutConfig.PoseMinimumReliableLandmarks) return false

        val shoulderCount = landmarkTypes.count { it in shoulderTypes }
        val hipCount = landmarkTypes.count { it in hipTypes }
        val coreCount = shoulderCount + hipCount

        return shoulderCount >= 1 &&
            hipCount >= 1 &&
            coreCount >= TopOutConfig.PoseMinimumCoreLandmarks
    }
}
