# Pose human gate update

This build keeps the simplified Android pose flow and the existing 3% start-boundary margin.

A MoveNet output is accepted as a reliable human pose only when all conditions below are met:

- Keypoint confidence: at least 0.50
- Reliable non-face keypoints: at least 6
- Shoulder keypoints: at least 1
- Hip keypoints: at least 1
- Elbow keypoints: at least 1
- Knee keypoints: at least 1

The same gate is used before motion-seeded detection switches to joint tracking and while climb logic consumes pose results.
