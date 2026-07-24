# Android optimization audit — v1.4 (build 5)

The original optimization pass kept behavior unchanged. This package also includes the later tracking-alignment changes listed below.

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
- Per-frame luma allocation in `PoseClimbAnalyzer`: analysis is 2 fps normally and 4 fps only before start/recovery; the current immutable-frame ownership is safer than introducing shared mutable ring buffers without device profiling.

## Later tracking alignment

- Removed the six-second expiry after a normal finish while reliable pose remains visible.
- Kept the two-consecutive-miss reset after a normal finish.
- Removed the artificial `start + 0.1s` minimum from normal finish timestamps.
