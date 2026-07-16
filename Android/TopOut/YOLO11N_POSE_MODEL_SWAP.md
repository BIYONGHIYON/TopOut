# Android YOLO11n-Pose model swap

## Changed

- Removed Google ML Kit Accurate Pose Detection.
- Added YOLO11n-Pose ONNX inference through the ONNX Runtime dependency already used by MobileSAM.
- The model uses a 640×640 letterboxed RGB input and the standard COCO 17-keypoint layout.
- Start verification chooses the strongest valid person independently.
- During an active climb, the closest previous person bounding box is used as a light association signal, replacing the continuity that ML Kit stream mode previously provided.
- The Gradle task `downloadYoloPoseModel` downloads the ONNX file before `preBuild`, verifies the pinned SHA-256 value, and exposes the generated directory as a main asset source.
- The model is not duplicated in this source archive; the first Android build needs network access to fetch the approximately 12 MB ONNX file.

## Unchanged

- mat-boundary luma motion wake-up and three-second calibration
- 0.5-second pose analysis interval
- 0.50 keypoint confidence threshold
- minimum six reliable joints and shoulder/hip human gate
- start and finish state machine
- closest top-hold or highest-pose finish reference
- five-second no-pose retry behavior
- raw clip timestamp collection, short-clip cleanup, raw three-second merge rule, final three-second padding
- unlimited detected clip count
- camera, audio, live-preview battery-saving behavior, result timeline, export, and settings UI
- app version 1.1

## Model provenance and licensing

The pinned ONNX export has SHA-256:

`70bd721f9cb797eb44cbc70bc65213397a0a26da38fe6fd5ccdf699016d33d3c`

Ultralytics YOLO is distributed under AGPL-3.0 unless a separate Enterprise license is obtained. Before distributing a closed-source Play Store build, verify the licensing route for the whole application. This project includes the AGPL-3.0 text and third-party notice, but those files alone do not replace the license obligations.
