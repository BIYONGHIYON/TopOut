#!/bin/bash
set -eu
ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_MODEL_DIR="$ROOT/app/src/main/assets/models"
GENERATED_MODEL_DIR="$ROOT/app/build/generated/moveNetPoseAssets/models"
ENCODER="$SOURCE_MODEL_DIR/mobile_sam_encoder.onnx"
DECODER="$SOURCE_MODEL_DIR/mobile_sam_decoder.onnx"
MOVENET="$GENERATED_MODEL_DIR/movenet-thunder-fp16.tflite"

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

# app/build.gradle.kts의 moveNetPoseModelSha256과 같은 값을 유지한다.
MOVENET_SHA256="41641538679ec79b07d4101e591dda47d098c09af29607674b2a40b8a3798dd3"
if [[ -f "$MOVENET" && -s "$MOVENET" ]]; then
  checksum=$(shasum -a 256 "$MOVENET" | awk '{print $1}')
  if [[ "$checksum" == "$MOVENET_SHA256" ]]; then
    size=$(du -h "$MOVENET" | awk '{print $1}')
    echo "✓ $(basename "$MOVENET") ($size, SHA-256 확인)"
  else
    echo "✗ MoveNet 파일의 SHA-256이 일치하지 않습니다: $checksum"
    missing=1
  fi
else
  echo "△ MoveNet 모델 미생성: ./gradlew downloadMoveNetPoseModel 실행 필요"
  missing=1
fi

if [[ $missing -ne 0 ]]; then
  echo ""
  echo "MobileSAM 파일을 확인한 뒤 Gradle Sync 또는 ./gradlew downloadMoveNetPoseModel을 실행해주세요."
  exit 1
fi

echo ""
echo "모델 파일 경로와 형식 확인 완료."
