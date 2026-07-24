# TopOut Android v1.4 (build 5)

- Automatic clip ranges are compared using their raw, unpadded boundaries.
- After short-range cleanup, adjacent ranges with a gap of 1.0 second or less are merged.
- The 3-second review padding is applied only after this merge.
- Pose detection now uses only two tracking modes: immediate-frame motion ROI and reliable-joint ROI.
- Initial motion search and active climbing run at 2 fps. Reliable pre-start/post-climb tracking and pose-loss recovery run at 4 fps; motion recovery still compares with the immediately previous analysis frame.
- One unreliable pose switches to motion ROI recovery. During a climb, recovery lasts up to 5 seconds; after a normal finish, two consecutive misses reset to initial motion search.
