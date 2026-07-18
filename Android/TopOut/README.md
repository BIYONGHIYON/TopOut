# TopOut Android

TopOut records climbing video with audio and automatically proposes climb ranges.

## Automatic detection

The Android release uses MoveNet SinglePose Lightning FP16 through TensorFlow Lite. A lightweight mat-boundary motion gate wakes pose analysis, and the active climb uses MoveNet's temporal smart crop to remain focused on the same person. The existing pose schedule is unchanged: inference runs at most every 0.5 seconds, while the FP16 model receives a 192×192 RGB input.

## Clip ranges

Detected ranges are not limited to 10. All ranges are retained through the recording, then overlap and short-range cleanup is applied during finalization.

## Other release behavior

- application ID: `com.topout.cam`
- camera and microphone recording
- optional live-preview stream suspension during recording
- in-app privacy policy and open-source license notices
- lazy MobileSAM initialization for top-hold selection
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
