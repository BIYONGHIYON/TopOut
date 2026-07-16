# Clip range count limit removal

TopOut no longer limits detected clip ranges to ten.

- raw ranges remain available for the complete recording;
- final normalization merges overlaps and applies the existing short-range/three-second-neighbour cleanup;
- all remaining valid ranges are displayed in the review screen;
- review navigation uses the complete `detectedRanges` collection, so 11 or more ranges are supported.
