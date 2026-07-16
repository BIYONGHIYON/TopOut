# TopOut Android

TopOut records climbing video with audio and automatically proposes climb ranges.

## Automatic detection

The Android release uses YOLO11n-Pose through ONNX Runtime. A lightweight mat-boundary motion gate wakes pose analysis, start verification independently selects the strongest valid person, and an active climb uses bounding-box continuity to remain on the same person. Pose inference runs at most every 0.5 seconds with a 640×640 letterboxed input.

## Clip ranges

Detected ranges are not limited to 10. All ranges are retained through the recording, then overlap and short-range cleanup is applied during finalization.

## Other release behavior

- application ID: `com.topout.cam`
- camera and microphone recording
- optional live-preview stream suspension during recording
- in-app privacy policy
- lazy MobileSAM initialization for top-hold selection
- no application debug logging

## Build

The bundled MobileSAM ONNX files must remain at:

```text
app/src/main/assets/models/mobile_sam_encoder.onnx
app/src/main/assets/models/mobile_sam_decoder.onnx
```

The YOLO11n-Pose ONNX file is downloaded automatically by the Gradle task
`downloadYoloPoseModel`, verified against a pinned SHA-256 value, and packaged from
`app/build/generated/yoloPoseAssets/models/yolo11n-pose.onnx`. The first build therefore
requires network access.

Build and verify:

```bash
./gradlew downloadYoloPoseModel
./check_models.command
./gradlew clean assembleDebug
```
