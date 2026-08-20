package com.topout.cam.detection

/** Pure start rule shared by the analyzer and unit tests. */
internal object PoseStartRule {
    fun isStartEligible(
        shoulderPointCount: Int,
        hipPointCount: Int,
        torsoMinX: Float,
        torsoMaxX: Float,
        lowerBodyPointCount: Int,
        anklePointCount: Int,
        sideEdgeMargin: Float,
        minimumLowerBodyPointsWhenGuarded: Int,
        minimumAnklePointsWhenGuarded: Int
    ): Boolean {
        val margin = sideEdgeMargin.coerceIn(0f, 0.25f)
        val hasShoulderAndHip = shoulderPointCount > 0 && hipPointCount > 0
        val needsLowerBodyGuard = !hasShoulderAndHip ||
            torsoMinX <= margin ||
            torsoMaxX >= 1f - margin
        val hasRequiredLowerBody =
            lowerBodyPointCount >= minimumLowerBodyPointsWhenGuarded &&
                anklePointCount >= minimumAnklePointsWhenGuarded
        return !needsLowerBodyGuard || hasRequiredLowerBody
    }

    /** The first reliable pose above the mat starts immediately; later starts require below-to-above. */
    fun shouldStart(
        lowestAboveMat: Boolean,
        previousLowestAboveMat: Boolean?,
        startEligible: Boolean = true
    ): Boolean = startEligible && lowestAboveMat && previousLowestAboveMat != true
}
