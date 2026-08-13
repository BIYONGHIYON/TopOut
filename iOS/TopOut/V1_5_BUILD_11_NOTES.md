# TopOut iOS v1.5 (build 11)

> 과거 릴리스 기록입니다. 현재 릴리스는 [v1.6 build 12](V1_6_BUILD_12_NOTES.md)이며 현재 동작은 [iOS README](README.md)를 기준으로 합니다.

## iPhone startup compatibility

- MobileSAM 로딩이 앱 진입과 카메라 권한·프리뷰 준비를 막지 않도록 시작 순서를 분리했습니다.
- 실제 카메라 프레임이 안정된 뒤 모델을 백그라운드에서 준비하고, Core ML의 GPU 사용을 제외해 iPhone 12 mini 같은 메모리 제약 기기의 시작 안정성을 보강했습니다.
- 모델 준비가 실패하더라도 카메라 화면은 정상 진입하며 분석 시 재시도 또는 기존 기본 경계를 사용합니다.

## Frame-accurate clip export

- 마스크를 포함하지 않은 저장에서도 H.264 passthrough trim을 사용하지 않고 선택 구간을 고화질로 다시 인코딩합니다.
- 선택 시작점이 키프레임 사이에 있어도 첫 비디오 프레임부터 디코딩 가능한 새 구간으로 만들며, 저장 영상 초반은 오디오와 같은 시점에서 시작합니다.
- 저장 시간은 선택 구간 길이와 해상도·FPS에 따라 기존 passthrough 방식보다 늘어날 수 있습니다.

## Validation

- Xcode 26.5 iOS Simulator SDK에서 전체 Swift 소스와 MobileSAM Core ML 인터페이스를 포함한 Debug 빌드를 확인했습니다.
- Archive·IPA는 생성하지 않으며 iPhone 12 mini의 최종 시작 안정성은 실기기 또는 TestFlight로 확인합니다.

## Release identity

- Marketing Version: `1.5`
- Current Project Version: `11`
