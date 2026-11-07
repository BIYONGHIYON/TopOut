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
    fun zoomAndVisionConstantsMatchCurrentPolicy() {
        assertEquals(0.5f, TopOutConfig.MinZoom, 0.0001f)
        assertEquals(1f, TopOutConfig.SelectionMinZoom, 0.0001f)
        assertEquals(5f, TopOutConfig.SelectionMaxZoom, 0.0001f)
        assertEquals(8f, TopOutConfig.CameraMaxZoom, 0.0001f)
        assertEquals(5_000L, TopOutConfig.MatBoundaryAnalysisTimeoutMillis)
        assertEquals(3.0, TopOutConfig.ReviewEndPreviewLeadSeconds, 0.0001)
        assertEquals(0.06f, TopOutConfig.PoseStartAboveMatVideoHeightRatio, 0.0001f)
        assertEquals(0.04f, TopOutConfig.PoseFinishAboveMatVideoHeightRatio, 0.0001f)
        assertEquals(256, TopOutConfig.MaskSize)
        // 탑홀드 최소 표시 원: 짧은 변의 3.75%, 22px 이상, 6% 이하 (iOS Config와 같다).
        assertEquals(0.0375f, TopOutConfig.FallbackHoldMaskDiameterRatio, 0.0001f)
        assertEquals(22f, TopOutConfig.FallbackHoldMaskMinDiameter, 0.0001f)
        assertEquals(0.06f, TopOutConfig.FallbackHoldMaskMaxDiameterRatio, 0.0001f)
        assertEquals(0.016f, TopOutConfig.RedLineRatio, 0.0001f)
        assertEquals(0.006f, TopOutConfig.WhiteLineRatio, 0.0001f)
        assertEquals(8f, TopOutConfig.MinRedLineWidth, 0.0001f)
        assertEquals(2.5f, TopOutConfig.MinWhiteLineWidth, 0.0001f)
    }
}
