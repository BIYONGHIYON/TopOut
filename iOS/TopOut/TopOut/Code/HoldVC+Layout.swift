import UIKit
import AVFoundation

extension HoldVC {
    // MARK: - 화면 구성
    func setupUI() {
        view.addSubview(camView)
        view.addSubview(recordingMatLineView)
        view.addSubview(powerSavingOverlayView)
        powerSavingOverlayView.addSubview(powerSavingCountdownLabel)
        powerSavingOverlayView.addSubview(powerSavingRecordButton)
        powerSavingOverlayView.addSubview(powerSavingElapsedLabel)
        view.addSubview(camHint)
        view.addSubview(previewTipLabel)
        view.addSubview(manualStopBtn)
        view.addSubview(livePreviewToggleButton)
        view.addSubview(settingsBtn)
        view.addSubview(zoomLabel)

        view.addSubview(selectView)
        selectView.addSubview(scrollView)
        scrollView.addSubview(imgView)
        imgView.addSubview(matLineView)
        selectView.addSubview(selectHint)
        selectView.addSubview(selectTipLabel)
        selectView.addSubview(readyBtn)
        selectView.addSubview(retakeBtn)
        selectView.addSubview(recordBtn)

        view.addSubview(spinner)
        view.addSubview(launchImageView)

        scrollView.delegate = self
        // 선택 화면은 고정된 카메라 화각 위에 표시하므로 스크롤과 확대를 막는다.
        scrollView.isScrollEnabled = false
        scrollView.pinchGestureRecognizer?.isEnabled = false
        matLineView.onChange = { [weak self] points in
            self?.matLinePoints = points
        }

        manualStopBtn.addTarget(self, action: #selector(onManualStop), for: .touchUpInside)
        powerSavingRecordButton.addTarget(self, action: #selector(onManualStop), for: .touchUpInside)
        livePreviewToggleButton.addTarget(self, action: #selector(onToggleLivePreviewDuringRecording), for: .touchUpInside)
        settingsBtn.addTarget(self, action: #selector(onSettings), for: .touchUpInside)
        readyBtn.addTarget(self, action: #selector(onReady), for: .touchUpInside)
        retakeBtn.addTarget(self, action: #selector(onRetake), for: .touchUpInside)
        recordBtn.addTarget(self, action: #selector(onRecordStart), for: .touchUpInside)
        applyRecordStartButtonStyle()

        let cameraPinch = UIPinchGestureRecognizer(target: self, action: #selector(onCameraPinch(_:)))
        camView.addGestureRecognizer(cameraPinch)
        // 녹화 전 화면은 안내 레이어가 프리뷰 위에 있으므로 같은 줌 제스처를 연결한다.
        // 준비 완료 뒤 보정 화면에서는 측정한 화각을 지키려고 줌을 막는다.
        let selectionPinch = UIPinchGestureRecognizer(target: self, action: #selector(onCameraPinch(_:)))
        imgView.addGestureRecognizer(selectionPinch)

        loadUserSettings()
        showCameraUI()
    }

    func layoutUI() {
        let bounds = view.bounds
        let safe = view.safeAreaInsets
        let isLandscape = isLandscapeLayout
        let activeFrame = selectedRecordingAspectRatio.displayRect(in: bounds, isLandscape: isLandscape)

        let topMessageHeight: CGFloat = isLandscape ? 30 : 32
        let topMessageOffsetBelowBaseline: CGFloat = 4
        let topMessageGap: CGFloat = 6 - topMessageOffsetBelowBaseline
        let topMessageY = alignedTopMessageY(
            contentTopY: activeFrame.minY,
            safeTopY: safe.top,
            messageHeight: topMessageHeight,
            gap: topMessageGap,
            offsetBelowBaseline: topMessageOffsetBelowBaseline
        )

        camView.frame = activeFrame
        previewLayer?.frame = camView.bounds
        recordingMatLineView.frame = activeFrame
        launchImageView.frame = bounds
        powerSavingOverlayView.frame = bounds
        layoutPowerSavingUI(in: bounds)

        selectView.frame = bounds
        scrollView.frame = activeFrame
        spinner.center = CGPoint(x: bounds.midX, y: bounds.midY)

        let messageWidthLimit: CGFloat = isLandscape ? 560 : bounds.width - 40
        let messageWidth = min(bounds.width - 40, messageWidthLimit)
        let messageX = bounds.midX - messageWidth * 0.5
        camHint.frame = CGRect(x: messageX, y: topMessageY, width: messageWidth, height: topMessageHeight)
        selectHint.frame = CGRect(x: messageX, y: topMessageY, width: messageWidth, height: topMessageHeight)

        if isLandscape {
            layoutLandscapeControls(in: bounds, safe: safe)
        } else {
            layoutPortraitControls(in: bounds, safe: safe)
        }
        updatePreviewTipVisibility()

        if imgView.bounds.size != scrollView.bounds.size {
            imgView.frame = scrollView.bounds
            matLineView.frame = imgView.bounds
            scrollView.contentSize = imgView.bounds.size
            resetZoom()
        } else {
            matLineView.frame = imgView.bounds
        }

        view.bringSubviewToFront(recordingMatLineView)
        view.bringSubviewToFront(powerSavingOverlayView)
        view.bringSubviewToFront(camHint)
        view.bringSubviewToFront(previewTipLabel)
        view.bringSubviewToFront(settingsBtn)
        view.bringSubviewToFront(zoomLabel)
        view.bringSubviewToFront(manualStopBtn)
        view.bringSubviewToFront(livePreviewToggleButton)
        view.bringSubviewToFront(selectView)
        // 선택 레이어가 화면 전체를 덮으므로 설정과 줌 표시는 그 위에 둔다.
        view.bringSubviewToFront(settingsBtn)
        view.bringSubviewToFront(zoomLabel)
        view.bringSubviewToFront(spinner)
        if !launchImageView.isHidden { view.bringSubviewToFront(launchImageView) }
    }

    private func layoutPortraitControls(in bounds: CGRect, safe: UIEdgeInsets) {
        let buttonHeight: CGFloat = 54
        let bottomPadding: CGFloat = 12
        let controlY = bounds.height - safe.bottom - buttonHeight - bottomPadding

        let tipFrame = CGRect(x: 32, y: controlY - 44, width: bounds.width - 64, height: 34)
        previewTipLabel.frame = tipFrame
        selectTipLabel.frame = tipFrame
        let centerButtonFrame = CGRect(x: bounds.midX - 90, y: controlY, width: 180, height: buttonHeight)
        readyBtn.frame = centerButtonFrame
        if mode == .select {
            // 보정 화면: 다시 촬영 · 녹화 시작
            let buttonWidth = min((bounds.width - 58) * 0.5, CGFloat(168))
            let spacing: CGFloat = 12
            let startX = bounds.midX - (buttonWidth * 2 + spacing) * 0.5
            retakeBtn.frame = CGRect(x: startX, y: controlY, width: buttonWidth, height: buttonHeight)
            recordBtn.frame = CGRect(x: startX + buttonWidth + spacing, y: controlY, width: buttonWidth, height: buttonHeight)
        } else {
            recordBtn.frame = centerButtonFrame
        }

        let recordingButtonHeight: CGFloat = 54
        let recordingButtonWidth = min((bounds.width - 58) * 0.5, CGFloat(168))
        let recordingButtonSpacing: CGFloat = 12
        if recState == .buffering && isLivePreviewVisibleDuringRecording {
            let totalWidth = recordingButtonWidth * 2 + recordingButtonSpacing
            let startX = bounds.midX - totalWidth * 0.5
            livePreviewToggleButton.frame = CGRect(
                x: startX,
                y: controlY,
                width: recordingButtonWidth,
                height: recordingButtonHeight
            )
            manualStopBtn.frame = CGRect(
                x: startX + recordingButtonWidth + recordingButtonSpacing,
                y: controlY,
                width: recordingButtonWidth,
                height: recordingButtonHeight
            )
        } else {
            manualStopBtn.frame = CGRect(x: bounds.midX - 105, y: controlY, width: 210, height: buttonHeight)
        }
        settingsBtn.frame = CGRect(x: bounds.width - safe.right - 70, y: controlY + 5, width: 44, height: 44)
        zoomLabel.frame = CGRect(x: safe.left + 22, y: controlY + 13, width: 64, height: 28)
    }

    private func layoutLandscapeControls(in bounds: CGRect, safe: UIEdgeInsets) {
        let buttonHeight: CGFloat = 48
        let controlY = bounds.height - safe.bottom - buttonHeight - 12
        let centerX = bounds.midX

        let centerButtonFrame = CGRect(x: centerX - 84, y: controlY, width: 168, height: buttonHeight)
        readyBtn.frame = centerButtonFrame
        if mode == .select {
            let buttonWidth: CGFloat = 176
            let spacing: CGFloat = 12
            let startX = centerX - (buttonWidth * 2 + spacing) * 0.5
            retakeBtn.frame = CGRect(x: startX, y: controlY, width: buttonWidth, height: buttonHeight)
            recordBtn.frame = CGRect(x: startX + buttonWidth + spacing, y: controlY, width: buttonWidth, height: buttonHeight)
        } else {
            recordBtn.frame = centerButtonFrame
        }
        if recState == .buffering && isLivePreviewVisibleDuringRecording {
            let buttonWidth: CGFloat = 176
            let spacing: CGFloat = 12
            let totalWidth = buttonWidth * 2 + spacing
            let startX = centerX - totalWidth * 0.5
            livePreviewToggleButton.frame = CGRect(x: startX, y: controlY, width: buttonWidth, height: buttonHeight)
            manualStopBtn.frame = CGRect(x: startX + buttonWidth + spacing, y: controlY, width: buttonWidth, height: buttonHeight)
        } else {
            manualStopBtn.frame = CGRect(x: centerX - 98, y: controlY, width: 196, height: buttonHeight)
        }
        zoomLabel.frame = CGRect(x: centerButtonFrame.minX - 78, y: controlY + 10, width: 64, height: 28)
        settingsBtn.frame = CGRect(x: centerButtonFrame.maxX + 16, y: controlY + 2, width: 44, height: 44)

        let tipWidth = min(bounds.width - safe.left - safe.right - 40, CGFloat(380))
        let landscapeTipFrame = CGRect(
            x: centerX - tipWidth * 0.5,
            y: max(safe.top + 38, controlY - 42),
            width: tipWidth,
            height: 32
        )
        previewTipLabel.frame = landscapeTipFrame
        selectTipLabel.frame = landscapeTipFrame
    }

    func layoutPowerSavingUI(in bounds: CGRect) {
        let isLandscape = bounds.width > bounds.height
        let countdownSize = isLandscape
            ? min(bounds.height * 0.72, bounds.width * 0.32)
            : min(bounds.width * 0.96, bounds.height * 0.62)
        powerSavingCountdownLabel.frame = CGRect(
            x: bounds.midX - countdownSize * 0.5,
            y: bounds.midY - countdownSize * 0.5,
            width: countdownSize,
            height: countdownSize
        )
        powerSavingCountdownLabel.font = .monospacedDigitSystemFont(ofSize: countdownSize * 0.88, weight: .black)

        let recordIconSize = isLandscape
            ? min(bounds.height * 0.44, bounds.width * 0.22)
            : min(bounds.width * 0.42, bounds.height * 0.34)
        powerSavingRecordButton.frame = CGRect(
            x: bounds.midX - recordIconSize * 0.5,
            y: bounds.midY - recordIconSize * 0.5,
            width: recordIconSize,
            height: recordIconSize
        )

        let elapsedHeight: CGFloat = isLandscape ? 64 : 86
        let recordGroupSpacing: CGFloat = isLandscape ? 16 : 28
        let elapsedWidthLimit: CGFloat = isLandscape ? 360 : bounds.width - 48
        let elapsedWidth = min(bounds.width - 48, elapsedWidthLimit)
        powerSavingElapsedLabel.frame = CGRect(
            x: bounds.midX - elapsedWidth * 0.5,
            y: powerSavingRecordButton.frame.maxY + recordGroupSpacing,
            width: elapsedWidth,
            height: elapsedHeight
        )

        if recState == .buffering && !isLivePreviewVisibleDuringRecording {
            let toggleWidth = min(bounds.width - 72, isLandscape ? CGFloat(176) : CGFloat(198))
            let toggleHeight: CGFloat = 42
            livePreviewToggleButton.frame = CGRect(
                x: bounds.midX - toggleWidth * 0.5,
                y: max(24, powerSavingRecordButton.frame.minY - toggleHeight - 22),
                width: toggleWidth,
                height: toggleHeight
            )
        }
    }

    private func alignedTopMessageY(
        contentTopY: CGFloat,
        safeTopY: CGFloat,
        messageHeight: CGFloat,
        gap: CGFloat,
        offsetBelowBaseline: CGFloat
    ) -> CGFloat {
        let preferredY = contentTopY - messageHeight - gap
        if contentTopY > messageHeight + gap {
            return max(CGFloat(6), preferredY)
        }
        return safeTopY + 2 + offsetBelowBaseline
    }
}
