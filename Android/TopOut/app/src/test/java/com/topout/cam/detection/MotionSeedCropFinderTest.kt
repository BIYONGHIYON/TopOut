package com.topout.cam.detection

import kotlin.math.abs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionSeedCropFinderTest {
    @Test
    fun expandsStrongestMotionBoundsByOneAndAHalf() {
        val width = 80
        val height = 160
        val previous = ByteArray(width * height) { 40 }
        val current = previous.copyOf()
        fill(current, width, left = 50, top = 30, right = 60, bottom = 40, value = 220)

        val crop = assertPresent(
            MotionSeedCropFinder.find(width, height, current, previous)
        )

        val cropLeft = crop.xMin * width
        val cropTop = crop.yMin * height
        val cropRight = cropLeft + crop.width * width
        val cropBottom = cropTop + crop.height * height
        assertTrue(cropLeft <= 50f)
        assertTrue(cropTop <= 30f)
        assertTrue(cropRight >= 60f)
        assertTrue(cropBottom >= 40f)
        assertTrue(abs(crop.width * width - crop.height * height) < 0.01f)
        assertTrue(abs(crop.width * width - 15f) < 0.01f)
    }

    @Test
    fun containsNearbyHighMotionCellsInOneCrop() {
        val width = 80
        val height = 160
        val previous = ByteArray(width * height) { 80 }
        val current = previous.copyOf()
        fill(current, width, 20, 40, 30, 50, 200)
        fill(current, width, 40, 60, 50, 70, 210)

        val crop = assertPresent(
            MotionSeedCropFinder.find(width, height, current, previous)
        )
        val left = crop.xMin * width
        val top = crop.yMin * height
        val right = left + crop.width * width
        val bottom = top + crop.height * height
        assertTrue(left <= 20f)
        assertTrue(top <= 40f)
        assertTrue(right >= 50f)
        assertTrue(bottom >= 70f)
    }

    @Test
    fun fallbackCanRestrictScoringToTheMotionTriggerBand() {
        val width = 80
        val height = 160
        val previous = ByteArray(width * height) { 50 }
        val current = previous.copyOf()
        // Stronger distraction outside the allowed band.
        fill(current, width, 4, 5, 35, 55, 230)
        // Trigger motion inside the allowed band.
        fill(current, width, 50, 90, 75, 125, 210)
        val top = IntArray(width) { 80 }
        val bottom = IntArray(width) { 140 }

        val crop = assertPresent(
            MotionSeedCropFinder.find(
                width = width,
                height = height,
                currentLuma = current,
                previousLuma = previous,
                activeTopByColumn = top,
                activeBottomExclusiveByColumn = bottom
            )
        )

        val cropCenterX = (crop.xMin + crop.width * 0.5f) * width
        val cropCenterY = (crop.yMin + crop.height * 0.5f) * height
        assertTrue(cropCenterX > width * 0.5f)
        assertTrue(cropCenterY > height * 0.5f)
    }

    @Test
    fun returnsNullWhenImmediatePairHasNoMeaningfulMotion() {
        val frame = ByteArray(40 * 80) { 100 }
        val crop = MotionSeedCropFinder.find(
            width = 40,
            height = 80,
            currentLuma = frame,
            previousLuma = frame.copyOf()
        )
        assertTrue(crop == null)
    }

    @Test
    fun rejectsMismatchedBuffers() {
        val crop = MotionSeedCropFinder.find(
            width = 10,
            height = 10,
            currentLuma = ByteArray(100),
            previousLuma = ByteArray(20)
        )
        assertTrue(crop == null)
    }

    private fun fill(
        buffer: ByteArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        value: Int
    ) {
        for (y in top until bottom) {
            for (x in left until right) {
                buffer[y * width + x] = value.toByte()
            }
        }
    }

    private fun <T : Any> assertPresent(value: T?): T {
        assertNotNull(value)
        return requireNotNull(value)
    }
}
