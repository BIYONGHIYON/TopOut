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
    fun missingTorsoWithInsufficientLowerBodyEvidenceCannotStart() {
        val eligible = PoseStartRule.isStartEligible(
            shoulderPointCount = 0,
            hipPointCount = 0,
            torsoMinX = 1f,
            torsoMaxX = 0f,
            lowerBodyPointCount = 1,
            anklePointCount = 0,
            sideEdgeMargin = 0.10f,
            minimumLowerBodyPointsWhenGuarded = 3,
            minimumAnklePointsWhenGuarded = 1
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
    fun centeredShouldersWithoutHipNeedLowerBodyEvidence() {
        assertFalse(
            PoseStartRule.isStartEligible(
                shoulderPointCount = 2,
                hipPointCount = 0,
                torsoMinX = 0.30f,
                torsoMaxX = 0.60f,
                lowerBodyPointCount = 1,
                anklePointCount = 0,
                sideEdgeMargin = 0.10f,
                minimumLowerBodyPointsWhenGuarded = 3,
                minimumAnklePointsWhenGuarded = 1
            )
        )
    }

    @Test
    fun centeredHipsWithoutShoulderNeedLowerBodyEvidence() {
        assertFalse(
            PoseStartRule.isStartEligible(
                shoulderPointCount = 0,
                hipPointCount = 2,
                torsoMinX = 0.30f,
                torsoMaxX = 0.60f,
                lowerBodyPointCount = 1,
                anklePointCount = 0,
                sideEdgeMargin = 0.10f,
                minimumLowerBodyPointsWhenGuarded = 3,
                minimumAnklePointsWhenGuarded = 1
            )
        )
    }

    @Test
    fun torsoJointAtSideEdgeNeedsLowerBodyEvidence() {
        assertFalse(
            PoseStartRule.isStartEligible(
                shoulderPointCount = 2,
                hipPointCount = 1,
                torsoMinX = 0.08f,
                torsoMaxX = 0.35f,
                lowerBodyPointCount = 1,
                anklePointCount = 0,
                sideEdgeMargin = 0.10f,
                minimumLowerBodyPointsWhenGuarded = 3,
                minimumAnklePointsWhenGuarded = 1
            )
        )
    }

    @Test
    fun centeredTorsoIgnoresLimbsAtSideEdge() {
        assertTrue(
            PoseStartRule.isStartEligible(
                shoulderPointCount = 1,
                hipPointCount = 1,
                torsoMinX = 0.25f,
                torsoMaxX = 0.65f,
                lowerBodyPointCount = 1,
                anklePointCount = 0,
                sideEdgeMargin = 0.10f,
                minimumLowerBodyPointsWhenGuarded = 3,
                minimumAnklePointsWhenGuarded = 1
            )
        )
    }

    @Test
    fun guardedTorsoWithThreeLowerBodyPointsButNoAnkleCannotStart() {
        assertFalse(
            PoseStartRule.isStartEligible(
                shoulderPointCount = 1,
                hipPointCount = 1,
                torsoMinX = 0.05f,
                torsoMaxX = 0.28f,
                lowerBodyPointCount = 3,
                anklePointCount = 0,
                sideEdgeMargin = 0.10f,
                minimumLowerBodyPointsWhenGuarded = 3,
                minimumAnklePointsWhenGuarded = 1
            )
        )
    }

    @Test
    fun guardedTorsoWithAnkleButOnlyTwoLowerBodyPointsCannotStart() {
        assertFalse(
            PoseStartRule.isStartEligible(
                shoulderPointCount = 1,
                hipPointCount = 1,
                torsoMinX = 0.05f,
                torsoMaxX = 0.28f,
                lowerBodyPointCount = 2,
                anklePointCount = 1,
                sideEdgeMargin = 0.10f,
                minimumLowerBodyPointsWhenGuarded = 3,
                minimumAnklePointsWhenGuarded = 1
            )
        )
    }

    @Test
    fun guardedTorsoWithThreeLowerBodyPointsIncludingAnkleRemainsEligible() {
        assertTrue(
            PoseStartRule.isStartEligible(
                shoulderPointCount = 1,
                hipPointCount = 1,
                torsoMinX = 0.05f,
                torsoMaxX = 0.28f,
                lowerBodyPointCount = 3,
                anklePointCount = 1,
                sideEdgeMargin = 0.10f,
                minimumLowerBodyPointsWhenGuarded = 3,
                minimumAnklePointsWhenGuarded = 1
            )
        )
    }
}
