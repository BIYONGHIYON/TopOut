# TopOut Android — Play Store release source

TopOut is an Android camera app that records climbing video with audio and automatically proposes climb ranges. The release app uses CameraX, Jetpack Compose, MediaPipe EfficientDet-Lite2, ONNX Runtime MobileSAM, and Media3.

## Release identity

```text
applicationId: com.topout.cam
versionCode: 1
versionName: 1.0
minSdk: 26
targetSdk: 36
```

## Automatic clip detection

The production analyzer uses only the person's screen position. Pose landmarks and GPU delegates are not used.

```text
WAITING_FOR_CHANGE
  Full-frame luma comparison at 4 fps
        ↓ meaningful change
DETECTING_PERSON
  EfficientDet-Lite2 person detection at 2 fps
        ↓ no person for 1 second          ↓ person found
WAITING_FOR_CHANGE                 TRACKING_PERSON / CLIMBING
                                    detector remains active at 2 fps
                                    even when the climber pauses
                                            ↓ missing for 5 seconds
                                    finish or return to waiting
```

Current thresholds:

- person class score: 0.25 or higher
- minimum person box area: 0.15% of analyzed frame
- climb crossing line: nominally 5% above the edited mat line, with 2% detector-box tolerance
- clip padding: 3 seconds before start and after the selected finish moment
- maximum stored ranges: 10
- short-range cleanup: ranges under 9 seconds merge with a neighbour within 3 seconds or are removed
- execution delegate: CPU only on every device

CameraX Preview, VideoCapture, and ImageAnalysis share one ViewPort, so frame-change analysis and person detection use the same selected aspect-ratio crop shown in the preview and encoded in the video.

## Models

Bundled in the project:

```text
app/src/main/assets/models/mobile_sam_encoder.onnx
app/src/main/assets/models/mobile_sam_decoder.onnx
```

Downloaded before asset merge when absent:

```text
app/src/main/assets/models/efficientdet_lite2_float16.tflite
```

Manual setup:

```bash
./download_person_detector_model.command
./check_models.command
```

The app does not download models at runtime.

## Build

1. Open this directory in Android Studio.
2. Select Android Studio Embedded JDK or JDK 17+.
3. Run **Sync Project with Gradle Files**.
4. Run **Build → Clean Project → Rebuild Project**.
5. Test on a physical Android device with camera and microphone permissions allowed.

For a signed Play Store bundle:

```text
Build → Generate Signed App Bundle or APK → Android App Bundle → release
```

Expected output:

```text
app/build/outputs/bundle/release/app-release.aab
```

## Release behavior retained

- FHD/4K and 30/60fps capability-aware recording settings
- selectable 9:16, 4:5, 3:4, and 1:1 crops
- audio recording
- automatic mat boundary proposal and vertical correction
- optional MobileSAM top-hold masking, loaded lazily only when top-hold selection is used
- preview-stream suspension while recording when live preview is disabled
- automatic clip review, trimming, overlay selection, and MediaStore export
- in-app privacy policy and open-source notices
- no app-authored debug logging

## Removed before release

- MediaPipe Pose Landmarker Heavy model and download task
- pose landmark classes, gates, tests, and constants
- GPU delegate selection and device-specific GPU fallback code
- mock-video engine, mock UI, and mock app identifier
- stale pose-specific documentation
