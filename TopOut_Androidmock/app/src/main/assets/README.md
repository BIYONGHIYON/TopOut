Runtime models are loaded from the `models` directory.

Required exact paths:

- `models/mobile_sam_encoder.onnx`
- `models/mobile_sam_decoder.onnx`
- `models/efficientdet_lite2_float16.tflite`

The EfficientDet-Lite2 model is downloaded by Gradle before assets are merged. Run
`./download_person_detector_model.command` from the project root when manual setup is needed.
