package com.topout.cam.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PoseReleaseConfigTest {
    @Test
    fun poseMatClearanceUsesTenPercentBoundary() {
        assertEquals(0.05f, TopOutConfig.PoseMatClearanceRatio, 0.0001f)
    }

    @Test
    fun motionWakeBandRemainsFivePercent() {
        assertEquals(0.05f, TopOutConfig.MotionMatBandHeightRatio, 0.0001f)
    }
}
