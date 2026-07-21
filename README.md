# 🧗‍♂️ TopOut

> **등반 시작부터 완등 순간까지, 자동으로 찾아주는 클라이밍 촬영 앱**

**TopOut**은 실내 클라이머를 위한 iOS·Android 영상 기록 앱입니다.  
사용자가 촬영 구도와 매트 경계선을 설정하면 앱이 매트 주변의 움직임과 사람의 Pose를 분석해 등반 구간을 자동으로 찾습니다. 탑홀드 마스킹을 활성화한 경우에는 사용자가 선택한 탑홀드에 가장 가까웠던 순간을, 비활성화한 경우에는 등반 중 신체가 가장 높이 올라간 순간을 기준으로 자동 편집 구간을 제안합니다.

녹화는 구간이 감지되어도 즉시 종료되지 않으며, 사용자가 종료 버튼을 누를 때까지 하나의 원본 영상으로 계속 진행됩니다. 한 번의 녹화 안에서 여러 번의 등반 시도를 감지할 수 있고, 촬영 종료 후 자동 선택된 구간을 확인하거나 직접 미세 조정해 저장할 수 있습니다.

---

## 📁 프로젝트 구조

```text
TopOut/
├── IOS/
│   └── TopOut/
│       ├── TopOut.xcodeproj
│       └── TopOut/
│           ├── Code/
│           ├── MobileSAM/
│           └── Assets.xcassets/
├── Android/
│   └── TopOut/
│       ├── app/
│       ├── docs/
│       ├── gradle/
│       └── settings.gradle.kts
└── README.md
```

- `IOS/TopOut`: UIKit·AVFoundation·Core ML 기반 iOS 구현
- `Android/TopOut`: Jetpack Compose·CameraX·MoveNet Thunder·TensorFlow Lite·ONNX Runtime 기반 Android 구현
- Android 버전은 iOS 구현의 기능과 주요 동작 조건을 기준으로 정합성을 맞췄습니다.

---

## ✨ 핵심 기능

### 🎥 연속 녹화와 다중 시도 감지

TopOut은 완등 후보 시점을 찾았다는 이유만으로 녹화를 자동 종료하지 않습니다. 정상 촬영 중에는 사용자가 직접 종료할 때까지 전체 원본 영상을 계속 기록하며, 하나의 녹화 안에서 여러 등반 시도를 각각 감지해 결과 화면에 제공합니다.

### 🟦 매트 경계선 자동 탐지 및 보정

앱은 벽과 매트가 만나는 경계를 자동으로 탐지합니다. 탐지 결과가 실제 환경과 다르면 사용자가 화면의 파란 조절점을 드래그해 경계선을 직접 수정할 수 있습니다.

### 🎯 MobileSAM 기반 탑홀드 선택

탑홀드 마스킹을 활성화하면 촬영 전 목표 홀드를 선택할 수 있습니다. 작은 홀드는 확대 화면에서 더 정밀하게 지정할 수 있으며, MobileSAM이 선택 영역을 분할해 외곽선으로 표시합니다.

- iOS: Core ML 형식의 MobileSAM Encoder·Decoder
- Android: ONNX Runtime 형식의 MobileSAM Encoder·Decoder

### 🧍 Motion Detection → Pose Detection 단계형 분석

등반 시작 전부터 고비용 Pose 모델을 계속 실행하지 않습니다.

1. 녹화 시작 후 3초 동안 매트 주변의 기준 영상을 보정합니다.
2. 매트 경계선 주변에서 경량 Motion Detection을 수행합니다.
3. 의미 있는 움직임이 발견될 때만 Android에서는 MoveNet Thunder FP16 Pose Detection을 시작합니다.
4. 초기 탐지는 긴 변 최대 512px의 전체 화면을 사용하고, 신뢰 가능한 관절 4개 이상과 어깨·골반 중심 관절을 추가 검증합니다.
5. 연속 3개 Pose 프레임에서 최소 3개 관절이 사람 크기의 5% 이상 이동하면 실제 사람 움직임으로 판단하고 Smart Crop을 활성화합니다.
6. Smart Crop 영역에서 유효한 사람 Pose가 2프레임 연속 확인되면 등반 시작 판정을 허용합니다.
7. 신뢰 가능한 관절점 중 화면상 가장 낮은 점이 매트 위로 올라가면 등반 시작으로 기록합니다.

Smart Crop 활성화 이후 Android는 전체 512px RGB 이미지를 다시 만들지 않고, 이전 Pose에서 계산한 사람 ROI를 CameraX YUV 프레임에서 바로 256×256 MoveNet 입력으로 변환합니다. 밝기 채널은 bilinear 보간하고 색상 채널은 nearest sampling을 사용해 발열과 배터리 소모를 줄입니다.

### 🏁 설정에 따른 종료 기준 선택

