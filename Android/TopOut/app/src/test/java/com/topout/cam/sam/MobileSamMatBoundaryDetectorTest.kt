package com.topout.cam.sam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MobileSamMatBoundaryDetectorTest {
    @Test
    fun selectsHighestValidLowerHalfMask() {
        val wall = rectangleMask(left = 10, right = 90, top = 20, bottom = 99)
        val mat = rectangleMask(left = 10, right = 90, top = 62, bottom = 99)
        val floor = rectangleMask(left = 0, right = 99, top = 88, bottom = 99)

        val line = MobileSamMatBoundaryDetector.select(
            listOf(
                MobileSamMatBoundaryDetector.PromptResult(0.70f, listOf(wall, mat)),
                MobileSamMatBoundaryDetector.PromptResult(0.90f, listOf(floor))
            )
        )

        assertNotNull(line)
        assertEquals(0.626f, line!![0].y, 0.01f)
        assertEquals(0.626f, line[1].y, 0.01f)
    }

    @Test
    fun rejectsMaskNarrowerThanFortyPercent() {
        val narrow = rectangleMask(left = 31, right = 69, top = 60, bottom = 99)
        val line = MobileSamMatBoundaryDetector.select(
            listOf(MobileSamMatBoundaryDetector.PromptResult(0.70f, listOf(narrow)))
        )
        assertNull(line)
    }

    @Test
    fun rejectsMaskWhoseTopEntersUpperHalf() {
        val upper = rectangleMask(left = 10, right = 90, top = 49, bottom = 99)
        val line = MobileSamMatBoundaryDetector.select(
            listOf(MobileSamMatBoundaryDetector.PromptResult(0.70f, listOf(upper)))
        )
        assertNull(line)
    }

    @Test
    fun rejectsMaskWhoseUpperBoundaryTiltsMoreThanFifteenDegrees() {
        val steep = slopedMask(left = 10, right = 90, leftTop = 52, rightTop = 92, bottom = 99)
        val line = MobileSamMatBoundaryDetector.select(
            listOf(MobileSamMatBoundaryDetector.PromptResult(0.70f, listOf(steep)))
        )
        assertNull(line)
    }

    @Test
    fun acceptsModeratelyTiltedUpperBoundary() {
        val moderate = slopedMask(left = 10, right = 90, leftTop = 60, rightTop = 72, bottom = 99)
        val line = MobileSamMatBoundaryDetector.select(
            listOf(MobileSamMatBoundaryDetector.PromptResult(0.70f, listOf(moderate)))
        )
        assertNotNull(line)
    }

    private fun slopedMask(
        left: Int,
        right: Int,
        leftTop: Int,
        rightTop: Int,
        bottom: Int,
        width: Int = 100,
        height: Int = 100
    ): MobileSamMatBoundaryDetector.SamBinaryMask {
        val values = BooleanArray(width * height)
        val span = (right - left).coerceAtLeast(1)
        for (x in left..right) {
            val progress = (x - left).toFloat() / span
            val top = (leftTop + (rightTop - leftTop) * progress).toInt().coerceIn(0, bottom)
            for (y in top..bottom) values[y * width + x] = true
        }
        return MobileSamMatBoundaryDetector.SamBinaryMask(
            width = width,
            height = height,
            visibleWidth = width,
            visibleHeight = height,
            positiveX = 0.50f,
            values = values
        )
    }

    private fun rectangleMask(
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
        width: Int = 100,
        height: Int = 100
    ): MobileSamMatBoundaryDetector.SamBinaryMask {
        val values = BooleanArray(width * height)
        for (y in top..bottom) {
            for (x in left..right) values[y * width + x] = true
        }
        return MobileSamMatBoundaryDetector.SamBinaryMask(
            width = width,
            height = height,
            visibleWidth = width,
            visibleHeight = height,
            positiveX = 0.50f,
            values = values
        )
    }
}
