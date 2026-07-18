package com.topout.cam.detection

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseSmartCropMotionGateTest {
    @Test
    fun enablesAfterThreeFramesWhenThreeCommonJointsMoveFivePercentOfPersonSize() {
        val gate = PoseSmartCropMotionGate()

        assertFalse(gate.observe(frame(shifted = emptySet(), amount = 0f)).shouldUnlock)
        assertFalse(gate.observe(frame(shifted = setOf(5, 6, 11), amount = 0.025f)).shouldUnlock)
        assertTrue(gate.observe(frame(shifted = setOf(5, 6, 11), amount = 0.055f)).shouldUnlock)
    }

    @Test
    fun remainsDisabledWhenOnlyTwoJointsMoveEnough() {
        val gate = PoseSmartCropMotionGate()

        assertFalse(gate.observe(frame(shifted = emptySet(), amount = 0f)).shouldUnlock)
        assertFalse(gate.observe(frame(shifted = setOf(5, 6), amount = 0.03f)).shouldUnlock)
        assertFalse(gate.observe(frame(shifted = setOf(5, 6), amount = 0.06f)).shouldUnlock)
    }

    @Test
    fun ignoresJointsThatAreNotPresentInAllThreeFrames() {
        val gate = PoseSmartCropMotionGate()

        assertFalse(gate.observe(frame(shifted = emptySet(), amount = 0f)).shouldUnlock)
        assertFalse(gate.observe(frame(shifted = setOf(5, 6, 11), amount = 0.03f) - 11).shouldUnlock)
        assertFalse(gate.observe(frame(shifted = setOf(5, 6, 11), amount = 0.06f)).shouldUnlock)
    }

    @Test
    fun resetsWindowWhenPoseHasNoMeasurablePersonSize() {
        val gate = PoseSmartCropMotionGate()

        assertFalse(gate.observe(frame(shifted = emptySet(), amount = 0f)).shouldUnlock)
        assertFalse(gate.observe(mapOf(5 to Offset(0.5f, 0.5f))).shouldUnlock)
        assertFalse(gate.observe(frame(shifted = setOf(5, 6, 11), amount = 0.06f)).shouldUnlock)
    }

    private fun frame(shifted: Set<Int>, amount: Float): Map<Int, Offset> {
        val base = linkedMapOf(
            5 to Offset(0.20f, 0.20f),
            6 to Offset(0.80f, 0.20f),
            11 to Offset(0.20f, 0.80f),
            12 to Offset(0.80f, 0.80f)
        )
        return base.mapValues { (type, point) ->
            if (type in shifted) Offset(point.x + amount, point.y) else point
        }
    }
}
