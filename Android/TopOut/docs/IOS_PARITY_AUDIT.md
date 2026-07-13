# TopOut iOS → Android 정합 수정 감사표

기준: 완성된 iOS 프로젝트의 실제 Swift 실행 코드. Android 수정은 언어·프레임워크 이름을 같게 만드는 것이 아니라, 사용자가 동일한 설정과 촬영 흐름을 사용했을 때 조건·상태 전환·상수·문구가 iOS 기준으로 동작하도록 맞추는 것을 목표로 했습니다.

## 1. 반영 완료 항목

| 영역 | iOS 기준 | Android 반영 |
|---|---|---|
| 기본값 | 자동 ON, 마스킹 OFF, 도움말 ON, 실시간 화면 OFF | `AppSettings`, `SettingsRepository` 기본값 변경 |
| 기능 독립성 | 자동 탐지와 마스킹 독립 | 강제 normalization과 스위치 잠금 제거 |
| 선택 조건 | 마스킹일 때만 탑홀드 필요 | `requiresTopHoldSelection = topHoldMaskingEnabled` |
| 자동만 사용 | 매트만 보정, 탑홀드 불필요 | SELECT 단계에서 mat-only 녹화 허용 |
| 종료 기준 | 마스크 ON 최근접 / OFF 최고점 | `MotionFinishReference` 두 전략 추가 |
| Pose 입력 | 긴 변 512 | Android 정확도 우선 설정으로 `PoseInputMaxLongSide = 512` 적용 |
| SAM mask | 256 | 후처리 최대 긴 변 256 |
| 둥근 외곽선 | 10% padding, corner 0.48 | outline 전용 padded bounds 적용 |
| 외곽선 선폭 | min side 1.6%/0.6%, 최소 8/2.5px | 선택·프리뷰·export overlay 공통 상수화 |
| 줌 | 선택 1–5, 카메라 최대 8 | 선택 최소·최대 및 카메라 상한 수정 |
| 기준 프레임 | video sample buffer 우선 | ImageAnalysis 프레임 우선, Preview bitmap fallback |
| 녹화 비트레이트 | 8/14/45/70Mbps + 비율 scale | CameraX Recorder target bitrate 적용 |
| 오디오 시작 실패 | 영상-only 지속 | `withAudioEnabled()` 실패 시 무음 fallback |
| 분석 불가 | 자동 분석이 필요한데 분석 스트림이 없으면 촬영 불가 | 명시적인 설정 변경 안내 추가 |
| 리뷰 seek | zero tolerance | ExoPlayer `SeekParameters.EXACT` 적용 |
| 플레이어 오류 | 도움말 ON 시 상단 안내 | Player listener와 동일 문구 추가 |
| 저장 상태 | 자르기/마스크/사진 앱/성공/실패 단계 | ViewModel saving status 분기 일치 |
| 상단 문구 | 설정 조합별 Swift 문자열 | `TopOutCopy.kt`로 중앙화 및 단계별 적용 |

## 2. 설정 조합별 흐름

| 자동 탐지 | 마스킹 | 준비 후 흐름 | 자동 종료 기준 |
|---|---|---|---|
| OFF | OFF | 바로 3초 카운트다운 | 없음 |
| ON | OFF | 매트 자동 탐지·수동 보정 | 신체 최고점 시점 |
| OFF | ON | 탑홀드 선택 | 없음 |
| ON | ON | 매트 보정 + 탑홀드 선택 | 탑홀드 최근접 시점 |

## 3. 상단 문구

### 프리뷰

- 자동+마스크: `벽과 매트 경계선 · 탑 홀드가 모두 보이게 맞춘 뒤 준비 완료`
- 마스크만: `탑홀드가 잘 보이게 맞춘 뒤 준비 완료`
- 자동만: `벽과 매트 경계선이 보이게 맞춘 뒤 준비 완료`
- 둘 다 OFF: 빈 문자열

### 선택

- 자동+마스크: `탑홀드를 탭하세요 · 파란 점으로 매트 경계를 보정하세요`
- 자동만: `파란 점으로 매트 경계를 보정한 뒤 녹화 시작을 눌러주세요`
- 마스크만: `탑홀드를 탭하세요`

### 녹화·탐지

- `3초 후 녹화 시작 · 매트 보정 중` / `3초 후 녹화 시작`
- `녹화 준비 중 · 매트 보정 중` / `녹화 준비 중`
- `모션 탐지 중 · 등반 시작 대기` / `녹화 중`
- `등반 구간 감지 중 · 탑홀드 추적 중`
- `등반 구간 감지 중 · 최고점 추적 중`
- `구간 저장됨 · 다음 등반 대기`
- `영상 정리 중...`

### 리뷰·저장

- `자동 감지된 시작~완등 구간을 확인해주세요`
- `저장할 구간을 직접 선택해주세요`
- `영상을 재생할 수 없습니다. 다시 촬영해주세요`
- `탑홀드 마스크를 입히는 중...`
- `선택 구간 자르는 중...`
- `사진 앱에 저장하는 중...`
- `구간 선택된 동영상을 저장했습니다`
- `사진 앱 저장 실패: …`
- `구간 저장 실패: …`

## 4. 추가된 회귀 테스트

- `AppSettingsIosParityTest.kt`
  - iOS 기본값
  - 기능 독립성
  - 탑홀드 선택 조건
  - 줌/Pose/SAM/outline 상수
- `TopOutCopyIosParityTest.kt`
  - 설정 조합별 상단 문구
  - 카운트다운·녹화·추적 문구
- `RecordingBitrateParityTest.kt`
  - FHD/4K, 30/60fps 비트레이트
  - 1:1 비율 scale
- 기존 렌즈 줌 테스트의 최대값을 8배로 조정

## 5. 플랫폼상 남는 차이

다음은 Android 코드 오류가 아니라 OS·프레임워크 차이이므로 iOS와 완전히 같은 픽셀·프레임·파일을 보장할 수 없습니다.

1. **Pose 엔진**: Apple Vision과 ML Kit Accurate Pose가 서로 다른 모델을 사용합니다.
2. **SAM 런타임**: Core ML과 ONNX Runtime의 연산 provider와 부동소수점 결과가 다릅니다.
3. **렌즈 전환**: iOS virtual camera와 Android 제조사별 공개 physical camera ID가 다릅니다.
4. **컨테이너·인코더**: iOS MOV/AVAssetWriter와 Android MP4/CameraX Recorder가 codec profile·keyframe·AAC 세부값을 동일하게 노출하지 않습니다.
5. **내보내기**: AVAssetExportSession과 Media3 Transformer의 passthrough·재인코딩 정책이 다릅니다.
6. **저장 위치**: iOS Photos와 Android MediaStore `Movies/TopOut`을 사용합니다.

## 6. 검증 상태

- 설정·문구·비트레이트 등 순수 Kotlin 모델 검증: 통과
- 전체 Kotlin 구문 형태 정적 검사: 오류 패턴 없음
- 전체 Gradle 빌드: 실행 환경에 Android SDK 및 Gradle 8.13 캐시가 없고 `services.gradle.org` DNS가 차단되어 미실행
- 실제 기기 검증 필요: CameraX 렌즈, 4K/60 지원, MobileSAM 메모리, 장시간 발열, Media3 export
