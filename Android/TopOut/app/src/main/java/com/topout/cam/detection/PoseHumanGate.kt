package com.topout.cam.detection

import com.topout.cam.model.TopOutConfig

/**
 * Rejects sparse landmark hallucinations before they can start or continue a climb.
 *
 * This keeps the original validation rule unchanged: enough reliable landmarks must survive,
 * and the torso must include at least one shoulder, one hip, and three core points in total.
 */
internal object PoseHumanGate {
    private val shoulderTypes = setOf(
        PoseLandmarkIds.LEFT_SHOULDER,
        PoseLandmarkIds.RIGHT_SHOULDER
    )
    private val hipTypes = setOf(
        PoseLandmarkIds.LEFT_HIP,
        PoseLandmarkIds.RIGHT_HIP
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
