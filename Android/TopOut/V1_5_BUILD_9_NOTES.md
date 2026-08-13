# TopOut Android v1.5 (build 9)

> 과거 릴리스 기록입니다. 현재 릴리스는 [v1.6 build 12](V1_6_BUILD_12_NOTES.md)이며 현재 동작은 [Android README](README.md)를 기준으로 합니다.

## Camera readiness

- `준비 완료`는 CameraX Preview가 `STREAMING` 상태이고 선택한 줌이 적용된 뒤에만 활성화됩니다.
- 자동 매트 경계는 Ready 이후 새 ImageAnalysis 프레임 6장을 건너뛴 프레임으로 계산해 콜드 스타트, 3A 안정화, 렌즈 전환 직후의 부정확한 입력을 피합니다.
- 분석 프레임은 UI에 표시하지 않으며 매트 보정·탑홀드 선택은 라이브 프리뷰 위에서 진행합니다.
- Preview, VideoCapture, ImageAnalysis는 같은 ViewPort와 화면비 좌표계를 공유합니다.
- Ready 이후 렌즈·줌·회전 기준을 고정하고, 탑홀드는 매 탭마다 새 프레임으로 다시 계산합니다.

## Detection and device compatibility

- MoveNet human gate는 어깨·골반·팔꿈치·무릎 4개 그룹 중 3개 이상이 confidence 0.25를 만족할 때 통과합니다.
- 정확한 60fps가 활성 CameraX 세션 전체에서 보장되지 않으면 30fps로 폴백합니다.
- 논리 멀티 카메라의 0.5x를 우선하고 물리 카메라 전환을 폴백으로 사용합니다.
- Android 16 대화면과 Fold 계열에서 Ready 이후 불필요한 재바인딩과 회전 상태 손실을 방지합니다.
- Android 9(API 28) 이하의 갤러리 저장 권한 흐름을 지원합니다.

## Review and saved-video playback

- MediaStore 저장 후 같은 content URI를 다시 열어 파일 디스크립터, 입력 스트림, 영상 메타데이터로 실제 갤러리 항목을 검증합니다.
- 클립별 저장 상태와 URI를 분리해 여러 구간을 각각 저장하고 확인할 수 있습니다.
- 저장 영상 보기의 뒤로/앞으로 이동 간격을 모두 5초로 통일했습니다.
- Media3 TimeBar를 드래그하는 동안 실제 재생 위치를 즉시 이동하고, 드래그 종료 시 정확한 위치로 맞춥니다.
- 기본 컨트롤의 전체 화면·하단 검정 스크림을 각각 약 18%, 32%로 낮춰 영상 가림을 줄였습니다.
- Fold처럼 세로 공간이 짧은 화면에서도 슬라이더와 `편집 화면으로 돌아가기` 버튼이 겹치지 않습니다.
