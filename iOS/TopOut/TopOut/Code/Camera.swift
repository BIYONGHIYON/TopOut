import UIKit
import CoreImage
@preconcurrency import AVFoundation

enum CameraCfg {
    // SAM 입력은 1024이므로 캡처 원본을 너무 크게 들고 있을 필요가 없습니다.
    // 녹화 해상도와 무관하게 탑홀드 선택용 캡처 이미지는 이 크기로 축소합니다.
    static let captureMaxLongSide: CGFloat = 1600
}

extension HoldVC {
    func requestCameraAccess() {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        switch status {
        case .authorized:
            requestMicrophoneAccessThenSetupCamera()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    granted ? self?.requestMicrophoneAccessThenSetupCamera() : self?.showCameraDeniedMessage()
                }
            }
        default:
            showCameraDeniedMessage()
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
        guard AVCaptureDevice.authorizationStatus(for: .audio) == .authorized,
              configureAudioSessionForRecording() else {
            disableAudioCaptureForCurrentSession()
            return
        }

        // setupCamera() builds the initial graph. After an audio interruption, however,
        // disableAudioCaptureForCurrentSession() removes only the microphone branch while keeping
        // the video graph alive. Restore that branch explicitly for the next recording instead of
        // relying on setupCamera(), which intentionally returns early once the camera is ready.
        sessionQ.async { [weak self] in
            guard let self else { return }

            guard self.isCameraReady else {
                self.recorder.setAudioCaptureEnabled(true)
                DispatchQueue.main.async { self.isAudioCaptureEnabled = true }
                return
            }

            let existingAudioInputs = self.session.inputs.compactMap { $0 as? AVCaptureDeviceInput }
                .filter { $0.device.hasMediaType(.audio) }
            let hasAudioOutput = self.session.outputs.contains { $0 === self.audioOutput }

            if !existingAudioInputs.isEmpty, hasAudioOutput {
                self.recorder.setAudioCaptureEnabled(true)
                DispatchQueue.main.async { self.isAudioCaptureEnabled = true }
                return
            }

            self.session.beginConfiguration()
            existingAudioInputs.forEach { self.session.removeInput($0) }
            if hasAudioOutput {
                self.session.removeOutput(self.audioOutput)
            }

            var attachedAudioInput: AVCaptureDeviceInput?
            if let microphone = AVCaptureDevice.default(for: .audio),
               let audioInput = try? AVCaptureDeviceInput(device: microphone),
               self.session.canAddInput(audioInput) {
                self.session.addInput(audioInput)
                attachedAudioInput = audioInput
            }

            var didAttachAudio = false
            if attachedAudioInput != nil, self.session.canAddOutput(self.audioOutput) {
                self.session.addOutput(self.audioOutput)
                didAttachAudio = true
            } else if let attachedAudioInput {
                self.session.removeInput(attachedAudioInput)
            }
            self.session.commitConfiguration()

            self.recorder.setAudioCaptureEnabled(didAttachAudio)
            DispatchQueue.main.async {
                self.isAudioCaptureEnabled = didAttachAudio
            }
        }
    }

    func setupCamera() {
        // Swift 6 대응:
        // VideoFrameSink delegate conformance가 MainActor-isolated로 해석될 수 있으므로
        // delegate 연결은 setupCamera()의 MainActor 컨텍스트에서 처리합니다.
        // 실제 콜백은 아래 videoOutputQ에서 들어옵니다.
        videoOutput.setSampleBufferDelegate(videoFrameSink, queue: videoOutputQ)
        audioOutput.setSampleBufferDelegate(videoFrameSink, queue: audioOutputQ)
        videoFrameSink.setStreamReadinessHandler { [weak self] in
            guard let self else { return }
            self.isCameraStreamReady = true
            if self.mode == .camera,
               self.recState == .idle,
               !self.isBusy,
               self.launchImageView.isHidden {
                self.readyBtn.isEnabled = true
            }
        }

        let selectedResolution = selectedRecordingResolution
        let selectedFrameRate = selectedRecordingFrameRate
        let shouldAttemptAudioCapture = isAudioCaptureEnabled

        sessionQ.async { [weak self] in
            guard let self else { return }
            guard !self.isCameraReady else {
                self.startCamera()
                return
            }

            self.session.beginConfiguration()
            if self.session.canSetSessionPreset(.inputPriority) {
                self.session.sessionPreset = .inputPriority
            } else if self.session.canSetSessionPreset(.high) {
                self.session.sessionPreset = .high
            }

            self.session.inputs.forEach { self.session.removeInput($0) }
            self.session.outputs.forEach { self.session.removeOutput($0) }

            guard let camera = self.backCamera() else {
                self.session.commitConfiguration()
                DispatchQueue.main.async {
                    self.showCameraError("후면 카메라를 찾을 수 없습니다. 실제 기기에서 실행 중인지 확인해주세요.")
                }
                return
            }
            self.activeCameraDevice = camera
            let resolvedFormat = self.resolvedRecordingFormat(
                for: camera,
                requestedResolution: selectedResolution,
                requestedFrameRate: selectedFrameRate
            )
            let didApplyFormat = self.applyCaptureFormat(
                to: camera,
                resolution: resolvedFormat.resolution,
                frameRate: resolvedFormat.frameRate
            )
            if didApplyFormat {
                self.appliedRecordingResolution = resolvedFormat.resolution
                self.appliedRecordingFrameRate = resolvedFormat.frameRate
            }
            self.applyCameraZoomLocked(self.currentCameraZoomFactor, to: camera)

            do {
                let input = try AVCaptureDeviceInput(device: camera)
                guard self.session.canAddInput(input) else {
                    self.session.commitConfiguration()
                    DispatchQueue.main.async { self.showCameraError("카메라 입력을 세션에 추가할 수 없습니다.") }
                        return
                }
                self.session.addInput(input)
            } catch {
                self.session.commitConfiguration()
                DispatchQueue.main.async { self.showCameraError("카메라 입력 생성 실패: \(error.localizedDescription)") }
                return
            }

            var attachedAudioInput: AVCaptureDeviceInput?
            if shouldAttemptAudioCapture,
               AVCaptureDevice.authorizationStatus(for: .audio) == .authorized,
               let microphone = AVCaptureDevice.default(for: .audio) {
                do {
                    let audioInput = try AVCaptureDeviceInput(device: microphone)
                    if self.session.canAddInput(audioInput) {
                        self.session.addInput(audioInput)
                        attachedAudioInput = audioInput
                    }
                } catch {
                    // 통화 중이거나 마이크가 점유된 경우 입력 추가가 실패할 수 있습니다.
                    // 이 경우 오디오를 제외하고 비디오 세션 구성은 계속합니다.
                }
            }


            self.videoOutput.alwaysDiscardsLateVideoFrames = true
            self.videoOutput.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
            ]
            guard self.session.canAddOutput(self.videoOutput) else {
                self.session.commitConfiguration()
                DispatchQueue.main.async { self.showCameraError("비디오 출력을 세션에 추가할 수 없습니다.") }
                return
            }
            self.session.addOutput(self.videoOutput)

            var didAttachAudio = false
            if attachedAudioInput != nil, self.session.canAddOutput(self.audioOutput) {
                self.session.addOutput(self.audioOutput)
                didAttachAudio = true
            } else if let attachedAudioInput {
                // 입력과 출력이 모두 연결된 경우에만 오디오 캡처를 활성 상태로 봅니다.
                self.session.removeInput(attachedAudioInput)
            }

            self.session.commitConfiguration()
            self.isCameraReady = true
            self.recorder.setAudioCaptureEnabled(didAttachAudio)

            DispatchQueue.main.async {
                self.isAudioCaptureEnabled = didAttachAudio
                self.applyResolvedRecordingFormatIfNeeded(resolvedFormat)
                self.installPreviewLayerIfNeeded()
                self.updateCameraOrientationIfPossible()
                self.startCamera()
                self.hideLaunchScreenAndShowCameraUIIfNeeded()
                self.view.setNeedsLayout()
            }
        }
    }

    /// 통화 등으로 마이크가 다른 클라이언트에 점유되었을 때
    /// 오디오 입력/출력만 제거하고 카메라와 비디오 녹화는 유지합니다.
    func disableAudioCaptureForCurrentSession() {
        isAudioCaptureEnabled = false
        recorder.setAudioCaptureEnabled(false)

        sessionQ.async { [weak self] in
            guard let self else { return }

            let audioInputs = self.session.inputs.compactMap { $0 as? AVCaptureDeviceInput }
                .filter { $0.device.hasMediaType(.audio) }
            let hasAudioOutput = self.session.outputs.contains { $0 === self.audioOutput }

            if !audioInputs.isEmpty || hasAudioOutput {
                self.session.beginConfiguration()
                audioInputs.forEach { self.session.removeInput($0) }
                if hasAudioOutput {
                    self.session.removeOutput(self.audioOutput)
                }
                self.session.commitConfiguration()
            }

            // 오디오 인터럽션 해소 후 세션이 멈춰 있으면 비디오 전용으로 다시 시작합니다.
            if self.isCameraReady, !self.session.isRunning {
                self.session.startRunning()
            }

            do {
                try AVAudioSession.sharedInstance().setActive(
                    false,
                    options: .notifyOthersOnDeactivation
                )
            } catch {
                // 이미 다른 오디오 클라이언트가 세션을 소유한 경우 무시합니다.
            }
        }
    }

    func applySelectedCaptureFormatIfPossible(completion: (() -> Void)? = nil) {
        guard recState == .idle else {
            DispatchQueue.main.async { completion?() }
            return
        }

        let selectedResolution = selectedRecordingResolution
        let selectedFrameRate = selectedRecordingFrameRate

        sessionQ.async { [weak self] in
            guard let self, self.isCameraReady, let camera = self.activeCameraDevice else {
                DispatchQueue.main.async { completion?() }
                return
            }

            let resolvedFormat = self.resolvedRecordingFormat(
                for: camera,
                requestedResolution: selectedResolution,
                requestedFrameRate: selectedFrameRate
            )

            let needsFormatChange = self.appliedRecordingResolution != resolvedFormat.resolution ||
                self.appliedRecordingFrameRate != resolvedFormat.frameRate
            let needsZoomChange = abs(camera.videoZoomFactor - self.currentCameraZoomFactor) > 0.001

            if needsFormatChange {
                self.videoFrameSink.resetStreamReadiness()
                DispatchQueue.main.async { [weak self] in
                    guard let self else { return }
                    self.isCameraStreamReady = false
                    self.readyBtn.isEnabled = false
                }

                self.session.beginConfiguration()
                if self.applyCaptureFormat(
                    to: camera,
                    resolution: resolvedFormat.resolution,
                    frameRate: resolvedFormat.frameRate
                ) {
                    self.appliedRecordingResolution = resolvedFormat.resolution
                    self.appliedRecordingFrameRate = resolvedFormat.frameRate
                }
                if needsZoomChange {
                    self.applyCameraZoomLocked(self.currentCameraZoomFactor, to: camera)
                }
                self.session.commitConfiguration()
            } else if needsZoomChange {
                self.applyCameraZoomLocked(self.currentCameraZoomFactor, to: camera)
            }

            DispatchQueue.main.async { [weak self] in
                self?.applyResolvedRecordingFormatIfNeeded(resolvedFormat)
                self?.updateCameraOrientationIfPossible()
                completion?()
            }
        }
    }

    @discardableResult
    private func applyCaptureFormat(
        to camera: AVCaptureDevice,
        resolution: RecordingResolution,
        frameRate: RecordingFrameRate
    ) -> Bool {
        let preferredFPS = frameRate.fps
        guard let format = bestVideoFormat(for: camera, resolution: resolution, fps: preferredFPS) else {
            return false
        }

        let fps = clampedFPS(preferredFPS, in: format)
        let frameDuration = CMTime(value: 1, timescale: CMTimeScale(Int(fps.rounded())))

        do {
            try camera.lockForConfiguration()
            camera.activeFormat = format
            camera.activeVideoMinFrameDuration = frameDuration
            camera.activeVideoMaxFrameDuration = frameDuration
            camera.unlockForConfiguration()
            return true
        } catch {
            return false
        }
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
        guard let camera = activeCameraDevice ?? backCamera() else { return [:] }

        var result: [RecordingResolution: Set<RecordingFrameRate>] = [:]
        for resolution in RecordingResolution.allCases {
            let frameRates = Set(RecordingFrameRate.allCases.filter { frameRate in
                camera.formats.contains { format in
                    supports(format: format, resolution: resolution, fps: frameRate.fps)
                }
            })
            result[resolution] = frameRates
        }
        return result
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
        if previewLayer == nil {
            let layer = AVCaptureVideoPreviewLayer(session: session)
            layer.videoGravity = .resizeAspectFill
            camView.layer.insertSublayer(layer, at: 0)
            previewLayer = layer
        } else {
            previewLayer?.session = session
        }
        previewLayer?.frame = camView.bounds
    }

    func startCamera() {
        sessionQ.async { [weak self] in
            guard let self else { return }
            guard self.isCameraReady else {
                return
            }
            guard !self.session.isRunning else { return }

            self.videoFrameSink.resetStreamReadiness()
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.isCameraStreamReady = false
                self.readyBtn.isEnabled = false
            }
            self.session.startRunning()
        }
    }

    func stopCamera(completion: (() -> Void)? = nil) {
        sessionQ.async { [weak self] in
            guard let self else {
                DispatchQueue.main.async { completion?() }
                return
            }

            if self.session.isRunning {
                self.session.stopRunning()
            }

            // 결과 화면의 AVPlayer가 카메라/마이크 세션과 동시에 미디어 자원을
            // 잡지 않도록, 캡처 세션이 실제로 멈춘 뒤 다음 화면을 시작합니다.
            DispatchQueue.main.async { completion?() }
        }
    }

    func updateCameraOrientationIfPossible() {
        let orientation = lockedVideoOrientation ?? currentVideoOrientation()
        setVideoOrientation(orientation, on: previewLayer?.connection)
        setVideoOrientation(orientation, on: videoOutput.connection(with: .video))
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
        guard mode == .camera, recState == .idle, !isBusy else { return }
        guard let camera = activeCameraDevice else { return }

        switch gesture.state {
        case .began:
            pinchStartZoomFactor = currentCameraZoomFactor
        case .changed, .ended:
            let desired = pinchStartZoomFactor * gesture.scale
            setCameraZoomFactor(desired, on: camera)
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
                self?.updateZoomLabel()
            }
        } catch { }
    }

    @objc func onReady() {
        guard mode == .camera, recState == .idle, !isBusy else { return }
        guard isCameraReady, isCameraStreamReady else {
            showCameraError("카메라가 아직 준비되지 않았습니다.")
            return
        }

        isBusy = true
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

            // 탑홀드만 사용하는 경우 Ready 시점의 정지 이미지는 더 이상 필요하지 않습니다.
            // 라이브 선택 화면으로 바로 진입하고, 실제 SAM 입력은 탑홀드를 탭하는 매 순간
            // 새 비디오 프레임을 캡처해 생성합니다.
            if self.requiresTopHoldSelection && !self.isAutoClipDetectionEnabled {
                self.setLoading(false)
                self.showSelectUI(with: nil)
                return
            }

            // 자동 구간 탐지의 매트 경계만 Ready 직후 도착하는 첫 비디오 프레임으로 한 번
            // 확정합니다. 이 이미지는 MobileSAM 입력에만 쓰이며 사용자에게 표시하지 않습니다.
            self.videoFrameSink.captureStableFrame(
                aspectRatio: self.activeRecordingAspect,
                maxLongSide: CameraCfg.captureMaxLongSide,
                camera: self.activeCameraDevice
            ) { [weak self] image in
                guard let self else { return }
                guard let image else {
                    self.unlockCameraOrientation()
                    self.isBusy = false
                    self.setLoading(false)
                    self.readyBtn.isEnabled = self.isCameraStreamReady
                    self.showCameraError("현재 카메라 프레임을 캡처하지 못했습니다.")
                    return
                }

                // 자동 구간 탐지가 켜져 있으면 사용자가 라이브 프리뷰 위에서 매트 경계를
                // 직접 확인·보정합니다. 탑홀드도 같은 라이브 화면에서 선택합니다.
                self.showSelectUI(with: image)
            }
        }
    }
}