자동 구간 탐지와 탑홀드 마스킹은 서로 독립적으로 사용할 수 있습니다.

| 자동 구간 탐지 | 탑홀드 마스킹 | 동작 |
|---|---|---|
| OFF | OFF | 별도의 선택 과정 없이 일반 녹화 |
| ON | OFF | 매트 경계선을 보정하고 **등반 중 가장 높은 Pose 시점**을 종료 기준으로 사용 |
| OFF | ON | 탑홀드를 선택한 뒤 일반 녹화 및 마스크 오버레이 사용 |
| ON | ON | 매트와 탑홀드를 설정하고 **탑홀드에 가장 가까웠던 Pose 시점**을 종료 기준으로 사용 |

등반 중 가장 낮은 관절점이 다시 매트 아래로 내려오거나 Pose가 일정 시간 이상 사라지면 해당 시도를 마무리합니다. 최종 자동 구간은 감지 시점 그대로가 아니라 시작 전·종료 후 여백을 포함해 저장됩니다.

### ✂️ 자동 구간 선택과 정밀 편집

녹화 종료 후 앱이 감지한 구간을 자동으로 선택합니다.

- 감지된 여러 구간 전환
- 시작점·끝점 수동 조정
- 느린 드래그 시 0.1초 단위 정밀 조정
- 탑홀드 외곽선 포함 여부 선택
- 선택 구간만 기기 갤러리에 저장

짧은 구간은 인접 구간과의 간격을 확인해 병합하거나 제외하며, 최근 감지 구간을 최대 10개까지 유지합니다.

### 🛡️ 촬영 중단 및 저장공간 보호

다음 상황에서는 파일 손상을 방지하기 위해 현재 녹화를 안전하게 마무리합니다.

- 앱이 백그라운드로 이동한 경우
- 전화 수신이나 다른 앱의 카메라·마이크 사용 등으로 촬영 세션이 중단된 경우
- 카메라 세션 오류가 발생한 경우
- 남은 저장공간이 안전 기준보다 부족한 경우

저장공간은 녹화 시작 직후와 녹화 중 1분 간격으로 확인합니다. 안전 기준은 **최소 1GB**와 현재 촬영 포맷 기준 **약 2분 분량의 예상 영상 용량 + 256MB 마무리 여유 공간** 중 더 큰 값을 사용합니다.

---

## ⚙️ 기본 설정

첫 실행 기본값은 두 플랫폼에서 동일합니다.

| 항목 | 기본값 |
|---|---|
| 해상도 | FHD |
| 프레임 | 60fps |
| 화면 비율 | 9:16 |
| 자동 구간 탐지 | ON |
| 탑홀드 마스킹 | OFF |
| 도움말 | ON |
| 녹화 중 실시간 화면 | OFF |

지원 후보:

- 해상도: FHD, 4K
- 프레임: 30fps, 60fps
- 화면 비율: 9:16, 4:5, 3:4, 1:1
- 세로·가로 촬영 지원

실제 선택 가능한 해상도와 프레임 조합은 기기의 후면 카메라 성능에 따라 달라집니다. 저장된 설정이 현재 기기에서 지원되지 않으면 가능한 조합으로 자동 보정됩니다.

---

## 🔄 작동 흐름

### 1. 모델 로딩

앱 실행 시 MobileSAM 모델을 온디바이스로 준비합니다. 모델 로딩 중에는 시스템 테마에 맞는 런치 이미지를 표시합니다.

### 2. 촬영 포맷 및 구도 설정

사용자는 촬영 해상도·프레임·화면 비율과 자동 탐지 옵션을 설정한 뒤 벽, 매트, 클라이머가 모두 보이도록 카메라 구도를 맞춥니다.

### 3. 매트 경계선 및 탑홀드 설정

- 자동 구간 탐지 ON: 매트 경계선을 자동 탐지하고 필요하면 수동 보정
- 탑홀드 마스킹 ON: 목표 홀드를 탭해 MobileSAM 영역 선택

### 4. 녹화 및 기준 영상 보정

3초 카운트다운과 함께 매트 주변 기준 영상을 수집합니다. 이후에는 경량 CV 분석으로 매트 경계의 움직임을 감시합니다.

### 5. 등반 시작 감지

경계 부근의 의미 있는 움직임이 확인되면 Pose Detection으로 전환합니다. Android에서는 전체 화면 Pose 3프레임의 관절 움직임을 검증한 뒤 Smart Crop을 활성화하고, Crop 영역에서 유효한 사람 Pose가 2프레임 연속 확인되어야 시작 판정을 허용합니다. 이후 가장 낮은 신체 관절점이 매트 기준선을 아래에서 위로 통과하면 등반 시작으로 기록합니다.

### 6. 종료 후보 시점 갱신

