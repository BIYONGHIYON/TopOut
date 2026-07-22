package com.topout.cam.detection

/** Pure start rule shared by the analyzer and unit tests. */
internal object PoseStartRule {
    /** The first reliable pose above the mat starts immediately; later starts require below-to-above. */
    fun shouldStart(
        lowestAboveMat: Boolean,
        previousLowestAboveMat: Boolean?
    ): Boolean = lowestAboveMat && previousLowestAboveMat != true
}
