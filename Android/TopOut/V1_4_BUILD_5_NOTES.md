# TopOut Android v1.4 (build 5)

> 과거 릴리스 기록입니다. 현재 릴리스는 [v1.5 build 9](V1_5_BUILD_9_NOTES.md)이며 현재 동작은 [Android README](README.md)를 기준으로 합니다.

- Automatic clip ranges are compared using their raw, unpadded boundaries.
- Valid detected climbs remain separate even when the next climb starts within 1.0 second.
- The 3-second review padding is applied independently to each remaining range after short-range cleanup.
- Pose detection now uses only two tracking modes: immediate-frame motion ROI and reliable-joint ROI.
- Initial motion search and active climbing run at 2 fps. Reliable pre-start/post-climb tracking and pose-loss recovery run at 4 fps; motion recovery still compares with the immediately previous analysis frame.
- One unreliable pose switches to motion ROI recovery. During a climb, recovery lasts up to 6 seconds; after a normal finish, two consecutive misses reset to initial motion search.
