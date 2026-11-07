import UIKit
import AVFoundation
import AVKit
import Photos

extension ReviewVC {
    // MARK: - 구간 편집과 저장 상태

    func setStatus(_ text: String) {
        statusLabel.text = text
        statusLabel.isHidden = false
    }

    func setVerifiedSavedAsset(
        localIdentifier: String,
        startSeconds: TimeInterval,
        endSeconds: TimeInterval,
        includeTopHoldOverlay: Bool
    ) {
        let selection = VerifiedSavedSelection(
            startSeconds: startSeconds,
            endSeconds: endSeconds,
            includeTopHoldOverlay: includeTopHoldOverlay
        )
        verifiedSavedAssets[selection] = localIdentifier
        updateSaveButtonMode()
    }

    var currentVerifiedSavedAssetIdentifier: String? {
        verifiedSavedAssets.first { selection, _ in
            abs(selection.startSeconds - selectedStart) < 0.01 &&
                abs(selection.endSeconds - selectedEnd) < 0.01 &&
                selection.includeTopHoldOverlay == includeTopHoldOverlay
        }?.value
    }

    private var verifiedSavedSelectionMatchesCurrent: Bool {
        currentVerifiedSavedAssetIdentifier != nil
    }

    func updateSaveButtonMode() {
        let matchesSavedSelection = verifiedSavedSelectionMatchesCurrent
        var config = saveBtn.configuration ?? UIButton.Configuration.filled()
        config.title = matchesSavedSelection ? L("review.view_saved") : L("review.save_clip")
        saveBtn.configuration = config
        saveBtn.accessibilityLabel = matchesSavedSelection ? L("review.view_saved") : L("review.save_clip")
        if !matchesSavedSelection {
            updateDetectedRangeNavigationUI()
        }
        normalizeReviewButtonContentLayout()
    }

    func setSaving(_ saving: Bool) {
        saveBtn.isEnabled = !saving
        if saving {
            var config = saveBtn.configuration ?? UIButton.Configuration.filled()
            config.title = L("review.saving")
            saveBtn.configuration = config
            saveBtn.accessibilityLabel = L("review.saving")
        } else {
            updateSaveButtonMode()
        }
        playPauseBtn.isEnabled = !saving
        seekBackwardBtn.isEnabled = !saving
        seekForwardBtn.isEnabled = !saving
        overlayToggleBtn.isEnabled = !saving && topHoldSelectionEnabled && !isSelectingTopHold
        trimSlider.isEnabled = !saving
        selectedRangeZoomSlider.isEnabled = !saving && trimSlider.maximumAllowedSelectedRangeZoomFactor > 1.01
        selectedRangeZoomLabel.alpha = saving ? 0.45 : 1.0
        selectedRangeZoomValueLabel.alpha = saving ? 0.45 : 1.0
        closeBtn.isEnabled = !saving
        if !saving {
            updateDetectedRangeNavigationUI()
        } else {
            previousRangeBtn.isEnabled = false
            nextRangeBtn.isEnabled = false
        }
    }

    func refreshSelectedRangeZoomControl(recenter: Bool) {
        let currentZoom = trimSlider.currentSelectedRangeZoomFactor
        let selectionMaximumZoom = trimSlider.maximumAllowedSelectedRangeZoomFactor
        let sliderMaximumZoom = Swift.max(currentZoom, selectionMaximumZoom)
        selectedRangeZoomSlider.minimumValue = 1
        selectedRangeZoomSlider.maximumValue = Float(Swift.max(1.0001, sliderMaximumZoom))

        let appliedZoom: Double
        if recenter {
            appliedZoom = trimSlider.recenterSelectedRangePreservingCurrentZoom()
        } else {
            appliedZoom = currentZoom
        }

        selectedRangeZoomSlider.value = Float(appliedZoom)
        selectedRangeZoomSlider.isEnabled = trimSlider.isEnabled && sliderMaximumZoom > 1.01
        selectedRangeZoomValueLabel.text = String(
            format: "%.1f×",
            locale: AppLanguageManager.locale,
            appliedZoom
        )
        selectedRangeZoomSlider.accessibilityValue = selectedRangeZoomValueLabel.text
        if recenter { positionSliderTimeBubbleIfVisible() }
    }

    @objc func onSelectedRangeZoomTouchDown() {
        pausePlayback()
    }

    @objc func onSelectedRangeZoomChanged() {
        pausePlayback()
        let appliedZoom = trimSlider.setSelectedRangeZoomFactor(Double(selectedRangeZoomSlider.value))
        selectedRangeZoomSlider.value = Float(appliedZoom)
        selectedRangeZoomValueLabel.text = String(
            format: "%.1f×",
            locale: AppLanguageManager.locale,
            appliedZoom
        )
        selectedRangeZoomSlider.accessibilityValue = selectedRangeZoomValueLabel.text
        positionSliderTimeBubbleIfVisible()
    }

