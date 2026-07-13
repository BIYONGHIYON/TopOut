import UIKit
import CoreImage
@preconcurrency import AVFoundation

private enum CameraCfg {
    // SAM 입력은 1024이므로 캡처 원본을 너무 크게 들고 있을 필요가 없습니다.
    // 녹화 해상도와 무관하게 탑홀드 선택용 캡처 이미지는 이 크기로 축소합니다.
    static let captureMaxLongSide: CGFloat = 1600
}

extension HoldVC {
    func requestCameraAccess() {
        // 화면/녹화/분석 입력은 기존과 동일하게 test.mov를 사용합니다.
        // 실제 카메라는 별도의 AVCaptureSession으로만 실행하며 화면에는 표시하지 않습니다.
        isAudioCaptureEnabled = false
        recorder.setAudioCaptureEnabled(false)
        setupCamera()
        requestBackgroundCameraAccess()
    }

    private func requestBackgroundCameraAccess() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureBackgroundCameraIfNeeded()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                guard granted else { return }
                DispatchQueue.main.async {
                    self?.configureBackgroundCameraIfNeeded()
                }
            }
        default:
            // 목업 앱의 동작은 카메라 권한과 독립적입니다.
            // 권한이 거부되어도 test.mov 프리뷰와 기존 기능은 그대로 유지합니다.
            break
        }
    }

    private func configureBackgroundCameraIfNeeded() {
        guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else { return }

        // 실제 카메라 프레임은 VideoFrameSink까지 들어오지만,
        // usesMockVideoInput=true이므로 화면/분석/녹화에는 사용되지 않고 즉시 버려집니다.
        videoOutput.setSampleBufferDelegate(videoFrameSink, queue: videoOutputQ)

        sessionQ.async { [weak self] in
            guard let self else { return }

            if self.isBackgroundCameraConfigured {
                if self.shouldRunBackgroundCamera, !self.session.isRunning {
                    self.session.startRunning()
                }
                return
            }

            self.session.beginConfiguration()
            if self.session.canSetSessionPreset(.high) {
                self.session.sessionPreset = .high
            }

            self.session.inputs.forEach { self.session.removeInput($0) }
            self.session.outputs.forEach { self.session.removeOutput($0) }

            var configuredCamera: AVCaptureDevice?
            if let camera = self.backCamera(),
               let input = try? AVCaptureDeviceInput(device: camera),
               self.session.canAddInput(input) {
                self.session.addInput(input)

                self.videoOutput.alwaysDiscardsLateVideoFrames = true
                self.videoOutput.videoSettings = [
                    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
                ]

                if self.session.canAddOutput(self.videoOutput) {
                    self.session.addOutput(self.videoOutput)
                    configuredCamera = camera
                }
            }
            self.session.commitConfiguration()

            guard let configuredCamera else { return }
            self.isBackgroundCameraConfigured = true

            DispatchQueue.main.async { [weak self] in
                self?.activeCameraDevice = configuredCamera
                self?.updateCameraOrientationIfPossible()
            }

            if self.shouldRunBackgroundCamera, !self.session.isRunning {
                self.session.startRunning()
            }
        }
    }

    private func requestMicrophoneAccessThenSetupCamera() {
        let status = AVCaptureDevice.authorizationStatus(for: .audio)
        switch status {
        case .authorized:
            // 권한만 확인하지 않고 오디오 세션을 실제로 활성화할 수 있는지도 확인합니다.
            // 통화 중에는 setActive가 실패할 수 있으며, 이때는 영상 전용으로 구성합니다.
            isAudioCaptureEnabled = configureAudioSessionForRecording()
            recorder.setAudioCaptureEnabled(isAudioCaptureEnabled)
            setupCamera()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .audio) { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.isAudioCaptureEnabled = granted && self.configureAudioSessionForRecording()
                    self.recorder.setAudioCaptureEnabled(self.isAudioCaptureEnabled)
                    self.setupCamera()
                }
            }
        default:
            // 마이크 권한이 없어도 영상 녹화는 계속 가능하게 둡니다.
            isAudioCaptureEnabled = false
            recorder.setAudioCaptureEnabled(false)
            setupCamera()
        }
    }

    @discardableResult
    private func configureAudioSessionForRecording() -> Bool {
        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(
                .playAndRecord,
                mode: .videoRecording,
                options: [.defaultToSpeaker, .allowBluetoothHFP]
            )
            try audioSession.setActive(true)
            return true
        } catch {
            // 통화 등으로 오디오 세션 우선권을 얻지 못하면 마이크 없이 진행합니다.
            return false
        }
    }

    func prepareAudioSessionForCameraUse() {
        // 목업 입력은 test.mov의 비디오 프레임만 사용합니다.
        // 실제 카메라/마이크 세션을 다시 활성화하지 않습니다.
        isAudioCaptureEnabled = false
        recorder.setAudioCaptureEnabled(false)
    }

    func setupCamera() {
        // 화면 표시, 기준 프레임, 녹화, 분석은 모두 test.mov에서 공급합니다.
        // 실제 카메라 세션의 성공 여부는 목업 준비 상태에 영향을 주지 않습니다.
        videoFrameSink.setUsesMockVideoInput(true)
        isAudioCaptureEnabled = false
        recorder.setAudioCaptureEnabled(false)
        isCameraReady = false

        installPreviewLayerIfNeeded()
        setupMockCameraIfNeeded()
    }

    /// 통화 등으로 마이크가 다른 클라이언트에 점유되었을 때
    /// 오디오 입력/출력만 제거하고 카메라와 비디오 녹화는 유지합니다.
    func disableAudioCaptureForCurrentSession() {
        isAudioCaptureEnabled = false
        recorder.setAudioCaptureEnabled(false)
    }

    func applySelectedCaptureFormatIfPossible(completion: (() -> Void)? = nil) {
        guard recState == .idle else {
            DispatchQueue.main.async { completion?() }
            return
        }

        // 실제 카메라 포맷 변경 대신 목업 프레임 공급 속도와 recorder 설정만 갱신합니다.
        mockCameraSource.setFrameRate(selectedRecordingFrameRate.fps)
        recorder.configure(
            resolution: selectedRecordingResolution,
            frameRate: selectedRecordingFrameRate,
            aspectRatio: selectedRecordingAspectRatio
        )
        updateCameraOrientationIfPossible()
        DispatchQueue.main.async { completion?() }
    }

    private func applyCaptureFormat(to camera: AVCaptureDevice, resolution: RecordingResolution, frameRate: RecordingFrameRate) {
        let preferredFPS = frameRate.fps
        guard let format = bestVideoFormat(for: camera, resolution: resolution, fps: preferredFPS) else {
            return
        }

        let fps = clampedFPS(preferredFPS, in: format)
        let frameDuration = CMTime(value: 1, timescale: CMTimeScale(Int(fps.rounded())))

        do {
            try camera.lockForConfiguration()
            camera.activeFormat = format
            camera.activeVideoMinFrameDuration = frameDuration
            camera.activeVideoMaxFrameDuration = frameDuration
            camera.unlockForConfiguration()
        } catch { }
    }

    private func bestVideoFormat(for camera: AVCaptureDevice, resolution: RecordingResolution, fps: Double) -> AVCaptureDevice.Format? {
        let targetShort = Int(resolution.shortSide)
        let targetLong = Int(resolution.longSide)
        let targetPixels = targetShort * targetLong

        let candidates = camera.formats.filter { format in
            supports(format: format, resolution: resolution, fps: fps)
        }

        return candidates.min { lhs, rhs in
            let lhsScore = score(format: lhs, targetShort: targetShort, targetLong: targetLong, targetPixels: targetPixels, preferredFPS: fps)
            let rhsScore = score(format: rhs, targetShort: targetShort, targetLong: targetLong, targetPixels: targetPixels, preferredFPS: fps)
            return lhsScore < rhsScore
        }
    }

    func supportedRecordingFrameRates() -> [RecordingResolution: Set<RecordingFrameRate>] {
        // 목업 영상은 recorder가 선택한 출력 FPS로 프레임을 재타임스탬프하므로
        // 기존 설정 화면의 모든 해상도/FPS 조합을 그대로 노출합니다.
        Dictionary(uniqueKeysWithValues: RecordingResolution.allCases.map {
            ($0, Set(RecordingFrameRate.allCases))
        })
    }

    private func supports(
        format: AVCaptureDevice.Format,
        resolution: RecordingResolution,
        fps: Double
    ) -> Bool {
        let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
        let shortSide = Int(min(dimensions.width, dimensions.height))
        let longSide = Int(max(dimensions.width, dimensions.height))
        let hasRequiredResolution = shortSide >= Int(resolution.shortSide) && longSide >= Int(resolution.longSide)
        guard hasRequiredResolution else { return false }

        return format.videoSupportedFrameRateRanges.contains { range in
            range.minFrameRate <= fps + 0.001 && fps <= range.maxFrameRate + 0.001
        }
    }

    private func resolvedRecordingFormat(
        for camera: AVCaptureDevice,
        requestedResolution: RecordingResolution,
        requestedFrameRate: RecordingFrameRate
    ) -> (resolution: RecordingResolution, frameRate: RecordingFrameRate) {
        if bestVideoFormat(for: camera, resolution: requestedResolution, fps: requestedFrameRate.fps) != nil {
            return (requestedResolution, requestedFrameRate)
        }

        let fallbackFrameRateOrder: [RecordingFrameRate] = [.fps30, .fps60]
        if let sameResolutionFrameRate = fallbackFrameRateOrder.first(where: {
            bestVideoFormat(for: camera, resolution: requestedResolution, fps: $0.fps) != nil
        }) {
            return (requestedResolution, sameResolutionFrameRate)
        }

        for resolution in RecordingResolution.allCases {
            if let frameRate = fallbackFrameRateOrder.first(where: {
                bestVideoFormat(for: camera, resolution: resolution, fps: $0.fps) != nil
            }) {
                return (resolution, frameRate)
            }
        }

        return (.fhd, .fps30)
    }

    private func applyResolvedRecordingFormatIfNeeded(
        _ format: (resolution: RecordingResolution, frameRate: RecordingFrameRate)
    ) {
        guard selectedRecordingResolution != format.resolution || selectedRecordingFrameRate != format.frameRate else {
            return
        }

        selectedRecordingResolution = format.resolution
        selectedRecordingFrameRate = format.frameRate
        recorder.configure(
            resolution: format.resolution,
            frameRate: format.frameRate,
            aspectRatio: selectedRecordingAspectRatio
        )
        mockCameraSource.setFrameRate(format.frameRate.fps)
        saveUserSettings()
    }

    private func score(
        format: AVCaptureDevice.Format,
        targetShort: Int,
        targetLong: Int,
        targetPixels: Int,
        preferredFPS: Double
    ) -> Int {
        let dims = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
        let short = Int(min(dims.width, dims.height))
        let long = Int(max(dims.width, dims.height))
        let pixels = short * long

        let supportsPreferredFPS = format.videoSupportedFrameRateRanges.contains { range in
            range.minFrameRate <= preferredFPS + 0.001 && preferredFPS <= range.maxFrameRate + 0.001
        }

        let fpsPenalty = supportsPreferredFPS ? 0 : 100_000_000
        let underPenalty = (short < targetShort || long < targetLong) ? 10_000_000 : 0
        let dimensionPenalty = abs(short - targetShort) * 10 + abs(long - targetLong) * 10
        let pixelPenalty = abs(pixels - targetPixels) / 1_000
        return fpsPenalty + underPenalty + dimensionPenalty + pixelPenalty
    }

    private func clampedFPS(_ fps: Double, in format: AVCaptureDevice.Format) -> Double {
        guard let range = format.videoSupportedFrameRateRanges.first(where: { $0.minFrameRate <= fps && fps <= $0.maxFrameRate }) else {
            let best = format.videoSupportedFrameRateRanges.min { lhs, rhs in
                abs(lhs.maxFrameRate - fps) < abs(rhs.maxFrameRate - fps)
            }
            return max(1, best?.maxFrameRate ?? fps)
        }
        return min(max(fps, range.minFrameRate), range.maxFrameRate)
    }

    private func backCamera() -> AVCaptureDevice? {
        let types: [AVCaptureDevice.DeviceType] = [
            .builtInTripleCamera,
            .builtInDualWideCamera,
            .builtInDualCamera,
            .builtInWideAngleCamera
        ]

        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: types,
            mediaType: .video,
            position: .back
        )

        return discovery.devices.first
    }

    private func installPreviewLayerIfNeeded() {
        camView.clipsToBounds = true

        // 목업 앱에서는 빈 AVCaptureSession용 preview layer를 만들지 않습니다.
        // camView에는 test.mov를 재생하는 AVPlayerLayer만 연결합니다.
        previewLayer?.removeFromSuperlayer()
        previewLayer = nil
    }

    private func setupMockCameraIfNeeded() {
        if mockCameraSource.isConfigured {
            isMockCameraReady = true
            isCameraReady = true
            attachAndStartMockPreview()
            refreshMockReadyUI()
            return
        }

        guard let url = bundledMockVideoURL() else {
            isMockCameraReady = false
            isCameraReady = false
            refreshMockReadyUI()
            showCameraError("test.mov 파일을 앱 번들에서 찾을 수 없습니다. TopOut 폴더에 test.mov를 추가해주세요.")
            return
        }

        isMockCameraReady = false
        isCameraReady = false
        refreshMockReadyUI()

        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try await self.mockCameraSource.configure(url: url)
                self.isMockCameraReady = true
                // 목업 앱에서의 카메라 준비 상태는 test.mov 준비 상태와 같습니다.
                self.isCameraReady = true
                self.attachAndStartMockPreview()
                self.refreshMockReadyUI()
            } catch {
                self.isMockCameraReady = false
                self.isCameraReady = false
                self.refreshMockReadyUI()
                self.showCameraError("test.mov 준비 실패: \(error.localizedDescription)")
            }
        }
    }

    private func bundledMockVideoURL() -> URL? {
        // 파일 교체 시 확장자 대소문자가 달라도 찾을 수 있게 처리합니다.
        if let url = Bundle.main.url(forResource: "test", withExtension: "mov") {
            return url
        }
        if let url = Bundle.main.url(forResource: "test", withExtension: "MOV") {
            return url
        }
        return Bundle.main.urls(forResourcesWithExtension: nil, subdirectory: nil)?.first {
            $0.deletingPathExtension().lastPathComponent.lowercased() == "test"
                && $0.pathExtension.lowercased() == "mov"
        }
    }

    private func attachAndStartMockPreview() {
        mockCameraSource.setFrameRate(selectedRecordingFrameRate.fps)
        mockCameraSource.setZoomFactor(currentCameraZoomFactor)
        mockCameraSource.setOrientation(lockedVideoOrientation ?? currentVideoOrientation())
        mockCameraSource.updatePreviewFrame(camView.bounds)

        if mockCameraSource.previewLayer.superlayer !== camView.layer {
            camView.layer.addSublayer(mockCameraSource.previewLayer)
        }
        mockCameraSource.setPreviewVisible(!camView.isHidden)
        mockCameraSource.start()
        view.setNeedsLayout()
    }

    private func refreshMockReadyUI() {
        guard isViewLoaded else { return }
        readyBtn.isEnabled = mode == .camera
            && recState == .idle
            && isSAMReady
            && isMockCameraReady
    }

    func startCamera() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            if self.isMockCameraReady {
                self.attachAndStartMockPreview()
            } else {
                self.setupMockCameraIfNeeded()
            }
        }

        sessionQ.async { [weak self] in
            guard let self else { return }
            self.shouldRunBackgroundCamera = true
            if self.isBackgroundCameraConfigured, !self.session.isRunning {
                self.session.startRunning()
            }
        }

        // 권한이 이미 허용되어 있지만 최초 구성 시점이 늦어진 경우를 대비합니다.
        configureBackgroundCameraIfNeeded()
    }

    func stopCamera(completion: (() -> Void)? = nil) {
        DispatchQueue.main.async { [weak self] in
            self?.mockCameraSource.pause()
        }

        sessionQ.async { [weak self] in
            guard let self else {
                DispatchQueue.main.async { completion?() }
                return
            }

            self.shouldRunBackgroundCamera = false
            if self.session.isRunning {
                self.session.stopRunning()
            }
            DispatchQueue.main.async { completion?() }
        }
    }

    func updateCameraOrientationIfPossible() {
        let orientation = lockedVideoOrientation ?? currentVideoOrientation()
        setVideoOrientation(orientation, on: videoOutput.connection(with: .video))
        mockCameraSource.setOrientation(orientation)
        mockCameraSource.updatePreviewFrame(camView.bounds)
    }

    func lockCurrentCameraOrientation() {
        if lockedVideoOrientation == nil {
            lockedVideoOrientation = currentVideoOrientation()
        }
        updateCameraOrientationIfPossible()
        refreshSupportedOrientations()
    }

    func unlockCameraOrientation() {
        lockedVideoOrientation = nil
        updateCameraOrientationIfPossible()
        refreshSupportedOrientations()
    }

    func currentVideoOrientation() -> TopOutVideoOrientation {
        let deviceOrientation = UIDevice.current.orientation
        if deviceOrientation.isValidInterfaceOrientation {
            return TopOutVideoOrientation.from(
                deviceOrientation: deviceOrientation,
                fallbackIsLandscape: isLandscapeLayout
            )
        }

        if #available(iOS 26.0, *), let scene = view.window?.windowScene {
            return TopOutVideoOrientation.from(
                interfaceOrientation: scene.effectiveGeometry.interfaceOrientation,
                fallbackIsLandscape: isLandscapeLayout
            )
        }

        return isLandscapeLayout ? .landscapeRight : .portrait
    }

    private func setVideoOrientation(_ orientation: TopOutVideoOrientation, on connection: AVCaptureConnection?) {
        guard let connection else { return }

        if #available(iOS 17.0, *) {
            let angle = orientation.captureRotationAngle
            if connection.isVideoRotationAngleSupported(angle) {
                connection.videoRotationAngle = angle
            }
        } else if connection.isVideoOrientationSupported {
            connection.videoOrientation = orientation.legacyCaptureVideoOrientation
        }
    }

    @objc func onCameraPinch(_ gesture: UIPinchGestureRecognizer) {
        guard mode == .camera, recState == .idle else { return }

        switch gesture.state {
        case .began:
            pinchStartZoomFactor = currentCameraZoomFactor
        case .changed, .ended:
            let desired = pinchStartZoomFactor * gesture.scale
            let clamped = max(Cfg.minZoom, min(desired, 8.0))
            currentCameraZoomFactor = clamped
            mockCameraSource.setZoomFactor(clamped)
            updateZoomLabel()
        default:
            break
        }
    }

    func setCameraZoomFactor(_ factor: CGFloat, on camera: AVCaptureDevice) {
        sessionQ.async { [weak self] in
            guard let self else { return }
            self.applyCameraZoomLocked(factor, to: camera)
        }
    }

    private func applyCameraZoomLocked(_ factor: CGFloat, to camera: AVCaptureDevice) {
        let minZoom = camera.minAvailableVideoZoomFactor
        let maxZoom = min(camera.maxAvailableVideoZoomFactor, 8.0)
        let clamped = max(minZoom, min(factor, maxZoom))
        do {
            try camera.lockForConfiguration()
            camera.videoZoomFactor = clamped
            camera.unlockForConfiguration()
            DispatchQueue.main.async { [weak self] in
                self?.currentCameraZoomFactor = clamped
                self?.mockCameraSource.setZoomFactor(clamped)
                self?.updateZoomLabel()
            }
        } catch { }
    }

    @objc func onReady() {
        guard mode == .camera, recState == .idle, !isBusy else { return }
        guard isMockCameraReady else {
            showCameraError("test.mov가 아직 준비되지 않았습니다.")
            setupMockCameraIfNeeded()
            return
        }

        setLoading(true)
        readyBtn.isEnabled = false

        // 준비완료 화면과 동일한 비율/줌의 비디오 프레임을 그대로 사용합니다.
        // PhotoOutput은 비디오 프리뷰와 FOV가 달라질 수 있어 선택/마스크 좌표 오차를 만들 수 있습니다.
        applySelectedCaptureFormatIfPossible { [weak self] in
            guard let self else { return }
            self.lockCurrentCameraOrientation()

            // 탑홀드 마스킹이 꺼져 있고 자동 구간 탐지도 꺼져 있으면
            // 별도 기준 프레임 없이 바로 일반 녹화를 시작합니다.
            if !self.requiresTopHoldSelection && !self.isAutoClipDetectionEnabled {
                self.startRecordingWithoutSelection()
                return
            }

            // 자동 구간 탐지에는 매트 기준 프레임이 필요하고,
            // 탑홀드 마스킹에는 사용자가 탑홀드를 선택할 기준 프레임이 필요합니다.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) { [weak self] in
                guard let self else { return }
                self.videoFrameSink.captureCurrentFrame(
                    aspectRatio: self.activeRecordingAspect,
                    maxLongSide: CameraCfg.captureMaxLongSide
                ) { [weak self] image in
                    guard let self else { return }
                    guard let image else {
                        self.unlockCameraOrientation()
                        self.setLoading(false)
                        self.readyBtn.isEnabled = true
                        self.showCameraError("현재 카메라 프레임을 캡처하지 못했습니다.")
                        return
                    }

                    // 자동 구간 탐지가 켜져 있으면 사용자가 매트 경계를 직접 확인·보정합니다.
                    // 탑홀드 마스킹이 켜진 경우에는 같은 화면에서 탑홀드도 함께 선택합니다.
                    self.showSelectUI(with: image)
                }
            }
        }
    }
}
