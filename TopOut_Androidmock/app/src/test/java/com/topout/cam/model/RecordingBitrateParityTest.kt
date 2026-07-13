package com.topout.cam.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingBitrateParityTest {
    @Test
    fun storyBitratesMatchIosRecorder() {
        assertEquals(
            8_000_000L,
            RecordingResolution.FHD.estimatedBitrate(
                RecordingFrameRate.FPS30,
                RecordingAspectRatio.STORY_9_16
            )
        )
        assertEquals(
            14_000_000L,
            RecordingResolution.FHD.estimatedBitrate(
                RecordingFrameRate.FPS60,
                RecordingAspectRatio.STORY_9_16
            )
        )
        assertEquals(
            45_000_000L,
            RecordingResolution.UHD.estimatedBitrate(
                RecordingFrameRate.FPS30,
                RecordingAspectRatio.STORY_9_16
            )
        )
        assertEquals(
            70_000_000L,
            RecordingResolution.UHD.estimatedBitrate(
                RecordingFrameRate.FPS60,
                RecordingAspectRatio.STORY_9_16
            )
        )
    }

    @Test
    fun croppedAspectRatiosUseIosPixelScaling() {
        assertEquals(
            7_875_000L,
            RecordingResolution.FHD.estimatedBitrate(
                RecordingFrameRate.FPS60,
                RecordingAspectRatio.SQUARE_1_1
            )
        )
    }
}
