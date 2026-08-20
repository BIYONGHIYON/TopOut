# TopOut Android release optimization

## Functional behavior retained
- MoveNet SinglePose Thunder FP16 model
- Dynamic pose inference interval: 0.5 seconds normally and 0.25 seconds before start/recovery
- Climbing-specific smart crop and pose confidence thresholds
- Existing start/finish detection policy
- Existing MobileSAM/ONNX behavior

## Release cleanup
- Removed every app-authored Logcat call and the `TopOutPose` logging helper.
- Added an R8 rule that strips future `android.util.Log` calls from release builds.
- Removed the obsolete pre-MoveNet PicoDet/RTMPose source and native stub.
- Removed internal patch-note files from the distribution root.

## Runtime optimization
- Reuses MoveNet input, output, float-output, resize bitmap, matrix and RGB pixel buffers.
- Memory-maps the uncompressed TFLite asset when possible.
- Enables XNNPACK with at most two CPU threads.
- Removes per-pixel `Pair` allocations in YUV frame conversion.
- Reuses the analyzer RGB scratch array.
- Uses an immediate-frame motion grid to seed a pixel-square ROI at 1.5x the selected motion bounds.
- Samples both the motion seed and pose-tracking crop directly from CameraX YUV into MoveNet's 256x256 RGB tensor.
- Does not run a full-frame 512px RGB fallback; without a usable motion seed the analyzer remains in WAITING.
- Uses bilinear luma sampling and allocation-free nearest chroma sampling for tracked frames.
- Avoids the tracked-frame Bitmap creation, Canvas resize, getPixels pass, and 512px RGB scratch work.
- Prefers a 960px CameraX analysis stream, while retaining 1280px as a compatibility fallback.
- Replaces overlapping motion-seed/smart-crop booleans with one explicit crop-mode state.
- Scores the motion grid with one primitive score array and keeps only the strongest cells without
  allocating and sorting one object per cell.
- Computes the initial wake-up ratio and per-cell motion scores in the same luma pass, caches the
  edited mat-line column bounds, and reuses a prepared motion crop after a failed MoveNet result.
- Computes CameraX ViewPort geometry once per analyzed frame and shares it across luma and pose paths.
- Keeps one 160px luma reference per pose-analysis tick so a climbing miss can relocate the ROI from immediate-frame motion without a full-frame RGB fallback.
- Builds one reliable-pose representation per inference instead of repeatedly converting Maps, Sets, and Lists.
- Accumulates MoveNet bounding/crop extrema while traversing keypoints and avoids advancing the same
  joint crop twice during continuous POSE tracking.
- Tags analyzer work with a recording-state generation so an inference that finishes after
  stop/reconfigure cannot publish a stale luma reference, crop, or climb event into the next take.
- Serializes MobileSAM session teardown behind in-flight ONNX work on an independent background
  scope, preventing native run/close overlap without blocking the main thread. A native call that
  never returns can still delay resource release, but no UI thread waits for that teardown.
- Tags MobileSAM's cached embedding so a timed-out encoder result cannot republish that large buffer
  after recording has explicitly released the selection cache.
- Moves rotation selection outside the 256×256 YUV conversion pixel loop and reuses coordinate arrays.
- Removes obsolete configuration values and unused frozen-frame/configure parameters.
- Invalidates pending zoom-confirmed recording starts on countdown cancellation, lifecycle unbind,
  and session replacement so a stale CameraControl callback cannot start a hidden recorder.
- Uses a generation-gated 1.5-second fallback when an OEM zoom future never completes, removes the
  delayed callback on every exit path, discards a recorder canceled before its Start event, and
  restores the UI when a surface/lifecycle rebind invalidates the start request or a retry reaches
  CameraX before that discarded recorder has finalized.

## Packaging optimization
- Enables R8 minification and resource shrinking for release builds.
- Keeps ONNX/TFLite model assets uncompressed for direct access/mmap.
- Uses modern uncompressed native-library packaging and disables native extraction.
- Enables Gradle build cache and parallel task execution.

## Motion-seeded pose tracking

- Initial CV wake-up compares the current 2 fps analysis frame with the immediately previous frame.
- At wake-up, the edited mat-line search band is divided into roughly square cells and scored against the immediately preceding frame.
- Up to eight strongest related cells seed a pixel-square crop whose side is 1.5x the longer dimension of their bounds.
- Initial search and pose-loss recovery now share one immediate previous-frame motion path; no older-frame fallback remains.
- There is no full-frame 512px RGB pose fallback; without a usable ROI the analyzer stays in WAITING.
- One reliable human pose immediately starts pose-derived tracking. An unreliable first result returns directly to WAITING.
- During CLIMBING only, temporary pose loss is tolerated for six seconds. Recovery runs at 4 fps while current-vs-previous luma motion refreshes the 1.5x motion-bounds ROI until reliable joints return.
- After a normal finish, continuously visible joints remain tracked without a time limit; two consecutive missing reliable poses return to WAITING.
