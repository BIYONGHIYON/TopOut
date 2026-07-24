# Motion-seeded ROI flow

## Waiting

- Run initial motion search at 2 Hz. After reliable pose acquisition, pre-start tracking and pose-loss recovery run at 4 Hz; active climbing stays at 2 Hz.
- Compare each 160px luma frame with the immediately previous analysis frame.
- Measure only the region above the edited mat line, capped at 40% of the visible frame height.
- When the motion threshold is crossed, compare the current frame with the immediately previous analysis frame.
- Place a pixel-square ROI covering about 25% of the visible screen area around the strongest changed cells.
- Use the same immediate-frame comparison for initial search and pose-loss recovery; there is no older-frame fallback.
- If neither difference can produce a crop, remain in WAITING. There is no 512px full-frame RGB pose fallback.

## First pose and tracking

- Sample only the motion ROI directly from CameraX YUV_420_888 into MoveNet Thunder's 256x256 RGB input.
- One reliable human pose immediately enables joint-based crop tracking.
- If the first MoveNet result is missing or does not pass the reliable-human gate, discard the crop and return to WAITING.
- A reliable pose whose lowest trusted joint is already above the mat starts immediately. Otherwise a later below-to-above transition starts the climb.

## Climbing and finish

- While climbing, update the closest-top-hold or highest-pose finish candidate on each reliable pose.
- Missing pose is tolerated for up to five seconds only while CLIMBING. On the first miss, joint-based crop tracking switches to a 4 fps motion-seeded quarter-frame ROI.
- During that five-second window, each current 160px luma frame is compared with the immediately previous pose-analysis frame; a usable motion crop replaces the previous ROI before MoveNet runs.
- When a reliable pose returns, joint-based crop tracking resumes from that pose. If no usable motion crop exists, the most recent crop is retained until a later frame provides motion or the timeout expires.
- A reliable pose whose lowest trusted joint moves below the mat ends the climb.
- After a normal finish, keep the current joint crop and follow continuously visible joints without a time limit.
- During this post-climb tracking window, two consecutive missing reliable poses return to WAITING.
- If the same tracked pose moves above the mat again, a new climb starts immediately.
