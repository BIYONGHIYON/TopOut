# TopOut

> 등반 시작부터 완등 후보 순간까지 자동으로 찾아주는 온디바이스 클라이밍 촬영 앱

TopOut은 한 번의 연속 촬영에서 여러 등반 시도의 시작과 완등 후보를 자동으로 찾고, 사용자가 구간을 검토·정밀 편집·저장하거나 같은 앵글로 바로 다시 촬영할 수 있게 하는 iOS·Android 앱입니다.

**출시 상태: iOS · Android 모두 출시 완료**

## 현재 릴리스

| 항목 | iOS | Android |
|---|---|---|
| 버전 | 1.5 (build 9) | 1.5 (versionCode 9) |
| 최소 OS | iOS 16.0 | Android 8.0 · API 26 |
| 앱 ID | `com.topout.cam` | `com.topout.cam` |
| UI | Swift · UIKit | Kotlin · Jetpack Compose · Material 3 |
| 카메라 | AVFoundation | CameraX 1.6.1 · Camera2 |
| Pose | Apple Vision | MoveNet Thunder FP16 · LiteRT 1.4.1 |
| MobileSAM | Core ML | ONNX Runtime 1.27.0 |
| 영상 | AVAssetWriter · AVPlayer · PhotoKit | CameraX Recorder · Media3 1.10.1 · MediaStore |

모든 영상 프레임, 관절 좌표, 중간 분석 결과는 기기 안에서 처리하며 외부 분석 서버로 전송하지 않습니다.

## 제품 흐름

```text
모델·카메라 준비
→ 촬영 포맷과 구도 설정
→ 준비 완료 이후 안정된 새 프레임으로 매트 경계 분석
→ 라이브 프리뷰에서 매트 보정·선택적 탑홀드 지정
→ 3초 카운트다운 후 연속 녹화
→ Motion / Pose 기반 다중 등반 구간 탐지
→ 구간 검토·정밀 편집·재촬영
→ 시스템 갤러리 저장 후 실제 저장 항목 재조회·재생 검증
```

자동 탐지는 편집 후보를 제안합니다. 원본 녹화는 자동 구간이 끝나도 중단되지 않으며 사용자가 종료할 때까지 계속됩니다.

## 저장소 구조와 문서

```text
TopOut/
├── iOS/TopOut/
│   ├── README.md
│   ├── V1_5_BUILD_9_NOTES.md
│   ├── TopOut.xcodeproj/
│   └── TopOut/
├── Android/TopOut/
│   ├── README.md
│   ├── V1_5_BUILD_9_NOTES.md
│   ├── app/
│   └── docs/
├── portfolio/
│   ├── index.html
│   ├── styles.css
│   └── assets/
└── README.md
```

