# Long-recording clip history fix

## Root cause

The app previously limited `completedRanges` to ten immediately after every detection. In a long
recording, later short false-positive ranges could push the raw count above ten and delete an early
valid climb. At finalization those late short ranges were removed by the 9-second/3-second cleanup,
so the review screen could end with only a few recent ranges even though fewer than ten valid ranges
remained.

## Updated policy

1. Keep every raw detected range for the duration of the recording.
2. Clamp ranges to the encoded video duration.
3. Merge overlapping padded ranges.
4. Remove or nearest-neighbour merge short ranges.
5. Apply the maximum-ten limit only to the cleaned final list.

The short-range check also accounts for:

- the 0.5-second sampling interval of the 2 fps person detector; and
- missing three-second context when a range touches the beginning or end of the recording.

## Regression coverage

`DetectedClipRangeStoreTest` covers:

- more than ten raw ranges without early deletion;
- early valid ranges surviving many later short false positives;
- overlapping padded ranges being coalesced;
- short-range nearest-neighbour merge within three seconds;
- recording-boundary padding truncation; and
- 2 fps duration quantization tolerance.
