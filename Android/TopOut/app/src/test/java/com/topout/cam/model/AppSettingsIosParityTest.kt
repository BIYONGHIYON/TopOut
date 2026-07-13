package com.topout.cam.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsIosParityTest {
    @Test
    fun defaultsMatchCompletedIosApp() {
        val settings = AppSettings()

        assertEquals(RecordingResolution.FHD, settings.resolution)
        assertEquals(RecordingFrameRate.FPS60, settings.frameRate)
        assertEquals(RecordingAspectRatio.STORY_9_16, settings.aspectRatio)
        assertTrue(settings.autoClipDetectionEnabled)
        assertFalse(settings.topHoldMaskingEnabled)
        assertTrue(settings.helpEnabled)
        assertFalse(settings.livePreviewDuringRecordingEnabled)
    }

    @Test
    fun topHoldSelectionDependsOnlyOnMasking() {
        assertFalse(
            AppSettings(autoClipDetectionEnabled = true, topHoldMaskingEnabled = false)
                .requiresTopHoldSelection
        )
        assertTrue(
            AppSettings(autoClipDetectionEnabled = false, topHoldMaskingEnabled = true)
                .requiresTopHoldSelection
        )
    }

    @Test
    fun normalizationDoesNotForceFeatureSwitchesTogether() {
        val autoOnly = AppSettings(
            autoClipDetectionEnabled = true,
            topHoldMaskingEnabled = false
        ).normalized()
        val maskOnly = AppSettings(
            autoClipDetectionEnabled = false,
            topHoldMaskingEnabled = true
        ).normalized()

        assertTrue(autoOnly.autoClipDetectionEnabled)
        assertFalse(autoOnly.topHoldMaskingEnabled)
        assertFalse(maskOnly.autoClipDetectionEnabled)
        assertTrue(maskOnly.topHoldMaskingEnabled)
    }

    @Test
    fun zoomAndDetectionConstantsMatchCurrentPolicy() {
        assertEquals(0.5f, TopOutConfig.MinZoom, 0.0001f)
        assertEquals(1f, TopOutConfig.SelectionMinZoom, 0.0001f)
        assertEquals(5f, TopOutConfig.SelectionMaxZoom, 0.0001f)
        assertEquals(8f, TopOutConfig.CameraMaxZoom, 0.0001f)
        assertEquals(4.0, TopOutConfig.FullFrameMotionFps, 0.0001)
        assertEquals(2.0, TopOutConfig.PersonDetectorFps, 0.0001)
        assertEquals(512, TopOutConfig.PersonDetectorInputMaxLongSide)
        assertEquals(1.0, TopOutConfig.PersonSearchWindowSeconds, 0.0001)
        assertEquals(5.0, TopOutConfig.PersonLostTimeoutSeconds, 0.0001)
        assertEquals(0.25f, TopOutConfig.PersonDetectorScoreThreshold, 0.0001f)
        assertEquals(0.05f, TopOutConfig.PersonMatClearanceRatio, 0.0001f)
        assertEquals(256, TopOutConfig.MaskSize)
        assertEquals(0.48f, TopOutConfig.RoundedCornerRatio, 0.0001f)
        assertEquals(0.016f, TopOutConfig.RedLineRatio, 0.0001f)
        assertEquals(0.006f, TopOutConfig.WhiteLineRatio, 0.0001f)
        assertEquals(8f, TopOutConfig.MinRedLineWidth, 0.0001f)
        assertEquals(2.5f, TopOutConfig.MinWhiteLineWidth, 0.0001f)
    }
}
