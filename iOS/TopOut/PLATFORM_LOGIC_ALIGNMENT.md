# Platform logic alignment

- Waiting-state motion analysis and stable climbing run at 2 fps.
- Each motion frame is compared only with the immediately previous analyzed frame.
- Raw changed-pixel activation is approximately 4.5%: 2.5% noise margin plus 2% adjusted threshold.
- iOS combines reliable non-face joints from every Vision observation, matching the earlier iOS behavior.
- No per-person proximity matching or required body-part group gate is applied.
- The nose is excluded from start, finish, highest-point, and top-hold decisions.
- Reliable pre-start/post-climb pose tracking and pose-loss recovery run at 4 fps.
- Pose-loss recovery timeout is 6 seconds.
- After a normal finish, visible pose tracking has no time limit; two consecutive misses return to initial motion search.
