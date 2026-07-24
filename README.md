# 🧗‍♂️ TopOut — Internal README

> **등반 시작부터 완등 후보 순간까지 자동으로 찾아주는 온디바이스 클라이밍 촬영 앱**

이 문서는 TopOut iOS·Android 비공개 저장소의 개발, 유지보수 및 탐지 기준 정합성 확인을 위한 기술 README입니다.

- Marketing Version: **1.4**
- Build / Version Code: **5**
- Bundle / Application ID: `com.topout.cam`
- iOS 최소 버전: **iOS 16.0**
- Android 최소 버전: **Android 8.0 · API 26**
- Android Target SDK: **36**
- 기준 패키지:
  - `TopOut_iOS_v1.4_build5_Start6_Finish2_Recovery6s`
  - `TopOut_Android_v1.4_build5_Start4_Finish2_Recovery6s`

---

## 1. 제품 동작 요약

TopOut은 자동 구간이 감지되어도 원본 녹화를 중단하지 않습니다. 사용자가 녹화를 직접 종료할 때까지 하나의 원본 영상을 계속 기록하고, 원본 내부에서 여러 등반 시도의 시작·종료 후보만 저장합니다.

```text
모델 준비
→ 촬영 포맷 및 구도 설정
→ 매트 경계 자동 탐지·수동 보정
→ 선택적으로 탑홀드 마스킹
→ 3초 카운트다운 후 연속 녹화
→ Motion / Pose 기반 다중 시도 감지
→ 짧은 원본 구간 정리
→ 각 구간에 앞뒤 3초 편집 여백 적용
→ 결과 확인·정밀 편집·저장
```

3초 카운트다운은 사용자 준비를 위한 UX입니다. 별도의 환경 기준 영상 보정 단계는 수행하지 않습니다.

---

## 2. 저장소 구조

```text
TopOut/
├── IOS/
│   └── TopOut/
│       ├── TopOut.xcodeproj
│       └── TopOut/
│           ├── Code/
│           ├── MobileSAM/
│           ├── Assets.xcassets/
│           └── Licenses/
├── Android/
│   └── TopOut/
│       ├── app/
│       │   └── src/main/
│       │       ├── java/com/topout/cam/
│       │       ├── assets/
│       │       └── res/
│       ├── docs/
│       ├── gradle/
│       └── settings.gradle.kts
└── README.md
```

### 주요 파일 대응

| 기능 | iOS | Android |
|---|---|---|
| 메인 촬영 화면·상태 | `HoldVC.swift`, `HoldVC+UI.swift`, `HoldVC+Recording.swift` | `TopOutApp.kt`, `TopOutViewModel.kt` |
| 카메라·녹화 | `Camera.swift`, `Recorder.swift` | `CameraEngine.kt`, `CameraPreview.kt` |
| 촬영 설정 | `RecordingSettingsVC.swift`, `RecordingFormat.swift` | `SettingsDialog.kt`, `SettingsRepository.kt`, `AppModels.kt` |
| MobileSAM 탑홀드 | `SAM.swift`, `Mask.swift`, `Pipeline.swift` | `OnnxMobileSamSegmenter.kt`, `HoldSegmenter.kt` |
| 매트 경계 탐지 | `MobileSAMMatDetector.swift`, `MatLineView.swift` | `MobileSamMatBoundaryDetector.kt`, `SelectionEditor.kt` |
| 움직임·Pose | `MotionDetector.swift`, `HoldVC+Motion.swift` | `PoseClimbAnalyzer.kt`, `MoveNetThunderPoseDetector.kt`, `PoseHumanGate.kt` |
| 구간 정리 | `HoldVC+Recording.swift` | `DetectedClipRangeStore.kt` |
| 결과 편집 | `ReviewVC.swift` | `ReviewScreen.kt`, `PrecisionTrimSlider.kt` |
| 저장공간·중단 보호 | `HoldVC+Stability.swift` | `StorageMonitor.kt`, `TopOutViewModel.kt`, `CameraEngine.kt` |
| 영상 내보내기 | `Recorder.swift` | `VideoExporter.kt` |

---

## 3. 기본 촬영 설정

| 항목 | 기본값 |
|---|---|
| 해상도 | FHD |
| 프레임률 | 60fps |
| 화면 비율 | 9:16 |
| 자동 구간 탐지 | ON |
| 탑홀드 마스킹 | OFF |
| 도움말 | ON |
| 녹화 중 실시간 프리뷰 | OFF |

