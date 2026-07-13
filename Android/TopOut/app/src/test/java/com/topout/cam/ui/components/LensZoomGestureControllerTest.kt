package com.topout.cam.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class LensZoomGestureControllerTest {
    private fun controller() = LensZoomGestureController(
        minimumZoom = 0.5f,
        maximumZoom = 8f,
        lensAnchors = listOf(0.5f, 1f, 3f)
    )

    @Test
    fun zoomInStopsBeforeEachNextLens() {
        val controller = controller()

        controller.begin(0.5f)
        assertEquals(0.9f, controller.update(2f), 0.0001f)
        assertEquals(0.9f, controller.update(2f), 0.0001f)

        controller.begin(0.9f)
        assertEquals(1f, controller.update(1.02f), 0.0001f)
        assertEquals(2f, controller.update(2f), 0.0001f)
        assertEquals(2.9f, controller.update(2f), 0.0001f)

        controller.begin(2.9f)
        assertEquals(3f, controller.update(1.02f), 0.0001f)
        assertEquals(6f, controller.update(2f), 0.0001f)
    }

    @Test
    fun zoomOutRequiresNewGestureAtLensAnchor() {
        val controller = controller()

        controller.begin(5f)
        assertEquals(3f, controller.update(0.5f), 0.0001f)

        controller.begin(3f)
        assertEquals(2.9f, controller.update(0.98f), 0.0001f)
        assertEquals(1.45f, controller.update(0.5f), 0.0001f)
        assertEquals(1f, controller.update(0.5f), 0.0001f)

        controller.begin(1f)
        assertEquals(0.9f, controller.update(0.98f), 0.0001f)
        assertEquals(0.5f, controller.update(0.5f), 0.0001f)
    }

    @Test
    fun regularPinchCanReverseInsideOneLensSection() {
        val controller = controller()

        controller.begin(1.5f)
        assertEquals(2.25f, controller.update(1.5f), 0.0001f)
        assertEquals(1.125f, controller.update(0.5f), 0.0001f)
        assertEquals(1f, controller.update(0.5f), 0.0001f)
    }

    @Test
    fun rebaseAfterLensLoadStartsAgainFromTransitionBoundary() {
        val controller = controller()

        controller.begin(0.9f)
        assertEquals(1f, controller.update(1.02f), 0.0001f)

        // Scale events that happen while CameraX loads the normal lens are intentionally not sent
        // to the controller. When STREAMING resumes, CameraPreview begins again at exactly 1.0x.
        controller.begin(1f)
        assertEquals(1f, controller.update(1f), 0.0001f)
        assertEquals(1.1f, controller.update(1.1f), 0.0001f)
    }
}
