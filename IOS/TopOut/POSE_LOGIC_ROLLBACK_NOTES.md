# iOS pose logic rollback notes

## Rolled back

- Removed motion-center-based initial person selection.
- Removed previous-frame-position-based person tracking.
- Removed the strict requirement that shoulder, hip, elbow, and knee groups must each contain a confident joint.
- Removed the person-center distance threshold and the related tracking state.

## Current pose aggregation

- Every `VNHumanBodyPoseObservation` returned by Vision contributes its reliable joints to one combined pose-point set.
- A pose is considered available when at least one non-face joint has confidence `>= 0.25`.
- The nose remains excluded from start, finish, highest-point, and top-hold-distance decisions.

## Retained behavior

- Waiting motion search: fixed 2 fps, current frame versus immediately previous analyzed frame.
- Motion activation: 2.5% base-noise margin plus 2% adjusted threshold.
- Pre-start/post-climb tracking and pose-loss recovery: 4 fps.
- Stable climbing: 2 fps.
- Pose-loss timeout: 6 seconds.
- After a normal finish, two consecutive pose misses return to initial motion search.
- Finish timestamps use the best valid finish reference; invalid start-equal candidates fall back to the actual mat crossing or last reliable pose time without adding 0.1 seconds.
