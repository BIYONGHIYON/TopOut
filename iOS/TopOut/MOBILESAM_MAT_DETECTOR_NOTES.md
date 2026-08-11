# MobileSAM Mat Boundary Detector

The iOS automatic mat-line detector mirrors the current Android v1.5 build 11 rule.

1. Reuse one MobileSAM image embedding.
2. Decode masks from center prompts at y = 70%, 80%, 90%, and 95%.
3. For each decoder mask, keep only the connected component containing the positive prompt.
4. Keep a component only when:
   - its horizontal span is at least 40% of the visible image width;
   - its highest pixel is at or below 50% of the visible image height;
   - the least-squares upper-boundary line tilts no more than 15 degrees.
5. Select the valid component whose upper edge is highest on screen.
6. Convert the fitted upper edge to the editable x = 6% and x = 94% mat-line points.
7. `HoldVC+UI.swift` uses the detected boundary at its original position without a downward correction.
8. If no candidate survives, use the 75% fallback line.

Ready waits for actual stream readiness and a new stable post-Ready frame before this pipeline runs. The analysis image is not displayed; mat correction stays on the live preview, and every top-hold tap captures a fresh frame and embedding.

No straightness/inlier ratio, area score, multi-height consensus, side prompts, or candidate scoring is used.
