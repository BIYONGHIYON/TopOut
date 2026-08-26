# iOS optimization audit — v1.4 (build 5)

> 이 문서는 v1.4 build 5 최적화부터 이어진 감사 기록입니다. 현재 릴리스는 [v2.0 build 17](V2_0_BUILD_17_NOTES.md)이며 최신 동작은 [iOS README](README.md)를 기준으로 합니다.

The original optimization pass kept behavior unchanged. Later platform-alignment notes below remain historical context.

## v2.0 build 16 유지보수 구조 정리

- `ReviewVC`에 포함돼 있던 구간 편집 컨트롤을 `TrimRangeSlider`로 분리했습니다.
- `Camera`에 포함돼 있던 프레임 대리자를 `VideoFrameSink`로 분리했습니다.
- `HoldVC+UI`의 화면 생성과 배치 계산을 `HoldVC+Layout`으로 분리했습니다.
- 비활성 상태였던 Motion 디버그 문자열 생성과 출력 경로를 제거해 분석 코드의 불필요한 분기와 문자열 생성을 없앴습니다.
- 화면 조건, 카메라 세션 순서, 탐지 임계값, 구간 시각, 저장 규칙은 변경하지 않았습니다.
- 전체 프로덕션 주석을 간결한 한국어로 통일했습니다.
- Swift 전체 타입 검사를 통과했습니다. 앱 빌드와 실기기 동작 검증은 수행하지 않았습니다.

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
- Closest-top-hold pose scoring now scans in place instead of allocating a temporary distance array on every analyzed pose frame. Vision이 선택한 한 사람의 신뢰 가능한 비얼굴 관절만 사용합니다.

### Rechecked and deliberately unchanged

- Motion analysis remains 2fps for CV/stable tracking and 4fps for pre-start or recovery tracking. The latest-frame-only queue, 384px pose input, and one-person selection behavior were not changed in that audit pass.
- Power-saving recording keeps the capture/writer and optional motion analysis active but disables `AVCaptureVideoPreviewLayer` rendering. Stopping those remaining components would stop the product's recording or automatic-range behavior.
- Review playback uses AVPlayer's periodic time observer only while its media clock advances; the callback also exits unless playback is active or pending. Observer, KVO, notification, and player cleanup remain in `deinit`.
- Real-device verification is still required for peak resident memory, thermal behavior, camera frame-drop cadence, and first-frame continuity, especially at 4K/60fps on low-RAM devices. Simulator build success does not measure those hardware paths.

## v1.6 build 14 follow-up audit

### Applied

- `VideoFrameSink` and Motion forwarding are activated only when the three-second countdown reaches the real recording start, then both flags are closed together after stop. Countdown frames and post-stop preview frames therefore do not wake an inactive recorder or detector. Request generations invalidate stale countdown and Motion-gate callbacks, while the active path preserves its existing one-latest-frame queue and 2fps/4fps timestamp gates.
- Audio capture callbacks now read only the recording flag from `VideoFrameSink`; they no longer copy video-only frame-capture requests, camera-settling state, or stream-readiness closures on every audio sample.
- Motion-cell filtering now builds the selected top-eight list without a temporary full filtered array. Pose candidate construction derives the 10% torso-edge start guard in the same joint scan and avoids a second reliable-point array. Luma downsampling precomputes source x-columns once per 160px frame instead of repeating that coordinate calculation for every output row.
- `stop()` drops any pending full-resolution sample buffer immediately, reducing overlap between the last Vision frame and review/export resources.
- Ready-format and stable-frame callbacks carry a request generation. Backgrounding, capture interruption, or a runtime error invalidates that generation, so an obsolete callback cannot start recording after the capture lifecycle has moved on.
- Removed the dead `ClipTimes.endPTS` state. Finish events append directly to `completedRanges`, and manual/automatic stop already salvages an open range from `MotionDetector` before finalization.
- Replaced the review time-bubble work item's self-reference with a generation check and cancel it explicitly during teardown.

### Deliberately unchanged / remaining runtime risk

- The five-second mat-analysis watchdog releases the UI flow but cannot cancel a Core ML native prediction already executing on the serial `workQ`. If that native call never returns, later work queued behind it (including a top-hold request or model release) can remain blocked. Safely replacing or abandoning that execution context requires a larger inference-worker isolation design and real-device stress verification; this pass does not pretend the UI timeout cancels Core ML.
- Camera-first startup, deferred MobileSAM loading, CPU + Neural Engine compute selection, 384px Vision input, 2fps/4fps state cadence, one-person selection, and existing start/finish semantics remain unchanged.
- No archive or real-device profiling was run. Peak RAM, thermal state, and battery impact still require Instruments and physical-device checks.
