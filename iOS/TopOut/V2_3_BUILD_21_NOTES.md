# TopOut iOS v2.3 (build 21)

## 릴리스 기준

- Marketing Version: `2.3`
- Current Project Version: `21`
- Deployment Target: iOS `16.0` 유지
- build 20은 App Store Connect에 이미 업로드되어 재업로드가 ITMS-90189로 거절되어, 코드 변경 없이 빌드 번호만 21로 올림

## 주요 변경 사항

- AVAudioSession 카테고리 설정과 활성화·비활성화를 주 스레드에서 호출하지 않도록 `AudioSessionQueue` 전용 직렬 큐로 이동해 Xcode `AVAudioSession Hang Risk` 경고 대응
- 녹화용 오디오 구성과 마이크 해제는 촬영 세션 큐를 거쳐 오디오 세션 큐에서 처리해, 마이크 입력 해제 → 비활성화 → 재활성화 → 입력 재연결 순서를 유지
- 결과 화면의 재생용 오디오 전환은 비동기로 진행하고, 통화 등으로 실패하면 영상 타임라인은 유지한 채 음소거로 폴백
- 녹화나 결과 검토 중 앱이 강제 종료되거나 OS가 프로세스를 정리해 남은 `TopOutContinuous` 임시 녹화 폴더를 다음 실행 시 백그라운드에서 삭제하고, 이번 실행의 녹화 폴더는 유지
- `ReviewVC`를 레이아웃·구간 편집·버튼 동작·재생 확장 파일로 분리 (동작 변경 없음)
- Xcode 권장 설정 중 Missing Localizability 정적 분석과 String Catalog 심볼 생성 활성화

## 릴리스 전 확인

- 카메라 진입, 녹화, 결과 화면 진입, 결과 닫기, 재촬영을 거치며 Xcode Issue Navigator에 `AVAudioSession Hang Risk`가 새로 표시되지 않는지 확인
- 녹화 영상에 소리가 정상 기록되는지, 통화 중 촬영에서는 영상만 계속 기록되는지 확인
- 결과 화면 재생 소리와 사진 앱 저장본 재생 확인
- 녹화 중 앱 강제 종료 후 재실행 시 앱 컨테이너 `tmp/TopOutContinuous`의 이전 폴더가 정리되는지 확인
- App Store 업로드 전 Marketing Version `2.3`, Current Project Version `21` 확인