- 탑홀드 마스킹 ON: 주요 관절과 탑홀드 영역의 거리가 가장 가까웠던 시점
- 탑홀드 마스킹 OFF: 주요 관절 중 가장 높은 점이 화면에서 가장 높았던 시점

해당 시점을 등반 중 계속 갱신합니다.

### 7. 시도 종료 및 다음 시도 대기

클라이머가 다시 매트 아래로 내려오거나 Pose가 일정 시간 사라지면 현재 구간을 종료합니다. 녹화는 계속 유지되며 앱은 다음 등반 시작을 다시 탐색합니다.

### 8. 결과 확인 및 저장

사용자가 녹화를 종료하면 자동 감지 구간이 적용된 결과 화면으로 이동합니다. 구간을 선택·수정한 뒤 원하는 부분만 저장할 수 있습니다.

---

## 🧠 플랫폼별 구현

| 영역 | iOS | Android |
|---|---|---|
| UI | UIKit | Jetpack Compose · Material 3 |
| 카메라 | AVFoundation | CameraX · Camera2 |
| 원본 녹화 | AVAssetWriter · MOV | CameraX Recorder · MP4 |
| Pose | Vision Human Body Pose | MoveNet SinglePose Thunder FP16 · TensorFlow Lite |
| MobileSAM | Core ML | ONNX Runtime |
| 영상 재생 | AVPlayer | Media3 ExoPlayer |
| 트리밍·내보내기 | AVAssetExportSession · Photos | Media3 Transformer · MediaStore |
| 설정 저장 | UserDefaults | DataStore Preferences |
| 앱 상태 | UIViewController 기반 상태 관리 | ViewModel · StateFlow |
| 저장 위치 | iOS 사진 앱 | `Movies/TopOut` |

### Pose 분석 차이

두 플랫폼은 동일한 상태 전환과 구간 계산 구조를 사용하지만 Pose 엔진이 다르므로 관절 좌표와 추론 결과가 완전히 동일하지는 않습니다.

- iOS
  - Vision Human Body Pose Detection
  - 입력 영상 긴 변 최대 384px
  - 관절 confidence 기준 0.25
- Android
  - MoveNet SinglePose Thunder FP16
  - TensorFlow Lite XNNPACK, CPU 최대 2스레드
  - 모델 입력 256×256, Pose 분석 간격 0.5초
  - 초기 전체 화면 탐지에서 긴 변 최대 512px 이미지 사용
  - Smart Crop 활성화 후 CameraX YUV에서 사람 ROI를 256×256 RGB 입력으로 직접 변환
  - 사람 판정: confidence 0.25 이상 관절 4개 이상, 몸통 관절 2개 이상, 어깨·골반 각각 최소 1개
  - Smart Crop 계산에는 confidence 0.15 이상 관절 사용
  - 연속 3개 Pose 프레임에서 최소 3개 관절이 사람 크기의 5% 이상 이동하면 Smart Crop 활성화
  - Smart Crop 활성화 후 유효한 사람 Pose 2프레임 연속 확인 뒤 시작 판정 허용
  - Smart Crop 상태에서 사람 형태가 2회 연속 부정확하면 전체 화면 탐지로 복귀
  - Pose가 최대 6초간 누락되어도 일시적인 가림으로 보고 기존 시도를 유지

### 공통 구간 처리 기준

- 시작 감지 전 여백: 3초
- 종료 기준 시점 이후 여백: 3초
- Pose 분석 간격: 0.5초
- Pose 미검출 허용 시간: 6초
- 원본 감지 구간이 2초 미만이면 인접 구간과의 간격이 3초 이내일 때 병합하고, 병합할 수 없으면 제외
- 최대 감지 구간 수: 최근 10개

> `2초 미만`은 시작·종료의 각 3초 여백을 추가하기 전의 원본 감지 구간 길이를 기준으로 합니다.

---

## 🧩 주요 파일 대응

| 기능 | iOS | Android |
|---|---|---|
| 메인 촬영 화면·상태 | `HoldVC.swift`, `HoldVC+UI.swift`, `HoldVC+Recording.swift` | `TopOutApp.kt`, `TopOutViewModel.kt` |
| 카메라·녹화 | `Camera.swift`, `Recorder.swift` | `CameraEngine.kt`, `CameraPreview.kt` |
| 촬영 설정 | `RecordingSettingsVC.swift`, `RecordingFormat.swift` | `SettingsDialog.kt`, `SettingsRepository.kt`, `AppModels.kt` |
| MobileSAM | `SAM.swift`, `Mask.swift`, `Pipeline.swift` | `OnnxMobileSamSegmenter.kt`, `HoldSegmenter.kt` |
| 매트 탐지 | `MatDetector.swift`, `MatLineView.swift` | `MatBoundaryDetector.kt`, `SelectionEditor.kt` |
| 움직임·Pose | `MotionDetector.swift` | `PoseClimbAnalyzer.kt`, `MoveNetThunderPoseDetector.kt`, `PoseHumanGate.kt`, `PoseStartRule.kt` |
| 결과 편집 | `ReviewVC.swift` | `ReviewScreen.kt`, `PrecisionTrimSlider.kt` |
| 저장공간·중단 보호 | `HoldVC+Stability.swift` | `StorageMonitor.kt`, `TopOutViewModel.kt`, `CameraEngine.kt` |
| 영상 내보내기 | `Recorder.swift` | `VideoExporter.kt` |