지원 후보:

- 해상도: FHD, 4K
- 프레임률: 30fps, 60fps
- 화면 비율: 9:16, 4:5, 3:4, 1:1
- 세로·가로 방향

기기에서 지원하지 않는 해상도·프레임 조합은 가능한 조합으로 보정합니다.

---

## 4. MobileSAM 매트 경계 탐지

두 플랫폼은 동일한 자동 후보 규칙을 사용합니다.

### 입력 프롬프트

- Positive X: `50%`
- Positive Y: `70%`, `80%`, `90%`, `95%`
- Negative point: `x = 50%`, `y = 28%`
- 하나의 이미지 임베딩을 네 높이에서 재사용

### 후보 구성 및 필터

각 디코더 채널에서 positive point와 연결된 성분만 사용합니다.

```text
가로 점유 폭 ≥ 화면 너비 40%
마스크 최상단 Y ≥ 화면 높이 50%
상단 경계 직선 기울기 ≤ 15°
```

유효 후보 중 상단 경계가 화면에서 가장 높은 후보를 선택합니다. 같은 높이라면 가로폭이 더 넓은 후보를 우선합니다.

### 최종 경계

- 연결 성분의 열별 최상단 픽셀을 추출
- 최소제곱 직선 피팅
- `x = 6%`, `x = 94%`의 두 점으로 편집 가능한 선 생성
- **별도의 10px 하향 보정을 적용하지 않고 검출 위치 그대로 사용**
- 후보가 없거나 모델 실행에 실패하면 `y = 75%` 수평 기본선 사용
- 자동 탐지 후 사용자가 두 조절점을 직접 수정 가능

현재 규칙에는 직선 inlier 비율, 마스크 면적 점수, 좌우 추가 프롬프트, 후보 간 합의 점수를 사용하지 않습니다.

### 기울어진 매트 처리

저장된 매트선은 단일 평균 Y가 아니라 x 위치별 선형 보간값으로 사용합니다.

```text
관절 x 위치
→ 해당 x에서의 매트 경계 y 계산
→ 그 위치의 시작선·종료선·Motion 탐색 범위 계산
```

따라서 최대 15°까지 허용된 완만한 기울기는 시작·종료 및 초기 Motion 영역에 반영됩니다. iOS의 6%·2%, Android의 4%·2% 오프셋은 선에 수직인 거리가 아니라 화면 세로축 Y 방향으로 차감합니다.

---

## 5. 공통 Motion 분석 기준

- 별도 환경 보정 없음
- 분석 프레임 너비: `160px`
- 기본 분석 주기: `2fps`, 즉 `0.5초`
- 현재 분석 프레임과 **직전 분석 프레임** 비교
- 픽셀 밝기 차이 기준: `20`
- 초기 탐색 영역: 각 x의 매트 경계 바로 위쪽부터 화면 높이 최대 `40%`
- 기본 노이즈 보정: `2.5%`
- 보정 후 활성 기준: `2%`
- 원본 변화 픽셀 비율 약 `4.5%`부터 활성 가능

```text
rawSignal = 변화 픽셀 / 탐색 영역 픽셀
adjustedSignal = max(0, rawSignal - 0.025)
활성 = adjustedSignal ≥ 0.02
```

초기 Motion 탐색은 기울어진 매트선의 x별 경계를 따라 수행합니다.

---

## 6. 분석 주기

| 상태 | iOS | Android |
|---|---:|---:|
| 초기 Motion 탐색 | 2fps | 2fps |
| 움직임 직후, 아직 유효 Pose를 한 번도 못 잡음 | 2fps | 2fps |
| 유효 Pose 확보 후, 아직 등반 시작 전 | 4fps | 4fps |
| 정상 등반 중 Pose 추적 | 2fps | 2fps |
| 이전에 본 Pose 유실 복구 | 4fps | 4fps |
| 정상 종료 후 다음 시작 대기 | 4fps | 4fps |

- 기본 간격: `0.5초`
- 고속 간격: `0.25초`
- 활성 등반 중 Pose 유실 복구 제한: `6초`
- 정상 종료 후 Pose가 계속 보이면 시간 제한 없이 다음 시작을 탐색
- 정상 종료 후 Pose가 `2프레임 연속` 미검출되면 초기 Motion 탐색으로 복귀

---

## 7. 플랫폼별 시작·공통 종료 기준

