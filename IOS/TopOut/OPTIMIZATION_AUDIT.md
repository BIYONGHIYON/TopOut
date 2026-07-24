# iOS optimization audit — v1.4 (build 5)

The original optimization pass kept behavior unchanged. This package also includes the later platform-alignment changes listed below.

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
- Project version remains 1.4 and build remains 5.

## Left unchanged deliberately

- Per-analysis small-frame luma allocation: one current and one previous 160px luma frame are retained; safe pooling still needs runtime profiling.
- Release stripping/build settings: no Xcode archive was available in this environment to validate binary and symbol behavior.

## Later platform alignment

- Initial motion analysis and active climbing remain at 2 fps and use only the immediately previous motion frame.
- Reliable pre-start/post-climb pose tracking and pose-loss recovery run at 4 fps; recovery timeout is 5 seconds.
- Motion activation is aligned to the Android 2.5% noise margin plus 2% adjusted threshold.
- Vision observations are reduced to one selected person: motion-nearest initially, previous-position-nearest afterward.
- Two consecutive misses after a normal finish return to initial motion search.
