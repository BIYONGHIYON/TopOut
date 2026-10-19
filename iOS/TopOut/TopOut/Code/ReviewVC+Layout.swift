import UIKit
import AVFoundation
import AVKit
import Photos

extension ReviewVC {
    // MARK: - 레이아웃

    func portraitReviewPanelBottom(screenHeight: CGFloat, safeBottom: CGFloat, activeFrame: CGRect) -> CGFloat {
        let secondRowButtonHeight: CGFloat = 42
        let blackAreaGap: CGFloat = 10
        let minimumBottomForBlackRow = activeFrame.maxY + blackAreaGap + secondRowButtonHeight

        // 기존보다 컨트롤 묶음을 아래로 내려서 9:16 영상 하단 검은 영역에 버튼 2줄이 안정적으로 들어오게 합니다.
        let loweredBottomMargin = max(CGFloat(16), safeBottom - 12)
        let loweredBottom = screenHeight - loweredBottomMargin
        let lowestSafeBottom = screenHeight - max(CGFloat(12), min(safeBottom * 0.35, CGFloat(18)))

        return min(lowestSafeBottom, max(loweredBottom, minimumBottomForBlackRow))
    }

    func layoutPortraitReviewControls(panelX: CGFloat, panelWidth: CGFloat, panelBottom: CGFloat) {
        let buttonHeight: CGFloat = 42
        let buttonGap: CGFloat = 8
        let playWidth: CGFloat = 58
        rangeLabel.font = .monospacedDigitSystemFont(ofSize: 13, weight: .semibold)
        applyReviewButtonInsets(isLandscape: false)

        let secondRowY = panelBottom - buttonHeight
        let halfWidth = (panelWidth - buttonGap) * 0.5
        retakeBtn.frame = CGRect(
            x: panelX,
            y: secondRowY,
            width: halfWidth,
            height: buttonHeight
        )
        closeBtn.frame = CGRect(
            x: retakeBtn.frame.maxX + buttonGap,
            y: secondRowY,
            width: halfWidth,
            height: buttonHeight
        )

        let firstRowY = secondRowY - buttonGap - buttonHeight
        let textButtonWidth = Swift.max(96, (panelWidth - playWidth - buttonGap * 2) * 0.5)
        playPauseBtn.frame = CGRect(
            x: panelX,
            y: firstRowY,
            width: playWidth,
            height: buttonHeight
        )
        overlayToggleBtn.frame = CGRect(
            x: playPauseBtn.frame.maxX + buttonGap,
            y: firstRowY,
            width: textButtonWidth,
            height: buttonHeight
        )
        saveBtn.frame = CGRect(
            x: overlayToggleBtn.frame.maxX + buttonGap,
            y: firstRowY,
            width: panelX + panelWidth - overlayToggleBtn.frame.maxX - buttonGap,
            height: buttonHeight
        )

        let zoomSliderHeight: CGFloat = 18
        let zoomLabelHeight: CGFloat = 11
        let zoomSliderWidth = panelWidth * 0.72
        let zoomSliderX = panelX + (panelWidth - zoomSliderWidth) * 0.5
        let zoomSliderY = firstRowY - buttonGap - zoomSliderHeight - 3
        selectedRangeZoomSlider.frame = CGRect(x: zoomSliderX, y: zoomSliderY, width: zoomSliderWidth, height: zoomSliderHeight)
        selectedRangeZoomLabel.frame = CGRect(x: panelX, y: zoomSliderY - zoomLabelHeight, width: panelWidth * 0.72, height: zoomLabelHeight)
        selectedRangeZoomValueLabel.frame = CGRect(x: panelX + panelWidth * 0.72, y: zoomSliderY - zoomLabelHeight, width: panelWidth * 0.28, height: zoomLabelHeight)

        let trimToZoomGap: CGFloat = 0
        let primarySliderHeight: CGFloat = 60
        trimSlider.frame = CGRect(
            x: panelX,
            y: selectedRangeZoomLabel.frame.minY - trimToZoomGap - primarySliderHeight,
            width: panelWidth,
            height: primarySliderHeight
        )
        layoutDetectedRangeNavigation(above: trimSlider.frame, panelX: panelX, panelWidth: panelWidth, isLandscape: false)
        positionSliderTimeBubbleIfVisible()
        normalizeReviewButtonContentLayout()
    }