final class VideoFrameSink: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate {
    private let recorder: ContinuousRecorder
    private let motionDetector: MotionDetector
    private let stateQ = DispatchQueue(label: "topout.video.frame.sink.state")
    private let ciContext = CIContext(options: [.useSoftwareRenderer: false])
    private var isRecording = false
    private var isMotionDetectionEnabled = true
    private var videoFramesSinceReset = 0
    private var didNotifyStreamReady = false
    private var streamReadinessHandler: (() -> Void)?

    private enum FrameCapturePolicy {
        case nextFrame
        case stableCamera(AVCaptureDevice?)
    }

    private struct FrameCaptureRequest {
        let id: UUID
        let aspectRatio: CGFloat
        let maxLongSide: CGFloat
        let completion: (UIImage?) -> Void
        let policy: FrameCapturePolicy
        let requestedAt: TimeInterval
        var observedFrames: Int
        var consecutiveStableFrames: Int
    }

    private var pendingFrameCapture: FrameCaptureRequest?

    init(recorder: ContinuousRecorder, motionDetector: MotionDetector) {
        self.recorder = recorder
        self.motionDetector = motionDetector
        super.init()
    }

    func setStreamReadinessHandler(_ handler: @escaping () -> Void) {
        stateQ.async { [weak self] in
            self?.streamReadinessHandler = handler
        }
    }

