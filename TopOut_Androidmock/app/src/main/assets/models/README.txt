Bundled ONNX files:
- mobile_sam_encoder.onnx
- mobile_sam_decoder.onnx

Person detection model:
- efficientdet_lite2_float16.tflite is downloaded by the Gradle pre-build task.
- Manual fallback: ./download_person_detector_model.command
- Runtime asset path: models/efficientdet_lite2_float16.tflite
- Source: Google MediaPipe EfficientDet-Lite2 float16 model.
