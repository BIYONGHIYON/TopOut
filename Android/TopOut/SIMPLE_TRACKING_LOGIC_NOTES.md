# Simplified Android pose tracking

- Initial motion search and active climbing run at 2 fps.
- Reliable pose tracking before the next climb and pose-loss recovery run at 4 fps.
- Motion always compares the current analysis frame with the immediately previous frame.
- Tracking has two modes only:
  - `MOTION`: update a square ROI to 1.5x the selected frame-difference bounds, then run MoveNet.
  - `POSE`: update a square ROI to 1.5x the reliable-joint bounds.
- Any unreliable pose switches `POSE -> MOTION`; any reliable pose switches `MOTION -> POSE`.
- Initial motion search is restricted to the region up to 40% of selected-video height above the edited mat line.
- After a reliable person has been found, motion recovery may move across the whole frame.
- Reliable pose requires confidence >= 0.25 and at least three of the four shoulder, hip, elbow, and knee groups. Each group passes when either its left or right joint is reliable.
- Start: lowest reliable joint is at least 6% of selected-video height above the x-interpolated mat line.
- Finish: lowest reliable joint moves below the boundary 4% of selected-video height above the x-interpolated mat line.
- Start-only edge guard: a pose missing either the shoulder or hip group, or with any shoulder/hip inside the left or right 10% of selected-video space, needs at least three reliable knee/ankle points including one ankle. Limb-only edge contact does not activate the guard when a shoulder and hip remain in the center 80%. Tracking and finish behavior are unchanged.
- During a climb, pose recovery can continue for up to 6 seconds.
- After a normal finish, the first pose miss switches to motion recovery; the second consecutive miss resets to initial motion search. Visible post-climb tracking has no time limit; only two consecutive pose misses reset to initial motion search.
