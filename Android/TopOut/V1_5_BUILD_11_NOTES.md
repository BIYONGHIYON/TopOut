# TopOut Android v1.5 (build 11)

> 과거 릴리스 기록입니다. 현재 릴리스는 [v3.0 versionCode 23](V3_0_BUILD_23_NOTES.md)이며 현재 동작은 [Android README](README.md)를 기준으로 합니다.

## Startup compatibility

- 앱 진입과 CameraX 프리뷰 시작이 MobileSAM ONNX 세션 준비를 기다리지 않도록 분리했습니다.
- 실제 프리뷰가 준비된 뒤 MobileSAM을 백그라운드에서 한 번만 준비해 메모리와 CPU가 제한된 기기의 시작 실패 위험을 줄였습니다.

## Frame-aligned clip export

- 선택 구간 시작이 키프레임 사이에 있으면 Media3의 기존 재인코딩 경로를 유지합니다.
- 결과 파일을 첫 영상 프레임부터 시작하도록 정렬해, 일부 갤러리 플레이어에서 오디오는 재생되지만 초반 영상이 멈춰 보일 수 있는 호환성 문제를 보강했습니다.

## Validation

- Android JVM 단위 테스트와 Debug Kotlin 컴파일을 통과했습니다.
- `lintDebug`를 통과했으며 AAB·APK 같은 배포 산출물은 생성하지 않았습니다.

## Release identity

- Version name: `1.5`
- Version code: `11`
