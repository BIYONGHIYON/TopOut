# TopOut Android v1.5 (build 10)

TopOut Android는 오디오가 포함된 연속 촬영에서 등반 구간을 온디바이스로 탐지하고, 결과를 정밀 편집해 시스템 갤러리에 저장·검증하는 앱입니다.

**출시 상태: 출시 완료**

## 현재 릴리스

- Application ID: `com.topout.cam`
- Minimum SDK: `26` (Android 8.0)
- Compile / Target SDK: `36`
- Version name / code: `1.5` / `10`
- UI: Kotlin, Jetpack Compose, Material 3
- Camera / media: CameraX 1.6.1, Media3 1.10.1
- AI: MobileSAM ONNX Runtime 1.27.0, MoveNet Thunder FP16 LiteRT 1.4.1

## 촬영 준비와 매트 경계

- CameraX Preview, VideoCapture, ImageAnalysis는 동일한 `ViewPort` 좌표계를 사용합니다.
- `준비 완료`는 CameraX가 실제 `STREAMING` 상태이고 선택한 줌이 적용된 뒤에만 활성화됩니다.
- 자동 매트 분석은 Ready 이후 새 ImageAnalysis 프레임 6장을 건너뛴 안정 프레임을 사용합니다. 분석용 비트맵은 화면에 표시하지 않고 선택 화면은 라이브 프리뷰를 유지합니다.
- MobileSAM은 화면 중앙의 y=`70%`, `80%`, `90%`, `95%` 프롬프트를 사용하며 후보가 없으면 y=`75%` 기본선을 제안합니다.
- Ready 이후 렌즈·줌·회전 기준을 고정합니다. 탑홀드는 탭할 때마다 새 프레임과 새 임베딩으로 계산합니다.
- 논리 멀티 카메라의 0.5x를 우선 사용하고, 필요할 때 물리 카메라 전환으로 보완합니다. 물리 카메라의 기본 native zoom은 1.0x입니다.
- 60fps는 Preview, VideoCapture, ImageAnalysis가 함께 바인딩된 실제 세션에서 CameraX가 정확한 60fps를 보장할 때만 사용하고, 그렇지 않으면 30fps로 폴백합니다.

## 자동 구간 탐지

- Motion: 160px luma, 현재 프레임과 직전 분석 프레임 비교, 기본 2fps
- 초기 탐색: 편집된 매트선 위 화면 높이 40% 영역
- Pose 입력: 변화 픽셀로 만든 화면 면적 약 25%의 정사각형 ROI를 YUV에서 256×256 RGB로 직접 샘플링
- Pose: MoveNet SinglePose Thunder FP16, confidence `0.25`
- Human gate: 어깨·골반·팔꿈치·무릎 4개 그룹 중 3개 이상 충족
- 시작선: x별 매트선보다 화면 높이 `4%` 위
- 종료선: x별 매트선보다 화면 높이 `2%` 위
- 시작 전·종료 후·Pose 유실 복구: 4fps
- 활성 등반 Pose 유실 허용: 최대 6초
- 정상 종료 후 Pose 2회 연속 미검출: 초기 Motion 탐색으로 복귀

전체 화면 512px RGB Pose 폴백은 사용하지 않습니다. 초기 Motion ROI를 만들 수 없으면 WAITING 상태에 머뭅니다.

## 결과 편집과 갤러리 검증

- 녹화 중 감지된 모든 원본 구간을 보존하고, 2초 미만의 짧은 구간만 인접 구간과의 간격이 3초 이내일 때 병합합니다.
- 최종 후보에는 앞뒤 3초 편집 여백을 적용합니다.
- 결과 화면에서 구간 확대, 0.1초 미세 조정, 중복 구간 방지, 같은 촬영 상태를 유지한 재촬영을 지원합니다.
- 내보낸 영상은 MediaStore에 게시한 뒤 같은 content URI를 다시 조회하고 파일 디스크립터·입력 스트림·메타데이터를 열어 실제 저장 결과를 검증합니다.
- 검증된 구간은 임시 export 파일이 아니라 같은 MediaStore URI로 재생합니다.
- 저장 영상 보기의 앞/뒤 이동은 모두 5초이며, 슬라이더를 움직이는 동안 재생 위치가 즉시 따라갑니다. Media3 기본 컨트롤의 전체 화면·하단 스크림 투명도도 영상 가독성을 위해 낮췄습니다.
- 여러 구간을 저장해도 구간별 검증 상태와 URI를 각각 유지합니다.

## 기기 대응

- Android 15 edge-to-edge와 16KB page-size 패키징
- Android 16 대화면에서 orientation 요청이 무시돼도 Ready 시점의 회전·구도를 세션 동안 고정
- Fold 계열의 짧고 넓은 화면에서 저장 영상 슬라이더와 하단 복귀 버튼이 겹치지 않는 레이아웃
- Android 9(API 28) 이하 저장 권한 흐름
- 앱 백그라운드, 카메라 중단, 저장공간 부족 시 원본 파일을 먼저 안전하게 종료
- 선택 화면의 시스템 뒤로가기는 프리뷰로 복귀하고, 카운트다운·녹화 중에는 안전한 종료 경로를 실행
- 영상 정리·갤러리 저장 중에는 시스템 뒤로가기로 Activity가 종료되지 않도록 보호
- 결과 영상과 실제 갤러리 저장본은 앱이 백그라운드로 이동하면 자동 일시정지
- 화면 재생성 시 ViewModel이 이전 Activity와 PreviewView를 유지하지 않도록 약한 참조 사용

## 모델 파일과 빌드

MobileSAM 모델은 다음 위치에 유지해야 합니다.

```text
app/src/main/assets/models/mobile_sam_encoder.onnx
app/src/main/assets/models/mobile_sam_decoder.onnx
```

MoveNet 모델은 `downloadMoveNetPoseModel` 태스크가 공식 파일을 내려받아 TFLite FlatBuffer를 검증하고 Gradle 캐시에 보관한 뒤 다음 generated assets 경로로 복사합니다.

```text
app/build/generated/moveNetPoseAssets/models/movenet-thunder-fp16.tflite
```

첫 빌드는 네트워크가 필요하며, 이후 오프라인 빌드에는 유효한 캐시가 필요합니다.

```bash
./gradlew downloadMoveNetPoseModel
./check_models.command
./gradlew testDebugUnitTest assembleDebug
```

## 문서

- [v1.5 build 10 변경 사항](V1_5_BUILD_10_NOTES.md)
- [Android 호환성](ANDROID_COMPATIBILITY_NOTES.md)
- [기기 호환성 수정](DEVICE_COMPATIBILITY_FIX_NOTES.md)
- [MobileSAM ONNX 배치와 진단](docs/ONNX_INTEGRATION.md)
- [iOS UI/UX 대응표](docs/UI_PARITY.md)
- 과거 릴리스 기록: [v1.5 build 9](V1_5_BUILD_9_NOTES.md), [v1.5 build 8](V1_5_BUILD_8_NOTES.md), [v1.5 build 7](V1_5_BUILD_7_NOTES.md), [v1.4 build 5](V1_4_BUILD_5_NOTES.md)
