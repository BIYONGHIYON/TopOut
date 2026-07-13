# TopOut Android — iOS 완성본 동작 정합 수정본

완성된 iOS TopOut의 실제 Swift 실행 로직을 기준으로 Kotlin/Android 구현을 다시 맞춘 프로젝트입니다. Android에서는 Jetpack Compose, CameraX, ML Kit Accurate Pose, ONNX Runtime MobileSAM, Media3를 사용합니다.

## 프로젝트 열기

1. Android Studio에서 이 폴더를 프로젝트 루트로 엽니다.
2. Gradle JDK는 JDK 17 이상 또는 Android Studio Embedded JDK를 선택합니다.
3. **Sync Project with Gradle Files** 후 **Clean Project → Rebuild Project**를 실행합니다.
4. 실제 Android 기기에서 카메라 권한을 허용합니다. 마이크 권한을 사용할 수 없으면 무음 영상 녹화를 시도합니다.

MobileSAM 모델은 다음 경로에 포함되어 있습니다.

```text
app/src/main/assets/models/mobile_sam_encoder.onnx
app/src/main/assets/models/mobile_sam_decoder.onnx
```

## iOS 기준으로 맞춘 핵심 동작

### 기본 설정과 기능 조합

첫 실행 기본값은 iOS와 동일합니다.

- FHD / 60fps / 9:16
- 자동 구간 탐지 ON
- 탑홀드 마스킹 OFF
- 도움말 ON
- 녹화 중 실시간 화면 OFF

자동 구간 탐지와 탑홀드 마스킹은 서로 독립적입니다.

| 자동 탐지 | 마스킹 | 동작 |
|---|---|---|
| OFF | OFF | 선택 화면 없이 카운트다운 후 일반 녹화 |
| ON | OFF | 매트 경계만 보정하고 최고점 기준 자동 구간 탐지 |
| OFF | ON | 탑홀드 선택 후 일반 녹화 |
| ON | ON | 매트·탑홀드 선택 후 탑홀드 최근접 기준 자동 구간 탐지 |

### 상단 안내 문구

카메라·선택·카운트다운·자동 탐지·저장·재생 오류의 상단 문구를 iOS 문자열과 동일하게 분기합니다. 관련 문구는 `TopOutCopy.kt`에 모아 두어 설정 조합에 따라 문구가 달라지는 문제를 방지했습니다.

### 자동 구간 탐지

- 마스킹 ON: 신뢰 가능한 관절과 탑홀드 박스의 거리가 가장 가까웠던 시점을 종료 기준으로 사용합니다.
- 마스킹 OFF: 등반 중 신체의 가장 높은 관절점이 화면에서 가장 높았던 시점을 종료 기준으로 사용합니다.
- 시작·종료 3초 패딩, Pose 0.50 confidence 및 최소 6개 관절·어깨/골반 중심 관절 검증, 0.5초 분석 간격, 5초 재시도, 9초 미만 구간 처리, 3초 이내 구간 병합, 최근 10개 구간 보관을 iOS 수치에 맞췄습니다.
- Android Pose 입력 긴 변은 벽 오탐 감소를 위해 512px로 사용합니다.
- 시작 검증은 프레임별 독립 판정인 `SINGLE_IMAGE_MODE`, 등반 시작 후 추적은 `STREAM_MODE`를 사용합니다.

### 매트와 MobileSAM

- 준비 화면은 `ImageAnalysis` 카메라 프레임을 우선 사용해 iOS의 video sample buffer 경로에 가깝게 맞췄습니다.
- MobileSAM 후처리 기준은 iOS와 같은 256 mask 공간입니다.
- HSV 허용 오차, 주변 재탐색, 벽·미세 영역 판정, 최대 8회 보정 decode, fallback 원형 마스크, contour 샘플링과 smoothing 수치를 맞췄습니다.
- 둥근 홀드의 10% outline padding, 0.48 corner ratio, 빨간색·흰색 선 두께 규칙을 적용했습니다.

### 카메라와 녹화

