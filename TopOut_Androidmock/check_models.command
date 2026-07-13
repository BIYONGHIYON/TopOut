#!/bin/bash
set -eu
ROOT="$(cd "$(dirname "$0")" && pwd)"
MODEL_DIR="$ROOT/app/src/main/assets/models"
ENCODER="$MODEL_DIR/mobile_sam_encoder.onnx"
DECODER="$MODEL_DIR/mobile_sam_decoder.onnx"
DETECTOR="$MODEL_DIR/efficientdet_lite2_float16.tflite"

missing=0
for file in "$ENCODER" "$DECODER" "$DETECTOR"; do
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
  echo "사람 탐지 모델이 없다면 ./download_person_detector_model.command 를 실행하세요."
  exit 1
fi

echo ""
echo "모델 파일 경로 확인 완료. Android Studio에서 Gradle Sync 후 app을 실행하세요."
