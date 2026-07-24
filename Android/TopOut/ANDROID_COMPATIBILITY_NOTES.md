# Android compatibility update

This project revision addresses the Google Play pre-launch compatibility findings for
Android 15 edge-to-edge layouts and 16 KB memory-page devices.

## Edge-to-edge

- `enableEdgeToEdge()` remains enabled in `MainActivity`.
- Deprecated `statusBarColor` and `navigationBarColor` theme parameters were removed.
- The navigation-bar contrast scrim is disabled on Android 10 and later.
- Permission and review controls now consume safe drawing/navigation-bar insets.
- Camera content intentionally remains edge-to-edge; its top and bottom controls already
  apply status/navigation bar padding.

## 16 KB native-library alignment

- Android Gradle Plugin remains newer than 8.5.1.
- Native libraries remain uncompressed (`jniLibs.useLegacyPackaging = false`) and
  `extractNativeLibs = false`, allowing App Bundles to request 16 KB ZIP alignment.
- The legacy `org.tensorflow:tensorflow-lite:2.17.0` dependency was replaced by
  `com.google.ai.edge.litert:litert-api:1.4.1` and `litert:1.4.1`.
- ONNX Runtime remains at 1.27.0 for MobileSAM.

After producing the release AAB, verify it before upload:

```bash
bundletool dump config --bundle=app-release.aab | grep alignment
```

The output should contain `PAGE_ALIGNMENT_16K`. Also inspect the release APK/AAB in
Android Studio's APK Analyzer or run Android's `check_elf_alignment.sh` against every
packaged `.so` file.

## Release identity

- Version name: `1.4`
- Version code: `6`