    func resetStreamReadiness() {
        stateQ.async { [weak self] in
            self?.videoFramesSinceReset = 0
            self?.didNotifyStreamReady = false
        }
    }


    func setRecording(_ recording: Bool) {
        stateQ.async { [weak self] in
            self?.isRecording = recording
        }
    }

    func setMotionDetectionEnabled(_ enabled: Bool) {
        stateQ.async { [weak self] in
            self?.isMotionDetectionEnabled = enabled
        }
    }

    func captureCurrentFrame(
        aspectRatio: CGFloat,
        maxLongSide: CGFloat,
        completion: @escaping (UIImage?) -> Void
    ) {
        enqueueFrameCapture(
            aspectRatio: aspectRatio,
            maxLongSide: maxLongSide,
            policy: .nextFrame,
            completion: completion
        )
    }

    /// Waits for new post-Ready frames and prefers frames captured after camera 3A has settled.
    /// This prevents a cold-start or just-reconfigured frame from becoming MobileSAM input.
    func captureStableFrame(
        aspectRatio: CGFloat,
        maxLongSide: CGFloat,
        camera: AVCaptureDevice?,
        completion: @escaping (UIImage?) -> Void
    ) {
        enqueueFrameCapture(
            aspectRatio: aspectRatio,
            maxLongSide: maxLongSide,
            policy: .stableCamera(camera),
            completion: completion
        )
    }