### 시작 경계

가장 낮은 신뢰 관절의 x 위치에서 매트선을 계산한 뒤 플랫폼별 시작 오프셋을 적용합니다.

```text
iOS:
startBoundaryY(x) = matLineY(x) - 0.06

Android:
startBoundaryY(x) = matLineY(x) - 0.04

가장 낮은 관절 Y ≤ startBoundaryY(x)
→ START
```

즉, iOS는 매트선보다 화면 높이의 `6%` 위, Android는 `4%` 위에서 시작을 판정합니다. 첫 유효 Pose가 이미 시작선 위에 있다면 즉시 시작할 수 있으며, 그렇지 않으면 시작선 아래→위 crossing을 사용합니다.

### 종료 경계

가장 낮은 신뢰 관절의 x 위치에서 매트선을 계산한 뒤, 화면 높이의 `2%` 위에 종료선을 둡니다.

```text
finishBoundaryY(x) = matLineY(x) - 0.02
가장 낮은 관절 Y > finishBoundaryY(x)
→ 정상 복귀 확인
```

즉, 실제 매트선까지 완전히 내려오기를 기다리지 않고 매트선보다 2% 위의 종료선을 아래로 통과하면 현재 시도를 닫습니다.

### 종료 후보 시점

탑홀드 설정에 따라 달라집니다.

- 탑홀드 마스킹 ON: 신뢰 관절이 탑홀드 사각 영역에 가장 가까웠던 시점
- 탑홀드 마스킹 OFF: 신뢰 관절 중 가장 높은 점의 Y가 전체 등반에서 가장 작았던 시점

정상 복귀 프레임은 등반 종료를 확정하는 신호이며, 실제 자동 편집 종료 후보는 위의 최적 시점을 우선 사용합니다.

---

## 8. iOS 탐지 구조

### Pose 엔진

- Apple Vision `VNDetectHumanBodyPoseRequest`
- 선택된 촬영 영역(active crop) 전체를 입력으로 사용
- 입력 긴 변 최대 `384px`
- 관절 confidence 기준 `0.25`
- 관절 기반 동적 Vision ROI는 사용하지 않음
- 변환 실패 시 원본 `CVPixelBuffer`를 Vision에 전달하고 active crop 좌표로 환산

### iOS 신뢰 관절

시작·종료·최고점·탑홀드 판단에 사용하는 관절:

- 어깨
- 팔꿈치
- 손목
- 골반
- 무릎
- 발목

**코 및 얼굴 관절은 판단에서 제외**합니다.

### iOS 다중 인물 처리 — 기존 방식 복원

현재 iOS는 사람 한 명을 선택하거나 프레임 간 동일 인물을 추적하지 않습니다.

```text
Vision이 반환한 모든 VNHumanBodyPoseObservation
→ confidence 0.25 이상인 비얼굴 관절 수집
→ 모든 사람의 관절을 하나의 판단 배열로 합침
→ 배열에 관절이 하나 이상 있으면 유효 Pose
```

다음 강화 로직은 롤백되어 현재 사용하지 않습니다.

- Motion 중심과 가장 가까운 사람 1명 선택
- 이전 프레임 위치와 가장 가까운 사람 1명 추적
- 사람 중심 거리 제한
- 어깨·골반·팔꿈치·무릎이 각각 1개 이상이어야 하는 anatomical gate

현장 테스트에서 기존 방식의 구간 정확도가 더 높아 복원한 상태입니다. 여러 사람이 동시에 화면에 들어오면 서로 다른 사람의 관절이 같은 판단 집합에 포함될 수 있다는 점은 현재 iOS 로직의 의도된 트레이드오프입니다.

### iOS 상태 흐름

```text
WAITING
직전 프레임 차이 기반 Motion 탐색
    ↓ Motion 활성
TRACKING
Vision Pose 확인 및 시작선 crossing 탐색
    ↓ START
CLIMBING
Pose·완등 후보 갱신
    ↓ 종료선 아래 복귀 또는 6초 유실
TRACKING
다음 등반 시작 탐색
```

### iOS 정상 종료 시각 보호

완등 후보가 시작 프레임과 같거나 START보다 이른 경우, 해당 후보를 그대로 사용하면 FINISH가 무효가 될 수 있습니다. 현재 코드는 종료 후보가 START보다 실제로 늦은지 검증합니다.

