import UIKit
import AVFoundation

extension HoldVC {
    // MARK: UI Setup
    func setupUI() {
        view.addSubview(camView)
        view.addSubview(overlayView)
        view.addSubview(powerSavingOverlayView)
        powerSavingOverlayView.addSubview(powerSavingCountdownLabel)
        powerSavingOverlayView.addSubview(powerSavingRecordButton)
        powerSavingOverlayView.addSubview(powerSavingElapsedLabel)
        view.addSubview(camHint)
        view.addSubview(previewTipLabel)
        view.addSubview(readyBtn)
        view.addSubview(manualStopBtn)
        view.addSubview(livePreviewToggleButton)
        view.addSubview(settingsBtn)
        view.addSubview(zoomLabel)

        view.addSubview(selectView)
        selectView.addSubview(scrollView)
        scrollView.addSubview(imgView)
        imgView.addSubview(selectOverlayView)
        imgView.addSubview(matLineView)
        selectView.addSubview(selectHint)
        selectView.addSubview(selectTipLabel)
        selectView.addSubview(retakeBtn)
        selectView.addSubview(recordBtn)

        view.addSubview(spinner)
        view.addSubview(launchImageView)

        scrollView.delegate = self
        matLineView.onChange = { [weak self] points in
            self?.matLinePoints = points
        }

        readyBtn.addTarget(self, action: #selector(onReady), for: .touchUpInside)
        manualStopBtn.addTarget(self, action: #selector(onManualStop), for: .touchUpInside)
        powerSavingRecordButton.addTarget(self, action: #selector(onManualStop), for: .touchUpInside)
        livePreviewToggleButton.addTarget(self, action: #selector(onToggleLivePreviewDuringRecording), for: .touchUpInside)
        settingsBtn.addTarget(self, action: #selector(onSettings), for: .touchUpInside)
        retakeBtn.addTarget(self, action: #selector(onRetake), for: .touchUpInside)
        recordBtn.addTarget(self, action: #selector(onRecordStart), for: .touchUpInside)

        let tap = UITapGestureRecognizer(target: self, action: #selector(onSelectTap(_:)))
        tap.numberOfTapsRequired = 1
        imgView.addGestureRecognizer(tap)

        let cameraPinch = UIPinchGestureRecognizer(target: self, action: #selector(onCameraPinch(_:)))
        camView.addGestureRecognizer(cameraPinch)

        loadUserSettings()
        showCameraUI()
    }

    func layoutUI() {
        let bounds = view.bounds
        let safe = view.safeAreaInsets
        let isLandscape = isLandscapeLayout
        let activeFrame = selectedRecordingAspectRatio.displayRect(in: bounds, isLandscape: isLandscape)

        let topMessageHeight: CGFloat = isLandscape ? 30 : 34
        let topMessageOffsetBelowBaseline: CGFloat = 7
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
        mockCameraSource.updatePreviewFrame(camView.bounds)
        overlayView.frame = activeFrame
        overlayView.contentMode = .scaleAspectFill
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
            selectOverlayView.frame = imgView.bounds
            matLineView.frame = imgView.bounds
            scrollView.contentSize = imgView.bounds.size
            resetZoom()
        } else {
            selectOverlayView.frame = imgView.bounds
            matLineView.frame = imgView.bounds
        }

        view.bringSubviewToFront(overlayView)
        view.bringSubviewToFront(powerSavingOverlayView)
        view.bringSubviewToFront(camHint)
        view.bringSubviewToFront(previewTipLabel)
        view.bringSubviewToFront(settingsBtn)
        view.bringSubviewToFront(zoomLabel)
        view.bringSubviewToFront(readyBtn)
        view.bringSubviewToFront(manualStopBtn)
        view.bringSubviewToFront(livePreviewToggleButton)
        view.bringSubviewToFront(selectView)
        view.bringSubviewToFront(spinner)
        if !launchImageView.isHidden { view.bringSubviewToFront(launchImageView) }
    }

    private func layoutPortraitControls(in bounds: CGRect, safe: UIEdgeInsets) {
        let bottomY = bounds.height - safe.bottom - 40

        let tipFrame = CGRect(x: 32, y: bottomY - 44, width: bounds.width - 64, height: 34)
        previewTipLabel.frame = tipFrame
        selectTipLabel.frame = tipFrame
        readyBtn.frame = CGRect(x: bounds.midX - 90, y: bottomY + 4, width: 180, height: 54)

        let recordingButtonHeight: CGFloat = 54
        let recordingButtonWidth = min((bounds.width - 58) * 0.5, CGFloat(168))
        let recordingButtonSpacing: CGFloat = 12
        if recState == .buffering && isDeveloperModeEnabled {
            let totalWidth = recordingButtonWidth * 2 + recordingButtonSpacing
            let startX = bounds.midX - totalWidth * 0.5
            livePreviewToggleButton.frame = CGRect(
                x: startX,
                y: bottomY,
                width: recordingButtonWidth,
                height: recordingButtonHeight
            )
            manualStopBtn.frame = CGRect(
                x: startX + recordingButtonWidth + recordingButtonSpacing,
                y: bottomY,
                width: recordingButtonWidth,
                height: recordingButtonHeight
            )
        } else {
            manualStopBtn.frame = CGRect(x: bounds.midX - 105, y: bottomY, width: 210, height: 54)
        }
        settingsBtn.frame = CGRect(x: bounds.width - safe.right - 70, y: bottomY + 8, width: 44, height: 44)
        zoomLabel.frame = CGRect(x: safe.left + 22, y: bottomY + 16, width: 64, height: 28)

        let buttonWidth = min((bounds.width - 58) * 0.5, CGFloat(168))
        let buttonHeight: CGFloat = 54
        let spacing: CGFloat = 12
        let totalWidth = buttonWidth * 2 + spacing
        let startX = bounds.midX - totalWidth * 0.5
        retakeBtn.frame = CGRect(x: startX, y: bottomY, width: buttonWidth, height: buttonHeight)
        recordBtn.frame = CGRect(x: startX + buttonWidth + spacing, y: bottomY, width: buttonWidth, height: buttonHeight)
    }

    private func layoutLandscapeControls(in bounds: CGRect, safe: UIEdgeInsets) {
        let buttonHeight: CGFloat = 48
        let controlY = bounds.height - safe.bottom - buttonHeight - 12
        let centerX = bounds.midX

        readyBtn.frame = CGRect(x: centerX - 84, y: controlY, width: 168, height: buttonHeight)
        if recState == .buffering && isDeveloperModeEnabled {
            let buttonWidth: CGFloat = 176
            let spacing: CGFloat = 12
            let totalWidth = buttonWidth * 2 + spacing
            let startX = centerX - totalWidth * 0.5
            livePreviewToggleButton.frame = CGRect(x: startX, y: controlY, width: buttonWidth, height: buttonHeight)
            manualStopBtn.frame = CGRect(x: startX + buttonWidth + spacing, y: controlY, width: buttonWidth, height: buttonHeight)
        } else {
            manualStopBtn.frame = CGRect(x: centerX - 98, y: controlY, width: 196, height: buttonHeight)
        }
        zoomLabel.frame = CGRect(x: readyBtn.frame.minX - 78, y: controlY + 10, width: 64, height: 28)
        settingsBtn.frame = CGRect(x: readyBtn.frame.maxX + 16, y: controlY + 2, width: 44, height: 44)

        let buttonWidth: CGFloat = 176
        let spacing: CGFloat = 12
        let totalWidth = buttonWidth * 2 + spacing
        let startX = centerX - totalWidth * 0.5
        retakeBtn.frame = CGRect(x: startX, y: controlY, width: buttonWidth, height: buttonHeight)
        recordBtn.frame = CGRect(x: startX + buttonWidth + spacing, y: controlY, width: buttonWidth, height: buttonHeight)

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

    private func layoutPowerSavingUI(in bounds: CGRect) {
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

        if recState == .buffering && !isDeveloperModeEnabled {
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


    // MARK: Launch / Model Loading
    var isSAMReady: Bool {
        encoder != nil && decoder != nil
    }

    func showLaunchScreen() {
        hasHiddenLaunchScreen = false
        launchImageView.image = launchImageForCurrentAppearance()
        launchImageView.isHidden = false
        previewTipLabel.isHidden = true
        readyBtn.isEnabled = false
        view.bringSubviewToFront(launchImageView)
    }

    func launchImageForCurrentAppearance() -> UIImage? {
        let imageName = traitCollection.userInterfaceStyle == .dark ? launchDarkImageName : launchLightImageName
        return UIImage(named: imageName)
    }

    func startSAMReadinessPolling() {
        samReadyPollWorkItem?.cancel()

        let item = DispatchWorkItem { [weak self] in
            self?.checkSAMReadyAndHideLaunch()
        }
        samReadyPollWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15, execute: item)
    }

    func checkSAMReadyAndHideLaunch() {
        guard !hasHiddenLaunchScreen else { return }

        if isSAMReady {
            hasHiddenLaunchScreen = true

            // Keep the launch screen on the device/system appearance,
            // then force the actual capture UI to Dark Mode right before showing it.
            overrideUserInterfaceStyle = .dark

            launchImageView.isHidden = true
            showCameraUI()
            return
        }

        startSAMReadinessPolling()
    }

    func registerTraitChangeHandlers() {
        if #available(iOS 17.0, *) {
            registerForTraitChanges([UITraitUserInterfaceStyle.self]) { (self: Self, _: UITraitCollection) in
                guard !self.launchImageView.isHidden else { return }
                self.launchImageView.image = self.launchImageForCurrentAppearance()
            }
        }
    }

    private var shouldShowPreviewTip: Bool {
        mode == .camera
            && recState == .idle
            && isHelpEnabled
            && !isLandscapeLayout
            && launchImageView.isHidden
    }

    func updatePreviewTipVisibility() {
        previewTipLabel.isHidden = !shouldShowPreviewTip
    }

    func setPreviewRenderingEnabled(_ enabled: Bool) {
        // 실제 카메라 세션은 권한/사용 상태와 마이크 입력 유지를 위해 실행하지만,
        // 사용자에게 보이는 프리뷰는 test.mov 프레임만 사용합니다.
        previewLayer?.connection?.isEnabled = false
        previewLayer?.isHidden = true
        mockCameraSource.setPreviewVisible(enabled)
    }

    func cachedPowerSavingStopIcon(size: CGFloat) -> UIImage? {
        let roundedSize = max(44, size.rounded(.toNearestOrAwayFromZero))
        let scale = traitCollection.displayScale
        if let image = cachedPowerSavingStopIcon,
           abs(cachedPowerSavingStopIconSize - roundedSize) < 0.5,
           abs(cachedPowerSavingStopIconScale - scale) < 0.01 {
            return image
        }

        let image = makePowerSavingStopIcon(size: roundedSize)
        cachedPowerSavingStopIcon = image
        cachedPowerSavingStopIconSize = roundedSize
        cachedPowerSavingStopIconScale = scale
        return image
    }

    func cachedManualStopIcon(size: CGFloat) -> UIImage? {
        let roundedSize = max(20, size.rounded(.toNearestOrAwayFromZero))
        let scale = traitCollection.displayScale
        if let image = cachedManualStopIcon,
           abs(cachedManualStopIconSize - roundedSize) < 0.5,
           abs(cachedManualStopIconScale - scale) < 0.01 {
            return image
        }

        let image = makeManualStopIcon(size: roundedSize)
        cachedManualStopIcon = image
        cachedManualStopIconSize = roundedSize
        cachedManualStopIconScale = scale
        return image
    }

    // MARK: Recording Status
    func setRecordingStatusText(_ text: String) {
        recordingStatusMessage = text
        camHint.text = text
        camHint.isHidden = false
    }

    func currentRecordingStatusText(hasRecordingStarted: Bool) -> String {
        if let recordingStatusMessage { return recordingStatusMessage }
        guard hasRecordingStarted else { return recordingCountdownStatusText() }
        return defaultActiveRecordingStatusText()
    }

    func recordingCountdownStatusText() -> String {
        isAutoClipDetectionEnabled ? "녹화 준비 중 · 매트 보정 중" : "녹화 준비 중"
    }

    func defaultActiveRecordingStatusText() -> String {
        isAutoClipDetectionEnabled ? "모션 탐지 중 · 등반 시작 대기" : "녹화 중"
    }

    // MARK: Screen State
    func showCameraUI() {
        mode = recState == .buffering ? .recording : .camera
        let isRecording = recState == .buffering
        let usesPowerSavingScreen = isRecording && !isDeveloperModeEnabled

        camView.isHidden = usesPowerSavingScreen
        setPreviewRenderingEnabled(!usesPowerSavingScreen)
        updateRecordingOverlayVisibility()
        zoomLabel.text = zoomDisplayText()
        camHint.isHidden = false
        selectView.isHidden = true
        powerSavingOverlayView.isHidden = !usesPowerSavingScreen

        let hasRecordingStarted = recordingElapsedStartDate != nil

        readyBtn.isHidden = isRecording
        readyBtn.isEnabled = !isRecording && isSAMReady && isMockCameraReady
        settingsBtn.isHidden = isRecording || !isSAMReady
        settingsBtn.isEnabled = !isRecording && recState == .idle
        zoomLabel.isHidden = isRecording || !isSAMReady
        manualStopBtn.isHidden = !isRecording || usesPowerSavingScreen || !hasRecordingStarted
        manualStopBtn.isEnabled = isRecording && hasRecordingStarted
        livePreviewToggleButton.isHidden = !isRecording || !hasRecordingStarted
        livePreviewToggleButton.isEnabled = isRecording && hasRecordingStarted
        powerSavingRecordButton.isHidden = true
        powerSavingElapsedLabel.isHidden = true
        powerSavingCountdownLabel.isHidden = true

        if isRecording {
            previewTipLabel.isHidden = true
            camHint.isHidden = false
            camHint.text = currentRecordingStatusText(hasRecordingStarted: hasRecordingStarted)
            updateLivePreviewToggleButton()
            if isDeveloperModeEnabled && hasRecordingStarted {
                let elapsed = Date().timeIntervalSince(recordingElapsedStartDate ?? Date())
                updateManualStopButton(elapsed: elapsed)
            }
        } else {
            manualStopButtonElapsedText = nil
            recordingStatusMessage = nil
            setPreviewRenderingEnabled(true)
            powerSavingOverlayView.isHidden = true
            powerSavingRecordButton.isHidden = true
            powerSavingElapsedLabel.isHidden = true
            powerSavingCountdownLabel.isHidden = true
            livePreviewToggleButton.isHidden = true
            updateReadyButtonTitle()
            if requiresTopHoldSelection {
                camHint.text = isAutoClipDetectionEnabled
                    ? "벽과 매트 경계선 · 탑 홀드가 모두 보이게 맞춘 뒤 준비 완료"
                    : "탑홀드가 잘 보이게 맞춘 뒤 준비 완료"
                camHint.isHidden = !isHelpEnabled
            } else if isAutoClipDetectionEnabled {
                camHint.text = "벽과 매트 경계선이 보이게 맞춘 뒤 준비 완료"
                camHint.isHidden = !isHelpEnabled
            } else {
                camHint.text = ""
                camHint.isHidden = true
            }
        }

        updatePreviewTipVisibility()

        retakeBtn.isEnabled = true
        recordBtn.isEnabled = pendingOverlay != nil
        setLoading(false)
        view.setNeedsLayout()
    }

    func updateRecordingOverlayVisibility() {
        let shouldShowOverlay = recState == .buffering
            && isDeveloperModeEnabled
            && isTopHoldMaskingEnabled
            && overlayView.image != nil
        overlayView.isHidden = !shouldShowOverlay
    }

    func updateLivePreviewToggleButton() {
        var config = livePreviewToggleButton.configuration ?? .filled()
        config.cornerStyle = .capsule
        config.contentInsets = NSDirectionalEdgeInsets(top: 10, leading: 12, bottom: 10, trailing: 12)
        config.baseForegroundColor = .white
        if isDeveloperModeEnabled {
            config.title = "실시간 영상 끄기"
            config.baseBackgroundColor = UIColor.black.withAlphaComponent(0.72)
        } else {
            config.title = "실시간 영상 표시"
            config.baseBackgroundColor = UIColor.white.withAlphaComponent(0.18)
        }
        config.image = nil
        config.imagePadding = 0
        livePreviewToggleButton.configuration = config
        livePreviewToggleButton.accessibilityLabel = config.title
    }

    func updateManualStopButton(elapsed: TimeInterval) {
        let elapsedText = formattedElapsedTime(elapsed)
        let stopIcon = cachedManualStopIcon(size: 34)
        let shouldUpdateStaticIcon = manualStopBtn.configuration?.image == nil
            || cachedManualStopIcon == nil
            || abs(cachedManualStopIconScale - traitCollection.displayScale) >= 0.01

        guard manualStopButtonElapsedText != elapsedText || shouldUpdateStaticIcon else { return }

        var config = manualStopBtn.configuration ?? .filled()
        var title = AttributedString(elapsedText)
        title.font = .monospacedDigitSystemFont(ofSize: 16, weight: .semibold)
        config.title = nil
        config.attributedTitle = title
        config.image = stopIcon
        config.imagePlacement = .leading
        config.imagePadding = 8
        config.cornerStyle = .capsule
        config.baseBackgroundColor = .systemRed
        config.baseForegroundColor = .white
        config.contentInsets = NSDirectionalEdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16)
        manualStopBtn.configuration = config
        manualStopBtn.titleLabel?.font = .monospacedDigitSystemFont(ofSize: 16, weight: .semibold)
        manualStopBtn.accessibilityLabel = "영상 녹화 종료, \(elapsedText)"
        manualStopButtonElapsedText = elapsedText
    }

    func showPowerSavingCountdown(_ value: Int) {
        guard !isDeveloperModeEnabled else { return }
        powerSavingOverlayView.isHidden = false
        powerSavingCountdownLabel.isHidden = false
        powerSavingCountdownLabel.text = "\(value)"
        powerSavingRecordButton.isHidden = true
        powerSavingElapsedLabel.isHidden = true
        livePreviewToggleButton.isHidden = true
        previewTipLabel.isHidden = true
        camHint.isHidden = false
        camHint.text = currentRecordingStatusText(hasRecordingStarted: false)
        camView.isHidden = true
        setPreviewRenderingEnabled(false)
        overlayView.isHidden = true
        view.bringSubviewToFront(powerSavingOverlayView)
        view.bringSubviewToFront(camHint)
    }

    func showPowerSavingRecording(elapsed: TimeInterval) {
        guard !isDeveloperModeEnabled else { return }

        let wasHidden = powerSavingOverlayView.isHidden || powerSavingRecordButton.isHidden || powerSavingElapsedLabel.isHidden
        powerSavingOverlayView.isHidden = false
        powerSavingCountdownLabel.isHidden = true
        powerSavingRecordButton.isHidden = false
        powerSavingElapsedLabel.isHidden = false
        livePreviewToggleButton.isHidden = false
        livePreviewToggleButton.isEnabled = true
        updateLivePreviewToggleButton()
        layoutPowerSavingUI(in: view.bounds)

        let buttonSide = max(180, min(powerSavingRecordButton.bounds.width, powerSavingRecordButton.bounds.height))
        let iconSize = buttonSide * 0.88
        let needsIconUpdate = powerSavingRecordButton.configuration?.image == nil
            || abs(cachedPowerSavingStopIconSize - max(44, iconSize.rounded(.toNearestOrAwayFromZero))) >= 0.5
            || abs(cachedPowerSavingStopIconScale - traitCollection.displayScale) >= 0.01

        if wasHidden || needsIconUpdate {
            var config = powerSavingRecordButton.configuration ?? .plain()
            config.title = nil
            config.contentInsets = NSDirectionalEdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0)
            config.image = cachedPowerSavingStopIcon(size: iconSize)
            config.imagePadding = 0
            config.baseForegroundColor = .white
            powerSavingRecordButton.configuration = config
            powerSavingElapsedLabel.font = .monospacedDigitSystemFont(ofSize: 62, weight: .bold)
        }

        let elapsedText = formattedElapsedTime(elapsed)
        if powerSavingElapsedLabel.text != elapsedText {
            powerSavingElapsedLabel.text = elapsedText
        }

        camView.isHidden = true
        setPreviewRenderingEnabled(false)
        overlayView.isHidden = true
        previewTipLabel.isHidden = true
        camHint.isHidden = false
        camHint.text = currentRecordingStatusText(hasRecordingStarted: recordingElapsedStartDate != nil)
        view.bringSubviewToFront(powerSavingOverlayView)
        view.bringSubviewToFront(livePreviewToggleButton)
        view.bringSubviewToFront(camHint)

        if wasHidden { view.setNeedsLayout() }
    }

    func hidePowerSavingRecordingUI() {
        powerSavingOverlayView.isHidden = true
        powerSavingCountdownLabel.isHidden = true
        powerSavingRecordButton.isHidden = true
        powerSavingElapsedLabel.isHidden = true
        livePreviewToggleButton.isHidden = true
        setPreviewRenderingEnabled(true)
    }

    func formattedElapsedTime(_ elapsed: TimeInterval) -> String {
        let safe = max(0, Int(elapsed.rounded(.down)))
        let minutes = safe / 60
        let seconds = safe % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }

    func updateReadyButtonTitle() {
        var config = readyBtn.configuration ?? .filled()
        config.title = (isAutoClipDetectionEnabled || requiresTopHoldSelection) ? "준비 완료" : "녹화 시작"
        readyBtn.configuration = config
        readyBtn.accessibilityLabel = config.title
    }

    func makePowerSavingStopIcon(size: CGFloat) -> UIImage? {
        makeStopIcon(size: size, centerFillColor: .systemRed, minimumSide: 44)
    }

    func makeManualStopIcon(size: CGFloat) -> UIImage? {
        makeStopIcon(size: size, centerFillColor: .white, minimumSide: 28)
    }

    private func makeStopIcon(size: CGFloat, centerFillColor: UIColor, minimumSide: CGFloat) -> UIImage? {
        let side = max(minimumSide, size)
        let format = UIGraphicsImageRendererFormat()
        format.scale = traitCollection.displayScale
        format.opaque = false

        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { _ in
            let lineWidth = max(2.5, side * 0.065)
            let circleInset = lineWidth / 2 + side * 0.04
            let circleRect = CGRect(x: circleInset, y: circleInset, width: side - circleInset * 2, height: side - circleInset * 2)

            UIColor.white.setStroke()
            let circle = UIBezierPath(ovalIn: circleRect)
            circle.lineWidth = lineWidth
            circle.stroke()

            centerFillColor.setFill()
            let squareSide = side * 0.42
            let squareRect = CGRect(
                x: (side - squareSide) / 2,
                y: (side - squareSide) / 2,
                width: squareSide,
                height: squareSide
            )
            UIBezierPath(roundedRect: squareRect, cornerRadius: squareSide * 0.12).fill()
        }
    }

    func showSelectUI(with image: UIImage) {
        clearSelectionCache(clearPending: true)

        mode = .select
        hidePowerSavingRecordingUI()
        frameImage = image
        pendingOverlay = nil
        pendingTopBox = nil

        imgView.image = image
        selectOverlayView.image = nil

        if requiresTopHoldSelection {
            pixels = PixelMap(image: image)
            emb = nil
        } else {
            pixels = nil
            emb = nil
        }

        if isAutoClipDetectionEnabled {
            let detectedLine = MatDetector.detect(in: image, horizontalBinCount: activeHorizontalBinCount)
            matLinePoints = detectedLine
            matLineView.setPoints(detectedLine)
            matLineView.isHidden = false

            if requiresTopHoldSelection {
                selectHint.text = "탑홀드를 탭하세요 · 파란 점으로 매트 경계를 보정하세요"
                selectTipLabel.text = "Tip. 작은 홀드는 확대해서 선택할 수 있어요"
                recordBtn.isEnabled = false
            } else {
                selectHint.text = "파란 점으로 매트 경계를 보정한 뒤 녹화 시작을 눌러주세요"
                selectTipLabel.text = "Tip. 파란 점은 위아래로 움직일 수 있어요"
                recordBtn.isEnabled = detectedLine.count >= 2
            }
        } else {
            matLinePoints = []
            matLineView.clear()
            matLineView.isHidden = true
            selectHint.text = "탑홀드를 탭하세요"
            selectTipLabel.text = "Tip. 작은 홀드는 확대해서 선택할 수 있어요"
            recordBtn.isEnabled = false
        }
        resetZoom()

        camView.isHidden = true
        overlayView.isHidden = true
        zoomLabel.isHidden = true
        camHint.isHidden = true
        previewTipLabel.isHidden = true
        readyBtn.isHidden = true
        settingsBtn.isHidden = true
        manualStopBtn.isHidden = true
        livePreviewToggleButton.isHidden = true
        selectHint.isHidden = !isHelpEnabled
        selectTipLabel.isHidden = !isHelpEnabled
        selectView.isHidden = false
        retakeBtn.isEnabled = true
        setLoading(false)
        view.setNeedsLayout()

        if requiresTopHoldSelection {
            warmUpEmbedding(for: image)
        }
    }

    func showCameraDeniedMessage() {
        showCameraError("카메라 권한이 필요합니다. 설정에서 카메라 접근을 허용해주세요.")
    }

    func showCameraError(_ message: String) {
        previewTipLabel.isHidden = true
        livePreviewToggleButton.isHidden = true
        camHint.text = message
        readyBtn.isEnabled = false
    }

    // MARK: Cache
    func clearSelectionCache(clearPending: Bool) {
        selectionRequestID += 1

        if clearPending {
            pendingOverlay = nil
            pendingTopBox = nil
        }

        frameImage = nil
        pixels = nil
        emb = nil

        imgView.image = nil
        selectOverlayView.image = nil
        matLineView.clear()
        recordBtn.isEnabled = false
    }

    func currentMatLineInImageCoordinates() -> [CGPoint] {
        guard let image = frameImage else { return [] }
        return matLinePoints.map { CGPoint(x: $0.x * image.size.width, y: $0.y * image.size.height) }
    }

    func setLoading(_ loading: Bool) {
        if loading {
            spinner.startAnimating()
        } else {
            spinner.stopAnimating()
        }
    }

    func updateZoomLabel() {
        zoomLabel.text = zoomDisplayText()
    }

    func zoomDisplayText() -> String {
        let displayFactor = displayZoomFactor(forRawZoomFactor: currentCameraZoomFactor)
        return String(format: "%.1fx", Double(displayFactor))
    }

    private func displayZoomFactor(forRawZoomFactor rawFactor: CGFloat) -> CGFloat {
        guard let camera = activeCameraDevice else {
            return rawFactor
        }

        // AVCaptureDevice.videoZoomFactor는 가상 카메라에서는 실제 카메라 앱의 0.5x/1.0x 표기와 다릅니다.
        // dual-wide / triple camera에서는 raw 1.0이 초광각 0.5x이고, 첫 switch-over 지점이 보통 광각 1.0x입니다.
        // 그래서 사용자에게 보여줄 값은 rawFactor를 광각 기준 raw zoom 값으로 나눠 표시합니다.
        let wideBaseline = wideCameraRawZoomBaseline(for: camera)
        guard wideBaseline > 0 else { return rawFactor }
        return rawFactor / wideBaseline
    }

    private func wideCameraRawZoomBaseline(for camera: AVCaptureDevice) -> CGFloat {
        switch camera.deviceType {
        case .builtInDualWideCamera, .builtInTripleCamera:
            let switchOverFactors = camera.virtualDeviceSwitchOverVideoZoomFactors
                .map { CGFloat(truncating: $0) }
                .sorted()

            if let first = switchOverFactors.first, first > camera.minAvailableVideoZoomFactor {
                return first
            }

            // 일부 기기/포맷에서 switch-over 값을 안정적으로 못 받는 경우의 안전값입니다.
            // 이 계열의 가상 카메라는 raw 1.0 ≈ 0.5x, raw 2.0 ≈ 1.0x로 보는 것이 UI상 자연스럽습니다.
            return 2.0
        default:
            return 1.0
        }
    }

}
