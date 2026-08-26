import UIKit
import AVFoundation

extension HoldVC {
    // MARK: - 시작 화면과 모델 준비
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

    func hideLaunchScreenAndShowCameraUIIfNeeded() {
        guard !hasHiddenLaunchScreen else { return }
        hasHiddenLaunchScreen = true

        // 카메라 진입은 MobileSAM 로딩과 독립적으로 진행됩니다.
        // 모델은 실제 카메라 프레임이 안정된 뒤 백그라운드에서 준비됩니다.
        overrideUserInterfaceStyle = .dark
        launchImageView.isHidden = true
        showCameraUI()
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
        previewLayer?.connection?.isEnabled = enabled
        previewLayer?.isHidden = !enabled
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

    // MARK: - 녹화 상태
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
        L("camera.preparing")
    }

    func defaultActiveRecordingStatusText() -> String {
        isAutoClipDetectionEnabled ? L("camera.waiting_motion") : L("camera.recording")
    }

    // MARK: - 화면 상태
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
        recordingMatLineView.isHidden = !(
            isRecording &&
            recordingElapsedStartDate != nil &&
            isDeveloperModeEnabled &&
            isAutoClipDetectionEnabled &&
            matLinePoints.count >= 2
        )
        if !recordingMatLineView.isHidden { recordingMatLineView.setPoints(matLinePoints, notify: false) }
        powerSavingOverlayView.isHidden = !usesPowerSavingScreen

        let hasRecordingStarted = recordingElapsedStartDate != nil

