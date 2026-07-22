package com.topout.cam.detection

import com.topout.cam.model.TopOutConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseHumanGateTest {
    @Test
    fun confidenceThresholdRemainsQuarter() {
        assertEquals(0.25f, TopOutConfig.PoseConfidence, 0.0001f)
    }

    @Test
    fun acceptsExactlyFourRequiredJoints() {
        assertTrue(
            PoseHumanGate.isValid(
                setOf(
                    PoseLandmark.LEFT_SHOULDER,
                    PoseLandmark.LEFT_HIP,
                    PoseLandmark.LEFT_ELBOW,
                    PoseLandmark.LEFT_KNEE
                )
            )
        )
    }

    @Test
    fun rejectsPoseWithoutShoulder() {
        assertFalse(
            PoseHumanGate.isValid(
                setOf(
                    PoseLandmark.LEFT_HIP,
                    PoseLandmark.LEFT_ELBOW,
                    PoseLandmark.LEFT_KNEE,
                    PoseLandmark.LEFT_WRIST,
                    PoseLandmark.LEFT_ANKLE
                )
            )
        )
    }

    @Test
    fun rejectsPoseWithoutHip() {
        assertFalse(
            PoseHumanGate.isValid(
                setOf(
                    PoseLandmark.LEFT_SHOULDER,
                    PoseLandmark.LEFT_ELBOW,
                    PoseLandmark.LEFT_KNEE,
                    PoseLandmark.LEFT_WRIST,
                    PoseLandmark.LEFT_ANKLE
                )
            )
        )
    }

    @Test
    fun rejectsPoseWithoutElbow() {
        assertFalse(
            PoseHumanGate.isValid(
                setOf(
                    PoseLandmark.LEFT_SHOULDER,
                    PoseLandmark.LEFT_HIP,
                    PoseLandmark.LEFT_KNEE,
                    PoseLandmark.LEFT_WRIST,
                    PoseLandmark.LEFT_ANKLE
                )
            )
        )
    }

    @Test
    fun rejectsPoseWithoutKnee() {
        assertFalse(
            PoseHumanGate.isValid(
                setOf(
                    PoseLandmark.LEFT_SHOULDER,
                    PoseLandmark.LEFT_HIP,
                    PoseLandmark.LEFT_ELBOW,
                    PoseLandmark.LEFT_WRIST,
                    PoseLandmark.LEFT_ANKLE
                )
            )
        )
    }

    @Test
    fun extraJointsAreOptional() {
        assertTrue(
            PoseHumanGate.isValid(
                setOf(
                    PoseLandmark.RIGHT_SHOULDER,
                    PoseLandmark.RIGHT_HIP,
                    PoseLandmark.RIGHT_ELBOW,
                    PoseLandmark.RIGHT_KNEE,
                    PoseLandmark.LEFT_WRIST,
                    PoseLandmark.RIGHT_ANKLE
                )
            )
        )
    }
}
