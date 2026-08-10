package com.topout.cam.ui.components

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LensZoomGestureControllerTest {
    private fun controller() = LensZoomGestureController(
        minimumZoom = 0.5f,
        maximumZoom = 8f,
        lensAnchors = listOf(0.5f, 1f, 3f),
        zoomSensitivity = 1f
    )

    @Test
    fun zoomInRequestsEachNextLensWithoutFingerRelease() {
        val controller = controller()

        controller.begin(0.5f)
        assertEquals(0.75f, controller.update(1.5f), 0.0001f)
        assertEquals(1f, controller.update(1.3f), 0.0001f)
        assertEquals(1f, controller.update(0.5f), 0.0001f)

        // CameraPreview performs this begin() when the new STREAMING token arrives; the user's
        // fingers can remain down throughout the rebase.
        controller.begin(1f)
        assertEquals(2f, controller.update(2f), 0.0001f)
        assertEquals(3f, controller.update(1.5f), 0.0001f)

        controller.begin(3f)
        assertEquals(6f, controller.update(2f), 0.0001f)
    }

    @Test
    fun zoomOutRequestsEachPreviousLensWithoutFingerRelease() {
        val controller = controller()

        controller.begin(5f)
        assertEquals(3.5f, controller.update(0.7f), 0.0001f)
        assertEquals(2.9f, controller.update(0.8f), 0.0001f)
        assertEquals(2.9f, controller.update(2f), 0.0001f)

        controller.begin(2.9f)
        assertEquals(1.45f, controller.update(0.5f), 0.0001f)
        assertEquals(0.9f, controller.update(0.5f), 0.0001f)

        controller.begin(0.9f)
        assertEquals(0.5f, controller.update(0.5f), 0.0001f)
    }

    @Test
    fun regularPinchCanReverseInsideOneLensSection() {
        val controller = controller()

        controller.begin(1.5f)
        assertEquals(2.25f, controller.update(1.5f), 0.0001f)
        assertEquals(1.125f, controller.update(0.5f), 0.0001f)
        assertEquals(0.9f, controller.update(0.8f), 0.0001f)
        assertEquals(0.9f, controller.update(2f), 0.0001f)

        controller.begin(0.9f)
        assertEquals(1f, controller.update(1.02f), 0.0001f)
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

    @Test
    fun exactGapEdgeRemainsOnCurrentLensUntilCrossed() {
        val controller = controller()

        controller.begin(0.5f)
        assertEquals(0.9f, controller.update(1.8f), 0.0001f)
        assertEquals(1f, controller.update(1.0001f), 0.0001f)
    }

    @Test
    fun seamlessLogicalCameraDoesNotCreateArtificialOneXBoundary() {
        val controller = LensZoomGestureController(
            minimumZoom = 0.5f,
            maximumZoom = 8f,
            lensAnchors = listOf(0.5f),
            zoomSensitivity = 1f
        )

        controller.begin(0.5f)
        assertEquals(1f, controller.update(2f), 0.0001f)
        assertEquals(2f, controller.update(2f), 0.0001f)
    }

    @Test
    fun hybridLogicalWideAndSeparateTelephotoSwitchesOnlyAtTelephoto() {
        val controller = LensZoomGestureController(
            minimumZoom = 0.5f,
            maximumZoom = 8f,
            lensAnchors = listOf(0.5f, 3f),
            zoomSensitivity = 1f
        )

        controller.begin(0.5f)
        assertEquals(1f, controller.update(2f), 0.0001f)
        assertEquals(2f, controller.update(2f), 0.0001f)
        assertEquals(3f, controller.update(1.5f), 0.0001f)
        assertEquals(3f, controller.update(0.5f), 0.0001f)

        controller.begin(3f)
        assertEquals(6f, controller.update(2f), 0.0001f)

        controller.begin(6f)
        assertEquals(3f, controller.update(0.5f), 0.0001f)
        assertEquals(2.9f, controller.update(0.99f), 0.0001f)
        assertEquals(2.9f, controller.update(2f), 0.0001f)

        controller.begin(2.9f)
        assertEquals(1.45f, controller.update(0.5f), 0.0001f)
        assertEquals(0.725f, controller.update(0.5f), 0.0001f)
    }

    @Test
    fun hybridLogicalWideAndMultipleTelephotosCrossEveryExternalRoute() {
        val controller = LensZoomGestureController(
            minimumZoom = 0.5f,
            maximumZoom = 8f,
            lensAnchors = listOf(0.5f, 3f, 5f),
            zoomSensitivity = 1f
        )

        controller.begin(0.5f)
        assertEquals(1f, controller.update(2f), 0.0001f)
        assertEquals(2f, controller.update(2f), 0.0001f)
        assertEquals(3f, controller.update(1.5f), 0.0001f)

        controller.begin(3f)
        assertEquals(4.5f, controller.update(1.5f), 0.0001f)
        assertEquals(5f, controller.update(1.1f), 0.0001f)

        controller.begin(5f)
        assertEquals(6f, controller.update(1.2f), 0.0001f)
        assertEquals(4.9f, controller.update(0.8f), 0.0001f)

        controller.begin(4.9f)
        assertEquals(3.43f, controller.update(0.7f), 0.0001f)
        assertEquals(2.9f, controller.update(0.8f), 0.0001f)

        controller.begin(2.9f)
        assertEquals(1.45f, controller.update(0.5f), 0.0001f)
        assertEquals(0.725f, controller.update(0.5f), 0.0001f)
    }

    @Test
    fun defaultSensitivitySoftensZoomInAndOutSymmetrically() {
        val controller = LensZoomGestureController(
            minimumZoom = 0.5f,
            maximumZoom = 8f,
            lensAnchors = listOf(0.5f)
        )

        controller.begin(1f)
        assertEquals(2.0.pow(0.95).toFloat(), controller.update(2f), 0.0001f)

        controller.begin(2f)
        assertEquals(2f * 0.5.pow(0.95).toFloat(), controller.update(0.5f), 0.0001f)
    }

    @Test
    fun defaultSensitivityStillTransitionsAndHoldsAtLensBoundaries() {
        val controller = LensZoomGestureController(
            minimumZoom = 0.5f,
            maximumZoom = 8f,
            lensAnchors = listOf(0.5f, 1f, 3f)
        )

        controller.begin(0.5f)
        assertEquals(1f, controller.update(2f), 0.0001f)
        assertEquals(1f, controller.update(0.5f), 0.0001f)

        controller.begin(1f)
        assertEquals(2.0.pow(0.95).toFloat(), controller.update(2f), 0.0001f)
        assertEquals(3f, controller.update(1.8f), 0.0001f)

        controller.begin(3f)
        assertEquals(3f * 2.0.pow(0.95).toFloat(), controller.update(2f), 0.0001f)
    }

    @Test
    fun deferredZoomInKeepsScalingInsideTheNextCameraSection() {
        val controller = controller()

        controller.begin(0.5f)
        assertEquals(1f, controller.update(2f), 0.0001f)
        assertTrue(controller.hasContinuablePendingTransition())
        assertEquals(1.5f, controller.updatePendingTransition(1.5f), 0.0001f)
        assertEquals(2.9f, controller.updatePendingTransition(2f), 0.0001f)
        assertEquals(1.5f, controller.updatePendingTransition(0.5f), 0.0001f)
        assertEquals(0.75f, controller.updatePendingTransition(0.5f), 0.0001f)
    }

    @Test
    fun zoomOutPendingTransitionRemainsLockedDuringImmediateRebind() {
        val controller = controller()

        controller.begin(1f)
        assertEquals(0.9f, controller.update(0.99f), 0.0001f)
        assertFalse(controller.hasContinuablePendingTransition())
    }
}
