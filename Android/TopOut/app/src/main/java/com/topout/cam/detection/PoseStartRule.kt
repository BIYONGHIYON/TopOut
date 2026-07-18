package com.topout.cam.detection

/** Pure start rule shared by the analyzer and unit tests. */
internal object PoseStartRule {
    /**
     * A climb can start only after the temporal human-motion gate has verified the candidate.
     * Once verified, the first pose above the mat is a start even without a previous pose sample.
     */
    fun shouldStart(
        lowestAboveMat: Boolean,
        previousLowestAboveMat: Boolean?,
        humanMotionVerified: Boolean
    ): Boolean = humanMotionVerified && lowestAboveMat && previousLowestAboveMat != true
}
