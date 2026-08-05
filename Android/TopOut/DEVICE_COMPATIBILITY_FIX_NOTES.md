# Device compatibility fixes

## Galaxy S25/S26 and other Android devices

- Preserved CameraX's real logical-camera minimum zoom instead of forcing it to 1.0x.
- The public zoom range now includes `anchorRatio * nativeMinZoom`, allowing logical multi-camera devices to expose 0.5x.
- Added the effective minimum zoom to lens gesture anchors, while keeping physical-camera switching as fallback.
- Corrected the default rear-camera fallback so its display anchor remains 1.0x and its native minimum carries sub-1x support.

## UI sizing

- Capped application font scaling at 1.0 while retaining the device's physical display density.
- Reduced normal Tip, status, button, settings, permission, and review text by 1sp.
- Preserved large countdown/recording timer sizes and precision timeline labels.
- Existing one-line Tip/status/button constraints continue to use ellipsis only when the text still cannot fit.

## Verification

The source changes were inspected, but a full Gradle build could not complete in the sandbox because Maven Central/plugin dependency downloads failed with a TLS handshake error. Run the following in Android Studio or a normal network environment:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Recommended device checks: Galaxy S25/S25+/S25 Ultra and S26 Ultra, portrait/landscape, 0.5x → 1.0x transition, system font size/display zoom at default and maximum.
