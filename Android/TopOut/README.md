# TopOut Android v1.6 (build 12)

TopOut Android는 오디오가 포함된 연속 촬영에서 등반 구간을 온디바이스로 탐지하고, 결과를 정밀 편집해 시스템 갤러리에 저장·검증하는 앱입니다.

**출시 상태: 출시 완료**

[Google Play에서 TopOut 보기](https://play.google.com/store/apps/details?id=com.topout.cam)

## 현재 릴리스

- Application ID: `com.topout.cam`
- Minimum SDK: `26` (Android 8.0)
- Compile / Target SDK: `36`
- Version name / code: `1.6` / `12`
- UI: Kotlin, Jetpack Compose, Material 3
- Camera / media: CameraX 1.6.1, Media3 1.10.1
- AI: MobileSAM ONNX Runtime 1.27.0, MoveNet Thunder FP16 LiteRT 1.4.1

## 촬영 준비와 매트 경계

- 앱과 카메라 진입은 MobileSAM ONNX 세션 준비를 기다리지 않습니다. CameraX 프리뷰가 실제로 준비된 뒤 모델을 백그라운드에서 한 번만 준비해 메모리 제약 기기의 시작 부하를 줄입니다.
- CameraX Preview, VideoCapture, ImageAnalysis는 동일한 `ViewPort` 좌표계를 사용합니다.
- `준비 완료`는 CameraX가 실제 `STREAMING` 상태이고 선택한 줌이 적용된 뒤에만 활성화됩니다.
- 자동 매트 분석은 Ready 이후 새 ImageAnalysis 프레임 6장을 건너뛴 안정 프레임을 사용합니다. 분석용 비트맵은 화면에 표시하지 않고 선택 화면은 라이브 프리뷰를 유지합니다.
- MobileSAM은 화면 중앙의 y=`70%`, `80%`, `90%`, `95%` 프롬프트를 사용하며 후보가 없으면 y=`75%` 기본선을 제안합니다.
- ONNX 매트 분석이 5초 안에 끝나지 않으면 대기를 끝내고 y=`75%` 기본선을 표시해 사용자가 직접 보정할 수 있습니다. 이후 도착한 분석 결과는 현재 선택 상태를 덮어쓰지 않습니다.
- Ready 이후 렌즈·줌·회전 기준을 고정합니다. 탑홀드는 탭할 때마다 새 프레임과 새 임베딩으로 계산합니다.
- 기기의 논리 멀티 카메라와 독립 물리 렌즈 구성을 함께 탐색해 실제로 사용할 줌 경로를 결정합니다. 물리 카메라의 기본 native zoom은 1.0x입니다.
- 60fps는 Preview, VideoCapture, ImageAnalysis가 함께 바인딩된 실제 세션에서 CameraX가 정확한 60fps를 보장할 때만 사용하고, 그렇지 않으면 30fps로 폴백합니다.

## 멀티 렌즈 줌

- 0.5x·1x·망원을 하나의 논리 멀티 카메라가 제공하는 기기는 CameraX 연속 줌을 사용해 별도 재바인딩 없이 해당 구간을 통과합니다.
- `0.5x·1x 논리 카메라 + 별도 망원` 혼합형에서는 논리 그룹 밖의 망원 카메라를 추가 경로로 유지합니다. 모든 렌즈가 논리 그룹에 포함된 기기에서는 중복 물리 경로를 제거하고, 완전히 분리된 기기에서는 각 광학 앵커를 독립 경로로 사용합니다.
- 분리된 상위 렌즈로 확대할 때는 현재 프리뷰를 한 번만 캡처하고, 손가락을 움직이는 동안 정지 프레임을 실제 핀치 배율에 맞춰 확대합니다. 손을 떼는 순간 최종 배율로 새 렌즈를 바인딩합니다.
- 확대 도중 이전 렌즈의 전환 경계까지 다시 축소하면 예약된 재바인딩을 취소하고 기존 라이브 카메라를 즉시 사용합니다. 하위 렌즈로 축소할 때는 기존처럼 즉시 재바인딩하고 새 프리뷰가 준비되면 짧게 페이드합니다.
- 전환 요청은 고유 ID와 명시적인 대기·재바인딩 상태로 관리합니다. 새 프리뷰의 `STREAMING` 상태와 목표 줌 적용을 모두 확인한 뒤 정지 프레임을 제거하며, 대상 렌즈 전환 실패 시 이전 렌즈 복구를 시도합니다.
- 핀치 입력은 로그 스케일 응답을 사용해 확대·축소 방향의 감도를 대칭으로 유지하면서 작은 손동작의 급격한 배율 변화를 줄였습니다.

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

녹화 설정의 30fps·60fps는 카메라 세션과 인코더 프레임률입니다. 분석기는 각 `ImageProxy`의 센서 타임스탬프를 기준으로 `0.5초` 또는 `0.25초`가 지나기 전 프레임을 닫기 때문에, 두 녹화 설정 모두 상태별 분석 상한은 각각 2fps·4fps로 같습니다. `KEEP_ONLY_LATEST` 백프레셔를 사용하므로 추론이 기기 성능보다 오래 걸릴 때는 프레임을 적체하지 않고 실제 분석 빈도가 이 상한보다 낮아질 수 있습니다.

전체 화면 512px RGB Pose 폴백은 사용하지 않습니다. 초기 Motion ROI를 만들 수 없으면 WAITING 상태에 머뭅니다.

## 결과 편집과 갤러리 검증

- 녹화 중 감지된 모든 원본 구간을 보존하고, 2초 미만의 짧은 구간만 인접 구간과의 간격이 3초 이내일 때 병합합니다.
- 최종 후보에는 앞뒤 3초 편집 여백을 적용합니다.
- 결과 화면에서 구간 확대, 0.1초 미세 조정, 중복 구간 방지, 같은 촬영 상태를 유지한 재촬영을 지원합니다. 확대 배율 슬라이더는 손잡이뿐 아니라 트랙 전체를 탭하거나 진동 없이 바로 드래그해 조절할 수 있습니다. 다시 촬영 확인창은 경고 본문만 13sp로 낮추고 버튼 크기는 유지합니다. 시작점 수정 후에는 새 시작점부터, 끝점 수정 후에는 새 끝점의 3초 전부터 자동 재생합니다.
- Media3 Transformer는 선택 구간 시작이 키프레임 사이에 있으면 해당 구간을 재인코딩합니다. 또한 첫 영상 프레임보다 앞선 오디오 샘플을 결과에서 제외해 OEM 갤러리에서도 저장 영상이 영상 프레임부터 시작하도록 정렬합니다.
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
- 결과 화면에서는 화면 켜짐 강제를 해제해 시스템 자동 잠금 시간을 따릅니다.
- 결과 영상과 실제 갤러리 저장본은 앱이 백그라운드로 이동하면 자동 일시정지하며, 재생 위치 확인은 영상이 실제 재생 중일 때만 동작합니다.
- 화면 재생성 시 ViewModel이 이전 Activity와 PreviewView를 유지하지 않도록 약한 참조 사용
- MobileSAM의 네 프롬프트 결과를 순차 평가하고 embedding·decoder 입력을 direct buffer로 재사용해 분석 순간의 peak heap과 복사를 줄임
- 2fps·4fps Motion/Pose 분석은 두 개의 luma 버퍼를 순환해 장시간 촬영 중 반복 할당과 GC 부하를 줄임
- 프레임 캡처나 매트 분석이 제한 시간을 넘기면 해당 요청을 취소하고 늦게 완성된 Bitmap을 회수해 선택 화면 이탈 뒤 메모리에 남지 않도록 처리

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

- [v1.6 build 12 변경 사항](V1_6_BUILD_12_NOTES.md)
- [Android 호환성](ANDROID_COMPATIBILITY_NOTES.md)
- [기기 호환성 수정](DEVICE_COMPATIBILITY_FIX_NOTES.md)
- [MobileSAM ONNX 배치와 진단](docs/ONNX_INTEGRATION.md)
- [iOS UI/UX 대응표](docs/UI_PARITY.md)
- 과거 릴리스 기록: [v1.5 build 11](V1_5_BUILD_11_NOTES.md), [v1.5 build 10](V1_5_BUILD_10_NOTES.md), [v1.5 build 9](V1_5_BUILD_9_NOTES.md), [v1.5 build 8](V1_5_BUILD_8_NOTES.md), [v1.5 build 7](V1_5_BUILD_7_NOTES.md), [v1.4 build 5](V1_4_BUILD_5_NOTES.md)
