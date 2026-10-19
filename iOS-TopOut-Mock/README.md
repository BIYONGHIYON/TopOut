# TopOut iOS 목업

해외 사용자에게 TopOut의 다국어 화면과 전체 사용 흐름을 보여주기 위한 독립 iOS 목업 앱입니다. 실제 iOS 앱의 UI, 언어 설정, 매트 분석, 구간 탐지, 결과 편집과 사진 앱 저장 흐름을 복제하고 입력 장치만 번들 영상으로 교체했습니다.

## 실행

1. `TopOutMock.xcodeproj`를 Xcode에서 엽니다.
2. `TopOutMock` 스킴과 실행할 iPhone 또는 Simulator를 선택합니다.
3. 앱을 실행한 뒤 설정에서 표시할 언어를 선택합니다.

목업 앱의 Bundle ID는 `com.topout.cam.mock`, 앱 표시는 `TopOut Mock`입니다. 카메라와 마이크 권한은 요청하지 않으며 사진 앱 저장·검증 기능을 사용할 때만 사진 보관함 권한을 요청합니다.

## 목업 영상

```text
TopOutMock/
└── MockMedia/
    └── test.MOV
```

`test.MOV`는 무음으로 무한 반복됩니다. 화면에 표시되는 프레임과 분석·녹화에 전달되는 프레임은 같은 `AVPlayerItemVideoOutput`에서 가져옵니다. 반복 지점에서 영상 시간이 처음으로 돌아가더라도 recorder에는 시스템의 연속 시간축을 전달해 녹화 타임스탬프가 역행하지 않습니다.

영상을 교체할 때는 새 파일의 이름과 확장자를 `test.MOV`로 유지합니다. 이 영상은 용량이 크고 목업별로 교체할 수 있어 루트 `.gitignore` 규칙에 따라 Git에는 포함되지 않습니다.

## 입력 흐름

```text
test.MOV 무음 반복 재생
→ AVPlayerItemVideoOutput 프레임 추출
→ VideoFrameSink
→ MotionDetector · Vision Pose
→ ContinuousRecorder
→ 기존 결과 편집·사진 앱 저장 화면
```

- 카메라 캡처 세션과 카메라 권한 요청 없음
- 마이크 입력과 녹음용 오디오 세션 없음
- 목업 녹화 파일은 무음
- 기존 MobileSAM, Motion, Vision Pose와 결과 편집 기준 유지
- 결과 화면의 재생용 오디오 세션 전환은 실제 앱과 같이 주 스레드 밖의 전용 큐에서 진행
- 실제 앱과 같이 시작 시 이전 실행이 남긴 `TopOutContinuous` 임시 녹화 폴더를 백그라운드에서 정리
- 탑홀드 분석이 5초 안에 끝나지 않으면 탭 위치의 기본 원형 마스크로 선택을 완료하고 늦게 도착한 결과는 무시
- 영어, 한국어, 일본어, 중국어 간체·번체, 프랑스어, 독일어, 스페인어, 포르투갈어 지원
- 실제 앱과 같은 다국어 안내를 사용하며, 벽과 매트 사이의 경계와 다음 조작을 한 줄 안에서 구체적으로 표시

## 주요 파일

```text
TopOutMock/Code/
├── MockCameraSource.swift   영상 반복 재생과 연속 프레임 생성
├── Camera.swift             기존 카메라 API를 목업 입력에 연결
├── VideoFrameSink.swift     목업 프레임을 분석·녹화 흐름에 전달
└── 나머지 파일              실제 iOS 앱의 UI와 분석·편집 로직
```

원본 출시 앱은 `iOS/TopOut`에서 계속 관리합니다. 목업 전용 변경은 이 폴더 안에서만 진행해 실제 카메라 앱에 섞이지 않도록 합니다.

## 확인 범위

Swift 전체 타입 검사와 Xcode 프로젝트·스킴 구성을 확인합니다. 실제 영상 반복의 자연스러움, 프레임 방향, 분석 결과와 다국어 화면 배치는 실행할 기기 또는 Simulator에서 최종 확인합니다.
