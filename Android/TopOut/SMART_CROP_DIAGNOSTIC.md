# Smart Crop False-Positive Diagnostic

This diagnostic build emits one `INFO` log per MoveNet inference (normally every 0.5 seconds) using the tag `TopOutCrop`.

Android Studio Logcat filter:

```text
tag:TopOutCrop
```

ADB:

```bash
adb logcat -s TopOutCrop:I
```

Important fields:

- `human`: whether the existing body-structure gate passed.
- `crop`: whether smart crop is already enabled.
- `joints`: reliable body keypoints out of 12.
- `core`: reliable shoulders and hips out of 4.
- `win`: valid pose frames collected for the 3-frame motion gate.
- `size`: detected body-keypoint bounding-box diagonal in normalized image coordinates.
- `threshold`: 5% movement threshold derived from `size`.
- `common`: same keypoints present in all three frames.
- `moved`: keypoints whose first-to-third-frame displacement reached the threshold.
- `top`: up to three largest movements as a percentage of detected person size.
- `unlock`: whether this frame enabled smart crop.

Joint abbreviations: LS/RS shoulder, LE/RE elbow, LW/RW wrist, LH/RH hip, LK/RK knee, LA/RA ankle.

For diagnosis, record an empty climbing wall until a false detection occurs and share the lines around the first `unlock=true` entry, including roughly five lines before and after it.

Post-unlock tracking behavior:

- First consecutive `human=false`: smart crop remains locked, confirmation resets, and the log shows `gate=LOCKED miss=1/2 confirm=0/2`.
- A following `human=true`: the miss count resets to zero and two-frame human confirmation starts again.
- Second consecutive `human=false`: smart crop and MoveNet tracking are reset, and the log shows `crop=false gate=RESET`.
