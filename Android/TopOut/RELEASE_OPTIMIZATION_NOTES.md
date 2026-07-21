# TopOut Android release optimization

## Functional behavior retained
- MoveNet SinglePose Thunder FP16 model
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
- Reuses MoveNet input, output, float-output, resize bitmap, matrix and RGB pixel buffers.
- Memory-maps the uncompressed TFLite asset when possible.
- Enables XNNPACK with at most two CPU threads.
- Removes per-pixel `Pair` allocations in YUV frame conversion.
- Reuses the analyzer RGB scratch array.
- Uses the 512px RGB preprocessing path only for initial full-frame discovery and motion verification.
- After smart crop unlocks, samples CameraX YUV directly into MoveNet's 256x256 RGB tensor.
- Uses bilinear luma sampling and allocation-free nearest chroma sampling for tracked frames.
- Avoids the tracked-frame Bitmap creation, Canvas resize, getPixels pass, and 512px RGB scratch work.
- Prefers a 960px CameraX analysis stream, while retaining 1280px as a compatibility fallback.

## Packaging optimization
- Enables R8 minification and resource shrinking for release builds.
- Keeps ONNX/TFLite model assets uncompressed for direct access/mmap.
- Uses modern uncompressed native-library packaging and disables native extraction.
- Enables Gradle build cache and parallel task execution.

## Smart-crop false-positive guard

- MoveNet now stays on full-frame inference until three consecutive valid body-pose frames are available.
- Smart crop is enabled only when at least three body joints present in all three frames move by at least 5% of the maximum detected person bounding-box diagonal between the first and third frame.
- Invalid or missing pose frames reset the three-frame verification window.
- When the condition passes, the current full-frame pose seeds the first smart-crop region directly.
