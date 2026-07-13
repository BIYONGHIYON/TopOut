# TopOut 1.0 release implementation notes

## Detection pipeline

- Replaced Pose Landmarker Heavy with person-only EfficientDet-Lite2.
- Uses low-resolution full-frame change detection at 4 fps while idle.
- Runs person detection at 2 fps only after meaningful change or while tracking a person.
- Returns to idle change detection after one second without a person during initial search.
- Allows five seconds of missed detections after a person has been acquired.
- Uses the current bounding box directly; no multi-frame smoothing or crossing confirmation delay.
- Uses CPU on all Android devices to avoid native GPU/OpenGL compatibility crashes.

## Cleanup

- Removed all pose-only source files and tests.
- Removed the 30 MB pose model setup path.
- Removed stale GPU and mock branches from the production project.
- Removed duplicated assignments found in the accumulated ViewModel edits.
- Deferred MobileSAM/ONNX Runtime object and session creation until top-hold selection, reducing launch-time memory and work for users who only use highest-point detection.
- Updated the in-app privacy text from pose landmarks to on-device person-area analysis.
