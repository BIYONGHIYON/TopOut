# TopOut Android v1.6 (build 12)

> 과거 릴리스 기록입니다. 현재 릴리스는 [v1.6 build 13](V1_6_BUILD_13_NOTES.md)이며 현재 동작은 [Android README](README.md)를 기준으로 합니다.

## Mat analysis recovery

- 사용자 피드백을 반영해 매트 경계 분석 대기를 5초로 제한했습니다.
- 제한 시간 안에 ONNX 분석이 끝나지 않으면 y=`75%` 기본 경계로 전환하고 사용자가 직접 보정해 촬영을 계속할 수 있습니다.
- 분석 요청과 watchdog을 하나의 요청 수명으로 관리하며, 완료·실패·재시도 시 기존 타이머를 취소합니다. 늦게 도착한 결과는 현재 선택을 덮어쓰지 않습니다.

## Review and copy parity

- 시작점 수정 후에는 새 시작점부터, 끝점 수정 후에는 새 끝점의 3초 전부터 자동 재생합니다.
- 선택 구간 확대 배율은 작은 손잡이를 정확히 누르지 않아도 슬라이더 트랙을 탭하거나 바로 드래그해 조절할 수 있습니다.
- 선택 구간 확대 배율을 천천히 조절하거나 양 끝에 도달할 때 느껴지던 불필요한 진동을 제거했습니다.
- 다시 촬영 확인창의 긴 경고 본문만 13sp로 줄여 버튼과 안내 문구를 쉽게 구분하도록 조정했습니다.
- 매트 보정, 안전한 녹화 종료와 저장 진행 문구를 iOS와 통일했습니다.
- 트림 미리보기 기준과 분석 제한 시간을 공용 설정값으로 모아 UI와 테스트의 숫자 중복을 제거했습니다.

## Low-memory and long-recording stability

- MobileSAM 프롬프트를 순차 평가하고 ONNX direct buffer를 재사용해 분석 시 중간 메모리와 복사를 줄였습니다.
- Motion/Pose의 2fps·4fps 주기는 유지하면서 luma 버퍼를 순환해 장시간 촬영의 반복 할당을 줄였습니다.
- 제한 시간이 지난 프레임 캡처와 분석 작업을 취소하고 늦게 완성된 Bitmap을 회수하도록 보강했습니다.

## Validation

- Android JVM 단위 테스트, Debug Kotlin 컴파일과 `lintDebug`를 통과했습니다.
- APK·AAB 같은 배포 산출물은 생성하지 않았습니다.

## Release identity

- Version name: `1.6`
- Version code: `12`
