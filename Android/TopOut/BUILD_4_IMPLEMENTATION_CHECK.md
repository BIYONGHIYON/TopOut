# TopOut Android 1.3 (build 4) implementation check

## Applied

- MobileSAM automatic mat boundary with 80% -> 85% -> 90% prompt stages
- 75% horizontal fallback after low-confidence stages
- accepted mat boundaries limited to 50%-100% of frame height
- left/right endpoint difference limited to 14% of frame height
- MobileSAM sessions loaded behind launch thumbnail before CameraX composition/binding
- preview/selection creates no recording file; CameraX recording starts only after Record and countdown
- rolling motion gate reads only the 40%-high region above the detected/edited mat line
- active-climb pose-loss grace period increased to 6 seconds
- pose inference remains 0.5-second throttled
- latest-frame-only CameraX analysis; no secondary pose task/bitmap queue
- frozen selection bitmap and cached MobileSAM embedding released before recording
- ONNX sessions loaded from reusable app-private model files to avoid temporary model byte arrays
- unused PaddleLite/native build artifacts and legacy CV mat detector removed
- versionName 1.3, versionCode 4

## Validation completed in this environment

- Kotlin PSI syntax parsing: all source and test `.kt` files passed
- Android XML parsing: all manifest/resource XML files passed
- requirement assertions: 20/20 passed
- archive integrity checked after packaging

## Not completed here

A full Android Gradle build could not run because the isolated environment could not download the
Gradle 8.14.5 distribution or the generated MoveNet model. Run Gradle Sync and an assemble task in
Android Studio with network access before device installation.

## Tracked-frame power optimization

- Initial person discovery and the three-frame motion verification retain the 512px RGB path.
- After smart crop unlocks, CameraX YUV_420_888 is sampled directly into MoveNet Thunder's 256x256 RGB tensor.
- Tracked frames no longer allocate an intermediate RGB Bitmap or run a second Canvas/getPixels resize pass.
- Direct tracked conversion uses bilinear luma sampling and nearest chroma sampling to balance edge detail with CPU cost.
