package com.topout.cam.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseStartRuleTest {
    @Test
    fun firstReliablePoseAlreadyAboveMatStartsImmediately() {
        assertTrue(
            PoseStartRule.shouldStart(
                lowestAboveMat = true,
                previousLowestAboveMat = null
            )
        )
    }

    @Test
    fun firstReliablePoseBelowMatDoesNotStart() {
        assertFalse(
            PoseStartRule.shouldStart(
                lowestAboveMat = false,
                previousLowestAboveMat = null
            )
        )
    }

    @Test
    fun belowToAboveCrossingStarts() {
        assertTrue(
            PoseStartRule.shouldStart(
                lowestAboveMat = true,
                previousLowestAboveMat = false
            )
        )
    }

    @Test
    fun remainingAboveDoesNotStartAgain() {
        assertFalse(
            PoseStartRule.shouldStart(
                lowestAboveMat = true,
                previousLowestAboveMat = true
            )
        )
    }

    @Test
    fun sideEdgePoseWithInsufficientLowerBodyEvidenceCannotStart() {
        val eligible = PoseStartRule.isStartEligible(
            minX = 0.02f,
            maxX = 0.24f,
            lowerBodyPointCount = 1,
            sideEdgeMargin = 0.05f,
            minimumLowerBodyPointsAtEdge = 2
        )

        assertFalse(
            PoseStartRule.shouldStart(
                lowestAboveMat = true,
                previousLowestAboveMat = false,
                startEligible = eligible
            )
        )
    }

    @Test
    fun centeredOrLowerBodyCompletePoseRemainsEligible() {
        assertTrue(
            PoseStartRule.isStartEligible(
                minX = 0.20f,
                maxX = 0.45f,
                lowerBodyPointCount = 0,
                sideEdgeMargin = 0.05f,
                minimumLowerBodyPointsAtEdge = 2
            )
        )
        assertTrue(
            PoseStartRule.isStartEligible(
                minX = 0.01f,
                maxX = 0.30f,
                lowerBodyPointCount = 2,
                sideEdgeMargin = 0.05f,
                minimumLowerBodyPointsAtEdge = 2
            )
        )
    }
}
