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
- Button and segmented-control labels use Compose auto-sizing so they remain readable without clipping on narrow and Fold-class layouts.
- Saved-video playback keeps Media3 controls separate from the bottom return action so the seek bar remains visible on short landscape screens.

## Verification

Run the current unit tests and debug build before device verification:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Recommended device checks: Galaxy S25/S25+/S25 Ultra, Z Flip3/Fold-class devices, portrait/landscape, 0.5x → 1.0x transition, and system font size/display zoom at default and maximum.
