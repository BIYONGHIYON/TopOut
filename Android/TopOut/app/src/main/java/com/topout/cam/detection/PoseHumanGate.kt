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
        var satisfiedGroups = 0
        if (shoulderCount >= TopOutConfig.PoseMinimumShoulders) satisfiedGroups++
        if (hipCount >= TopOutConfig.PoseMinimumHips) satisfiedGroups++
        if (elbowCount >= TopOutConfig.PoseMinimumElbows) satisfiedGroups++
        if (kneeCount >= TopOutConfig.PoseMinimumKnees) satisfiedGroups++
        return satisfiedGroups >= TopOutConfig.PoseMinimumSatisfiedGroups
    }

    /**
     * MoveNet은 사람이 없는 벽에서 홀드 위에 손가락 크기의 뼈대를 만들고 높은 신뢰도를 주기도 한다.
     * 믿을 만한 관절이 화면 긴 변의 5%보다 좁게 몰려 있으면 사람으로 보지 않는다.
     * 실제 등반자 1,203프레임의 최소 폭은 5.6%였고, 가짜 뼈대 41건 중 20건이 이 기준에 걸렸다.
     */
    fun hasPlausibleSize(spanXPixels: Float, spanYPixels: Float, imageLongSidePixels: Int): Boolean {
        if (imageLongSidePixels <= 0) return true
        return maxOf(spanXPixels, spanYPixels) >= imageLongSidePixels * TopOutConfig.PoseMinimumSkeletonSpanRatio
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
