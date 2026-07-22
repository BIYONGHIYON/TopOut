# Motion-seeded Smart Crop Diagnostic

The current Android detector no longer waits for three full-frame pose results before enabling Smart Crop.

## Current sequence

```text
current frame vs. three analysis frames earlier
        ↓
rolling CV start candidate
        ↓
current frame vs. immediately previous frame
        ↓
full-frame motion-cell scoring
        ↓
pixel-square ROI covering approximately 25% of screen area
        ↓
MoveNet Thunder on the ROI
        ↓
first reliable human pose seeds pose-derived tracking
        ↓
climb-start judgment is enabled immediately
```

## Motion ROI parameters

- grid columns: 8
- grid rows: calculated from frame aspect ratio so cells remain approximately square
- selected cells: up to 8
- relative selection threshold: 45% of the strongest cell score
- absolute changed-pixel threshold: 2% per cell
- ROI area: approximately 25% of the visible frame
- ROI shape: square in source pixels to avoid MoveNet aspect distortion

If the selected high-motion cells fit inside the ROI, the ROI is positioned to contain them all. If they are too spread out, a sliding weighted window selects the cluster with the greatest total motion score.

## Recovery behavior

- Before reliable joints are found, the motion ROI can be refreshed from each new immediate-frame difference.
- The first reliable cropped pose starts pose-derived tracking and is immediately eligible for climb-start judgment.
- Two consecutive invalid or missing TRACKING results return to motion-seed mode.
- After a climb ends, the analyzer returns to the rolling three-frame CV gate before searching for the next attempt.

## Analysis rate

All analyzer states use a fixed 2 Hz rate. Calibration, waiting, motion ROI refresh, and pose inference no longer switch between separate 8 Hz, 6 Hz, and 12 Hz schedules.
