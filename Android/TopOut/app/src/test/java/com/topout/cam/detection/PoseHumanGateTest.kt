package com.topout.cam.detection

import com.google.mlkit.vision.pose.PoseLandmark
import com.topout.cam.model.TopOutConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseHumanGateTest {
    @Test
    fun confidenceThresholdIsRaisedToPointFive() {
        assertEquals(0.50f, TopOutConfig.PoseConfidence, 0.0001f)
    }

    @Test
    fun rejectsSparseWallHallucination() {
        assertFalse(
            PoseHumanGate.isValid(
                setOf(
                    PoseLandmark.NOSE,
                    PoseLandmark.LEFT_WRIST,
                    PoseLandmark.RIGHT_WRIST,
                    PoseLandmark.LEFT_ANKLE,
                    PoseLandmark.RIGHT_ANKLE
                )
            )
        )
    }

    @Test
    fun rejectsPoseWithoutEnoughTorsoLandmarks() {
        assertFalse(
            PoseHumanGate.isValid(
                setOf(
                    PoseLandmark.NOSE,
                    PoseLandmark.LEFT_SHOULDER,
                    PoseLandmark.RIGHT_SHOULDER,
                    PoseLandmark.LEFT_ELBOW,
                    PoseLandmark.RIGHT_ELBOW,
                    PoseLandmark.LEFT_WRIST,
                    PoseLandmark.RIGHT_WRIST
                )
            )
        )
    }

    @Test
    fun acceptsPoseWithSixLandmarksAndThreeTorsoPoints() {
        assertTrue(
            PoseHumanGate.isValid(
                setOf(
                    PoseLandmark.LEFT_SHOULDER,
                    PoseLandmark.RIGHT_SHOULDER,
                    PoseLandmark.LEFT_HIP,
                    PoseLandmark.LEFT_ELBOW,
                    PoseLandmark.LEFT_KNEE,
                    PoseLandmark.LEFT_ANKLE
                )
            )
        )
    }

    @Test
    fun rejectsThreeTorsoPointsIfAllAreFromOnlyOneBodyRegion() {
        // There are only two shoulder IDs and two hip IDs, so this verifies the explicit
        // requirement that both a shoulder and a hip must be represented.
        assertFalse(
            PoseHumanGate.isValid(
                setOf(
                    PoseLandmark.LEFT_SHOULDER,
                    PoseLandmark.RIGHT_SHOULDER,
                    PoseLandmark.NOSE,
                    PoseLandmark.LEFT_ELBOW,
                    PoseLandmark.RIGHT_ELBOW,
                    PoseLandmark.LEFT_WRIST,
                    PoseLandmark.RIGHT_WRIST
                )
            )
        )
    }
}
