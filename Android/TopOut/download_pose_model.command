#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
DEST="$ROOT/app/src/main/assets/models/pose_landmarker_heavy.task"
TMP="$DEST.download"
URL="https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/1/pose_landmarker_heavy.task"

mkdir -p "$(dirname "$DEST")"
echo "MediaPipe Pose Landmarker Heavy 다운로드 중..."
curl -L --fail --retry 3 --connect-timeout 30 "$URL" -o "$TMP"
BYTES=$(wc -c < "$TMP" | tr -d ' ')
if [[ "$BYTES" -lt 30000000 ]]; then
  echo "다운로드 파일이 너무 작습니다: $BYTES bytes" >&2
  rm -f "$TMP"
  exit 1
fi
mv "$TMP" "$DEST"
echo "완료: $DEST ($BYTES bytes)"
