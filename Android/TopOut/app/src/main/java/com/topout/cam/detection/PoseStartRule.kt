package com.topout.cam.detection

/** Pure start rule shared by the analyzer and unit tests. */
internal object PoseStartRule {
    /**
     * A first valid pose above the mat is a start, even when there was no previous pose sample.
     * Remaining above the mat does not emit duplicate starts.
     */
    fun shouldStart(lowestAboveMat: Boolean, previousLowestAboveMat: Boolean?): Boolean =
        lowestAboveMat && previousLowestAboveMat != true

}
