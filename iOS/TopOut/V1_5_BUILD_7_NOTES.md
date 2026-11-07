# TopOut iOS v1.5 (build 7)

> 과거 릴리스 기록입니다. 현재 릴리스는 [v3.0 build 23](V3_0_BUILD_23_NOTES.md)이며 현재 동작은 [iOS README](README.md)를 기준으로 합니다.

## Selection and recording UX

- Ready-time video frame is used only for MobileSAM mat-boundary inference and is never displayed as a frozen selection image.
- Mat boundary correction is drawn over the live camera preview.
- Camera zoom is disabled as soon as Ready is pressed and remains fixed through selection/recording.
- Every top-hold tap captures a fresh video frame and runs MobileSAM on that frame.
- Automatic-detection recording can show a low-opacity read-only mat boundary over the live preview.

## Review and range handling

- Retake warning: `기존의 매트경계 위치를 유지하여 다시 촬영합니다.
저장하지 않은 영상이 삭제됩니다.`
- Close warning: `저장하지 않은 영상이 삭제됩니다.`
- Stop-time open ranges and queued finish events with the same start are coalesced instead of creating duplicate review clips.

## Release identity

- Version: `1.5`
- Build: `7`

The existing multi-person Vision pose aggregation behavior is intentionally unchanged in this release.
