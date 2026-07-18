# TopOut Android release optimization

## Functional behavior retained
- MoveNet SinglePose Lightning FP16 model
- 0.5-second pose inference interval
- Climbing-specific smart crop and pose confidence thresholds
- Existing start/finish detection policy
- Existing MobileSAM/ONNX behavior

## Release cleanup
- Removed every app-authored Logcat call and the `TopOutPose` logging helper.
- Added an R8 rule that strips future `android.util.Log` calls from release builds.
- Removed the obsolete pre-MoveNet PicoDet/RTMPose source and native stub.
- Removed internal patch-note files from the distribution root.

## Runtime optimization
- Reuses MoveNet input, output, float-output, coordinate and RGB pixel buffers.
- Memory-maps the uncompressed TFLite asset when possible.
- Enables XNNPACK with at most two CPU threads.
- Removes per-pixel `Pair` allocations in YUV frame conversion.
- Reuses the analyzer RGB scratch array.
- Reduces the pose preprocessing long side from 640 to 384 before the fixed 192x192 model input.
- Prefers a 960px CameraX analysis stream, while retaining 1280px as a compatibility fallback.

## Packaging optimization
- Enables R8 minification and resource shrinking for release builds.
- Keeps ONNX/TFLite model assets uncompressed for direct access/mmap.
- Uses modern uncompressed native-library packaging and disables native extraction.
- Enables Gradle build cache and parallel task execution.
