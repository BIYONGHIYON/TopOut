# TopOut iOS startup sequence

- 시작 썸네일이 표시되는 동안 MobileSAM Encoder/Decoder를 먼저 로드합니다.
- 모델 로딩이 끝나기 전에는 카메라 권한 흐름, 캡처 세션 구성, `startRunning()`을 시작하지 않습니다.
- MobileSAM과 카메라 초기화를 겹치지 않게 실행해 순간 부하를 낮춥니다.
- 모델 로딩이 실패해도 카메라는 정상 진입하며, 매트 경계는 75% fallback을 사용합니다.
- 카메라 세션이 준비되면 시작 썸네일을 닫고 카메라 UI를 표시합니다.
- 카메라 프리뷰는 설정에서 선택한 해상도와 FPS를 유지합니다.
- 실제 비디오 프레임 6장이 도착하기 전에는 `준비 완료`를 활성화하지 않습니다.
- 자동 매트 분석은 Ready 이후 최소 4개의 새 프레임과 0.2초를 기다리고, 노출·초점·화이트밸런스가 2프레임 연속 안정된 프레임을 우선 사용합니다.
- 3A가 계속 조정 중이면 0.9초 뒤 최신 프레임으로 진행하며 프레임 요청 전체 제한은 1.8초입니다.
- 분석용 이미지는 MobileSAM 입력에만 사용하며 선택 화면은 라이브 프리뷰를 유지합니다.
- 녹화 동작, 출력 FPS, 출력 해상도는 변경하지 않았습니다.

## Runtime pose-frame queue optimization (v1.3 build 4)

- `MotionDetector` no longer enqueues every camera frame while Vision pose analysis is busy.
- At most one pending `CMSampleBuffer` is retained, and it is always replaced by the newest frame.
- Frame processing is re-enqueued one item at a time so recording state changes such as `configure` and `stop` are not starved.
- The existing 0.5-second pose-analysis interval remains unchanged.
