# TopOut Android v2.3 (versionCode 20)

## 릴리스 기준

- Version name: `2.3`
- Google Play versionCode: `20`
- Minimum SDK `26`, Compile / Target SDK `36` 유지

## 주요 변경 사항

- 시작 화면을 iOS와 같이 고정 1초 표시 없이 운영: 권한 요청 중에는 시작 화면 위에 시스템 권한 창을 표시하고, 권한 거부 시 권한 안내로, 허용 시 시작 화면 아래에서 카메라를 연결한 뒤 연결 성공·실패 시점에 닫음
- 녹화나 결과 검토 중 앱이 강제 종료되거나 OS가 프로세스를 정리해 cache에 남은 `topout_raw_*`·`topout_export_*` 임시 영상을 다음 실행의 ViewModel 생성 시 IO dispatcher에서 삭제하고, 이번 실행 이후 파일은 유지
- MoveNet 모델 다운로드를 404로 종료된 tfhub.dev 링크에서 같은 원본을 제공하는 Kaggle Models 아카이브로 교체하고 고정 SHA-256으로 다운로드·캐시·generated asset을 검증. 앱에 들어가는 모델 파일은 v2.2와 동일
- `CameraEngine`에서 후면 렌즈 탐색·렌즈별 기능 조회·분석 크기와 ViewPort 계산을 별도 파일로 분리 (동작 변경 없음)

## 릴리스 전 확인

- 첫 실행에서 카메라·마이크 권한 허용·거부 각각의 시작 화면 전환 확인
- 권한이 이미 있는 상태의 앱 시작과 언어 변경 후 재구성에서 시작 화면이 카메라 연결 직후 닫히는지 확인
- 녹화 중 앱 강제 종료 후 재실행 시 `adb shell run-as com.topout.cam ls cache`에서 이전 임시 영상이 정리되는지 확인
- 렌즈 배율 전환, 4K·60fps 지원 표시, 결과 저장 확인
- `./check_models.command`로 MoveNet SHA-256 확인
- Google Play 업로드 전 versionName `2.3`, versionCode `20` 확인
