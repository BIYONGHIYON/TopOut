These two bundled ONNX files are loaded by TopOut without renaming:

mobile_sam_encoder.onnx
mobile_sam_decoder.onnx

External weight sidecar files such as *.onnx.data are not expected. The Android app uses the ONNX files above; mobile_sam.pt is not required.


Pose detection:
- pose_landmarker_heavy.task is downloaded automatically by the Gradle pre-build task.
- Manual fallback from the project root: ./download_pose_model.command
- Runtime asset path: models/pose_landmarker_heavy.task
