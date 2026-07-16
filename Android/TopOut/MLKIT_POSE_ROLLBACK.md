# Android ML Kit pose rollback

> **과거 변경 이력:** 이 문서는 이전 ML Kit 롤백 단계의 기록입니다. 현재 1.1 빌드는 `YOLO11N_POSE_MODEL_SWAP.md`의 YOLO11n-Pose 구성을 사용합니다.

The release app now uses Google ML Kit Accurate Pose Detection again.

## Restored automatic-detection pipeline

- `com.google.mlkit:pose-detection-accurate:18.0.0-beta5`
- mat-boundary motion gate with the existing three-second calibration
- independent single-image pose inference for start verification
- stream-mode pose inference during tracking
- pose analysis every 0.5 seconds
- pose input long side limited to 512 px
- minimum 6 reliable landmarks plus the existing shoulder/hip human gate
- five-second pose-loss timeout
- three-second clip padding

EfficientDet, MediaPipe Object Detector, custom bounding-box tracking, GPU delegate handling, and the detector-model download task were removed.

## Clip-range history

The 10-range limit remains removed. All raw detected ranges are retained until recording finalization. Overlap merging and the existing short-range/three-second-neighbour cleanup run before every valid range is shown in the result screen.
