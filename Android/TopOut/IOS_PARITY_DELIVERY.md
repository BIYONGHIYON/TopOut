# iOS 정합 수정본 전달 메모

- 기준 iOS: 업로드된 완성본 `TopOut(7).zip`
- 수정 대상 Android: 업로드된 `TopOut(6).zip`
- 핵심 변경: 자동 탐지/마스킹 독립, 최고점 종료 기준, 기본값, 단계별 상단 문구, Pose 512, 시작 검증 SINGLE_IMAGE_MODE, 등반 추적 STREAM_MODE, SAM 256, outline 규칙, 줌 상한, bitrate, 리뷰 seek 및 저장 상태
- 상세 감사표: `docs/IOS_PARITY_AUDIT.md`

이 전달본은 소스 프로젝트이며 `.gradle`, `.idea`, `app/build`, `local.properties` 등 로컬/생성 파일을 제거했습니다. Android Studio에서 프로젝트를 열고 Gradle Sync 및 Rebuild를 실행하세요.
