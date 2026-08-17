# TopOut iOS v1.4 (build 5)

> 과거 릴리스 기록입니다. 현재 릴리스는 [v1.6 build 14](V1_6_BUILD_14_NOTES.md)이며 현재 동작은 [iOS README](README.md)를 기준으로 합니다.

- Automatic clip ranges are compared using their raw, unpadded boundaries.
- Valid detected climbs remain separate even when the next climb starts within 1.0 second.
- The 3-second review padding is applied independently to each remaining range after short-range cleanup.


## Pose/motion alignment

- Initial motion analysis and stable climbing run at 2 fps. After a reliable pose is acquired, pre-start/post-climb tracking and pose-loss recovery run at 4 fps.
- Motion activation uses a 2.5% base-noise margin plus a 2% adjusted threshold.
- After a normal finish, two consecutive pose misses return to initial CV motion search.
- Reliable non-face joints from all Vision observations are combined, matching the earlier iOS pose behavior.
- No shoulder/hip/elbow/knee group gate is applied; at least one reliable non-face joint is sufficient.
- The nose remains excluded from climb decisions.
- Pose-loss recovery timeout is 6 seconds.
