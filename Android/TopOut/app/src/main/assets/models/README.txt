Bundled source assets loaded directly by TopOut:

mobile_sam_encoder.onnx
mobile_sam_decoder.onnx

Generated build asset:

movenet-lightning-fp16.tflite

The official MoveNet SinglePose Lightning FP16 model is generated under
build/generated/moveNetPoseAssets/models by the Gradle task
`downloadMoveNetPoseModel` before preBuild. The task validates the downloaded
file as a TFLite FlatBuffer by checking the TFL3 file identifier.

External weight sidecar files such as *.onnx.data are not expected.
