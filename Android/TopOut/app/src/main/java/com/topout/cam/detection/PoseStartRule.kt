package com.topout.cam.detection

/** Pure start rule shared by the analyzer and unit tests. */
internal object PoseStartRule {
    fun isStartEligible(
        minX: Float,
        maxX: Float,
        lowerBodyPointCount: Int,
        sideEdgeMargin: Float,
        minimumLowerBodyPointsAtEdge: Int
    ): Boolean {
        val margin = sideEdgeMargin.coerceIn(0f, 0.25f)
        val touchesSideEdge = minX <= margin || maxX >= 1f - margin
        return !touchesSideEdge || lowerBodyPointCount >= minimumLowerBodyPointsAtEdge
    }

    /** The first reliable pose above the mat starts immediately; later starts require below-to-above. */
    fun shouldStart(
        lowestAboveMat: Boolean,
        previousLowestAboveMat: Boolean?,
        startEligible: Boolean = true
    ): Boolean = startEligible && lowestAboveMat && previousLowestAboveMat != true
}
