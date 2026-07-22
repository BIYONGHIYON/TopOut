package com.topout.cam.detection

import com.topout.cam.model.TopOutConfig

/** Rejects sparse or anatomically incomplete MoveNet outputs before climb logic can use them. */
object PoseHumanGate {
    fun isValid(
        shoulderCount: Int,
        hipCount: Int,
        elbowCount: Int,
        kneeCount: Int
    ): Boolean {
        if (shoulderCount < TopOutConfig.PoseMinimumShoulders) return false
        if (hipCount < TopOutConfig.PoseMinimumHips) return false
        if (elbowCount < TopOutConfig.PoseMinimumElbows) return false
        if (kneeCount < TopOutConfig.PoseMinimumKnees) return false
        return true
    }

    /** Compatibility overload retained for unit tests and small pure callers. */
    fun isValid(reliableLandmarkTypes: Set<Int>): Boolean {
        var shoulderCount = 0
        var hipCount = 0
        var elbowCount = 0
        var kneeCount = 0

        for (type in reliableLandmarkTypes) {
            when (type) {
                PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER -> shoulderCount++
                PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP -> hipCount++
                PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW -> elbowCount++
                PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE -> kneeCount++
            }
        }

        return isValid(
            shoulderCount = shoulderCount,
            hipCount = hipCount,
            elbowCount = elbowCount,
            kneeCount = kneeCount
        )
    }
}
