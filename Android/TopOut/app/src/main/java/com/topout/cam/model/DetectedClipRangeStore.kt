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

/**
 * Finalizes raw pose-detected ranges only after the full recording duration is known.
 *
 * Order is intentional and shared with iOS:
 * 1) keep raw start/end times while recording;
 * 2) remove or merge raw ranges shorter than two seconds using raw gaps of at most three seconds;
 * 3) keep every remaining detected climb as a separate range, regardless of the gap to the next climb;
 * 4) after cleanup is complete, add three seconds of review padding to both sides.
 */
internal object ClipRangeNormalizer {
    fun normalize(
        input: List<ClipRange>,
        recordingDurationSeconds: Double? = null,
        minimumDetectionDurationSeconds: Double = TopOutConfig.ShortClipMinimumDetectionSeconds,
        boundaryPaddingSeconds: Double = TopOutConfig.ClipBoundaryPaddingSeconds,
        shortRangeMergeGapSeconds: Double = TopOutConfig.ShortClipMergeGapSeconds
    ): List<ClipRange> {
        val durationLimit = recordingDurationSeconds?.coerceAtLeast(0.0)
        val working = input.mapNotNull { range ->
            val rawStart = if (durationLimit == null) {
                range.unpaddedStartSeconds.coerceAtLeast(0.0)
            } else {
                range.unpaddedStartSeconds.coerceIn(0.0, durationLimit)
            }
            val rawEnd = if (durationLimit == null) {
                range.unpaddedEndSeconds.coerceAtLeast(0.0)
            } else {
                range.unpaddedEndSeconds.coerceIn(0.0, durationLimit)
            }
            if (rawEnd > rawStart) RawRange(rawStart, rawEnd) else null
        }.sortedWith(compareBy<RawRange> { it.startSeconds }.thenBy { it.endSeconds })
            .toMutableList()

        while (true) {
            val shortIndex = working.indexOfFirst {
                it.durationSeconds < minimumDetectionDurationSeconds
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
            val canMergePrevious = previousGap != null && previousGap <= shortRangeMergeGapSeconds
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
        }

        return working.mapNotNull { raw ->
            val paddedStart = max(0.0, raw.startSeconds - boundaryPaddingSeconds)
            val paddedEndUnclamped = raw.endSeconds + boundaryPaddingSeconds
            val paddedEnd = durationLimit?.let { min(it, paddedEndUnclamped) } ?: paddedEndUnclamped
            if (paddedEnd > paddedStart) {
                ClipRange(
                    startSeconds = paddedStart,
                    endSeconds = paddedEnd,
                    unpaddedStartSeconds = raw.startSeconds,
                    unpaddedEndSeconds = raw.endSeconds
                )
            } else null
        }
    }

    private data class RawRange(
        val startSeconds: Double,
        val endSeconds: Double
    ) {
        val durationSeconds: Double get() = (endSeconds - startSeconds).coerceAtLeast(0.0)
    }

    private fun union(first: RawRange, second: RawRange): RawRange = RawRange(
        startSeconds = min(first.startSeconds, second.startSeconds),
        endSeconds = max(first.endSeconds, second.endSeconds)
    )
}