    private func enqueueFrameCapture(
        aspectRatio: CGFloat,
        maxLongSide: CGFloat,
        policy: FrameCapturePolicy,
        completion: @escaping (UIImage?) -> Void
    ) {
        let request = FrameCaptureRequest(
            id: UUID(),
            aspectRatio: aspectRatio,
            maxLongSide: maxLongSide,
            completion: completion,
            policy: policy,
            requestedAt: ProcessInfo.processInfo.systemUptime,
            observedFrames: 0,
            consecutiveStableFrames: 0
        )

        // Keep preview running at the selected camera FPS, but do not retain every preview frame.
        stateQ.async { [weak self] in
            self?.pendingFrameCapture = request
        }

        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) { [weak self] in
            guard let self else { return }
            self.stateQ.async { [weak self] in
                guard let self, self.pendingFrameCapture?.id == request.id else { return }
                self.pendingFrameCapture = nil
                DispatchQueue.main.async { request.completion(nil) }
            }
        }
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer) else { return }
        let mediaType = CMFormatDescriptionGetMediaType(formatDescription)

        let state = stateQ.sync { () -> (
            recording: Bool,
            motionDetection: Bool,
            capture: FrameCaptureRequest?,
            streamReadyHandler: (() -> Void)?
        ) in
            var capture: FrameCaptureRequest?
            var readyHandler: (() -> Void)?

            if mediaType == kCMMediaType_Video {
                videoFramesSinceReset += 1
                if !didNotifyStreamReady, videoFramesSinceReset >= 6 {
                    didNotifyStreamReady = true
                    readyHandler = streamReadinessHandler
                }

                if var request = pendingFrameCapture {
                    request.observedFrames += 1
                    let shouldCapture: Bool
                    switch request.policy {
                    case .nextFrame:
                        shouldCapture = true
                    case let .stableCamera(camera):
                        let elapsed = ProcessInfo.processInfo.systemUptime - request.requestedAt
                        let cameraIsSettled = camera.map {
                            !$0.isAdjustingExposure && !$0.isAdjustingFocus && !$0.isAdjustingWhiteBalance
                        } ?? true
                        request.consecutiveStableFrames = cameraIsSettled
                            ? request.consecutiveStableFrames + 1
                            : 0
                        let hasFreshFrames = request.observedFrames >= 4 && elapsed >= 0.20
                        let hasStableCamera = request.consecutiveStableFrames >= 2
                        shouldCapture = (hasFreshFrames && hasStableCamera) || elapsed >= 0.90
                    }

                    if shouldCapture {
                        capture = request
                        pendingFrameCapture = nil
                    } else {
                        pendingFrameCapture = request
                    }
                }
            }
            return (isRecording, isMotionDetectionEnabled, capture, readyHandler)
        }
        let shouldRecord = state.recording
        let shouldRunMotionDetection = state.motionDetection

        if let readyHandler = state.streamReadyHandler {
            DispatchQueue.main.async(execute: readyHandler)
        }

        if mediaType == kCMMediaType_Audio {
            guard shouldRecord else { return }
            recorder.appendAudio(sampleBuffer)
            return
        }

        if let request = state.capture,
           let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) {
            DispatchQueue.global(qos: .userInitiated).async { [ciContext, pixelBuffer] in
                let image = autoreleasepool {
                    Self.makeCroppedImage(
                        from: pixelBuffer,
                        aspectRatio: request.aspectRatio,
                        maxLongSide: request.maxLongSide,
                        context: ciContext
                    )
                }
                DispatchQueue.main.async { request.completion(image) }
            }
        }

        guard shouldRecord else { return }
        recorder.append(sampleBuffer)
        if shouldRunMotionDetection {
            motionDetector.update(sampleBuffer)
        }
    }

    private static func makeCroppedImage(
        from pixelBuffer: CVPixelBuffer,
        aspectRatio: CGFloat,
        maxLongSide: CGFloat,
        context: CIContext
    ) -> UIImage? {
        let source = CIImage(cvPixelBuffer: pixelBuffer)
        let sourceExtent = source.extent.integral
        guard sourceExtent.width > 0, sourceExtent.height > 0, aspectRatio > 0 else { return nil }

        let currentAspect = sourceExtent.width / sourceExtent.height
        let crop: CGRect
        if currentAspect > aspectRatio {
            let width = sourceExtent.height * aspectRatio
            crop = CGRect(
                x: sourceExtent.midX - width * 0.5,
                y: sourceExtent.minY,
                width: width,
                height: sourceExtent.height
            )
        } else {
            let height = sourceExtent.width / aspectRatio
            crop = CGRect(
                x: sourceExtent.minX,
                y: sourceExtent.midY - height * 0.5,
                width: sourceExtent.width,
                height: height
            )
        }

        let normalized = source
            .cropped(to: crop.integral)
            .transformed(by: CGAffineTransform(translationX: -crop.integral.minX, y: -crop.integral.minY))

        let longSide = max(normalized.extent.width, normalized.extent.height)
        let scale = longSide > maxLongSide ? maxLongSide / longSide : 1
        let outputExtent = CGRect(
            x: 0,
            y: 0,
            width: floor(normalized.extent.width * scale),
            height: floor(normalized.extent.height * scale)
        )
        let output = normalized.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

        guard let cgImage = context.createCGImage(output, from: outputExtent) else { return nil }
        return UIImage(cgImage: cgImage, scale: 1, orientation: .up)
    }
}
