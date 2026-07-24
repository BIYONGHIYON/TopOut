# Simplified Android pose tracking

- Initial motion search and active climbing run at 2 fps.
- Reliable pose tracking before the next climb and pose-loss recovery run at 4 fps.
- Motion always compares the current analysis frame with the immediately previous frame.
- Tracking has two modes only:
  - `MOTION`: update a 25%-area square ROI from frame difference, then run MoveNet.
  - `POSE`: update the ROI from reliable joints.
- Any unreliable pose switches `POSE -> MOTION`; any reliable pose switches `MOTION -> POSE`.
- Initial motion search is restricted to the region up to 40% of frame height above the edited mat line.
- After a reliable person has been found, motion recovery may move across the whole frame.
- Reliable pose still requires confidence >= 0.25 and at least one shoulder, hip, elbow, and knee.
- Start: lowest reliable joint is at least 3% of frame height above the mat line.
- Finish: lowest reliable joint moves below the actual mat line.
- During a climb, pose recovery can continue for up to 5 seconds.
- After a normal finish, the first pose miss switches to motion recovery; the second consecutive miss resets to initial motion search. Visible post-climb tracking has no time limit; only two consecutive pose misses reset to initial motion search.
