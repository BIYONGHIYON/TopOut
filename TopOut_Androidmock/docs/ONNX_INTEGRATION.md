# MobileSAM ONNX 모델 배치 및 진단

ONNX 실행 코드와 모델 파일이 이미 포함되어 있습니다. 모델 위치는 다음과 같습니다.

```text
app/src/main/assets/models/mobile_sam_encoder.onnx
app/src/main/assets/models/mobile_sam_decoder.onnx
```

## 예상하는 표준 입력과 출력

Encoder:

```text
input: float32 [1, 3, 1024, 1024]
image_embeddings: float32 [1, 256, 64, 64]
```

Decoder:

```text
image_embeddings
point_coords
point_labels
mask_input
has_mask_input
orig_im_size

masks
iou_predictions
low_res_masks
```

앱은 텐서 이름과 일부 동적 shape을 자동으로 확인합니다. Encoder는 NCHW와 NHWC 레이아웃을 감지하고, decoder mask가 원본 크기 또는 256×256 low-resolution 출력인 경우 모두 원본 좌표로 변환합니다.

## 첫 실행 오류 확인

### 모델 파일을 찾지 못한 경우

다음 철자와 대소문자를 그대로 사용했는지 확인합니다.

```text
mobile_sam_encoder.onnx
mobile_sam_decoder.onnx
```

### external data 오류

모델이 `.onnx.data` 파일을 별도로 참조하면 두 `.onnx` 파일만으로 실행되지 않습니다. 가중치가 모델 내부에 포함된 형태로 다시 export해야 합니다.

### 입력 이름 오류

오류 메시지에 모델의 실제 `available=[...]` 이름이 표시됩니다. 표준 이름과 완전히 다른 custom export라면 `OnnxMobileSamSegmenter.kt` 하단 alias 목록에 해당 이름을 추가합니다.

### 출력 타입 오류

현재 구현은 일반적인 float32 MobileSAM 모델을 대상으로 합니다. float16 전용 모델이라면 입력 텐서 생성과 출력 변환을 float16 형식으로 확장해야 합니다.

## 성능 구조

- 앱 시작 시 encoder와 decoder 세션을 한 번만 생성합니다.
- 한 장의 정지 화면에서 탑홀드를 다시 탭할 때 encoder embedding을 재사용합니다.
- Decoder와 외곽선 추출만 다시 실행하므로 반복 선택 속도를 줄였습니다.
- Z Flip3 호환성을 우선해 기본 CPU Execution Provider를 사용합니다.