        readyBtn.isHidden = isRecording
        readyBtn.isEnabled = !isRecording && isCameraReady && isCameraStreamReady && !isBusy
        settingsBtn.isHidden = isRecording
        settingsBtn.isEnabled = !isRecording && recState == .idle
        zoomLabel.isHidden = isRecording
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
                    ? L("camera.preview_both")
                    : L("camera.preview_hold")
                camHint.isHidden = !isHelpEnabled
            } else if isAutoClipDetectionEnabled {
                camHint.text = L("camera.preview_mat")
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
        config.titleAlignment = .center
        config.titleLineBreakMode = .byWordWrapping
        config.contentInsets = NSDirectionalEdgeInsets(top: 6, leading: 10, bottom: 6, trailing: 10)
        config.baseForegroundColor = .white
        if isDeveloperModeEnabled {
            config.title = L("camera.hide_live_preview")
            config.baseBackgroundColor = UIColor.black.withAlphaComponent(0.72)
        } else {
            config.title = L("camera.show_live_preview")
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
        manualStopBtn.accessibilityLabel = "\(L("camera.stop_recording")), \(elapsedText)"
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
        config.title = (isAutoClipDetectionEnabled || requiresTopHoldSelection)
            ? L("camera.ready")
            : L("camera.start_recording")
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

    func showSelectUI(with image: UIImage?) {
        clearSelectionCache(clearPending: true)

        mode = .select
        hidePowerSavingRecordingUI()
        frameImage = image
        pendingOverlay = nil
        pendingTopBox = nil

        // 준비 완료 프레임은 매트 분석에만 쓰고 보정은 실시간 화면 위에서 진행한다.
        imgView.image = nil
        selectOverlayView.image = nil

        // 탑홀드는 탭한 순간의 새 프레임으로 색상 정보와 임베딩을 만든다.
        pixels = nil
        emb = nil

        let boundaryRequestID = selectionRequestID
        if isAutoClipDetectionEnabled, let image {
            // 추론 전 75% 기본선을 먼저 표시하면 완료 시 선이 순간 이동해 보입니다.
            // 추론 중에는 선을 숨기고, 최종 예측 또는 fallback을 한 번만 표시합니다.
            matLinePoints = []
            matLineView.clear()
            matLineView.isHidden = true
            recordBtn.isEnabled = false
            setLoading(true)

            selectHint.text = L("camera.analyzing_mat")
            selectTipLabel.text = L("camera.tip_analysis")

            isBusy = true
            var didFinishBoundaryAnalysis = false
            let finishBoundaryAnalysis: ([CGPoint]) -> Void = { [weak self] detectedLine in
                guard let self,
                      !didFinishBoundaryAnalysis,
                      self.selectionRequestID == boundaryRequestID,
                      self.mode == .select,
                      self.frameImage === image else { return }

                didFinishBoundaryAnalysis = true
                self.matBoundaryAnalysisTimeoutWorkItem?.cancel()
                self.matBoundaryAnalysisTimeoutWorkItem = nil
                self.isBusy = false
                self.matLinePoints = detectedLine
                self.matLineView.setPoints(detectedLine)
                self.matLineView.isHidden = false
                self.setLoading(false)

                if self.requiresTopHoldSelection {
                    self.selectHint.text = L("camera.selection_both")
                    self.selectTipLabel.text = ""
                    self.selectTipLabel.isHidden = true
                    // 준비 완료 임베딩은 저장하지 않고 탑홀드 탭마다 새 프레임을 사용한다.
                    self.emb = nil
                } else {
                    self.selectHint.text = L("camera.selection_mat")
                    self.selectTipLabel.text = L("camera.tip_adjust_line")
                    self.selectTipLabel.isHidden = !self.isHelpEnabled
                    self.recordBtn.isEnabled = detectedLine.count >= 2
                }
            }

            let timeoutWorkItem = DispatchWorkItem {
                finishBoundaryAnalysis(Self.samMatDefaultLine)
            }
            matBoundaryAnalysisTimeoutWorkItem = timeoutWorkItem
            DispatchQueue.main.asyncAfter(
                deadline: .now() + Cfg.matBoundaryAnalysisTimeout,
                execute: timeoutWorkItem
            )
            detectMatBoundaryWithMobileSAM(in: image, completion: finishBoundaryAnalysis)
        } else {
            matLinePoints = []
            matLineView.clear()
            matLineView.isHidden = true
            selectHint.text = L("camera.selection_hold")
            selectTipLabel.text = ""
            selectTipLabel.isHidden = true
            recordBtn.isEnabled = false
        }
        resetZoom()

        camView.isHidden = false
        overlayView.isHidden = true
        recordingMatLineView.isHidden = true
        // 준비 완료 뒤 녹화 종료나 재촬영 전까지 카메라 화각을 고정한다.
        zoomLabel.isHidden = true
        camHint.isHidden = true
        previewTipLabel.isHidden = true
        readyBtn.isHidden = true
        settingsBtn.isHidden = true
        manualStopBtn.isHidden = true
        livePreviewToggleButton.isHidden = true
        selectHint.isHidden = !isHelpEnabled
        selectTipLabel.isHidden = !isHelpEnabled || (selectTipLabel.text?.isEmpty ?? true)
        selectView.isHidden = false
        retakeBtn.isEnabled = true
        if !isAutoClipDetectionEnabled {
            setLoading(false)
        }
        view.setNeedsLayout()

    }

    func showCameraDeniedMessage() {
        showCameraError(L("permission.camera_settings_error"))
    }

    func showCameraError(_ message: String) {
        if !hasHiddenLaunchScreen {
            hideLaunchScreenAndShowCameraUIIfNeeded()
        }
        previewTipLabel.isHidden = true
        livePreviewToggleButton.isHidden = true
        camHint.text = message
        camHint.isHidden = false
        readyBtn.isEnabled = false
    }

    // MARK: - 선택 캐시
    func clearSelectionCache(clearPending: Bool) {
        matBoundaryAnalysisTimeoutWorkItem?.cancel()
        matBoundaryAnalysisTimeoutWorkItem = nil
        selectionRequestID += 1

        if clearPending {
            pendingOverlay = nil
            pendingTopBox = nil
        }

        frameImage = nil
        pixels = nil
        emb = nil
        isBusy = false

        imgView.image = nil
        selectOverlayView.image = nil
        matLineView.clear()
        recordBtn.isEnabled = false
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
        return String(
            format: "%.1fx",
            locale: AppLanguageManager.locale,
            Double(displayFactor)
        )
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
