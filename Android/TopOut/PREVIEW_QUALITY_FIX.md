# Ready selection preview and stable-frame fix

## Previous symptom

초기 구현은 `준비 완료` 시점의 ImageAnalysis 비트맵을 선택 화면에 확대 표시해 라이브 CameraX 프리뷰보다 흐리게 보였습니다. 이후 정지 화면을 제거한 뒤에는 앱 콜드 스타트 직후 너무 빨리 Ready를 누를 경우 CameraX/3A/줌이 안정되기 전 프레임이 MobileSAM 입력으로 들어가 첫 매트 경계가 부정확할 수 있었습니다.

## Introduced in v1.5 build 9

- 선택 화면은 정지 이미지를 표시하지 않고 실제 `PreviewView`의 라이브 프리뷰를 유지합니다.
- Preview가 `STREAMING` 상태이고 선택한 줌이 적용되기 전에는 Ready를 활성화하지 않습니다.
- 자동 매트 분석은 Ready 요청 이후 새 ImageAnalysis 프레임 6장을 건너뛴 프레임을 사용합니다.
- Preview, VideoCapture, ImageAnalysis는 동일한 CameraX `ViewPort`와 화면비 좌표계를 사용합니다.
- 자동 매트용 이미지와 embedding은 분석 후 해제하며 탑홀드 탭에는 재사용하지 않습니다.
- 탑홀드는 누르는 순간의 새 프레임과 새 embedding으로 계산합니다.
- ImageAnalysis를 제공하지 못하는 OEM에서는 카메라 준비 gate를 통과한 뒤의 최신 `PreviewView` snapshot을 폴백으로 사용합니다.

이 구조는 화면 선명도와 좌표 정합성을 함께 유지하고, 앱 실행 직후 첫 매트 경계가 재촬영 때보다 부정확해지는 문제를 방지합니다.
