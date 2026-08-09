package com.topout.cam.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryClipSelectionTest {
    @Test
    fun matchesSameTrimWithinPlayerRoundingTolerance() {
        val saved = GalleryClipSelection(12.345, 34.567, includeOverlay = false)
        val current = GalleryClipSelection(12.349, 34.571, includeOverlay = false)

        assertTrue(saved.matches(current))
    }

    @Test
    fun rejectsDifferentTrimOrOverlayChoice() {
        val saved = GalleryClipSelection(12.0, 34.0, includeOverlay = false)

        assertFalse(saved.matches(GalleryClipSelection(12.02, 34.0, includeOverlay = false)))
        assertFalse(saved.matches(GalleryClipSelection(12.0, 34.0, includeOverlay = true)))
    }
}
