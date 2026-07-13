package com.topout.cam.detection

import com.topout.cam.model.TopOutConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseHumanGateTest {
    @Test
    fun confidenceThresholdRemainsPointFive() {
        assertEquals(0.50f, TopOutConfig.PoseConfidence, 0.0001f)
    }

    @Test
    fun rejectsSparseWallHallucination() {
        assertFalse(
            PoseHumanGate.isValid(
                setOf(
                    PoseLandmarkIds.NOSE,
                    PoseLandmarkIds.LEFT_WRIST,
                    PoseLandmarkIds.RIGHT_WRIST,
                    PoseLandmarkIds.LEFT_ANKLE,
                    PoseLandmarkIds.RIGHT_ANKLE
                )
            )
        )
    }

    @Test
    fun rejectsPoseWithoutEnoughTorsoLandmarks() {
        assertFalse(
            PoseHumanGate.isValid(
                setOf(
                    PoseLandmarkIds.NOSE,
                    PoseLandmarkIds.LEFT_SHOULDER,
                    PoseLandmarkIds.RIGHT_SHOULDER,
                    PoseLandmarkIds.LEFT_ELBOW,
                    PoseLandmarkIds.RIGHT_ELBOW,
                    PoseLandmarkIds.LEFT_WRIST,
                    PoseLandmarkIds.RIGHT_WRIST
                )
            )
        )
    }

    @Test
    fun acceptsPoseWithSixLandmarksAndThreeTorsoPoints() {
        assertTrue(
            PoseHumanGate.isValid(
                setOf(
                    PoseLandmarkIds.LEFT_SHOULDER,
                    PoseLandmarkIds.RIGHT_SHOULDER,
                    PoseLandmarkIds.LEFT_HIP,
                    PoseLandmarkIds.LEFT_ELBOW,
                    PoseLandmarkIds.LEFT_KNEE,
                    PoseLandmarkIds.LEFT_ANKLE
                )
            )
        )
    }

    @Test
    fun rejectsCorePointsFromOnlyOneBodyRegion() {
        assertFalse(
            PoseHumanGate.isValid(
                setOf(
                    PoseLandmarkIds.LEFT_SHOULDER,
                    PoseLandmarkIds.RIGHT_SHOULDER,
                    PoseLandmarkIds.NOSE,
                    PoseLandmarkIds.LEFT_ELBOW,
                    PoseLandmarkIds.RIGHT_ELBOW,
                    PoseLandmarkIds.LEFT_WRIST,
                    PoseLandmarkIds.RIGHT_WRIST
                )
            )
        )
    }
}
