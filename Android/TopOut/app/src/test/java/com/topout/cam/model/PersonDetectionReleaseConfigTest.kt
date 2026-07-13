package com.topout.cam.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonDetectionReleaseConfigTest {
    @Test
    fun releaseDetectorUsesExpectedAssetAndCpuFriendlyCadence() {
        assertEquals(
            "models/efficientdet_lite2_float16.tflite",
            TopOutConfig.PersonDetectorModelAssetPath
        )
        assertEquals(4.0, TopOutConfig.FullFrameMotionFps, 0.0001)
        assertEquals(2.0, TopOutConfig.PersonDetectorFps, 0.0001)
        assertTrue(TopOutConfig.FullFrameMotionFps >= TopOutConfig.PersonDetectorFps)
    }

    @Test
    fun searchAndLostTimeoutsStayIndependent() {
        assertEquals(1.0, TopOutConfig.PersonSearchWindowSeconds, 0.0001)
        assertEquals(5.0, TopOutConfig.PersonLostTimeoutSeconds, 0.0001)
        assertTrue(TopOutConfig.PersonLostTimeoutSeconds > TopOutConfig.PersonSearchWindowSeconds)
    }
}
