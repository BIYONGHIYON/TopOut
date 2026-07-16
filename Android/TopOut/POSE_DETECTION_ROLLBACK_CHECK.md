# Pose detection rollback check

> **과거 변경 이력:** 이 문서는 이전 ML Kit 롤백 단계의 기록입니다. 현재 1.1 빌드는 `YOLO11N_POSE_MODEL_SWAP.md`의 YOLO11n-Pose 구성을 사용합니다.

The ML Kit pose analyzer is restored byte-for-byte from the immediately previous working Android build (`TopOut_Android_release_mlkit_pose_ios_clip_parity`).

Unchanged pose settings:

- ML Kit Accurate Pose Detection 18.0.0-beta5
- 0.5 s pose sampling
- 512 px maximum input long side
- 0.50 landmark confidence
- minimum 6 reliable landmarks
- minimum 3 shoulder/hip core landmarks
- 5 s no-pose timeout
- same mat-boundary and motion-trigger rules

The pre-padding short-range merge policy is retained outside the analyzer. Original start/end boundaries are reconstructed in `TopOutViewModel` from the unchanged padded events, so clip post-processing no longer requires modifications inside the pose detector.
