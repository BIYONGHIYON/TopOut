# MobileSAM Mat Boundary Detector

The iOS automatic mat-line detector mirrors the current Android rule.

1. Reuse one MobileSAM image embedding.
2. Decode masks from center prompts at y = 70%, 80%, 90%, and 95%.
3. Use the connected component containing the positive prompt to anchor a robust Theil-Sen upper-boundary line.
4. Add disconnected upper-edge fragments when they stay within two low-resolution mask pixels of that line. Each image column contributes at most once, so gaps and upward/downward outliers do not count as support.
5. Keep a candidate only when:
   - non-outlier upper-edge columns support the line across at least 60% of the visible image width;
   - its highest supporting pixel is at or below 50% of the visible image height;
   - the robust upper-boundary line tilts no more than 15 degrees.
6. Select the valid candidate whose upper edge is highest on screen.
7. Convert the fitted upper edge to the editable x = 6% and x = 94% mat-line points.
8. `HoldVC+UI.swift` uses the detected boundary at its original position without a downward correction.
9. If no candidate survives, use the 75% fallback line.

Ready waits for actual stream readiness and a new stable post-Ready frame before this pipeline runs. The analysis image is not displayed; mat correction stays on the live preview, and every top-hold tap captures a fresh frame and embedding.

No area score, multi-height consensus, side prompts, or cross-prompt candidate scoring is used.