- [iOS 현재 문서](iOS/TopOut/README.md)
- [Android 현재 문서](Android/TopOut/README.md)
- [iOS build 9 변경 사항](iOS/TopOut/V1_5_BUILD_9_NOTES.md)
- [Android build 9 변경 사항](Android/TopOut/V1_5_BUILD_9_NOTES.md)
- [제품 포트폴리오](portfolio/index.html)
- [TopOut 공개 저장소](https://github.com/BIYONGHIYON/TopOut-app)

`V1_4_*`, `V1_5_BUILD_7_*`, `V1_5_BUILD_8_*` 문서는 당시 동작을 보존하는 과거 릴리스 기록입니다. 현재 동작의 기준은 각 플랫폼 README와 build 9 노트입니다.

로컬의 `TopOut-app/` 폴더는 별도 Git 이력을 가진 공개 저장소 checkout이므로 이 비공개 저장소의 `.gitignore`에서 제외합니다.

## 촬영 준비와 프레임 정합성

선택 UI에는 캡처된 정지 이미지를 표시하지 않습니다. 매트 보정과 탑홀드 선택은 계속 움직이는 실제 카메라 프리뷰 위에서 진행하고, MobileSAM 입력 프레임만 내부에서 별도로 캡처합니다.

### iOS

- 실제 비디오 프레임 6장이 도착하기 전에는 `준비 완료`를 활성화하지 않습니다.
- Ready 이후 최소 4개의 새 프레임과 0.2초를 기다리고, 노출·초점·화이트밸런스가 2프레임 연속 안정된 프레임을 우선 사용합니다.
- 3A가 계속 조정 중이면 0.9초 후 최신 프레임으로 진행하며 전체 요청 제한은 1.8초입니다.
- 선택한 촬영 포맷이 이미 적용된 경우 Ready에서 불필요하게 재설정하지 않습니다.

### Android

- CameraX Preview가 실제 `STREAMING` 상태이고 선택한 줌이 적용된 뒤 `준비 완료`를 활성화합니다.
- Ready 이후 새 ImageAnalysis 프레임 6장을 건너뛴 프레임을 자동 매트 분석에 사용합니다.
- Preview, VideoCapture, ImageAnalysis는 같은 ViewPort와 화면비 좌표계를 공유합니다.
- Android 16 대화면에서 orientation 요청이 무시되는 경우에도 Ready 시점 회전·구도를 세션 동안 고정합니다.

두 플랫폼 모두 Ready 이후 렌즈·줌·회전을 고정하며, 탑홀드는 탭할 때마다 새 프레임과 새 임베딩으로 계산합니다.

## MobileSAM 매트 경계

두 플랫폼은 같은 자동 후보 규칙을 사용합니다.

- Positive X: `50%`
- Positive Y: `70%`, `80%`, `90%`, `95%`
- Negative point: `x = 50%`, `y = 28%`
- 가로 점유 폭: 화면 너비 `40%` 이상
- 마스크 최상단: 화면 높이 `50%` 이하 영역에서 시작
- 상단 경계 기울기: 최대 `15°`
- 유효 후보 중 화면에서 가장 높은 상단 경계 선택
- 경계를 `x = 6%`, `x = 94%`의 편집 가능한 두 점으로 변환
- 후보가 없으면 `y = 75%` 수평 기본선 사용
- 별도의 10px 하향 보정 없음

기울어진 매트는 평균 Y 하나로 축약하지 않고 관절의 x 위치마다 직선을 보간해 시작선·종료선을 계산합니다.

## Motion과 Pose 상태 정책

공통 Motion 기준:

- 분석 프레임 너비 `160px`
- 현재 분석 프레임과 직전 분석 프레임 비교
- 기본 주기 `2fps`, 시작 전·종료 후·유실 복구 `4fps`
- 픽셀 밝기 차이 기준 `20`
- 매트선 위 화면 높이 최대 `40%`에서 초기 탐색
- 노이즈 보정 `2.5%`, 보정 후 활성 기준 `2%`
- 활성 등반 중 Pose 유실 허용 `6초`
- 정상 종료 후 Pose 2회 연속 미검출 시 초기 Motion 탐색 복귀

| 판정 | iOS | Android |
|---|---|---|
| 시작선 | x별 매트선보다 화면 높이 6% 위 | x별 매트선보다 화면 높이 4% 위 |
| 종료선 | x별 매트선보다 화면 높이 2% 위 | x별 매트선보다 화면 높이 2% 위 |
| Pose 입력 | active crop, 긴 변 최대 384px | Motion/Pose ROI, 256×256 RGB |
| 사람 검증 | 모든 Vision observation의 비얼굴 관절 결합 | SinglePose의 4개 신체 그룹 중 3개 이상 |

Android의 4개 그룹은 어깨·골반·팔꿈치·무릎이며, 각 그룹은 좌우 중 하나가 confidence `0.25` 이상이면 충족합니다. iOS는 현장 정확도를 위해 단일 인물 선택이나 anatomical gate 없이 모든 observation의 신뢰 비얼굴 관절을 합칩니다.

시작·종료·최고점·탑홀드 판단에는 어깨, 팔꿈치, 손목, 골반, 무릎, 발목을 사용하며 얼굴 관절은 제외합니다.

## 감지 구간과 리뷰

1. 패딩 없는 원본 구간을 시작 시각 순으로 정렬합니다.
2. 원본 길이가 2초 미만인 구간만 확인합니다.
3. 앞뒤 이웃 중 원본 간격이 3초 이내인 가장 가까운 구간과 병합합니다.
4. 병합할 이웃이 없는 짧은 구간은 제외합니다.
5. 나머지 정상 구간은 다음 시도와 가까워도 각각 유지합니다.
6. 각 구간에 앞뒤 3초 편집 여백을 적용합니다.
7. 녹화 전체 길이 안으로 범위를 제한합니다.

결과 화면은 전체·확대 타임라인, 0.1초 미세 조정, 구간별 저장 상태, 중복 범위 방지, 촬영 컨텍스트를 유지한 재촬영을 제공합니다.

## 갤러리 저장 검증

내보내기 성공만으로 저장 완료를 표시하지 않습니다. 사용자가 실제로 접근할 시스템 갤러리 항목을 다시 열 수 있어야 저장 성공으로 확정합니다.

### iOS

```text
PhotoKit 저장
→ 생성된 localIdentifier 보관
→ 동일 PHAsset 재조회
→ 실제 AVAsset 요청
→ 사진 보관함 asset 재생
```

### Android

```text
MediaStore 게시
→ 동일 content URI 재조회
→ 파일 디스크립터·입력 스트림·메타데이터 확인
→ 동일 content URI를 Media3로 재생
```

Android 저장 영상 보기에는 앞/뒤 5초 이동, 드래그 즉시 화면이 따라오는 슬라이더, 영상 가림을 줄인 반투명 컨트롤이 적용돼 있습니다. 두 플랫폼 모두 여러 클립의 저장·검증 식별자를 구간별로 유지합니다.

## 카메라와 저장 안정성

- 기본값: FHD, 60fps, 9:16, 자동 구간 탐지 ON, 탑홀드 마스킹 OFF, 녹화 중 프리뷰 OFF
- 지원 후보: FHD / 4K, 30 / 60fps, 9:16 / 4:5 / 3:4 / 1:1, 세로 / 가로
- 지원하지 않는 조합은 기기에서 보장되는 포맷으로 폴백
- Android 60fps는 Preview·VideoCapture·ImageAnalysis의 활성 세션 전체가 정확한 FPS를 보장할 때만 선택
- 백그라운드 전환, 카메라·마이크 중단, 세션 오류, 저장공간 부족 시 원본을 먼저 안전하게 종료
- 최소 저장공간: `max(1GB, 예상 2분 영상 용량 + 256MB)`
- 저장공간은 녹화 시작 전과 녹화 중 1분 간격으로 확인

## 빌드

### iOS

1. `iOS/TopOut/TopOut.xcodeproj`를 엽니다.
2. Development Team과 Bundle Identifier를 확인합니다.
3. MobileSAM Core ML 모델과 `PrivacyInfo.xcprivacy`의 Target Membership을 확인합니다.
4. 실제 iPhone/iPad에서 카메라·마이크·사진 보관함 권한과 Archive를 검증합니다.

프로젝트 설정: iOS 16.0, Swift 5, Marketing Version 1.5, Current Project Version 9.

### Android

1. Android Studio에서 `Android/TopOut`을 엽니다.
2. Gradle JDK 17을 사용합니다.
3. Gradle Sync 후 모델 검증과 테스트·디버그 빌드를 실행합니다.

```bash
cd Android/TopOut
./gradlew downloadMoveNetPoseModel
./check_models.command
./gradlew testDebugUnitTest assembleDebug
```

첫 MoveNet 빌드는 공식 모델 다운로드를 위한 네트워크가 필요하고, 이후에는 검증된 Gradle 캐시를 사용할 수 있습니다.

## 회귀 테스트 핵심 체크리스트

- [ ] 앱 실행 직후에도 `준비 완료`가 안정 프레임 전에는 활성화되지 않는가
- [ ] 동일 앵글에서 첫 매트 경계와 재촬영 후 매트 경계가 일관적인가
- [ ] 선택 화면이 라이브 프리뷰를 유지하고 분석 정지 화면을 노출하지 않는가
- [ ] 탑홀드를 다시 누를 때마다 현재 프레임으로 마스크가 갱신되는가
- [ ] 수평·경사 매트에서 x별 경계 보간이 올바른가
- [ ] iOS 6%, Android 4% 시작선과 공통 2% 종료선이 적용되는가
- [ ] Android Pose gate가 4/4·3/4는 통과하고 2/4는 거부하는가
- [ ] 활성 등반 중 6초 복구와 정상 종료 후 2회 미검출 복귀가 동작하는가
- [ ] 유효한 연속 등반이 가까워도 서로 다른 구간으로 남는가
- [ ] 같은 구간을 중복 저장 후보로 추가하지 않는가
- [ ] 저장 완료 후 실제 Photos/MediaStore 항목을 앱 안에서 재생할 수 있는가
- [ ] Android 저장 영상 슬라이더가 드래그와 동시에 이동하고 앞/뒤 이동이 모두 5초인가
- [ ] Android Fold·가로 모드에서 영상 슬라이더와 하단 버튼이 겹치지 않는가
- [ ] Android 60fps 선택이 실제 활성 세션의 보장 여부와 일치하는가
- [ ] Android release AAB의 16KB page alignment를 확인했는가

## 알려진 플랫폼 차이

- iOS는 여러 Vision observation의 신뢰 관절을 하나의 판단 집합으로 합칩니다.
- Android는 MoveNet SinglePose 한 명과 3-of-4 anatomical group gate를 사용합니다.
- iOS는 active crop 전체를 Vision 입력으로 사용하고, Android는 Motion/Pose ROI를 동적으로 갱신합니다.
- Core ML, Vision, LiteRT, ONNX Runtime의 처리 시간과 카메라 포맷 지원 범위는 기기별로 달라질 수 있습니다.

---

자동 감지는 편집 후보를 빠르게 제안하기 위한 기능입니다. 릴리스 전에는 다양한 암장, 조도, 촬영 각도, 매트 색상, 다중 인물 환경과 실제 기기에서 회귀 테스트해야 합니다.
