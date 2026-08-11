# iOS optimization audit — v1.4 (build 5)

> 이 문서는 v1.4 build 5 최적화 당시의 감사 기록입니다. 현재 릴리스는 [v1.5 build 11](V1_5_BUILD_11_NOTES.md)이며 최신 동작은 [iOS README](README.md)를 기준으로 합니다.

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
- This audit snapshot was created at version 1.4 build 5; the current project identity is 1.5 build 11.

## Left unchanged deliberately

- Per-analysis small-frame luma allocation: one current and one previous 160px luma frame are retained; safe pooling still needs runtime profiling.
- Release stripping/build settings: no Xcode archive was available in this environment to validate binary and symbol behavior.

## Later platform alignment

- Initial motion analysis and active climbing remain at 2 fps and use only the immediately previous motion frame.
- Reliable pre-start/post-climb pose tracking and pose-loss recovery run at 4 fps; recovery timeout is 6 seconds.
- Motion activation is aligned to the Android 2.5% noise margin plus 2% adjusted threshold.
- Vision observations are combined into one non-face joint set, matching the earlier iOS pose behavior; the removed person-selection and body-group validation code no longer adds state or per-frame distance calculations.
- Two consecutive misses after a normal finish return to initial motion search.
