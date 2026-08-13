# TopOut Android v1.5 (build 7)

> 과거 릴리스 기록입니다. 현재 릴리스는 [v1.6 build 12](V1_6_BUILD_12_NOTES.md)이며 현재 동작은 [Android README](README.md)를 기준으로 합니다.

## Camera and device compatibility

- Uses CameraX `SessionConfig` to accept 60 fps only when the exact Preview + VideoCapture (+ ImageAnalysis) session guarantees 60..60 fps.
- Prefers the OEM logical rear camera for seamless 0.5x zoom on supported Samsung/Pixel devices; physical ultra-wide camera binding remains a fallback.
- Keeps the selected capture orientation/crop immutable after Ready so Android 16 large-screen rotation policy cannot rebind the recording session.
- Uses `fullUser` orientation behavior and tracks actual display rotation before Ready, including 180-degree changes.
- Requires legacy shared-storage permission on Android 8/9 before entering the camera flow.

## Selection and recording UX

- Ready-time frame is used only for MobileSAM mat-boundary inference and is never displayed as a frozen selection image.
- Mat boundary correction is drawn over the live preview.
- Camera zoom/lens changes are disabled after Ready.
- Every top-hold tap captures a fresh displayed frame and runs MobileSAM on that frame.
- Automatic-detection recording can show a low-opacity read-only mat boundary over the live preview.
- Review retake preserves the previous capture orientation plus the existing mat/hold selection.

## Review and range handling

- Retake warning: `기존의 매트경계 위치를 유지하여 다시 촬영합니다.
저장하지 않은 영상이 삭제됩니다.`
- Close warning: `저장하지 않은 영상이 삭제됩니다.`
- Stop-time open ranges and queued finish events with the same start are coalesced instead of creating duplicate review clips.

### Android UI readability
- Increased camera/action button label sizes for large/high-density displays such as Galaxy Fold while keeping existing button dimensions.
- Button and segmented-control labels now use Compose text auto-sizing so labels grow when space allows and shrink within a safe minimum instead of being ellipsized or clipped.
- Slightly increased guidance, zoom, recording timer, settings section, and interactive settings text sizes.
