# TopOut iOS v1.5 (build 9)

> 과거 릴리스 기록입니다. 현재 릴리스는 [v3.0 build 23](V3_0_BUILD_23_NOTES.md)이며 현재 동작은 [iOS README](README.md)를 기준으로 합니다.

## Camera readiness

- 실제 비디오 프레임 6장이 도착하기 전에는 `준비 완료`를 활성화하지 않습니다.
- Ready 직후 자동 매트 분석은 최소 4개의 새 프레임과 0.2초를 기다린 뒤, 노출·초점·화이트밸런스가 2프레임 연속 안정된 프레임을 우선 사용합니다.
- 카메라 3A가 오래 조정 중이면 0.9초 후 최신 프레임으로 진행하며 프레임 요청 전체 제한은 1.8초입니다.
- 이미 선택된 촬영 포맷은 Ready 때 불필요하게 다시 적용하지 않아 콜드 스타트 직후의 세션 흔들림을 줄였습니다.
- 매트 분석 이미지는 사용자에게 표시하지 않고 선택 화면은 라이브 프리뷰를 유지합니다. 탑홀드는 매 탭마다 새 프레임으로 다시 계산합니다.

## Saved-video verification

- Photos 저장이 완료되면 생성된 `localIdentifier`로 같은 `PHAsset`을 다시 조회합니다.
- PhotoKit을 통해 실제 `AVAsset`을 불러올 수 있을 때만 저장 성공으로 확정합니다.
- 저장된 클립 확인은 임시 export 파일이 아닌 사진 보관함에서 다시 읽은 asset을 재생합니다.
- 여러 선택 구간의 저장·검증 상태를 각각 유지합니다.

## Release identity

- Marketing Version: `1.5`
- Current Project Version: `9`