    @objc func onSelectedRangeZoomTouchEnded() {
        refreshSelectedRangeZoomControl(recenter: true)
    }

    func handleRangeChanged(lower: TimeInterval, upper: TimeInterval, activeHandle: TrimRangeSlider.ActiveHandle) {
        selectedStart = lower
        selectedEnd = upper
        updateSaveButtonMode()

        switch activeHandle {
        case .lower:
            playhead = selectedStart
        case .upper:
            playhead = selectedEnd
        }

        trimSlider.playheadValue = playhead
        refreshSelectedRangeZoomControl(recenter: false)
        showSliderTimeBubble(for: playhead)
        pausePlayback()
        seek(to: playhead)
    }

    func handlePlayheadChanged(_ value: TimeInterval) {
        playhead = clamped(value, min: selectedStart, max: selectedEnd)
        trimSlider.playheadValue = playhead
        showSliderTimeBubble(for: playhead)
        pausePlayback()
        seek(to: playhead)
    }

    func resumePlaybackAfterTrimEdit(activeHandle: TrimRangeSlider.ActiveHandle) {
        pausePlayback()
        switch activeHandle {
        case .lower:
            playhead = selectedStart
        case .upper:
            playhead = Swift.max(
                selectedStart,
                selectedEnd - Cfg.reviewEndPreviewLeadSeconds
            )
        }
        trimSlider.playheadValue = playhead
        trimSlider.ensureValueVisible(playhead)
        showSliderTimeBubble(for: playhead)
        playFromCurrentPlayhead()
    }

    func showSliderTimeBubble(for seconds: TimeInterval) {
        let visibleSeconds = clamped(seconds, min: 0, max: duration)
        rangeLabel.text = formatSecondsBubble(visibleSeconds)
        rangeLabel.isHidden = false
        rangeLabel.alpha = 1
        positionSliderTimeBubble(for: visibleSeconds)
        scheduleHideSliderTimeBubble(after: 0.95)
    }

    func positionSliderTimeBubbleIfVisible() {
        guard !rangeLabel.isHidden else { return }
        positionSliderTimeBubble(for: playhead)
    }

    private func positionSliderTimeBubble(for seconds: TimeInterval) {
        guard trimSlider.bounds.width > 0 else { return }

        let bubbleHeight: CGFloat = 28
        let maximumBubbleWidth: CGFloat = 92
        let fittingSize = rangeLabel.sizeThatFits(CGSize(width: maximumBubbleWidth, height: bubbleHeight))
        let bubbleWidth = Swift.max(CGFloat(56), Swift.min(maximumBubbleWidth, fittingSize.width + 18))
        let anchorX = trimSlider.frame.minX + trimSlider.xPositionForValue(seconds)
        let minX = view.safeAreaInsets.left + 8
        let maxX = view.bounds.width - view.safeAreaInsets.right - 8 - bubbleWidth
        let bubbleX = Swift.max(minX, Swift.min(anchorX - bubbleWidth / 2, maxX))
        let preferredBubbleY = detectedRangeLabel.isHidden
            ? trimSlider.frame.minY - bubbleHeight - 6
            : detectedRangeLabel.frame.minY - bubbleHeight - 4
        let bubbleY = Swift.max(view.safeAreaInsets.top + 4, preferredBubbleY)

        rangeLabel.frame = CGRect(x: bubbleX, y: bubbleY, width: bubbleWidth, height: bubbleHeight)
    }