- 선택 화면 줌은 1–5배, 카메라 표시 줌 상한은 8배입니다.
- Preview, VideoCapture, ImageAnalysis는 하나의 ViewPort를 공유합니다.
- CameraX Recorder에 iOS 비트레이트 정책을 적용합니다.
  - FHD 30fps: 8Mbps
  - FHD 60fps: 14Mbps
  - 4K 30fps: 45Mbps
  - 4K 60fps: 70Mbps
  - 4:5·3:4·1:1은 iOS와 같은 픽셀 비율로 축소
- 오디오 활성화가 시작 단계에서 실패하면 무음 녹화로 fallback합니다.
- 분석 스트림을 제공할 수 없는 촬영 조합에서는 자동 탐지를 조용히 생략하지 않고 설정 변경 안내를 표시합니다.

### 결과 편집과 저장

- ExoPlayer는 정확한 seek 정책을 사용합니다.
- 자동 감지 구간을 바꾸면 일시정지 → 구간 적용 → 시작점 이동 → 재생 순서로 동작합니다.
- 탑홀드 마스크는 기본 미포함이며 사용자가 선택할 수 있습니다.
- 재생 실패, 구간 자르기, 마스크 합성, 사진 앱 저장, 성공·실패 문구를 iOS와 맞췄습니다.
- Media3 Transformer로 선택 구간을 MP4로 내보내고 Android MediaStore의 `Movies/TopOut`에 저장합니다.

## 주요 대응 파일

| iOS | Android |
|---|---|
| `HoldVC`, `HoldVC+UI`, `HoldVC+Recording` | `TopOutApp.kt`, `TopOutViewModel.kt`, `TopOutCopy.kt` |
| `Camera.swift`, `Recorder.swift` | `CameraEngine.kt`, `CameraPreview.kt` |
| `RecordingSettingsVC.swift` | `SettingsDialog.kt`, `SettingsRepository.kt` |
| `SAM.swift`, `Mask.swift` | `OnnxMobileSamSegmenter.kt`, `BitmapUtils.kt` |
| `MatDetector.swift` | `MatBoundaryDetector.kt` |
| `MotionDetector.swift` | `PoseClimbAnalyzer.kt` |
| `ReviewVC.swift` | `ReviewScreen.kt`, `PrecisionTrimSlider.kt` |
| `AVAssetExportSession`, Photos | `VideoExporter.kt`, MediaStore |

세부 변경 내역은 [`docs/IOS_PARITY_AUDIT.md`](docs/IOS_PARITY_AUDIT.md)에 있습니다.

## 플랫폼상 동일하게 만들 수 없는 부분

동작 조건과 수치는 iOS를 기준으로 맞췄지만 다음은 플랫폼 프레임워크가 달라 완전히 같은 바이너리 결과를 보장할 수 없습니다.

- Apple Vision Pose와 ML Kit Accurate Pose의 관절 좌표
- Core ML과 ONNX Runtime의 추론·부동소수점 결과
- AVFoundation 논리 멀티카메라와 Android 제조사별 Camera2/CameraX 렌즈 전환
- MOV/AVAssetWriter와 MP4/CameraX Recorder의 codec profile, GOP, 오디오 세부 사양
- AVAssetExportSession과 Media3 Transformer의 재인코딩·합성 결과

## 검증

완료한 검사:

- 원본 Android 프로젝트 대비 수정 패치 생성
- iOS Swift 문자열과 설정 조합별 상단 문구 대조
- 기본 설정·기능 독립성·비트레이트·줌·Pose·SAM 상수 회귀 테스트 추가
- Android 의존성이 필요 없는 모델 계층을 별도 Kotlin 컴파일·실행하여 통과 확인
- 전체 Kotlin 파일의 구문 형태 정적 검사

현재 실행 환경에는 Android SDK와 Gradle 8.13 배포본 캐시가 없고 외부 Gradle 배포 서버 DNS가 차단되어 전체 Android Gradle 빌드를 실행하지 못했습니다. Android Studio에서 Sync/Rebuild 후 실제 기기의 카메라·Pose·SAM·Media3 저장 흐름을 확인해야 합니다.
