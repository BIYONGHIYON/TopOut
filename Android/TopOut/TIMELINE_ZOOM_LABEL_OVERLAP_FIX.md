# Android selected-range zoom label overlap fix

## Problem

The `선택된 구간 확대` title and current magnification text were too close to the zoom slider, allowing the slider thumb/track to cover the text on some Android layouts.

## Change

- The label row and zoom slider now share one parent `Column`, so their positions are relative.
- The label row has its own 16dp layout band above the slider.
- The label row is moved 3dp upward relative to the slider, creating a stable visual gap.
- The entire compact zoom control keeps the previous 6dp upward placement near the main start/end slider.
- The visible zoom slider remains 22dp high and 72% of the available width.
- Timeline trimming, zoom math, playback scrubbing, detection, clip cleanup, padding, settings copy, and version 1.1 are unchanged.
