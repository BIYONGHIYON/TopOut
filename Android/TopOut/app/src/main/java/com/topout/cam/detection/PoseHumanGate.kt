package com.topout.cam.detection

import com.topout.cam.model.TopOutConfig

/**
 * Rejects sparse MoveNet pose outputs before they can start or finish a climb.
 *
 * Climbing footage is commonly back-facing, distant, partially occluded, and motion-blurred. The gate
 * therefore uses a moderate MoveNet threshold while still requiring body structure: enough reliable
 * joints overall, at least two torso joints, and at least one shoulder plus one hip.
 */
object PoseHumanGate {
    private val shoulderTypes = setOf(
        PoseLandmark.LEFT_SHOULDER,
        PoseLandmark.RIGHT_SHOULDER
    )
    private val hipTypes = setOf(
        PoseLandmark.LEFT_HIP,
        PoseLandmark.RIGHT_HIP
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
