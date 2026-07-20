# TopOut iOS v1.3 startup sequence

- 시작 썸네일이 표시되는 동안 MobileSAM Encoder/Decoder를 먼저 로드합니다.
- 모델 로딩이 끝나기 전에는 카메라 권한 흐름, 캡처 세션 구성, `startRunning()`을 시작하지 않습니다.
- MobileSAM과 카메라 초기화를 겹치지 않게 실행해 순간 부하를 낮춥니다.
- 모델 로딩이 실패해도 카메라는 정상 진입하며, 매트 경계는 75% fallback을 사용합니다.
- 카메라 세션이 준비되면 시작 썸네일을 닫고 카메라 UI를 표시합니다.
- 카메라 프리뷰는 설정에서 선택한 해상도와 FPS를 유지합니다.
- 녹화 전 프리뷰 프레임을 계속 보관하지 않으며, 준비 완료 시 필요한 다음 프레임만 요청합니다.
- 녹화 동작, 출력 FPS, 출력 해상도는 변경하지 않았습니다.

## Runtime pose-frame queue optimization (v1.3 build 4)

- `MotionDetector` no longer enqueues every camera frame while Vision pose analysis is busy.
- At most one pending `CMSampleBuffer` is retained, and it is always replaced by the newest frame.
- Frame processing is re-enqueued one item at a time so recording state changes such as `configure` and `stop` are not starved.
- The existing 0.5-second pose-analysis interval remains unchanged.
