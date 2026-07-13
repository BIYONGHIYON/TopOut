package com.topout.cam.detection

/** Pure start rule shared by the analyzer and unit tests. */
internal object PoseStartRule {
    /**
     * A first valid pose above the mat is a start, even when there was no previous pose sample.
     * Remaining above the mat does not emit duplicate starts.
     */
    fun shouldStart(lowestAboveMat: Boolean, previousLowestAboveMat: Boolean?): Boolean =
        lowestAboveMat && previousLowestAboveMat != true

    /**
     * Every START path uses the same pre-roll calculation. This includes both:
     * 1) the first valid pose already being above the mat, and
     * 2) a later below-to-above crossing.
     *
     * The result is clamped to the beginning of the encoded recording because footage before
     * recording time zero does not exist.
     */
    fun clipStartSeconds(
        detectionSeconds: Double,
        preRollSeconds: Double
    ): Double = (detectionSeconds - preRollSeconds).coerceAtLeast(0.0)
}
