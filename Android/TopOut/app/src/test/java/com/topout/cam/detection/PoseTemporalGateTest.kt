package com.topout.cam.detection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.topout.cam.model.TopOutConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseTemporalGateTest {
    private val box = Rect(0.2f, 0.2f, 0.6f, 0.8f)

    @Test
    fun threeLandmarksPastFourPercentPassStartMotion() {
        val reference = mapOf(
            0 to Offset(0.30f, 0.30f),
            1 to Offset(0.35f, 0.40f),
            2 to Offset(0.40f, 0.50f)
        )
        val current = mapOf(
            0 to Offset(0.34f, 0.30f),
            1 to Offset(0.39f, 0.40f),
            2 to Offset(0.44f, 0.50f)
        )
        assertEquals(
            3,
            PoseTemporalGate.movedLandmarkCount(
                box,
                reference,
                box,
                current,
                TopOutConfig.PoseStartMotionMinimumBoxDiagonalRatio
            )
        )
    }

    @Test
    fun onlyTwoMovedLandmarksDoNotPassStartMotion() {
        val reference = mapOf(
            0 to Offset(0.30f, 0.30f),
            1 to Offset(0.35f, 0.40f),
            2 to Offset(0.40f, 0.50f)
        )
        val current = mapOf(
            0 to Offset(0.34f, 0.30f),
            1 to Offset(0.39f, 0.40f),
            2 to Offset(0.41f, 0.50f)
        )
        val moved = PoseTemporalGate.movedLandmarkCount(
            box,
            reference,
            box,
            current,
            TopOutConfig.PoseStartMotionMinimumBoxDiagonalRatio
        )
        assertEquals(2, moved)
        assertTrue(moved < TopOutConfig.PoseStartMotionMinimumLandmarks)
    }

    @Test
    fun relaxedMaintenanceExpiresAfterEightSeconds() {
        assertTrue(
            PoseTemporalGate.canUseRelaxedMaintenance(
                seconds = 18.0,
                lastStrictPoseSeconds = 10.0,
                maximumRelaxedSeconds = TopOutConfig.PoseMaintenanceMaximumRelaxedSeconds
            )
        )
        assertFalse(
            PoseTemporalGate.canUseRelaxedMaintenance(
                seconds = 18.01,
                lastStrictPoseSeconds = 10.0,
                maximumRelaxedSeconds = TopOutConfig.PoseMaintenanceMaximumRelaxedSeconds
            )
        )
    }
}
