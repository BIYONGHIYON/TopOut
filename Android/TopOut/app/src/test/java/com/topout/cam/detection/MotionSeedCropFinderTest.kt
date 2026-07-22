package com.topout.cam.detection

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MotionSeedCropFinderTest {
    @Test
    fun findsQuarterFrameSquareAroundStrongestMotionCluster() {
        val width = 80
        val height = 160
        val previous = ByteArray(width * height) { 40 }
        val current = previous.copyOf()
        fill(current, width, left = 55, top = 25, right = 78, bottom = 65, value = 220)

        val crop = assertNotNull(
            MotionSeedCropFinder.find(width, height, current, previous)
        )

        val cropLeft = crop.xMin * width
        val cropTop = crop.yMin * height
        val cropRight = cropLeft + crop.width * width
        val cropBottom = cropTop + crop.height * height
        assertTrue(cropLeft <= 55f)
        assertTrue(cropTop <= 25f)
        assertTrue(cropRight >= 78f)
        assertTrue(cropBottom >= 65f)
        assertTrue(abs(crop.width * width - crop.height * height) < 0.01f)
        assertTrue(abs(crop.width * crop.height - 0.25f) < 0.03f)
    }

    @Test
    fun containsNearbyHighMotionCellsInOneCrop() {
        val width = 120
        val height = 160
        val previous = ByteArray(width * height) { 80 }
        val current = previous.copyOf()
        fill(current, width, 30, 60, 48, 82, 200)
        fill(current, width, 62, 72, 82, 98, 210)

        val crop = assertNotNull(
            MotionSeedCropFinder.find(width, height, current, previous)
        )
        val left = crop.xMin * width
        val top = crop.yMin * height
        val right = left + crop.width * width
        val bottom = top + crop.height * height
        assertTrue(left <= 30f)
        assertTrue(top <= 60f)
        assertTrue(right >= 82f)
        assertTrue(bottom >= 98f)
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

        val crop = assertNotNull(
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
}
