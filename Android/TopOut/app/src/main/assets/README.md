Runtime models are loaded from the `models` directory.

Required exact paths:

- `models/mobile_sam_encoder.onnx`
- `models/mobile_sam_decoder.onnx`
- `models/pose_landmarker_heavy.task`

The MediaPipe pose bundle is downloaded by Gradle before assets are merged. Run
`./download_pose_model.command` from the project root when manual setup is needed.
