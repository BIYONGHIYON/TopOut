# TopOut Android Mock — Person Detector

`app/src/main/assets/mock/test.MOV`를 실제 카메라 입력처럼 무한 반복해 자동 구간 감지를 검증하는 테스트 전용 프로젝트입니다.

## 분석 로직

- 전체 화면의 연속 프레임 변화를 4fps로 비교
- 변화가 생기면 EfficientDet-Lite2 사람 객체 탐지를 2fps로 실행
- 1초 동안 사람이 없으면 다시 프레임 변화 감지 상태로 복귀
- 사람이 잡히면 바운딩 박스의 아래쪽과 매트 경계를 비교해 시작·종료 판정
- 모든 Android 기기에서 CPU 고정으로 객체 탐지
- 사람 신뢰도 0.25 이상, 위치 통과 1프레임 즉시 확인
- 매트 기준 5%, 위치 완충 2%
- 영상 미리보기와 결과 파일은 무음
- 자동 감지 구간 정리·리뷰·저장 흐름은 기존 Mock과 동일
- 앱 소스의 디버그 로그 제거 상태 유지

## test.MOV 위치

```text
app/src/main/assets/mock/test.MOV
```

사용자 컴퓨터 기준:

```text
/Users/biyonghiyon_e/Desktop/TopOut/TopOut_Androidmock/app/src/main/assets/mock/test.MOV
```

## 실행

```text
Android Studio → Sync Project with Gradle Files → Rebuild Project → Run
```

첫 Gradle 빌드에서 약 12MB의 EfficientDet-Lite2 float16 모델을 내려받습니다. 자동 다운로드가 실패하면 프로젝트 루트에서 실행합니다.

```bash
./download_person_detector_model.command
```

## 테스트 앱 식별자

```text
applicationId: com.topout.cam.mock
앱 이름: TopOut Mock
```

프로덕션 앱 `com.topout.cam`과 동시에 설치할 수 있습니다. 이 프로젝트는 Play Store 배포용이 아닙니다.

## 객체 탐지 실행 장치
MediaPipe GPU delegate의 제조사·GPU 드라이버별 네이티브 크래시를 피하기 위해
모든 Android 기기에서 EfficientDet-Lite2 객체 탐지를 CPU 2fps로 실행합니다.