- 정상 복귀 종료: 유효한 완등 후보가 없으면 실제 종료선 crossing 시각 사용
- 6초 Pose 유실 종료: 유효한 완등 후보가 없으면 마지막 유효 Pose 시각 사용
- `START + 0.1초` 같은 인위적 보정은 사용하지 않음

### iOS Pose 유실

- 유효 Pose를 본 뒤 유실되면 `4fps`로 복구
- 등반 전 `6초` 동안 복구되지 않으면 초기 Motion 탐색 복귀
- 등반 중 `6초` 동안 복구되지 않으면 유효 종료 후보 또는 마지막 유효 Pose 시각으로 시도 종료
- 정상 종료 후에는 `2프레임 연속` 미검출 시 초기 Motion 탐색 복귀

---

## 9. Android 탐지 구조

### Pose 엔진 및 런타임

- MoveNet SinglePose Thunder FP16
- 모델 입력: `256 × 256`
- LiteRT `1.4.1`
- 기존 `org.tensorflow.lite.Interpreter` API 사용
- CameraX `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`
- 한 번의 추론에서 한 사람만 반환

### Android Motion ROI → Pose ROI 흐름

```text
초기 MOTION
→ 매트선 위 40% 영역에서 직전 프레임 차이 확인
→ 변화 픽셀을 포함하는 화면 면적 25% 정사각형 ROI 생성
→ ROI에서 MoveNet 실행

유효 Pose
→ POSE 모드
→ 관절 좌표로 추적 ROI 갱신

Pose 1회 미검출
→ 즉시 MOTION 모드
→ 직전 분석 프레임 변화 영역으로 ROI 복구

유효 Pose 재검출
→ POSE 모드 복귀
```

초기 사람 탐색은 매트 위 탐색 영역으로 제한합니다. 유효한 사람을 한 번 본 뒤의 복구 Motion ROI는 화면 전체에서 이동할 수 있습니다.

CameraX YUV 프레임의 ROI를 직접 `256 × 256` RGB 입력으로 샘플링하며, 전체 화면 512px RGB fallback은 사용하지 않습니다.

### Android 유효 Pose 조건

confidence `0.25` 이상인 동일 SinglePose 결과에서 다음 영역이 각각 하나 이상 있어야 합니다.

- 어깨 ≥ 1
- 골반 ≥ 1
- 팔꿈치 ≥ 1
- 무릎 ≥ 1

시작·종료·완등 후보에는 어깨, 팔꿈치, 손목, 골반, 무릎, 발목을 사용하고 코는 사용하지 않습니다.

추적 ROI 계산에는 confidence `0.15` 이상 keypoint를 사용할 수 있지만, 시작·종료 판단은 `0.25` 기준과 anatomical gate를 통과해야 합니다.

### Android Pose 유실

- 등반 중: 마지막 유효 Pose 이후 최대 `6초` 동안 Motion ROI 복구
- 6초 이내 복구: 같은 등반 계속
- 6초 복구 실패: 유효한 종료 후보가 START보다 늦으면 해당 구간 저장, 아니면 Retry
- 정상 종료 후: Pose 추적을 시간 제한 없이 유지
- 정상 종료 후 `2프레임 연속` 미검출: 초기 Motion 탐색 복귀
- `start + 0.1초` 인위적 보정 없음

### Android 정상 종료 저장 조건

정상 종료 시에는 저장된 완등 후보를 우선하고, 후보가 없으면 종료선 crossing 시각을 사용합니다. ViewModel은 `finish > start`인 경우에만 원본 구간을 저장합니다.

---

## 10. 구간 정리 및 병합

모든 정리는 먼저 **패딩 없는 원본 감지 구간**으로 수행합니다.

1. 원본 구간을 시작 시각 순으로 정렬
2. 원본 길이가 `2초 미만`인 짧은 구간 확인
3. 앞뒤 이웃 중 원본 간격이 `3초 이내`인 가장 가까운 구간과 병합
4. 병합할 이웃이 없으면 짧은 구간 제외
5. 남은 모든 유효 등반은 다음 등반과의 간격과 관계없이 별도 구간으로 유지
6. 각 원본 구간에 앞뒤 `3초` 편집 여백 적용
7. 전체 녹화 길이 범위로 clamp

### 삭제된 일반 인접 구간 병합

다음 로직은 iOS·Android 모두 삭제되었습니다.

```text
구간 A 종료 후 1초 이내 구간 B 시작
→ A와 B를 하나로 병합
```

