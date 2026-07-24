# TopOut iOS v1.4 (build 5)

- Automatic clip ranges are compared using their raw, unpadded boundaries.
- After short-range cleanup, adjacent ranges with a gap of 1.0 second or less are merged.
- The 3-second review padding is applied only after this merge.


## Pose/motion alignment

- Initial motion analysis and stable climbing run at 2 fps. After a reliable pose is acquired, pre-start/post-climb tracking and pose-loss recovery run at 4 fps.
- Motion activation uses a 2.5% base-noise margin plus a 2% adjusted threshold.
- After a normal finish, two consecutive pose misses return to initial CV motion search.
- With multiple Vision observations, initial selection uses the person closest to the motion center; later frames use the person closest to the previously selected center.
- Only the selected person's joints participate in climb decisions.
- Pose-loss recovery timeout is 5 seconds.
