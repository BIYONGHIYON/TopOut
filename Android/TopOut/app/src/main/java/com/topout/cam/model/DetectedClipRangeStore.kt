package com.topout.cam.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Keeps every raw automatic-detection range until recording finalization.
 *
 * Ranges are not capped. Long recordings may contain many valid climbs, and discarding ranges by
 * count can remove correct early detections merely because later detections were added.
 */
internal class DetectedClipRangeStore {
    private val ranges = mutableListOf<ClipRange>()

    fun clear() = ranges.clear()

    fun append(range: ClipRange) {
        if (range.endSeconds <= range.startSeconds) return
        val last = ranges.lastOrNull()
        if (last != null &&
            abs(last.startSeconds - range.startSeconds) < DUPLICATE_TOLERANCE_SECONDS &&
            abs(last.endSeconds - range.endSeconds) < DUPLICATE_TOLERANCE_SECONDS
        ) {
            return
        }
        ranges += range
    }

    fun snapshot(): List<ClipRange> = ranges.toList()

    private companion object {
        const val DUPLICATE_TOLERANCE_SECONDS = 0.05
    }
}

/** Finalizes raw ranges only after the full recording duration is known. */
internal object ClipRangeNormalizer {
    fun normalize(
        input: List<ClipRange>,
        recordingDurationSeconds: Double? = null,
        minimumTotalDurationSeconds: Double = TopOutConfig.ShortClipMinimumTotalSeconds,
        durationToleranceSeconds: Double = TopOutConfig.ShortClipDurationToleranceSeconds,
        boundaryPaddingSeconds: Double = TopOutConfig.ClipBoundaryPaddingSeconds,
        shortRangeMergeGapSeconds: Double = TopOutConfig.ShortClipMergeGapSeconds
    ): List<ClipRange> {

        var working = mergeOverlappingRanges(
            input.filter { it.endSeconds > it.startSeconds }
                .sortedWith(compareBy<ClipRange> { it.startSeconds }.thenBy { it.endSeconds })
        ).toMutableList()

        while (true) {
            val shortIndex = working.indexOfFirst { range ->
                isShortRange(
                    range = range,
                    recordingDurationSeconds = recordingDurationSeconds,
                    minimumTotalDurationSeconds = minimumTotalDurationSeconds,
                    durationToleranceSeconds = durationToleranceSeconds,
                    boundaryPaddingSeconds = boundaryPaddingSeconds
                )
            }
            if (shortIndex < 0) break

            val short = working[shortIndex]
            val previousIndex = (shortIndex - 1).takeIf { shortIndex > 0 }
            val nextIndex = (shortIndex + 1).takeIf { it < working.size }
            val previousGap = previousIndex?.let {
                max(0.0, short.startSeconds - working[it].endSeconds)
            }
            val nextGap = nextIndex?.let {
                max(0.0, working[it].startSeconds - short.endSeconds)
            }
            val canMergePrevious = previousGap != null &&
                previousGap <= shortRangeMergeGapSeconds
            val canMergeNext = nextGap != null && nextGap <= shortRangeMergeGapSeconds

            when {
                canMergePrevious && canMergeNext -> {
                    if (requireNotNull(previousGap) <= requireNotNull(nextGap)) {
                        val previous = requireNotNull(previousIndex)
                        working[previous] = union(working[previous], short)
                        working.removeAt(shortIndex)
                    } else {
                        val next = requireNotNull(nextIndex)
                        working[shortIndex] = union(short, working[next])
                        working.removeAt(next)
                    }
                }

                canMergePrevious -> {
                    val previous = requireNotNull(previousIndex)
                    working[previous] = union(working[previous], short)
                    working.removeAt(shortIndex)
                }

                canMergeNext -> {
                    val next = requireNotNull(nextIndex)
                    working[shortIndex] = union(short, working[next])
                    working.removeAt(next)
                }

                else -> working.removeAt(shortIndex)
            }

            // A short-range merge can bridge two padded ranges. Coalesce them before the next pass
            // so duplicate or overlapping clips do not remain as separate review ranges.
            working = mergeOverlappingRanges(working).toMutableList()
        }

        return mergeOverlappingRanges(working)
    }


    private fun isShortRange(
        range: ClipRange,
        recordingDurationSeconds: Double?,
        minimumTotalDurationSeconds: Double,
        durationToleranceSeconds: Double,
        boundaryPaddingSeconds: Double
    ): Boolean {
        var requiredDuration = minimumTotalDurationSeconds

        // Padding is clamped at the beginning/end of the recording. Do not classify a valid climb
        // as short merely because up to three seconds of requested context could not exist there.
        if (range.startSeconds <= BOUNDARY_EPSILON_SECONDS) {
            requiredDuration -= boundaryPaddingSeconds
        }
        if (recordingDurationSeconds != null &&
            recordingDurationSeconds - range.endSeconds <= BOUNDARY_EPSILON_SECONDS
        ) {
            requiredDuration -= boundaryPaddingSeconds
        }

        requiredDuration = max(0.0, requiredDuration - durationToleranceSeconds)
        return range.durationSeconds < requiredDuration
    }

    private fun mergeOverlappingRanges(sortedInput: List<ClipRange>): List<ClipRange> {
        if (sortedInput.isEmpty()) return emptyList()

        val result = ArrayList<ClipRange>(sortedInput.size)
        for (range in sortedInput.sortedBy { it.startSeconds }) {
            val previous = result.lastOrNull()
            if (previous == null || range.startSeconds > previous.endSeconds) {
                result += range
            } else {
                result[result.lastIndex] = union(previous, range)
            }
        }
        return result
    }

    private const val BOUNDARY_EPSILON_SECONDS = 0.10

    private fun union(first: ClipRange, second: ClipRange): ClipRange = ClipRange(
        startSeconds = min(first.startSeconds, second.startSeconds),
        endSeconds = max(first.endSeconds, second.endSeconds)
    )
}
