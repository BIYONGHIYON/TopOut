# Platform logic alignment

- Waiting-state motion analysis and stable climbing run at 2 fps.
- Each motion frame is compared only with the immediately previous analyzed frame.
- Raw changed-pixel activation is approximately 4.5%: 2.5% noise margin plus 2% adjusted threshold.
- Initial multi-person selection chooses the pose center closest to the detected motion center.
- Tracking and climbing select the pose center closest to the previously selected person.
- Only one selected person's joints are used for start, finish, highest-point, and top-hold decisions.
- Reliable pre-start/post-climb pose tracking and pose-loss recovery run at 4 fps.
- Pose-loss recovery timeout is 5 seconds.
- After a normal finish, visible pose tracking has no time limit; two consecutive misses return to initial motion search.