이제 A와 B는 각각 별도 감지 구간으로 유지됩니다. 단, 앞뒤 3초 패딩 때문에 결과 화면의 두 구간 범위가 서로 겹칠 수는 있습니다.

감지 구간 수는 최근 N개로 제한하지 않고 녹화 중 발생한 모든 원본 구간을 보관합니다.

---

## 11. 촬영과 저장 안정성

다음 상황에서는 원본 파일 손상을 줄이기 위해 녹화를 안전하게 마무리합니다.

- 앱 백그라운드 이동
- 전화 수신 또는 다른 앱의 카메라·마이크 점유
- 카메라 세션 오류
- 저장공간 안전 기준 미달

저장공간 기준:

```text
max(
  1GB,
  현재 포맷 기준 약 2분 예상 영상 용량 + 256MB 마무리 여유
)
```

저장공간은 녹화 시작 직후와 녹화 중 1분 간격으로 확인합니다.

---

## 12. 플랫폼 기술 스택

| 영역 | iOS | Android |
|---|---|---|
| 최소 OS | iOS 16.0 | Android 8.0 · API 26 |
| UI | UIKit | Jetpack Compose · Material 3 |
| 언어 | Swift 5 | Kotlin 2.3 · JVM 17 |
| 카메라 | AVFoundation | CameraX 1.6.1 · Camera2 |
| 원본 녹화 | AVAssetWriter · MOV | CameraX Recorder · MP4 |
| Pose | Apple Vision | MoveNet Thunder FP16 · LiteRT 1.4.1 |
| MobileSAM | Core ML | ONNX Runtime 1.27.0 |
| 영상 재생 | AVPlayer | Media3 1.10.1 |
| 내보내기 | AVAssetExportSession · Photos | Media3 Transformer · MediaStore |
| 설정 저장 | UserDefaults | DataStore Preferences |

### Android 빌드 도구

- compileSdk: `36`
- targetSdk: `36`
- minSdk: `26`
- Android Gradle Plugin: `8.13.2`
- Gradle Wrapper: `8.14.5`
- Kotlin: `2.3.20`
- Java / JVM Target: `17`

### Android 15 Edge-to-Edge

- `enableEdgeToEdge()` 명시 적용
- 투명 상태바·내비게이션 바
- Compose `safeDrawing` / `navigationBarsPadding` 인셋 적용
- Android 10 이상 내비게이션 바 자동 대비 스크림 비활성화
- 지원 중단된 시스템 바 색상 테마 속성에 의존하지 않음

### Android 16KB 메모리 페이지 구성

- LiteRT `1.4.1` 사용
- ONNX Runtime Android `1.27.0`
- `jniLibs.useLegacyPackaging = false`
- `onnx`, `tflite` 리소스 비압축 유지

최종 Play 배포 전에는 실제 release AAB를 빌드해 네이티브 라이브러리 정렬과 16KB 페이지 호환성을 별도로 확인해야 합니다.

---

## 13. 빌드

### iOS

1. `IOS/TopOut/TopOut.xcodeproj` 열기
2. 개발 팀과 Bundle Identifier 확인
3. 아래 파일의 Target Membership 확인
   - `MobileSAM_Encoder.mlmodel`
   - `MobileSAM_Decoder.mlmodel`
   - `PrivacyInfo.xcprivacy`
4. 카메라·마이크·사진 보관함 권한 문구 확인
5. 실제 iPhone 또는 iPad에서 실행

프로젝트 설정:

- Deployment Target: `iOS 16.0`
- Swift: `5.0`
- Marketing Version: `1.4`
- Current Project Version: `5`

### Android

1. Android Studio에서 `Android/TopOut` 열기
2. Gradle JDK를 JDK 17 또는 Embedded JDK로 설정
3. Gradle Sync 실행
4. MoveNet 모델 다운로드 태스크가 정상 완료되는지 확인
5. 실제 기기에서 CameraX, MoveNet, MobileSAM 및 Media3 동작 확인

MoveNet 모델은 빌드 전 공식 모델 URL에서 다운로드해 Gradle 캐시에 검증·저장한 뒤 앱 assets에 복사합니다. 오프라인 빌드에서는 유효한 캐시 파일이 필요합니다.

MobileSAM 모델 경로:

```text
Android/TopOut/app/src/main/assets/models/mobile_sam_encoder.onnx
Android/TopOut/app/src/main/assets/models/mobile_sam_decoder.onnx
```

