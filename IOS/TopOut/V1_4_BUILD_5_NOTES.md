# TopOut iOS v1.4 (build 5)

- Automatic clip ranges are compared using their raw, unpadded boundaries.
- After short-range cleanup, adjacent ranges with a gap of 1.0 second or less are merged.
- The 3-second review padding is applied only after this merge.
