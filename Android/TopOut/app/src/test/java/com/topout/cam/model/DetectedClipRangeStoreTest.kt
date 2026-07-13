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
    fun longRecordingKeepsEarlyValidRangesAfterLateNoiseIsRemoved() {
        val store = DetectedClipRangeStore()
        store.append(ClipRange(0.0, 12.0))
        store.append(ClipRange(20.0, 32.0))

        // These late one-second false positives make the raw count exceed ten, but each is isolated
        // by more than the three-second merge gap and must be removed during finalization.
        repeat(12) { index ->
            val start = 40.0 + index * 5.0
            store.append(ClipRange(start, start + 1.0))
        }

        val result = ClipRangeNormalizer.normalize(store.snapshot())

        assertEquals(
            listOf(ClipRange(0.0, 12.0), ClipRange(20.0, 32.0)),
            result
        )
    }

    @Test
    fun overlappingPaddedRangesAreCoalesced() {
        val result = ClipRangeNormalizer.normalize(
            listOf(
                ClipRange(0.0, 12.0),
                ClipRange(10.0, 22.0),
                ClipRange(30.0, 42.0)
            )
        )

        assertEquals(
            listOf(ClipRange(0.0, 22.0), ClipRange(30.0, 42.0)),
            result
        )
    }

    @Test
    fun shortRangeStillMergesWithNearestRangeWithinThreeSeconds() {
        val result = ClipRangeNormalizer.normalize(
            listOf(
                ClipRange(0.0, 12.0),
                ClipRange(14.0, 16.0),
                ClipRange(30.0, 42.0)
            )
        )

        assertEquals(
            listOf(ClipRange(0.0, 16.0), ClipRange(30.0, 42.0)),
            result
        )
    }

    @Test
    fun validEarlyRangeIsNotRemovedWhenLeadingPaddingWasClamped() {
        val result = ClipRangeNormalizer.normalize(
            input = listOf(ClipRange(0.0, 6.1)),
            recordingDurationSeconds = 120.0
        )

        assertEquals(listOf(ClipRange(0.0, 6.1)), result)
    }

    @Test
    fun twoFpsSamplingToleranceKeepsAnApproximatelyThreeSecondDetection() {
        val result = ClipRangeNormalizer.normalize(
            input = listOf(ClipRange(10.0, 18.5)),
            recordingDurationSeconds = 120.0
        )

        assertEquals(listOf(ClipRange(10.0, 18.5)), result)
    }


    @Test
    fun moreThanTenValidRangesAreAllPreserved() {
        val input = List(12) { index ->
            val start = index * 15.0
            ClipRange(start, start + 12.0)
        }

        val result = ClipRangeNormalizer.normalize(
            input = input,
            recordingDurationSeconds = 200.0
        )

        assertEquals(input, result)
    }

    @Test
    fun genuinelyShortIsolatedMiddleRangeIsStillRemoved() {
        val result = ClipRangeNormalizer.normalize(
            input = listOf(ClipRange(10.0, 18.0)),
            recordingDurationSeconds = 120.0
        )

        assertEquals(emptyList<ClipRange>(), result)
    }
}
