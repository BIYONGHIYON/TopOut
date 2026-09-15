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
    fun rejectsMaskWhoseUpperLineSupportsLessThanSixtyPercent() {
        val narrow = rectangleMask(left = 21, right = 79, top = 60, bottom = 99)
        val line = MobileSamMatBoundaryDetector.select(
            listOf(MobileSamMatBoundaryDetector.PromptResult(0.70f, listOf(narrow)))
        )
        assertNull(line)
    }

    @Test
    fun acceptsDisconnectedFragmentsWhoseUpperEdgesSupportSixtyPercent() {
        val fragmented = fragmentedMask(
            ranges = listOf(5..24, 40..59, 75..94),
            top = 64,
            bottom = 99
        )

        val line = MobileSamMatBoundaryDetector.select(
            listOf(MobileSamMatBoundaryDetector.PromptResult(0.70f, listOf(fragmented)))
        )

        assertNotNull(line)
        assertEquals(0.646f, line!![0].y, 0.01f)
        assertEquals(0.646f, line[1].y, 0.01f)
    }

    @Test
    fun rejectsDisconnectedFragmentsThatDoNotSupportOneLine() {
        val values = BooleanArray(100 * 100)
        listOf(5..24, 75..94).forEach { range ->
            for (x in range) for (y in 58..99) values[y * 100 + x] = true
        }
        for (x in 40..59) for (y in 70..99) values[y * 100 + x] = true
        val fragmented = MobileSamMatBoundaryDetector.SamBinaryMask(
            width = 100,
            height = 100,
            visibleWidth = 100,
            visibleHeight = 100,
            positiveX = 0.50f,
            values = values
        )

        val line = MobileSamMatBoundaryDetector.select(
            listOf(MobileSamMatBoundaryDetector.PromptResult(0.75f, listOf(fragmented)))
        )

        assertNull(line)
    }

    @Test
    fun ignoresUpwardAndDownwardOutliersWhenSelectingUpperLine() {
        val values = BooleanArray(100 * 100)
        for (x in 5..94) {
            val top = when (x) {
                in 20..24 -> 52
                in 70..74 -> 78
                else -> 64
            }
            for (y in top..99) values[y * 100 + x] = true
        }
        val mask = MobileSamMatBoundaryDetector.SamBinaryMask(
            width = 100,
            height = 100,
            visibleWidth = 100,
            visibleHeight = 100,
            positiveX = 0.50f,
            values = values
        )

        val line = MobileSamMatBoundaryDetector.select(
            listOf(MobileSamMatBoundaryDetector.PromptResult(0.70f, listOf(mask)))
        )

        assertNotNull(line)
        assertEquals(0.646f, line!![0].y, 0.01f)
        assertEquals(0.646f, line[1].y, 0.01f)
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

    private fun fragmentedMask(
        ranges: List<IntRange>,
        top: Int,
        bottom: Int,
        width: Int = 100,
        height: Int = 100
    ): MobileSamMatBoundaryDetector.SamBinaryMask {
        val values = BooleanArray(width * height)
        for (range in ranges) {
            for (x in range) {
                for (y in top..bottom) values[y * width + x] = true
            }
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
