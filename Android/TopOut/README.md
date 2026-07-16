# TopOut Android

TopOut records climbing video with audio and automatically proposes climb ranges.

## Automatic detection

The Android release uses Google ML Kit Accurate Pose Detection. A lightweight mat-boundary motion gate wakes pose analysis, start verification uses single-image mode, and an active climb uses stream mode. Pose inference runs at most every 0.5 seconds with a 512 px maximum input long side.

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

Check assets and build:

```bash
./check_models.command
./gradlew clean assembleDebug
```