    func layoutLandscapeReviewControls(panelX: CGFloat, panelWidth: CGFloat, panelBottom: CGFloat) {
        let buttonHeight: CGFloat = 38
        let gap: CGFloat = 8
        applyReviewButtonInsets(isLandscape: true)

        let buttonPanelWidth = min(panelWidth, CGFloat(760))
        let buttonPanelX = panelX + (panelWidth - buttonPanelWidth) * 0.5
        let availableWidth = Swift.max(0, buttonPanelWidth - gap * 4)
        let playWidth = min(CGFloat(58), max(CGFloat(50), availableWidth * 0.10))
        let retakeWidth = min(CGFloat(110), max(CGFloat(84), availableWidth * 0.17))
        let closeWidth = min(CGFloat(90), max(CGFloat(70), availableWidth * 0.14))
        let remainingWidth = Swift.max(0, availableWidth - playWidth - retakeWidth - closeWidth)
        let overlayWidth = min(CGFloat(170), max(CGFloat(126), remainingWidth * 0.46))
        let saveWidth = Swift.max(CGFloat(130), remainingWidth - overlayWidth)
        let buttonY = panelBottom - buttonHeight

        playPauseBtn.frame = CGRect(x: buttonPanelX, y: buttonY, width: playWidth, height: buttonHeight)
        overlayToggleBtn.frame = CGRect(x: playPauseBtn.frame.maxX + gap, y: buttonY, width: overlayWidth, height: buttonHeight)
        saveBtn.frame = CGRect(x: overlayToggleBtn.frame.maxX + gap, y: buttonY, width: saveWidth, height: buttonHeight)
        retakeBtn.frame = CGRect(x: saveBtn.frame.maxX + gap, y: buttonY, width: retakeWidth, height: buttonHeight)
        closeBtn.frame = CGRect(x: retakeBtn.frame.maxX + gap, y: buttonY, width: closeWidth, height: buttonHeight)

        let zoomSliderHeight: CGFloat = 16
        let zoomLabelHeight: CGFloat = 10
        let zoomSliderWidth = panelWidth * 0.68
        let zoomSliderX = panelX + (panelWidth - zoomSliderWidth) * 0.5
        let zoomSliderY = buttonY - gap - zoomSliderHeight - 3
        selectedRangeZoomSlider.frame = CGRect(x: zoomSliderX, y: zoomSliderY, width: zoomSliderWidth, height: zoomSliderHeight)
        selectedRangeZoomLabel.frame = CGRect(x: panelX, y: zoomSliderY - zoomLabelHeight, width: panelWidth * 0.72, height: zoomLabelHeight)
        selectedRangeZoomValueLabel.frame = CGRect(x: panelX + panelWidth * 0.72, y: zoomSliderY - zoomLabelHeight, width: panelWidth * 0.28, height: zoomLabelHeight)

        let trimToZoomGap: CGFloat = 0
        let primarySliderHeight: CGFloat = 58
        trimSlider.frame = CGRect(
            x: panelX,
            y: selectedRangeZoomLabel.frame.minY - trimToZoomGap - primarySliderHeight,
            width: panelWidth,
            height: primarySliderHeight
        )
        rangeLabel.font = .monospacedDigitSystemFont(ofSize: 13, weight: .semibold)
        layoutDetectedRangeNavigation(above: trimSlider.frame, panelX: panelX, panelWidth: panelWidth, isLandscape: true)
        positionSliderTimeBubbleIfVisible()
        normalizeReviewButtonContentLayout()
    }

    private func layoutDetectedRangeNavigation(above sliderFrame: CGRect, panelX: CGFloat, panelWidth: CGFloat, isLandscape: Bool) {
        let hasRanges = !detectedRanges.isEmpty
        let hasMultipleRanges = detectedRanges.count > 1
        let rowHeight: CGFloat = isLandscape ? 26 : 28
        let rowGap: CGFloat = isLandscape ? 4 : 5
        let buttonSize = rowHeight
        let labelAvailableWidth = panelWidth - (hasMultipleRanges ? (buttonSize + 8) * 2 : 0)
        let labelWidth = min(Swift.max(CGFloat(96), labelAvailableWidth), CGFloat(isLandscape ? 190 : 170))
        let totalWidth = hasMultipleRanges ? labelWidth + (buttonSize + 8) * 2 : labelWidth
        let rowX = panelX + (panelWidth - totalWidth) * 0.5
        let rowY = max(view.safeAreaInsets.top + 2, sliderFrame.minY - rowGap - rowHeight)

        detectedRangeLabel.frame = CGRect(
            x: hasMultipleRanges ? rowX + buttonSize + 8 : rowX,
            y: rowY,
            width: labelWidth,
            height: rowHeight
        )
        detectedRangeLabel.isHidden = !hasRanges

        if hasMultipleRanges {
            previousRangeBtn.frame = CGRect(x: rowX, y: rowY, width: buttonSize, height: buttonSize)
            nextRangeBtn.frame = CGRect(x: detectedRangeLabel.frame.maxX + 8, y: rowY, width: buttonSize, height: buttonSize)
        } else {
            previousRangeBtn.frame = .zero
            nextRangeBtn.frame = .zero
        }
    }

    func applyReviewButtonInsets(isLandscape: Bool) {
        let verticalInset: CGFloat = isLandscape ? 4 : 5
        let horizontalInset: CGFloat = isLandscape ? 7 : 9

        for button in [playPauseBtn, overlayToggleBtn, saveBtn, retakeBtn, closeBtn] {
            var config = button.configuration
            config?.cornerStyle = .capsule
            if button === overlayToggleBtn {
                config?.titleAlignment = .center
                config?.titleLineBreakMode = .byWordWrapping
                config?.imagePlacement = .leading
                config?.imagePadding = 3
                config?.contentInsets = NSDirectionalEdgeInsets(
                    top: verticalInset,
                    leading: 6,
                    bottom: verticalInset,
                    trailing: 6
                )
            } else {
                config?.titleAlignment = .center
                config?.titleLineBreakMode = .byWordWrapping
                config?.contentInsets = NSDirectionalEdgeInsets(
                    top: verticalInset,
                    leading: horizontalInset,
                    bottom: verticalInset,
                    trailing: horizontalInset
                )
            }
            button.configuration = config
        }
        normalizeReviewButtonContentLayout()
    }

    func normalizeReviewButtonContentLayout() {
        for button in [playPauseBtn, overlayToggleBtn, saveBtn, retakeBtn, closeBtn] {
            button.contentHorizontalAlignment = .center
            button.contentVerticalAlignment = .center
            button.titleLabel?.textAlignment = .center
            button.titleLabel?.numberOfLines = 2
            button.titleLabel?.lineBreakMode = .byWordWrapping
            button.titleLabel?.adjustsFontSizeToFitWidth = true
            button.titleLabel?.minimumScaleFactor = button === saveBtn ? 0.64 : 0.68
            button.imageView?.contentMode = .scaleAspectFit
            button.setNeedsLayout()
            button.layoutIfNeeded()
        }
    }
}
