# Android optimization audit — v1.4 (build 5)

> 이 문서는 v1.4 build 5 최적화 당시의 감사 기록입니다. 현재 릴리스는 [v1.6 build 12](V1_6_BUILD_12_NOTES.md)이며 최신 동작은 [Android README](README.md)를 기준으로 합니다.

The original optimization pass kept behavior unchanged. Later tracking-alignment notes below remain historical context.

## Removed

- Unused Compose UI helper `CheckToggleIcon` and its imports.
- Unused typography object, dark-mode probe, and unused theme color.
- Unused face landmark aliases. MoveNet still parses all 17 output keypoints through `PoseLandmark.COUNT`; only unused named aliases were removed.
- Unused aspect-ratio mat-bin helper and obsolete config aliases (`MatBoundaryBins`, `PoseIntervalSeconds`).
- Unused `lifecycle-viewmodel-compose` dependency.
- Instrumentation-test runner and dependencies because the project has no `androidTest` source set. JVM unit tests remain.
- Stale notes for older pose-gate, Smart Crop, and pre-slope mat-boundary implementations.

## Optimized

- MobileSAM mat candidate selection now uses a single minimum scan instead of sorting every candidate.
- Connected-component traversal records each x-column's top pixel during flood fill. This removes a second component bitmap and a later nested boundary scan while producing the same line.
- MoveNet model downloads are cached under the Gradle user home. A clean project build still copies the model into generated assets, but does not re-download it after the first valid download.

## Verified

- Kotlin parser run over all production sources: no syntax-form errors were found; unresolved Android/Compose references are expected without the Android SDK classpath.
- Old and optimized MobileSAM boundary implementations matched in 1,000 randomized masks.
- Static declaration scan found no remaining production declaration referenced only by itself.

## Left unchanged deliberately

- Broad ONNX Runtime / TensorFlow Lite ProGuard rules: narrowing these without a release-device test can cause JNI/runtime failures.
- CameraX resolution and lens fallbacks: device compatibility paths, not dead code.
- MobileSAM encoder/decoder sessions remain warm after camera startup. Closing them during recording
  would reduce steady native RAM, but the next retake would reload both models inside the five-second
  mat-analysis watchdog unless preload ordering is redesigned and verified on a low-memory device.
- The optional 4K top-hold review/export overlay is still a full-frame ARGB bitmap. Replacing it with
  a vector/Canvas overlay could reduce review RAM, but changes Media3 export rendering and requires
  4K device-output comparison before it is safe to ship.

## Later tracking alignment

- Removed the six-second expiry after a normal finish while reliable pose remains visible.
- Kept the two-consecutive-miss reset after a normal finish.
- Removed the artificial `start + 0.1s` minimum from normal finish timestamps.

## v1.6 review UI cleanup

- Moved the selected-range zoom track input into `TrackTappableZoomSlider`, keeping pointer handling out of the trim layout.
- Consolidated the three discard-warning booleans into one immutable confirmation state.
- Isolated endpoint tolerance in `TimelineZoomMath.stableMaximumZoomFactor` and covered it with a regression test so floating-point noise cannot reintroduce right-edge jitter.

## v1.6 low-memory and long-recording pass

- MobileSAM mat prompts are decoded and evaluated lazily. Only one prompt's mask list remains live at
  a time instead of retaining all four decoder results until selection finishes.
- Cached MobileSAM embeddings now live in one direct `FloatBuffer`, which ONNX Runtime consumes via a
  duplicate view. This removes the repeated heap-to-direct copy previously made for every decoder call.
- Encoder preprocessing writes normalized 1024x1024 RGB values directly into the ONNX input buffer,
  avoiding an additional approximately 12 MB `FloatArray` at peak analysis memory.
- The decoder's immutable zero-mask input uses one reusable direct buffer instead of allocating and
  copying a new `FloatArray` for every prompt and hold-correction decode.
- A canceled/timed-out ImageAnalysis frame request now recycles a bitmap that finishes conversion
  after its caller has left, preventing an orphaned native bitmap on slow devices.
- The 2/4-fps pose analyzer rotates two fixed-size luma buffers instead of allocating a new byte array
  on every analyzed frame. Immediate-frame comparison behavior remains unchanged while long takes
  create less garbage and GC/CPU wake-up work.
- Mat-analysis timeout now cancels its owned analysis coroutine. MobileSAM checks cancellation between
  decoder prompts, so a timed-out request stops before unnecessary remaining prompts where the native
  call permits cancellation to resume.
- Removed the trim slider's write-only `requestedZoomFactor` Compose state; viewport span/start remain
  the single source of truth.
- Removed the unreferenced `Bitmap.rotated` helper and its `Matrix` dependency.
- Storage-size messages now use an explicit invariant decimal locale, avoiding locale-dependent
  formatting warnings without changing the Korean unit labels.

### Verification

- `:app:testDebugUnitTest`, `:app:compileDebugKotlin`, and `:app:lintDebug` completed successfully.
- `git diff --check -- Android/TopOut` completed without whitespace errors.
- CameraX still uses `STRATEGY_KEEP_ONLY_LATEST`, a single analysis executor, and the existing 2/4-fps
  gates; no capture cadence, detection threshold, clip timing, lens switching, or export rule changed.
- Lint reports no errors and 47 non-blocking warnings: dependency-update notices, launcher-resource
  duplication/shape suggestions, three Compose parameter-order style notices, two KTX style hints,
  two min-SDK folder hints, and the deliberately retained fixed-orientation compatibility warning.
  None indicates a failing code path; dependency, launcher, and orientation changes were kept outside
  this behavior-preserving pass.
