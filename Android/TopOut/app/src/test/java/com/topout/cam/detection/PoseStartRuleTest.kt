package com.topout.cam.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseStartRuleTest {
    @Test
    fun firstPoseAlreadyAboveMatStartsImmediately() {
        assertTrue(PoseStartRule.shouldStart(lowestAboveMat = true, previousLowestAboveMat = null))
    }

    @Test
    fun firstPoseBelowMatDoesNotStart() {
        assertFalse(PoseStartRule.shouldStart(lowestAboveMat = false, previousLowestAboveMat = null))
    }

    @Test
    fun belowToAboveCrossingStarts() {
        assertTrue(PoseStartRule.shouldStart(lowestAboveMat = true, previousLowestAboveMat = false))
    }

    @Test
    fun remainingAboveDoesNotStartAgain() {
        assertFalse(PoseStartRule.shouldStart(lowestAboveMat = true, previousLowestAboveMat = true))
    }
}
