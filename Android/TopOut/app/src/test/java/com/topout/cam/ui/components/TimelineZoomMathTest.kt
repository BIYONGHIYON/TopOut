package com.topout.cam.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineZoomMathTest {
    @Test
    fun maximumZoomIgnoresFloatingPointNoiseButPreservesRealOverflow() {
        assertEquals(4.0, TimelineZoomMath.stableMaximumZoomFactor(4.0, 4.00005), 0.000001)
        assertEquals(4.001, TimelineZoomMath.stableMaximumZoomFactor(4.0, 4.001), 0.000001)
    }

    @Test
    fun trackTapMapsEntireUsableWidthToZoomRange() {
        assertEquals(
            1.0,
            TimelineZoomMath.valueForTrackPosition(10.0, 210.0, 1.0, 5.0, 10.0),
            0.0001
        )
        assertEquals(
            3.0,
            TimelineZoomMath.valueForTrackPosition(105.0, 210.0, 1.0, 5.0, 10.0),
            0.0001
        )
        assertEquals(
            5.0,
            TimelineZoomMath.valueForTrackPosition(200.0, 210.0, 1.0, 5.0, 10.0),
            0.0001
        )
    }

    @Test
    fun maximumZoomKeepsBothHandlesInsideWithMargin() {
        val viewport = TimelineZoomMath.viewportForSelectedRange(
            durationSeconds = 60.0,
            lowerSeconds = 20.0,
            upperSeconds = 30.0,
            requestedZoomFactor = 99.0,
            minimumSelectionSeconds = 0.1
        )
        assertTrue(20.0 > viewport.startSeconds)
        assertTrue(30.0 < viewport.endSeconds)
        assertEquals(25.0, viewport.startSeconds + viewport.spanSeconds * 0.5, 0.0001)
    }

    @Test
    fun selectionNearVideoStartNeverMovesHandleOutsideTrack() {
        val viewport = TimelineZoomMath.viewportForSelectedRange(
            durationSeconds = 40.0,
            lowerSeconds = 0.0,
            upperSeconds = 4.0,
            requestedZoomFactor = 99.0,
            minimumSelectionSeconds = 0.1
        )
        assertEquals(0.0, viewport.startSeconds, 0.0001)
        assertTrue(4.0 <= viewport.endSeconds)
    }

    @Test
    fun fullLengthSelectionDisablesMeaningfulZoom() {
        val maxZoom = TimelineZoomMath.maximumZoomFactor(
            durationSeconds = 30.0,
            lowerSeconds = 0.0,
            upperSeconds = 30.0,
            minimumSelectionSeconds = 0.1
        )
        assertEquals(1.0, maxZoom, 0.0001)
    }
    @Test
    fun trimmingAfterZoomPreservesViewportSpanAndZoomFactor() {
        val viewport = TimelineZoomMath.viewportPreservingZoom(
            durationSeconds = 60.0,
            lowerSeconds = 22.0,
            upperSeconds = 31.0,
            currentSpanSeconds = 15.0,
            minimumSelectionSeconds = 0.1
        )
        assertEquals(15.0, viewport.spanSeconds, 0.0001)
        assertEquals(4.0, viewport.zoomFactor, 0.0001)
        assertTrue(22.0 >= viewport.startSeconds)
        assertTrue(31.0 <= viewport.endSeconds)
    }

}
