These two bundled ONNX files are loaded by TopOut without renaming:

mobile_sam_encoder.onnx
mobile_sam_decoder.onnx

External weight sidecar files such as *.onnx.data are not expected. The Android app uses the ONNX files above; mobile_sam.pt is not required.
