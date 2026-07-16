Bundled source assets loaded directly by TopOut:

mobile_sam_encoder.onnx
mobile_sam_decoder.onnx

Generated build asset:

yolo11n-pose.onnx

The YOLO model is generated under build/generated/yoloPoseAssets/models by the
Gradle task downloadYoloPoseModel before preBuild. The task verifies SHA-256:
70bd721f9cb797eb44cbc70bc65213397a0a26da38fe6fd5ccdf699016d33d3c

External weight sidecar files such as *.onnx.data are not expected.
