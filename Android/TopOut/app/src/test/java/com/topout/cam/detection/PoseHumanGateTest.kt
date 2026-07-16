package com.topout.cam.detection

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
                    YoloPoseLandmark.NOSE,
                    YoloPoseLandmark.LEFT_WRIST,
                    YoloPoseLandmark.RIGHT_WRIST,
                    YoloPoseLandmark.LEFT_ANKLE,
                    YoloPoseLandmark.RIGHT_ANKLE
                )
            )
        )
    }

    @Test
    fun rejectsPoseWithoutEnoughTorsoLandmarks() {
        assertFalse(
            PoseHumanGate.isValid(
                setOf(
                    YoloPoseLandmark.NOSE,
                    YoloPoseLandmark.LEFT_SHOULDER,
                    YoloPoseLandmark.RIGHT_SHOULDER,
                    YoloPoseLandmark.LEFT_ELBOW,
                    YoloPoseLandmark.RIGHT_ELBOW,
                    YoloPoseLandmark.LEFT_WRIST,
                    YoloPoseLandmark.RIGHT_WRIST
                )
            )
        )
    }

    @Test
    fun acceptsPoseWithSixLandmarksAndThreeTorsoPoints() {
        assertTrue(
            PoseHumanGate.isValid(
                setOf(
                    YoloPoseLandmark.LEFT_SHOULDER,
                    YoloPoseLandmark.RIGHT_SHOULDER,
                    YoloPoseLandmark.LEFT_HIP,
                    YoloPoseLandmark.LEFT_ELBOW,
                    YoloPoseLandmark.LEFT_KNEE,
                    YoloPoseLandmark.LEFT_ANKLE
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
                    YoloPoseLandmark.LEFT_SHOULDER,
                    YoloPoseLandmark.RIGHT_SHOULDER,
                    YoloPoseLandmark.NOSE,
                    YoloPoseLandmark.LEFT_ELBOW,
                    YoloPoseLandmark.RIGHT_ELBOW,
                    YoloPoseLandmark.LEFT_WRIST,
                    YoloPoseLandmark.RIGHT_WRIST
                )
            )
        )
    }
}
