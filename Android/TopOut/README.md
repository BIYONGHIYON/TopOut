# TopOut Android v1.3 (build 4)

TopOut records climbing video with audio and automatically proposes climb ranges.

## Automatic detection

The Android release uses MoveNet SinglePose Lightning FP16 through TensorFlow Lite. MobileSAM proposes the mat boundary from automatic prompts at 80%, 85%, and 90%. A rolling luma-motion gate analyzes only the region above that edited boundary, and the active climb uses MoveNet's temporal smart crop to remain focused on the same person. The existing pose schedule is unchanged: inference runs at most every 0.5 seconds, while the FP16 model receives a 192×192 RGB input.

## Clip ranges

Detected ranges are not limited to 10. All ranges are retained through the recording, then overlap and short-range cleanup is applied during finalization.

## Other release behavior

- application ID: `com.topout.cam`
- camera and microphone recording
- optional live-preview stream suspension during recording
- in-app privacy policy and open-source license notices
- MobileSAM sessions preload behind the startup thumbnail before CameraX is created
- automatic MobileSAM mat boundary detection with a 75% fallback
- six-second pose-loss grace period during an active climb
- preview and selection do not create a recording file; recording starts only after the record action and countdown
- latest-frame-only pose analysis via CameraX `STRATEGY_KEEP_ONLY_LATEST`
- frozen selection image/embedding released before long recording
- no application debug logging

## Build

The bundled MobileSAM ONNX files must remain at:

```text
app/src/main/assets/models/mobile_sam_encoder.onnx
app/src/main/assets/models/mobile_sam_decoder.onnx
```

The official MoveNet SinglePose Lightning FP16 model is downloaded automatically by the Gradle task `downloadMoveNetPoseModel`, validated as a TFLite FlatBuffer, and packaged from:

```text
app/build/generated/moveNetPoseAssets/models/movenet-lightning-fp16.tflite
```

The first build therefore requires network access.

Build and verify:

```bash
./gradlew downloadMoveNetPoseModel
./check_models.command
./gradlew clean assembleDebug
```
