# TopOut iOS v1.5 (build 10)

TopOut iOS는 오디오가 포함된 연속 촬영에서 등반 구간을 온디바이스로 탐지하고, 결과를 정밀 편집해 시스템 사진 보관함에 저장·검증하는 앱입니다.

**출시 상태: 출시 완료**

[App Store에서 TopOut 보기](https://apps.apple.com/kr/app/topout/id6790163178)

## 현재 릴리스

- Bundle ID: `com.topout.cam`
- Deployment Target: iOS 16.0
- Marketing / Current Project Version: `1.5` / `10`
- UI: Swift, UIKit
- Camera / media: AVFoundation, AVAssetWriter, AVPlayer, PhotoKit
- AI: MobileSAM Core ML, Apple Vision Human Body Pose

## 촬영 준비와 매트 경계

- 앱 시작 시 MobileSAM 모델을 먼저 준비한 뒤 카메라 세션을 시작합니다.
- 실제 비디오 프레임 6장이 도착하기 전에는 `준비 완료` 버튼을 활성화하지 않습니다.
- Ready 이후 최소 4개의 새 프레임과 0.2초를 기다리고, 노출·초점·화이트밸런스가 2프레임 연속 안정된 프레임을 우선 사용합니다. 0.9초가 지나면 최신 프레임으로 진행하며 전체 요청 제한은 1.8초입니다.
- 자동 매트 분석 프레임은 MobileSAM 입력에만 사용하고, 매트 보정과 탑홀드 선택은 라이브 프리뷰 위에서 진행합니다.
- MobileSAM은 y=`70%`, `80%`, `90%`, `95%` 프롬프트를 사용하며 후보가 없으면 y=`75%` 기본선을 제안합니다.
- Ready 이후 카메라 방향·렌즈·줌을 고정합니다. 탑홀드는 탭할 때마다 새 비디오 프레임으로 계산합니다.
- 전화나 다른 앱의 오디오 점유가 끝나면 다음 녹화를 위해 마이크 입력·출력을 카메라 세션에 다시 연결합니다.
- 세로 화면의 준비·설정·줌·녹화 컨트롤은 홈 버튼과 홈 인디케이터를 침범하지 않도록 하단 안전 영역 안에 배치합니다.

## 자동 구간 탐지

- Motion: 160px luma, 현재 프레임과 직전 분석 프레임 비교, 기본 2fps
- 초기 탐색: 편집된 매트선 위 화면 높이 40% 영역
- Pose: `VNDetectHumanBodyPoseRequest`, 활성 촬영 영역의 긴 변 최대 384px
- confidence `0.25` 이상의 모든 Vision observation 비얼굴 관절을 하나의 집합으로 결합
- 시작선: x별 매트선보다 화면 높이 `6%` 위
- 종료선: x별 매트선보다 화면 높이 `2%` 위
- 시작 전·종료 후·Pose 유실 복구: 4fps
- 활성 등반 Pose 유실 허용: 최대 6초
- 정상 종료 후 Pose 2회 연속 미검출: 초기 Motion 탐색으로 복귀

녹화 설정의 30fps·60fps는 카메라와 인코더 프레임률입니다. 분석기는 `CMSampleBuffer`의 presentation timestamp를 기준으로 0.5초 또는 0.25초가 지나기 전 프레임을 건너뛰므로 두 설정 모두 상태별 분석 상한은 2fps·4fps로 같습니다. 분석 중에는 최신 대기 프레임 한 장만 유지하므로 처리 시간이 길어지면 큐를 쌓지 않고 실제 분석 빈도가 이 상한보다 낮아질 수 있습니다.

iOS는 단일 인물 선택이나 어깨·골반·팔꿈치·무릎 anatomical gate를 사용하지 않습니다. 이는 현장 테스트에서 기존 다중 observation 결합 방식이 더 정확했던 결과를 반영한 의도된 플랫폼 차이입니다.

## 결과 편집과 사진 보관함 검증

- 녹화 중 감지된 모든 원본 구간을 보존하고, 2초 미만의 짧은 구간만 인접 구간과의 간격이 3초 이내일 때 병합합니다.
- 최종 후보에는 앞뒤 3초 편집 여백을 적용합니다.
- 결과 화면에서 구간 확대, 0.1초 미세 조정, 중복 구간 방지, 같은 촬영 상태를 유지한 재촬영을 지원합니다.
- PhotoKit 저장 완료 시 생성된 `localIdentifier`를 보관하고, 같은 `PHAsset`을 다시 조회해 실제 `AVAsset`을 열 수 있을 때만 저장 성공으로 확정합니다.
- 검증된 구간은 임시 export 파일이 아니라 사진 보관함에서 다시 읽은 실제 asset으로 재생합니다.
- 여러 구간을 저장해도 구간별 검증 상태와 identifier를 각각 유지합니다.
- 결과 화면은 시스템 자동 잠금 시간을 따르며, 앱이 백그라운드로 이동하면 결과 영상과 사진 보관함 저장 영상 재생을 즉시 일시정지합니다.

## 빌드

1. `TopOut.xcodeproj`를 엽니다.
2. Development Team과 Bundle Identifier를 확인합니다.
3. `MobileSAM_Encoder.mlmodel`, `MobileSAM_Decoder.mlmodel`, `PrivacyInfo.xcprivacy`의 Target Membership을 확인합니다.
4. 카메라·마이크·사진 보관함 권한 문구를 확인합니다.
5. 실제 iPhone 또는 iPad에서 Archive/실행 검증을 수행합니다.

```bash
xcodebuild -project TopOut.xcodeproj -scheme TopOut -configuration Debug \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

## 문서

- [v1.5 build 10 변경 사항](V1_5_BUILD_10_NOTES.md)
- [MobileSAM 매트 경계](MOBILESAM_MAT_DETECTOR_NOTES.md)
- [시작과 프레임 처리](STARTUP_OPTIMIZATION_NOTES.md)
- [Pose 로직](POSE_LOGIC_ROLLBACK_NOTES.md)
- 과거 릴리스 기록: [v1.5 build 9](V1_5_BUILD_9_NOTES.md), [v1.5 build 8](V1_5_BUILD_8_NOTES.md), [v1.5 build 7](V1_5_BUILD_7_NOTES.md), [v1.4 build 5](V1_4_BUILD_5_NOTES.md)
