package com.topout.cam.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectedClipRangeStoreTest {
    @Test
    fun rawRangesAreNotCappedBeforeFinalCleanup() {
        val store = DetectedClipRangeStore()
        repeat(14) { index ->
            val start = index * 10.0
            store.append(ClipRange(start, start + 1.0))
        }
        assertEquals(14, store.snapshot().size)
    }

    @Test
    fun shortRawRangeMergesUsingRawThreeSecondGapThenPaddingIsAdded() {
        val result = ClipRangeNormalizer.normalize(
            input = listOf(
                ClipRange(10.0, 15.0),
                ClipRange(18.0, 19.0),
                ClipRange(40.0, 45.0)
            ),
            recordingDurationSeconds = 60.0
        )
        assertEquals(
            listOf(
                ClipRange(5.0, 22.0, 10.0, 19.0),
                ClipRange(35.0, 48.0, 40.0, 45.0)
            ),
            result
        )
    }

    @Test
    fun isolatedRawRangeShorterThanTwoSecondsIsDiscardedBeforePadding() {
        val result = ClipRangeNormalizer.normalize(
            input = listOf(ClipRange(10.0, 11.99)),
            recordingDurationSeconds = 30.0
        )
        assertEquals(emptyList<ClipRange>(), result)
    }

    @Test
    fun exactlyTwoSecondRawRangeIsKeptAndThenPadded() {
        val result = ClipRangeNormalizer.normalize(
            input = listOf(ClipRange(10.0, 12.0)),
            recordingDurationSeconds = 30.0
        )
        assertEquals(listOf(ClipRange(5.0, 15.0, 10.0, 12.0)), result)
    }

    @Test
    fun shortRawRangeBeyondThreeSecondGapIsDiscardedEvenIfFinalPaddingWouldOverlap() {
        val result = ClipRangeNormalizer.normalize(
            input = listOf(
                ClipRange(10.0, 15.0),
                ClipRange(18.01, 19.0)
            ),
            recordingDurationSeconds = 30.0
        )
        assertEquals(listOf(ClipRange(5.0, 18.0, 10.0, 15.0)), result)
    }

    @Test
    fun adjacentValidRangesStaySeparateEvenWhenGapIsOneSecond() {
        val result = ClipRangeNormalizer.normalize(
            input = listOf(
                ClipRange(10.0, 15.0),
                ClipRange(16.0, 21.0)
            ),
            recordingDurationSeconds = 30.0
        )
        assertEquals(
            listOf(
                ClipRange(5.0, 18.0, 10.0, 15.0),
                ClipRange(11.0, 24.0, 16.0, 21.0)
            ),
            result
        )
    }

    @Test
    fun adjacentValidRangesStaySeparateEvenWhenPaddingOverlaps() {
        val result = ClipRangeNormalizer.normalize(
            input = listOf(
                ClipRange(10.0, 15.0),
                ClipRange(15.5, 20.0),
                ClipRange(21.0, 25.0)
            ),
            recordingDurationSeconds = 40.0
        )
        assertEquals(
            listOf(
                ClipRange(5.0, 18.0, 10.0, 15.0),
                ClipRange(10.5, 23.0, 15.5, 20.0),
                ClipRange(16.0, 28.0, 21.0, 25.0)
            ),
            result
        )
    }

    @Test
    fun paddingIsClampedOnlyAfterCleanup() {
        val result = ClipRangeNormalizer.normalize(
            input = listOf(
                ClipRange(0.5, 3.0),
                ClipRange(27.0, 29.5)
            ),
            recordingDurationSeconds = 30.0
        )
        assertEquals(
            listOf(
                ClipRange(0.0, 6.0, 0.5, 3.0),
                ClipRange(22.0, 30.0, 27.0, 29.5)
            ),
            result
        )
    }

    @Test
    fun moreThanTenValidRangesAreAllPreserved() {
        val input = List(12) { index ->
            val start = index * 10.0
            ClipRange(start, start + 4.0)
        }
        val result = ClipRangeNormalizer.normalize(input, recordingDurationSeconds = 120.0)
        assertEquals(12, result.size)
    }
    @Test
    fun sameStartStopSnapshotAndLateFinishAreCoalesced() {
        val store = DetectedClipRangeStore()
        store.append(ClipRange(10.0, 18.2))
        store.append(ClipRange(10.02, 18.7))
        assertEquals(listOf(ClipRange(10.0, 18.7)), store.snapshot())
    }

}
