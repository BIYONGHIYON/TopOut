# TopOut Android release validation

## Production detection configuration

- Full-frame luma change comparison: 4 fps
- Person detector: MediaPipe EfficientDet-Lite2 float16
- Person detection rate: 2 fps
- Delegate: CPU only
- Initial search timeout after frame change: 1 second
- Missing timeout after person acquisition: 5 seconds
- Bounding-box smoothing: disabled
- Crossing confirmation delay: disabled
- Mat clearance: 5% with 2% detector-box tolerance
- Clip boundary padding: 3 seconds
- Stored clip range limit: 10

## Cleanup completed

- Removed Pose Landmarker Heavy source, model setup, constants, and tests.
- Removed GPU delegate and device-specific fallback paths.
- Removed mock-video source and mock application identifiers.
- Removed app-authored debug logging.
- Removed duplicated ViewModel assignments and obsolete model-ready state.
- Deferred MobileSAM and ONNX Runtime creation until top-hold selection is used.
- Kept production camera, microphone, audio recording, CameraX preview suspension,
  selection, review, export, in-app privacy, and open-source notice flows.

## Checks completed in the delivery environment

- Android resource XML parsing: passed (15 files).
- Shell script syntax: passed.
- Kotlin stub compilation for `PersonClimbAnalyzer.kt`: passed.
- Kotlin stub compilation for `AppModels.kt`: passed.
- Production identifier and CPU-only detector settings: verified.
- Searches for stale pose, GPU, mock, and app-authored logging references: passed.
- Generated build/cache/local machine files: excluded.
- ZIP archive integrity: verify after packaging.

## Not completed in the delivery environment

A complete Android Gradle build could not be executed because the Gradle distribution
was not cached and the environment could not resolve `services.gradle.org`. Run the
following in Android Studio on a networked machine before signing:

```text
Sync Project with Gradle Files
Build > Clean Project
Build > Rebuild Project
```

The first build downloads the official EfficientDet-Lite2 model into:

```text
app/src/main/assets/models/efficientdet_lite2_float16.tflite
```

Manual fallback:

```bash
./download_person_detector_model.command
./check_models.command
```

## Long-recording clip history regression

- Raw detections are no longer truncated to ten before cleanup.
- Overlapping padded ranges are coalesced before the final range-count limit.
- The range-count limit is applied only after short-range removal/merge.
- Start/end boundary padding truncation and 2 fps timestamp quantization are accounted for.
- Pure Kotlin regression scenarios passed with `kotlinc` in this environment.
- Full Gradle tests could not run because `services.gradle.org` was unavailable from the container.
