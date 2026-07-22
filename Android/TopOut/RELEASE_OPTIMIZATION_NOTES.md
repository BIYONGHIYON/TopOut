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
- Uses an immediate-frame motion grid to seed a pixel-square ROI covering approximately 25% of the visible frame area.
- Samples both the motion seed and pose-tracking crop directly from CameraX YUV into MoveNet's 256x256 RGB tensor.
- Retains the 512px RGB path only as a safety fallback when no usable motion seed is available.
- Uses bilinear luma sampling and allocation-free nearest chroma sampling for tracked frames.
- Avoids the tracked-frame Bitmap creation, Canvas resize, getPixels pass, and 512px RGB scratch work.
- Prefers a 960px CameraX analysis stream, while retaining 1280px as a compatibility fallback.
- Replaces overlapping motion-seed/smart-crop booleans with one explicit crop-mode state.
- Reuses motion-ROI integral-image scratch buffers instead of allocating large FloatArrays per search.
- Computes CameraX ViewPort geometry once per analyzed frame and shares it across luma and pose paths.
- Keeps one 160px luma reference per pose-analysis tick so a climbing miss can relocate the ROI from immediate-frame motion without a full-frame RGB fallback.
- Builds one reliable-pose representation per inference instead of repeatedly converting Maps, Sets, and Lists.
- Moves rotation selection outside the 256×256 YUV conversion pixel loop and reuses coordinate arrays.
- Removes obsolete configuration values and unused frozen-frame/configure parameters.

## Packaging optimization
- Enables R8 minification and resource shrinking for release builds.
- Keeps ONNX/TFLite model assets uncompressed for direct access/mmap.
- Uses modern uncompressed native-library packaging and disables native extraction.
- Enables Gradle build cache and parallel task execution.

## Motion-seeded pose tracking

- The rolling CV wake-up condition compares the current analysis frame with the frame two samples earlier.
- At wake-up, the full visible frame is divided into roughly square cells and scored against the immediately preceding frame.
- Up to eight strongest related cells seed a pixel-square crop whose area is approximately one quarter of the frame.
- If the strongest immediate pair has no usable signal, the two-frame trigger pair inside the mat search band is used as a fallback.
- There is no full-frame 512px RGB pose fallback; without a usable ROI the analyzer stays in WAITING.
- One reliable human pose immediately starts pose-derived tracking. An unreliable first result returns directly to WAITING.
- During CLIMBING only, temporary pose loss is tolerated for six seconds; while missing, current-vs-previous luma motion refreshes the quarter-frame ROI until reliable joints return.
- After a normal finish, continuously visible joints remain tracked for up to six seconds; two consecutive missing reliable poses return to WAITING.
