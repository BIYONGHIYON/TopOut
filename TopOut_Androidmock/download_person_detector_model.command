#!/bin/zsh
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEST="$ROOT_DIR/app/src/main/assets/models/efficientdet_lite2_float16.tflite"
TEMP="$DEST.download"
URL="https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/float16/latest/efficientdet_lite2.tflite"
mkdir -p "$(dirname "$DEST")"
rm -f "$TEMP"
trap 'rm -f "$TEMP"' EXIT
curl -L --fail --retry 3 --connect-timeout 30 "$URL" -o "$TEMP"
SIZE=$(stat -f%z "$TEMP" 2>/dev/null || stat -c%s "$TEMP")
if [[ "$SIZE" -lt 12000000 ]]; then
  echo "Model download is incomplete: $SIZE bytes"
  exit 1
fi
mv "$TEMP" "$DEST"
trap - EXIT
echo "Saved: $DEST ($SIZE bytes)"