---

## 14. 회귀 테스트 체크리스트

### 공통

- [ ] 자동 매트 경계가 원래 검출 위치에 표시되고 10px 아래로 이동하지 않는가
- [ ] 수평·완만한 경사 매트에서 두 조절점과 x별 보간이 정상인가
- [ ] 초기 Motion이 2fps, 현재↔직전 분석 프레임 비교로 동작하는가
- [ ] 원본 변화 약 4.5% 수준에서 Motion이 활성화되는가
- [ ] iOS 시작선이 매트보다 6% 위, Android 시작선이 4% 위이며 두 플랫폼 종료선이 2% 위인가
- [ ] 등반 전 Pose와 Pose 유실 복구가 4fps인가
- [ ] 정상 등반 중 추적이 2fps인가
- [ ] 활성 등반 Pose 유실 6초 후 올바르게 종료 또는 Retry 되는가
- [ ] 정상 종료 후 2프레임 연속 미검출 시 초기 Motion으로 복귀하는가
- [ ] 유효한 연속 등반이 1초 이내 시작돼도 별도 구간으로 남는가
- [ ] 2초 미만 고립 구간만 정리되고 모든 정상 구간이 유지되는가

### iOS

- [ ] `HoldVC+UI.swift`에 미정의 `adjustedLine` 참조가 없는가
- [ ] 코가 시작·종료·완등 판단에서 제외되는가
- [ ] 모든 Vision observation의 비얼굴 관절이 합쳐지는 기존 방식인가
- [ ] 단일 인물 선택·이전 위치 추적·anatomical gate 코드가 다시 들어오지 않았는가
- [ ] 완등 후보가 START와 같을 때 실제 종료선 crossing 또는 마지막 유효 Pose로 fallback 되는가

### Android

- [ ] 어깨·골반·팔꿈치·무릎이 각각 1개 이상이어야 유효 Pose인가
- [ ] Motion ROI 면적이 화면의 25% 정사각형인가
- [ ] Android 15 Edge-to-Edge에서 권한·촬영·리뷰 화면 인셋이 안전한가
- [ ] release AAB의 네이티브 라이브러리가 16KB 페이지 환경에 적합한가

---

## 15. 현재 빌드 변경 요약

### v1.4 · build 5

- 자동 매트 경계 10px 하향 보정 삭제
- 기울어진 매트선의 x별 경계 보간 유지
- 시작 경계: iOS는 매트선보다 화면 높이 `6%` 위, Android는 `4%` 위
- 종료 경계: 매트선보다 화면 높이 `2%` 위
- 초기 Motion: 고정 `2fps`, 직전 분석 프레임 비교
- Motion 기준: 노이즈 `2.5%` 보정 + 활성 `2%`
- 등반 전 Pose·Pose 유실 복구: `4fps`
- 정상 등반 추적: `2fps`
- Pose 유실 복구 제한: `6초`
- 정상 종료 후 `2프레임 연속` 미검출 시 초기 탐색 복귀
- 유효 구간 간 1초 이내 일반 병합 로직 삭제
- iOS 단일 인물 선택 및 anatomical gate 롤백
- iOS 코 제외 유지 및 관련 코드 정리
- iOS invalid finish-reference fallback 유지
- Android 15 Edge-to-Edge 및 LiteRT 기반 16KB 페이지 대응 구성

---

## 16. 알려진 플랫폼 차이

- iOS는 여러 Vision observation의 관절을 하나의 판단 집합으로 합칩니다.
- Android는 MoveNet SinglePose 결과 한 명만 사용하며 anatomical gate를 적용합니다.
- Apple Vision과 MoveNet의 관절 좌표·confidence는 동일하지 않습니다.
- iOS는 active crop 전체 Vision 입력을 사용하고, Android는 Motion/Pose ROI를 동적으로 갱신합니다.
- Core ML, LiteRT 및 ONNX Runtime의 부동소수점 결과와 처리 시간은 기기별로 달라질 수 있습니다.
- 카메라 해상도·프레임률·렌즈 전환 지원 범위는 기기 하드웨어에 따라 달라집니다.

---

> 자동 탐지는 편집 후보를 제안하기 위한 기능입니다. 릴리스 전에는 다양한 암장, 촬영 각도, 조도, 매트 색상, 다중 인물 환경과 실제 기기에서 회귀 테스트해야 합니다.
