# TopOut Android v1.4 (build 5)

TopOut records climbing video with audio and automatically proposes climb ranges.

## Automatic detection

The Android release uses MoveNet SinglePose Thunder FP16 through TensorFlow Lite. MobileSAM proposes the mat boundary from automatic prompts at 80%, 85%, and 90%. A rolling luma-motion gate compares the current frame with the frame two analysis samples earlier inside the 40%-high region above the edited mat line. When that gate wakes pose search, the strongest immediate-frame changes seed a pixel-square ROI covering approximately one quarter of the screen area; if that immediate difference cannot form a crop, the two-frame trigger difference is used instead. One reliable human pose immediately switches the detector to joint-based tracking. Motion analysis and pose inference use one fixed 2 Hz schedule, and pose inference runs at most every 0.5 seconds with a 256×256 RGB input.

## Pose preprocessing and power use

Pose inference never uses a full-frame 512px RGB fallback. A usable motion-seeded ROI is required before MoveNet runs, and both the initial quarter-frame crop and later joint-based crop are sampled directly from CameraX YUV_420_888 into MoveNet Thunder's 256×256 RGB tensor. If neither the immediate difference nor the two-frame trigger difference can form a usable ROI, the analyzer stays in motion waiting.

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

The official MoveNet SinglePose Thunder FP16 model is downloaded automatically by the Gradle task `downloadMoveNetPoseModel`, validated as a TFLite FlatBuffer, and packaged from:

```text
app/build/generated/moveNetPoseAssets/models/movenet-thunder-fp16.tflite
```

The first build therefore requires network access.

Build and verify:

```bash
./gradlew downloadMoveNetPoseModel
./check_models.command
./gradlew clean assembleDebug
```
