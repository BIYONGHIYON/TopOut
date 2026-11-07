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
        recordBtn.isEnabled = false
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
            && selectTipLabel.isHidden
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
        let showsLivePreview = isLivePreviewVisibleDuringRecording
        let usesPowerSavingScreen = isRecording && !showsLivePreview
        let hasRecordingStarted = recordingElapsedStartDate != nil

        camView.isHidden = usesPowerSavingScreen
        setPreviewRenderingEnabled(!usesPowerSavingScreen)
        zoomLabel.text = zoomDisplayText()

        // 실시간 영상이 보이는 녹화 중에는 경계선을 표시하고 파란 점으로 바로 보정할 수 있다.
        let showsRecordingMatLine = isRecording && hasRecordingStarted && showsLivePreview
            && isAutoClipDetectionEnabled && matLinePoints.count >= 2
        recordingMatLineView.showsHandles = showsRecordingMatLine
        recordingMatLineView.isHidden = !showsRecordingMatLine
        if showsRecordingMatLine { recordingMatLineView.setPoints(matLinePoints, notify: false) }
        powerSavingOverlayView.isHidden = !usesPowerSavingScreen

        // 녹화 전 화면: 실시간 프리뷰 위에 안내와 준비 완료(또는 녹화 시작)를 둔다. 탑홀드는 결과 화면에서 고른다.
        selectView.isHidden = isRecording
        readyBtn.isHidden = isRecording
        retakeBtn.isHidden = true
        recordBtn.isHidden = isRecording
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
            if showsLivePreview && hasRecordingStarted {
                let elapsed = Date().timeIntervalSince(recordingElapsedStartDate ?? Date())
                updateManualStopButton(elapsed: elapsed)
            }
        } else {
            manualStopButtonElapsedText = nil
            recordingStatusMessage = nil
            setPreviewRenderingEnabled(true)
            camView.alpha = 1
            powerSavingOverlayView.isHidden = true
            livePreviewToggleButton.isHidden = true
            camHint.isHidden = true
            imgView.image = nil
            // 경계선은 준비 완료를 누른 뒤 측정해 보정 화면에서 보여준다.
            matLineView.clear()
            matLineView.isHidden = true
            refreshRecordButtonAvailability()
            refreshCameraHint()
        }

        updatePreviewTipVisibility()
        setLoading(false)
        view.setNeedsLayout()
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
        view.bringSubviewToFront(powerSavingOverlayView)
        view.bringSubviewToFront(camHint)
    }

    func showPowerSavingRecording(elapsed: TimeInterval) {
        guard !isLivePreviewVisibleDuringRecording else { return }

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

    func makePowerSavingStopIcon(size: CGFloat) -> UIImage? {
        makeStopIcon(size: size, centerFillColor: .systemRed, minimumSide: 44)
    }

    func makeManualStopIcon(size: CGFloat) -> UIImage? {
        makeStopIcon(size: size, centerFillColor: .white, minimumSide: 28)
    }

    /// 녹화 시작 버튼: 녹화 종료 버튼과 같은 빨간 캡슐에 문구 없이 녹화 아이콘만 둔다.
    func applyRecordStartButtonStyle() {
        var config = UIButton.Configuration.filled()
        config.title = nil
        config.attributedTitle = nil
        config.image = makeRecordStartIcon(size: 30)
        config.cornerStyle = .capsule
        config.baseBackgroundColor = .systemRed
        config.baseForegroundColor = .white
        config.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16)
        recordBtn.configuration = config
        recordBtn.accessibilityLabel = L("camera.start_recording")
    }

    /// 종료 아이콘과 같은 흰 원 테두리 안에 채운 원을 그려 시작·종료를 모양으로 구분한다.
    private func makeRecordStartIcon(size: CGFloat) -> UIImage? {
        let side = max(28, size)
        let format = UIGraphicsImageRendererFormat()
        format.scale = traitCollection.displayScale
        format.opaque = false

        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { _ in
            let lineWidth = max(2.5, side * 0.065)
            let circleInset = lineWidth / 2 + side * 0.04
            let ringRect = CGRect(x: circleInset, y: circleInset, width: side - circleInset * 2, height: side - circleInset * 2)

            UIColor.white.setStroke()
            let ring = UIBezierPath(ovalIn: ringRect)
            ring.lineWidth = lineWidth
            ring.stroke()

            UIColor.white.setFill()
            let dot = side * 0.46
            UIBezierPath(ovalIn: CGRect(x: (side - dot) / 2, y: (side - dot) / 2, width: dot, height: dot)).fill()
        }
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
        selectHint.isHidden = true
        readyBtn.isEnabled = false
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
