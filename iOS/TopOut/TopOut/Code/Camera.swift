import UIKit
import CoreImage
@preconcurrency import AVFoundation

enum CameraCfg {
    // 분석용 캡처 이미지는 녹화 해상도와 무관하게 이 크기로 줄인다.
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
            configureAudioSessionThenSetupCamera(microphoneGranted: true)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .audio) { [weak self] granted in
                DispatchQueue.main.async {
                    self?.configureAudioSessionThenSetupCamera(microphoneGranted: granted)
                }
            }
        default:
            // 마이크 권한이 없어도 영상 녹화는 계속 가능하게 둡니다.
            isAudioCaptureEnabled = false
            recorder.setAudioCaptureEnabled(false)
            setupCamera()
        }
    }

    /// 권한만 확인하지 않고 오디오 세션을 실제로 활성화할 수 있는지도 확인합니다.
    /// 통화 중에는 setActive가 실패할 수 있으며, 이때는 영상 전용으로 구성합니다.
    /// 오디오 세션 활성화는 주 스레드를 막지 않도록 촬영 세션 큐에서 진행합니다.
    private func configureAudioSessionThenSetupCamera(microphoneGranted: Bool) {
        sessionQ.async { [weak self] in
            let audioActivated = microphoneGranted && AudioSessionQueue.activateForRecording()
            DispatchQueue.main.async {
                guard let self else { return }
                self.isAudioCaptureEnabled = audioActivated
                self.recorder.setAudioCaptureEnabled(audioActivated)
                self.setupCamera()
            }
        }
    }

    func prepareAudioSessionForCameraUse() {
        guard AVCaptureDevice.authorizationStatus(for: .audio) == .authorized else {
            disableAudioCaptureForCurrentSession()
            return
        }

        // 오디오 중단 뒤 영상 연결은 유지하고 다음 녹화 전에 마이크 연결만 복구한다.
        // 세션 큐에서 오디오 세션을 활성화해 앞선 마이크 해제·비활성화 뒤에 순서대로 실행한다.
        sessionQ.async { [weak self] in
            guard let self else { return }

            guard AudioSessionQueue.activateForRecording() else {
                DispatchQueue.main.async { self.disableAudioCaptureForCurrentSession() }
                return
            }

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
        // Swift 6 격리를 위해 대리자 연결은 주 실행자에서, 콜백은 전용 큐에서 처리한다.
        videoOutput.setSampleBufferDelegate(videoFrameSink, queue: videoOutputQ)
        audioOutput.setSampleBufferDelegate(videoFrameSink, queue: audioOutputQ)
        videoFrameSink.setStreamReadinessHandler { [weak self] in
            guard let self else { return }
            self.isCameraStreamReady = true
            if self.recState == .idle, self.mode == .camera || self.mode == .select {
                // 준비 완료 분석이 5초 제한 안에 끝나도록 자동 구간 탐지를 쓸 때만 미리 불러온다.
                // 녹화 중에는 해제한 모델을 다시 불러오지 않는다.
                if self.isAutoClipDetectionEnabled { self.preloadMobileSAMAfterCameraStarts() }
                self.refreshRecordButtonAvailability()
            }
        }

        let selectedResolution = selectedRecordingResolution
        let selectedFrameRate = selectedRecordingFrameRate
        let shouldAttemptAudioCapture = isAudioCaptureEnabled

        sessionQ.async { [weak self] in
            guard let self else { return }
            guard !self.isCameraReady else {
                DispatchQueue.main.async { [weak self] in
                    self?.startCamera()
                }
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
                    self.showCameraError(L("error.rear_camera"))
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
                    DispatchQueue.main.async { self.showCameraError(L("error.camera_configuration")) }
                        return
                }
                self.session.addInput(input)
            } catch {
                self.session.commitConfiguration()
                DispatchQueue.main.async { self.showCameraError(L("error.camera_start")) }
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
                DispatchQueue.main.async { self.showCameraError(L("error.camera_configuration")) }
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
        let shouldKeepVideoRunning = UIApplication.shared.applicationState == .active
            && recState != .exporting
            && recState != .reviewing

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
            if shouldKeepVideoRunning, self.isCameraReady, !self.session.isRunning {
                self.session.startRunning()
            }

            AudioSessionQueue.deactivateNotifyingOthers()
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
                    self.disableCameraStartButtons()
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
        // 앱이 비활성 상태면 늦게 끝난 카메라 설정으로 촬영을 다시 시작하지 않는다.
        guard UIApplication.shared.applicationState == .active else { return }

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
                self.disableCameraStartButtons()
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

    func refreshReadyButtonAfterSessionRecovery() {
        // 같은 세션 큐에서 실행 상태를 확인해 새 시작에만 프레임 준비 조건을 적용한다.
        sessionQ.async { [weak self] in
            guard let self else { return }
            let isRunning = self.session.isRunning
            DispatchQueue.main.async { [weak self] in
                guard let self,
                      self.mode == .camera,
                      self.recState == .idle else { return }
                if isRunning {
                    self.refreshRecordButtonAvailability()
                } else {
                    self.disableCameraStartButtons()
                }
            }
        }
    }

    func updateCameraOrientationIfPossible() {
        let orientation = lockedVideoOrientation ?? currentVideoOrientation()
        // 프리뷰는 화면에 수평으로 보이는 각도를 따로 쓴다. iPhone Duo처럼 접고 펼 때 창 크기와
        // 디스플레이가 바뀌는 기기에서도 AVFoundation이 계산한 값이 정확하다.
        if #available(iOS 17.0, *),
           let previewAngle = coordinatedPreviewRotationAngle(),
           let connection = previewLayer?.connection,
           connection.isVideoRotationAngleSupported(previewAngle) {
            connection.videoRotationAngle = previewAngle
        } else {
            setVideoOrientation(orientation, on: previewLayer?.connection)
        }
        setVideoOrientation(orientation, on: videoOutput.connection(with: .video))
    }

    /// 현재 카메라와 프리뷰 레이어 기준의 회전 조정자를 준비한다. 렌즈 전환으로 기기가 바뀌면 새로 만든다.
    @available(iOS 17.0, *)
    private func rotationCoordinator() -> AVCaptureDevice.RotationCoordinator? {
        guard let device = activeCameraDevice, let layer = previewLayer else { return nil }
        if let existing = cameraRotationCoordinator as? AVCaptureDevice.RotationCoordinator,
           existing.device === device,
           existing.previewLayer === layer {
            return existing
        }
        let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: layer)
        cameraRotationCoordinator = coordinator
        return coordinator
    }

    private func coordinatedPreviewRotationAngle() -> CGFloat? {
        guard #available(iOS 17.0, *), let coordinator = rotationCoordinator() else { return nil }
        return coordinator.videoRotationAngleForHorizonLevelPreview
    }

    /// 녹화·분석 프레임이 수평이 되도록 AVFoundation이 계산한 각도를 TopOut 방향으로 바꾼다.
    /// 일반 iPhone에서는 기존 기기 방향 매핑과 같은 값(세로 90°, 가로 0°/180°)이 나온다.
    private func coordinatedCaptureOrientation() -> TopOutVideoOrientation? {
        guard #available(iOS 17.0, *), let coordinator = rotationCoordinator() else { return nil }
        let angle = coordinator.videoRotationAngleForHorizonLevelCapture
        let normalized = (Int((angle / 90).rounded()) % 4 + 4) % 4
        switch normalized {
        case 0: return .landscapeRight
        case 1: return .portrait
        case 2: return .landscapeLeft
        default: return .portraitUpsideDown
        }
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
        if let coordinated = coordinatedCaptureOrientation() {
            return coordinated
        }

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
        guard mode == .camera, recState == .idle, !isBusy, isAutoClipDetectionEnabled else { return }
        guard isCameraReady, isCameraStreamReady else {
            showCameraError(L("error.camera_session_not_ready"))
            return
        }

        isBusy = true
        setLoading(true)
        readyBtn.isEnabled = false
        readyFlowRequestID &+= 1
        let requestID = readyFlowRequestID

        // 준비 완료 화면과 같은 비율·줌의 비디오 프레임을 쓴다. 사진 출력은 영상 화각과 다를 수 있다.
        applySelectedCaptureFormatIfPossible { [weak self] in
            guard let self, self.isReadyFlowValid(requestID) else { return }
            self.lockCurrentCameraOrientation()

            // 매트 경계는 준비 완료 직후 노출·초점이 안정된 비디오 프레임으로 한 번 측정한다.
            // 이 이미지는 MobileSAM 입력에만 쓰고, 보정은 실시간 화면 위에서 한다.
            self.videoFrameSink.captureStableFrame(
                aspectRatio: self.activeRecordingAspect,
                maxLongSide: CameraCfg.captureMaxLongSide,
                camera: self.activeCameraDevice
            ) { [weak self] image in
                guard let self, self.isReadyFlowValid(requestID) else { return }
                guard let image else {
                    self.unlockCameraOrientation()
                    self.isBusy = false
                    self.setLoading(false)
                    self.refreshRecordButtonAvailability()
                    self.showCameraError(L("camera.capture_failed"))
                    return
                }
                self.isBusy = false
                self.showSelectUI(with: image)
            }
        }
    }

    /// 준비 완료 프레임 캡처를 멈춘다. 백그라운드 전환과 카메라 중단에서 쓴다.
    func cancelPendingReadyFlow() {
        readyFlowRequestID &+= 1
        guard mode == .camera, recState == .idle, isBusy else { return }
        isBusy = false
        setLoading(false)
        unlockCameraOrientation()
        refreshRecordButtonAvailability()
    }

    private func isReadyFlowValid(_ requestID: Int) -> Bool {
        requestID == readyFlowRequestID &&
            UIApplication.shared.applicationState == .active &&
            view.window != nil &&
            mode == .camera &&
            recState == .idle &&
            isBusy
    }
}