---

## 🚀 실행 방법

### iOS

1. Xcode에서 `IOS/TopOut/TopOut.xcodeproj`를 엽니다.
2. `Signing & Capabilities`에서 개발 팀과 Bundle Identifier를 현재 계정에 맞게 설정합니다.
3. `MobileSAM_Encoder.mlmodel`, `MobileSAM_Decoder.mlmodel`, `PrivacyInfo.xcprivacy`의 Target Membership을 확인합니다.
4. 카메라·마이크·사진 보관함 권한 문구가 앱 타깃에 포함되어 있는지 확인합니다.
5. 카메라와 Core ML 동작을 확인하려면 실제 iPhone 또는 iPad에서 실행합니다.

업로드된 Xcode 프로젝트의 Deployment Target이 현재 설치된 SDK와 맞지 않으면, 프로젝트 설정에서 사용 가능한 iOS 버전으로 조정합니다.

### Android

1. Android Studio에서 `Android/TopOut` 폴더를 프로젝트 루트로 엽니다.
2. Gradle JDK를 **JDK 17** 또는 Android Studio Embedded JDK로 설정합니다.
3. `Sync Project with Gradle Files`를 실행합니다.
4. 필요하면 `Clean Project` 후 `Rebuild Project`를 실행합니다.
5. 카메라와 ML 추론을 확인하려면 실제 Android 기기에서 실행합니다.

Android 기본 환경:

- `minSdk 26`
- `targetSdk 36`
- Gradle 8.13
- Kotlin/JVM 17

MobileSAM 모델 경로:

```text
Android/TopOut/app/src/main/assets/models/mobile_sam_encoder.onnx
Android/TopOut/app/src/main/assets/models/mobile_sam_decoder.onnx
```

Android Pose 모델은 MoveNet SinglePose Thunder FP16을 사용하며, 모델 입력 크기는 256×256입니다. 초기 전체 화면 탐지에는 긴 변 최대 512px 이미지를 사용하고, Smart Crop이 활성화된 이후에는 CameraX YUV 프레임에서 사람 ROI를 직접 MoveNet 입력 버퍼로 변환합니다.

---

## 🔐 권한 및 개인정보 관련 설정

### iOS

- 카메라 접근
- 마이크 접근
- 사진 보관함 저장 접근
- `PrivacyInfo.xcprivacy`
  - 디스크 공간 확인 API 사용 사유
  - UserDefaults 사용 사유

### Android

- `android.permission.CAMERA`
- `android.permission.RECORD_AUDIO`
- Android 9 이하 저장을 위한 제한적 `WRITE_EXTERNAL_STORAGE`

Android 10 이상에서는 MediaStore를 통해 `Movies/TopOut`에 영상을 저장합니다.

---

## 📌 플랫폼 차이와 한계

기능 흐름과 주요 수치는 iOS·Android에서 최대한 동일하게 맞췄지만, 다음 요소는 플랫폼 프레임워크와 기기 제조사 차이로 완전히 동일한 결과를 보장하지 않습니다.

- Apple Vision과 MoveNet Thunder의 Pose 관절 좌표 및 confidence
- Core ML, TensorFlow Lite, ONNX Runtime의 추론·부동소수점 결과
- AVFoundation과 CameraX의 렌즈 전환 및 지원 포맷 판정
- MOV와 MP4의 코덱 프로파일, GOP, 오디오 세부 사양
- AVAssetExportSession과 Media3 Transformer의 재인코딩 결과
- Android 제조사별 카메라 해상도·프레임·고속 촬영 지원 범위

---

## 📌 프로젝트 특징

TopOut은 단순한 카메라 앱이 아니라, 클라이밍 영상에서 사용자가 실제로 필요한 **등반 시작부터 최고 지점까지의 구간**을 자동으로 제안하는 온디바이스 비디오 도구입니다.

경량 Motion Detection으로 시작 후보를 좁힌 뒤에만 Pose Detection을 실행하고, MobileSAM을 통해 사용자가 선택한 탑홀드를 정밀하게 표시합니다. 촬영이 끝난 뒤에는 자동 구간 선택과 수동 미세 조정을 함께 제공해 클라이머가 긴 원본 영상을 직접 돌려보며 시작·완등 순간을 찾는 시간을 줄입니다.
