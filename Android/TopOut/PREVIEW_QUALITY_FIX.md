# Ready selection preview quality fix

## Symptom
After pressing **Ready**, the frozen selection preview looked visibly softer than the live CameraX preview.

## Root cause
`TopOutViewModel.onReady()` preferred a CPU `ImageAnalysis` snapshot capped at a 1600px long side.
That YUV-to-RGB bitmap was intended for analysis, but it was also enlarged and displayed by
`SelectionEditor`. The live preview itself remained sharp; only the frozen Ready frame was degraded.

## Fix
- `PreviewView.bitmap` is now the primary and single frame for both the selection screen and MobileSAM.
- MobileSAM already resizes its input to 1024px internally, so this does not increase model tensor size.
- The separate ImageAnalysis capture is skipped when the PreviewView bitmap is available, reducing
  YUV conversion work and preventing a time mismatch between the displayed frame and detected line.
- ImageAnalysis remains a fallback for devices where `PreviewView.bitmap` is temporarily unavailable.
- When automatic mat detection runs, its embedding is reused for top-hold selection on the exact same frame.

Version remains **1.3**, build number **4**.
