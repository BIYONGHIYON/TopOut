# TopOut Android v1.6 (build 13)

> 과거 릴리스 기록입니다. 현재 릴리스는 [v3.0 versionCode 23](V3_0_BUILD_23_NOTES.md)이며 현재 동작은 [Android README](README.md)를 기준으로 합니다.

## Detection parity

- Android의 등반 시작선을 편집된 매트 경계보다 화면 높이 6% 위로 조정해 iOS와 동일한 시작 여백을 사용합니다.
- 종료선 2%, 상태별 2fps·4fps 분석 주기와 기존 Pose 판정 흐름은 유지합니다.

## Validation

- Android JVM 단위 테스트, Debug Kotlin 컴파일과 `lintDebug`를 통과했습니다.
- APK·AAB 같은 배포 산출물은 생성하지 않습니다.

## Release identity

- Version name: `1.6`
- Version code: `13`
