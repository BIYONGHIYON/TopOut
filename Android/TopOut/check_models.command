#!/bin/bash
set -eu
ROOT="$(cd "$(dirname "$0")" && pwd)"
MODEL_DIR="$ROOT/app/src/main/assets/models"
ENCODER="$MODEL_DIR/mobile_sam_encoder.onnx"
DECODER="$MODEL_DIR/mobile_sam_decoder.onnx"
POSE="$MODEL_DIR/pose_landmarker_heavy.task"

missing=0
for file in "$ENCODER" "$DECODER" "$POSE"; do
  if [[ -f "$file" && -s "$file" ]]; then
    size=$(du -h "$file" | awk '{print $1}')
    echo "✓ $(basename "$file") ($size)"
  else
    echo "✗ 누락: $file"
    missing=1
  fi
done

if [[ $missing -ne 0 ]]; then
  echo ""
  echo "누락된 모델을 준비해 주세요. Pose 모델은 ./download_pose_model.command 로 받을 수 있습니다."
  exit 1
fi

echo ""
echo "모델 파일 경로 확인 완료. Android Studio에서 Gradle Sync 후 app을 실행하세요."
