package com.topout.cam.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseStartRuleTest {
    @Test
    fun unverifiedPoseNeverStarts() {
        assertFalse(
            PoseStartRule.shouldStart(
                lowestAboveMat = true,
                previousLowestAboveMat = null,
                humanMotionVerified = false
            )
        )
    }

    @Test
    fun firstVerifiedPoseAlreadyAboveMatStartsImmediately() {
        assertTrue(
            PoseStartRule.shouldStart(
                lowestAboveMat = true,
                previousLowestAboveMat = null,
                humanMotionVerified = true
            )
        )
    }

    @Test
    fun firstVerifiedPoseBelowMatDoesNotStart() {
        assertFalse(
            PoseStartRule.shouldStart(
                lowestAboveMat = false,
                previousLowestAboveMat = null,
                humanMotionVerified = true
            )
        )
    }

    @Test
    fun verifiedBelowToAboveCrossingStarts() {
        assertTrue(
            PoseStartRule.shouldStart(
                lowestAboveMat = true,
                previousLowestAboveMat = false,
                humanMotionVerified = true
            )
        )
    }

    @Test
    fun verifiedRemainingAboveDoesNotStartAgain() {
        assertFalse(
            PoseStartRule.shouldStart(
                lowestAboveMat = true,
                previousLowestAboveMat = true,
                humanMotionVerified = true
            )
        )
    }
}
