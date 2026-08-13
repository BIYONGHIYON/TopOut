# TopOut iOS v1.6 (build 13)

## Multi-person pose targeting

- 매트선 인근의 밝기 변화를 8열 저해상도 격자로 나눠 실제 움직임 위치를 보존합니다.
- Vision이 여러 사람을 반환하면 격자 변화 영역과 겹치고 가까운 한 명만 최초 등반자로 선택합니다.
- 추적이 시작된 뒤에는 직전 관절 경계와 중심에 가장 자연스럽게 이어지는 한 명만 유지해 주변 인물의 관절이 시작·종료·완등 판단에 섞이지 않도록 했습니다.
- 직전 대상과 겹치지 않고 정규화된 촬영 영역에서 중심 거리가 `0.40`보다 먼 인물은 즉시 대체하지 않고 기존 Pose 유실 복구 후 새 Motion 위치에서 다시 선택합니다.
- Vision 추론 횟수와 2fps·4fps 분석 주기는 유지하며, Pose 유실 후 초기 Motion 탐색으로 돌아갈 때만 대상을 다시 선택합니다.

## Validation

- Xcode 26.5 iOS Simulator SDK에서 arm64·x86_64 Debug 빌드를 통과했습니다.
- Archive·IPA는 생성하지 않으며 다중 인물 선택 품질은 실기기에서 최종 확인합니다.

## Release identity

- Marketing Version: `1.6`
- Current Project Version: `13`
