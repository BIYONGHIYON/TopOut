# TopOut 1.5 (Build 8)

> 과거 릴리스 기록입니다. 현재 릴리스는 [v3.0 build 23](V3_0_BUILD_23_NOTES.md)이며 현재 동작은 [iOS README](README.md)를 기준으로 합니다.

## Saved-video verification
- Saving is no longer considered complete solely because the export/write API returned success.
- The app reopens the exact media item created in the system gallery before showing verified success.
- The result screen changes the save button to `저장된 영상 확인` for the exact trim/mask selection that was verified.
- Viewing uses the actual Photos/MediaStore item rather than the temporary export file.

## iOS
- Saves with PhotoKit and captures the created PHAsset local identifier.
- Fetches that exact PHAsset and requests its AVAsset before reporting verified success.
- Supports full or limited Photos access; app-created assets are available in the limited-library selection.
- Suppresses the automatic limited-library expansion prompt because TopOut does not browse existing library items.

## Android
- Queries the exact MediaStore content URI after publishing it.
- Reopens the file descriptor and input stream and validates video metadata before reporting verified success.
- Playback uses that same MediaStore content URI.

Version: 1.5
Build: 8
