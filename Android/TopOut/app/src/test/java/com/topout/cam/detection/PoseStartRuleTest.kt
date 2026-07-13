package com.topout.cam.detection

import org.junit.Assert.assertEquals
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
    @Test
    fun firstPoseAlreadyAboveUsesThreeSecondPreRoll() {
        val start = PoseStartRule.clipStartSeconds(
            detectionSeconds = 8.5,
            preRollSeconds = 3.0
        )
        assertEquals(5.5, start, 0.0001)
    }

    @Test
    fun belowToAboveCrossingUsesTheSameThreeSecondPreRoll() {
        val start = PoseStartRule.clipStartSeconds(
            detectionSeconds = 12.0,
            preRollSeconds = 3.0
        )
        assertEquals(9.0, start, 0.0001)
    }

    @Test
    fun preRollNeverCreatesNegativeClipStart() {
        val start = PoseStartRule.clipStartSeconds(
            detectionSeconds = 1.25,
            preRollSeconds = 3.0
        )
        assertEquals(0.0, start, 0.0001)
    }

}
