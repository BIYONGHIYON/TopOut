# iOS optimization audit — v1.4 (build 5)

> 이 문서는 v1.4 build 5 최적화 당시의 감사 기록입니다. 현재 릴리스는 [v1.6 build 14](V1_6_BUILD_14_NOTES.md)이며 최신 동작은 [iOS README](README.md)를 기준으로 합니다.

The original optimization pass kept behavior unchanged. Later platform-alignment notes below remain historical context.

## Removed

- Obsolete chroma/brightness `MatDetector.swift`; MobileSAM is the only automatic mat-boundary detector now.
- Empty production no-op `PoseDebugOverlayView.swift`.
- Unused UIImage orientation-normalization function; the used `UIImage.bounds` helper remains.
- Unused mat-bin adaptation and its old chroma-detector configuration constants.
- Unused mat-line coordinate helper, recorder PTS accessors, completed-range convenience property, timeline no-op, and timeline formatter.
- Unused `referenceImage` parameter from `MotionDetector.configure`.
- Unused UIImage-to-small-frame conversion path in `MotionDetector`; live detection uses camera sample buffers only.

## Optimized

- MobileSAM mat candidate selection keeps only the current best candidate instead of collecting and sorting all candidates.
- Connected-component traversal records each x-column's top pixel during flood fill. This removes the component bitmap and later nested boundary scan while preserving the resulting boundary.

## Verified

- All Swift production files passed `swiftc -frontend -parse` together.
- Static declaration scan leaves only UIKit/AVFoundation/application delegate callbacks and protocol requirements as apparent single-reference declarations.
- This audit snapshot was created at version 1.4 build 5; the current project identity is 1.6 build 14.

## Left unchanged deliberately

- Per-analysis small-frame luma allocation: one current and one previous 160px luma frame are retained; safe pooling still needs runtime profiling.
- Release stripping/build settings: no Xcode archive was available in this environment to validate binary and symbol behavior.

## Later platform alignment

- Initial motion analysis and active climbing remain at 2 fps and use only the immediately previous motion frame.
- Reliable pre-start/post-climb pose tracking and pose-loss recovery run at 4 fps; recovery timeout is 6 seconds.
- Motion activation is aligned to the Android 2.5% noise margin plus 2% adjusted threshold.
- Vision still runs once per analysis frame, but multiple observations are no longer merged. The initial 160px motion grid selects one nearby person, and later frames retain the observation that best overlaps the previous joint bounds and center. This adds only small scalar scoring and no extra Vision inference.
- Two consecutive misses after a normal finish return to initial motion search.

## v1.6 review UI cleanup

- Moved the custom selected-range zoom control into `ReviewControls.swift`, reducing the responsibilities of `ReviewVC`.
- Routed retake and close confirmations through one `UIAlertController` path; the retake alert keeps the system layout and only reduces the long message font.
- Kept the custom zoom control independent from `UISlider`, avoiding endpoint feedback while retaining full-track tap and drag input.

## v1.6 low-memory and lifecycle audit

### Applied

- `ContinuousRecorder` now applies writer backpressure directly on the capture delegate queues instead of asynchronously retaining an unbounded series of 4K/60fps `CMSampleBuffer` objects. `AVCaptureVideoDataOutput.alwaysDiscardsLateVideoFrames` remains responsible for dropping overload frames.
- Ready-time mat detection creates a non-cached embedding. A Core ML call that returns after the 5-second UI watchdog can therefore no longer publish a stale Ready-frame embedding for a later live top-hold tap.
- MobileSAM decoder work is wrapped in one autorelease pool per prompt while candidate comparison remains in the original prompt/channel order. This shortens the lifetime of Core ML decoder outputs and connected-component buffers without changing candidate selection.
- The unused embedding warm-up entry point was removed. A completed top-hold selection also drops its no-longer-used `PixelMap` and embedding immediately.
- MobileSAM encoder, decoder, and embedding references are released on recording entry, app backgrounding, and memory warnings. Teardown and inference share the serial `workQ`; the preload gate is reset before teardown is enqueued, so a quick background/foreground transition queues reload safely after teardown instead of skipping it. Models remain loaded on Retake so the next Ready analysis does not spend its 5-second watchdog budget loading them.
- Stream readiness preloads MobileSAM only in idle camera or selection states. A review retake that restarts the camera directly into its recording countdown therefore cannot reload the models that recording entry just released; selection also restarts its live camera and preload after returning from the background.
- Camera startup now refuses to revive capture while the application is inactive. Audio-interruption recovery also cannot restart a stopped camera while exporting, reviewing, or backgrounded.
- If an automatic background/interruption stop finishes without a usable video after the app has already become active, the idle camera is restarted explicitly; recovery no longer depends on receiving a second activation notification.
- `HoldVC` deinitialization explicitly cancels the mat-analysis watchdog, countdown work items, and both recording timers in addition to removing notification observers and ending the background task.
- The selected-range zoom control suppresses duplicate endpoint value callbacks and publishes its current multiplier through `accessibilityValue`.
- Closest-top-hold pose scoring now scans in place instead of allocating a temporary distance array on every analyzed pose frame. Vision still aggregates every detected person's reliable non-face joints exactly as before.

### Rechecked and deliberately unchanged

- Motion analysis remains 2fps for CV/stable tracking and 4fps for pre-start or recovery tracking. The latest-frame-only queue, 384px pose input, and multi-person aggregation behavior were not changed.
- Power-saving recording keeps the capture/writer and optional motion analysis active but disables `AVCaptureVideoPreviewLayer` rendering. Stopping those remaining components would stop the product's recording or automatic-range behavior.
- Review playback uses AVPlayer's periodic time observer only while its media clock advances; the callback also exits unless playback is active or pending. Observer, KVO, notification, and player cleanup remain in `deinit`.
- Real-device verification is still required for peak resident memory, thermal behavior, camera frame-drop cadence, and first-frame continuity, especially at 4K/60fps on low-RAM devices. Simulator build success does not measure those hardware paths.