    func scheduleHideSliderTimeBubble(after delay: TimeInterval) {
        sliderTimeBubbleHideWorkItem?.cancel()
        sliderTimeBubbleHideGeneration &+= 1
        let generation = sliderTimeBubbleHideGeneration
        let workItem = DispatchWorkItem { [weak self] in
            guard let self, self.sliderTimeBubbleHideGeneration == generation else { return }
            UIView.animate(withDuration: 0.18, animations: {
                self.rangeLabel.alpha = 0
            }, completion: { _ in
                guard self.sliderTimeBubbleHideGeneration == generation else { return }
                self.rangeLabel.isHidden = true
                self.sliderTimeBubbleHideWorkItem = nil
            })
        }
        sliderTimeBubbleHideWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: workItem)
    }

    @objc func onTapPreviousRange() {
        guard let current = selectedDetectedRangeIndex, current > 0 else { return }
        applyDetectedRange(at: current - 1)
    }

    @objc func onTapNextRange() {
        guard let current = selectedDetectedRangeIndex, current < detectedRanges.count - 1 else { return }
        applyDetectedRange(at: current + 1)
    }

    private func applyDetectedRange(at index: Int) {
        guard detectedRanges.indices.contains(index) else { return }
        pausePlayback()
        selectedDetectedRangeIndex = index
        let range = detectedRanges[index]
        selectedStart = clamped(range.startSeconds, min: 0, max: duration)
        selectedEnd = clamped(range.endSeconds, min: selectedStart + 0.1, max: duration)
        updateSaveButtonMode()
        playhead = selectedStart
        trimSlider.lowerValue = selectedStart
        trimSlider.upperValue = selectedEnd
        trimSlider.playheadValue = playhead
        refreshSelectedRangeZoomControl(recenter: true)
        updateDetectedRangeNavigationUI()
        showSliderTimeBubble(for: playhead)
        playFromCurrentPlayhead()
    }

    func updateDetectedRangeNavigationUI() {
        if helpEnabled {
            statusLabel.text = autoClipDetectionEnabled && !detectedRanges.isEmpty
                ? L("review.auto_prompt")
                : L("review.manual_prompt")
        }

        if let index = selectedDetectedRangeIndex, detectedRanges.indices.contains(index) {
            detectedRangeLabel.text = L("review.detected_range", index + 1, detectedRanges.count)
        } else {
            detectedRangeLabel.text = nil
        }

        let hasRanges = !detectedRanges.isEmpty
        let shouldShowRangeNavigation = detectedRanges.count > 1
        detectedRangeLabel.isHidden = !hasRanges
        previousRangeBtn.isHidden = !shouldShowRangeNavigation
        nextRangeBtn.isHidden = !shouldShowRangeNavigation
        statusLabel.isHidden = !helpEnabled

        if let index = selectedDetectedRangeIndex, shouldShowRangeNavigation {
            previousRangeBtn.isEnabled = index > 0
            nextRangeBtn.isEnabled = index < detectedRanges.count - 1
            previousRangeBtn.alpha = previousRangeBtn.isEnabled ? 1.0 : 0.45
            nextRangeBtn.alpha = nextRangeBtn.isEnabled ? 1.0 : 0.45
        }
    }

    func updateOverlayToggleButton() {
        let hasOverlay = topHoldOverlayImage != nil
        // 탑홀드 마스킹을 쓰지 않은 녹화는 버튼을 숨기고 닫기가 2행 전체를 쓴다.
        // 아직 마스크를 고르지 않았으면 누를 때 탑홀드를 탭하라고 안내한다.
        overlayToggleBtn.isHidden = !topHoldSelectionEnabled
        overlayToggleBtn.isEnabled = topHoldSelectionEnabled && !isSelectingTopHold
        topHoldOverlayView.isHidden = !(hasOverlay && includeTopHoldOverlay)

        var config = includeTopHoldOverlay
            ? UIButton.Configuration.filled()
            : UIButton.Configuration.gray()
        var title = AttributedString(L("review.top_hold_mask"))
        title.font = .systemFont(ofSize: 13, weight: .semibold)
        config.title = nil
        config.attributedTitle = title
        config.cornerStyle = .capsule
        if hasOverlay {
            config.image = UIImage(systemName: includeTopHoldOverlay ? "checkmark.square.fill" : "square")
        } else {
            config.image = UIImage(systemName: "hand.tap")
        }
        config.imagePlacement = .leading
        config.imagePadding = 3
        config.titleAlignment = .center
        config.titleLineBreakMode = .byWordWrapping
        let isLandscape = view.bounds.width > view.bounds.height
        config.contentInsets = NSDirectionalEdgeInsets(
            top: isLandscape ? 4 : 5,
            leading: 6,
            bottom: isLandscape ? 4 : 5,
            trailing: 6
        )
        if includeTopHoldOverlay {
            config.baseForegroundColor = .white
        }
        overlayToggleBtn.configuration = config
        overlayToggleBtn.titleLabel?.font = .systemFont(ofSize: 13, weight: .semibold)
        normalizeReviewButtonContentLayout()
        if !hasOverlay {
            overlayToggleBtn.accessibilityLabel = L("review.top_hold_tap_tip")
        } else {
            overlayToggleBtn.accessibilityLabel = includeTopHoldOverlay
                ? L("review.top_hold_mask_include")
                : L("review.top_hold_mask_exclude")
        }
    }

    private func formatSecondsBubble(_ seconds: TimeInterval) -> String {
        L(
            "time.seconds_short",
            String(
                format: "%.1f",
                locale: AppLanguageManager.locale,
                Swift.max(0, seconds)
            )
        )
    }

    func clamped(_ value: TimeInterval, min lower: TimeInterval, max upper: TimeInterval) -> TimeInterval {
        Swift.max(lower, Swift.min(value, upper))
    }
}
