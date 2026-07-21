# TopOut Android v1.3 changes

## Startup load scheduling

- `MainActivity` resolves `TopOutViewModel` before rendering the one-second brand thumbnail.
- The ViewModel preloads the MobileSAM encoder and decoder in the background.
- `TopOutApp` does not compose `CameraPreview` while `launchVisible` is true, so CameraX is not configured or bound during model loading.
- If loading takes longer than the brand thumbnail, the launch overlay remains visible until loading finishes.
- If MobileSAM loading fails, the camera can still open and automatic mat detection falls back to a 75% line.

## MobileSAM mat boundary

- Positive prompts are attempted at normalized y positions 80%, 85%, and 90%.
- Each level starts at x=50%; x=42% and x=58% are used when the central result is not strong.
- A negative wall prompt is placed at y=28%.
- Only boundaries in the lower 50% of the camera frame are accepted.
- The maximum normalized left-to-right height difference is 14%.
- Weak candidates require agreement across prompt stages; otherwise the detector returns a horizontal 75% fallback.
- The same MobileSAM embedding can be reused for optional top-hold selection.

## Motion gate above the mat

- Motion is measured only from the detected/edited mat boundary upward by 40% of frame height.
- The current throttled CV frame is compared with the frame three CV-analysis samples earlier.
- No pixels on or below the mat line contribute to the wake-up signal.
- Calibration uses the same rolling region, reducing false triggers from camera exposure drift.

## Pose tracking

- During an active climb, temporary pose loss is tolerated for 6 seconds instead of 5 seconds.
- Existing smart-crop and consecutive-human verification behavior is preserved.

## Version

- `versionCode = 4`
- `versionName = 1.3`


## Preview and recording lifecycle

- CameraX may keep `VideoCapture` bound to preserve the exact lens, crop, and aspect ratio, but no
  recording/file encoder is started during preview or selection.
- `Recorder.prepareRecording(...).start(...)` is invoked only after the user presses the recording
  button and the countdown completes.

## Latest-frame-only pose inference

- Pose conversion and MoveNet inference now run directly on CameraX's single analysis executor.
- `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST` therefore retains at most one newer camera frame while
  inference is busy and continuously replaces it with the freshest frame.
- The previous extra pose executor was removed, so Runnables, Bitmaps, and ImageProxies cannot form
  a secondary queue during long recordings or on slower devices.

## MobileSAM startup memory

- ONNX assets are copied once to `noBackupFilesDir/onnx-models` and loaded by file path.
- Subsequent launches reuse the copied files, avoiding temporary 40+ MB model byte arrays while the
  launch thumbnail is visible.

- The frozen selection bitmap and its MobileSAM embedding are released when the recording countdown
  begins; the fitted mat line and optional hold mask remain available for detection and review.
