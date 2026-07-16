package com.topout.cam.detection

import com.topout.cam.model.TopOutConfig

/**
 * Rejects sparse YOLO pose outputs before they can start or finish a climb.
 *
 * The previous ML Kit version used the same rule: enough reliable joints overall, enough torso
 * joints, and at least one shoulder plus one hip. Only the landmark index source has changed.
 */
object PoseHumanGate {
    private val shoulderTypes = setOf(
        YoloPoseLandmark.LEFT_SHOULDER,
        YoloPoseLandmark.RIGHT_SHOULDER
    )
    private val hipTypes = setOf(
        YoloPoseLandmark.LEFT_HIP,
        YoloPoseLandmark.RIGHT_HIP
    )
    private val coreTypes = shoulderTypes + hipTypes

    fun isValid(reliableLandmarkTypes: Set<Int>): Boolean {
        if (reliableLandmarkTypes.size < TopOutConfig.PoseMinimumReliableLandmarks) return false
        if (reliableLandmarkTypes.count { it in coreTypes } < TopOutConfig.PoseMinimumCoreLandmarks) {
            return false
        }
        if (reliableLandmarkTypes.none { it in shoulderTypes }) return false
        if (reliableLandmarkTypes.none { it in hipTypes }) return false
        return true
    }
}
