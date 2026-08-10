# TopOut Android v1.5 (build 10)

## Navigation and recording safety

- 선택 화면의 시스템 뒤로가기는 프리뷰 복귀 동작으로 통일했습니다.
- 카운트다운 또는 녹화 중 시스템 뒤로가기는 Activity를 종료하지 않고 안전한 녹화 종료 경로를 실행합니다.
- 영상 정리와 갤러리 저장 중에는 시스템 뒤로가기를 소비해 CameraX finalization, Media3 export, MediaStore 쓰기가 중간에 취소되지 않도록 했습니다.

## Playback lifecycle

- 결과 영상과 실제 MediaStore 저장 영상은 앱이 백그라운드로 이동하면 즉시 일시정지합니다.
- 저장 영상 보기의 앞/뒤 5초 이동, 실시간 슬라이더 탐색, 낮은 컨트롤 스크림 투명도는 그대로 유지합니다.

## Quality and release validation

- ViewModel의 `LifecycleOwner`와 `PreviewView` 참조를 약한 참조로 변경해 화면 재생성 후 이전 Activity가 유지될 위험을 줄였습니다.
- Media3 Transformer 사용 범위에 `UnstableApi` opt-in을 명시해 release lint 오류를 해소했습니다.
- Android JVM 단위 테스트 55개와 `lintRelease`를 통과했습니다.

## Release identity

- Version name: `1.5`
- Version code: `10`
