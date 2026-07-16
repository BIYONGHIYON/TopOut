#!/bin/bash
set -eu
ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_MODEL_DIR="$ROOT/app/src/main/assets/models"
GENERATED_MODEL_DIR="$ROOT/app/build/generated/yoloPoseAssets/models"
ENCODER="$SOURCE_MODEL_DIR/mobile_sam_encoder.onnx"
DECODER="$SOURCE_MODEL_DIR/mobile_sam_decoder.onnx"
YOLO="$GENERATED_MODEL_DIR/yolo11n-pose.onnx"
YOLO_SHA="70bd721f9cb797eb44cbc70bc65213397a0a26da38fe6fd5ccdf699016d33d3c"

missing=0
for file in "$ENCODER" "$DECODER"; do
  if [[ -f "$file" && -s "$file" ]]; then
    size=$(du -h "$file" | awk '{print $1}')
    echo "✓ $(basename "$file") ($size)"
  else
    echo "✗ 누락: $file"
    missing=1
  fi
done

if [[ -f "$YOLO" && -s "$YOLO" ]]; then
  actual=$(shasum -a 256 "$YOLO" | awk '{print $1}')
  if [[ "$actual" == "$YOLO_SHA" ]]; then
    size=$(du -h "$YOLO" | awk '{print $1}')
    echo "✓ $(basename "$YOLO") ($size, SHA-256 확인)"
  else
    echo "✗ YOLO SHA-256 불일치: $actual"
    missing=1
  fi
else
  echo "△ YOLO 모델 미생성: ./gradlew downloadYoloPoseModel 실행 필요"
  missing=1
fi

if [[ $missing -ne 0 ]]; then
  echo ""
  echo "MobileSAM 파일을 확인한 뒤 Gradle Sync 또는 ./gradlew downloadYoloPoseModel을 실행해주세요."
  exit 1
fi

echo ""
echo "모델 파일 경로 및 체크섬 확인 완료."
