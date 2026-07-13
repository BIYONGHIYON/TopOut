# Person detector model download fix

The previous mock project referenced an EfficientDet-Lite3 URL that is not published in the
MediaPipe pre-trained Object Detector model bucket. This build uses Google's officially
published EfficientDet-Lite2 float16 model instead.

- Asset: `app/src/main/assets/models/efficientdet_lite2_float16.tflite`
- Official URL: `https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/float16/latest/efficientdet_lite2.tflite`
- Input size: 448 × 448, resized internally by MediaPipe
- Detection logic uses 2 fps person detection, 1-second initial search, and 5-second lost-person timeout.

If Android Studio cannot download it automatically, run:

```bash
./download_person_detector_model.command
```
