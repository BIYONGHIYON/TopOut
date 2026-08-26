import UIKit
@preconcurrency import AVFoundation

enum CameraCfg {
    // 분석용 이미지는 녹화 해상도와 무관하게 이 크기로 줄인다.
    static let captureMaxLongSide: CGFloat = 1600
}

extension HoldVC {
    // MARK: - 목업 입력 준비

    /// 카메라와 마이크 권한 없이 번들 영상을 입력으로 사용한다.
    func requestCameraAccess() {
        isAudioCaptureEnabled = false
        recorder.setAudioCaptureEnabled(false)
        setupCamera()
    }

    func prepareAudioSessionForCameraUse() {
        isAudioCaptureEnabled = false
        recorder.setAudioCaptureEnabled(false)
    }

    func disableAudioCaptureForCurrentSession() {
        isAudioCaptureEnabled = false
        recorder.setAudioCaptureEnabled(false)
    }

    func setupCamera() {
        if mockCameraSource != nil {
            startCamera()
            return
        }

        guard let videoURL = mockVideoURL() else {
            showCameraError("MockMedia/test.MOV 파일을 찾을 수 없습니다.")
            return
        }

        let source = MockCameraSource(videoURL: videoURL)
        source.setFrameHandler { [weak self] sampleBuffer in
            self?.videoFrameSink.processMockVideoBuffer(sampleBuffer)
        }

        videoFrameSink.setStreamReadinessHandler { [weak self] in
            guard let self else { return }
            self.isCameraStreamReady = true

            if self.recState == .idle,
               self.mode == .camera || self.mode == .select || self.mode == .processing {
                self.preloadMobileSAMAfterCameraStarts()
            }
            if self.mode == .camera,
               self.recState == .idle,
               !self.isBusy,
               self.launchImageView.isHidden {
                self.readyBtn.isEnabled = true
            }
        }

        mockCameraSource = source
        previewLayer = source.previewLayer
        camView.layer.insertSublayer(source.previewLayer, at: 0)
        source.previewLayer.frame = camView.bounds

        isCameraReady = true
        isAudioCaptureEnabled = false
        appliedRecordingResolution = selectedRecordingResolution
        appliedRecordingFrameRate = selectedRecordingFrameRate
        recorder.configure(
            resolution: selectedRecordingResolution,
            frameRate: selectedRecordingFrameRate,
            aspectRatio: selectedRecordingAspectRatio
        )

        startCamera()
        hideLaunchScreenAndShowCameraUIIfNeeded()
        view.setNeedsLayout()
    }

    private func mockVideoURL() -> URL? {
        Bundle.main.url(
            forResource: "test",
            withExtension: "MOV",
            subdirectory: "MockMedia"
        ) ?? Bundle.main.url(forResource: "test", withExtension: "MOV")
    }

    // MARK: - 녹화 설정

    func applySelectedCaptureFormatIfPossible(completion: (() -> Void)? = nil) {
        guard recState == .idle else {
            DispatchQueue.main.async { completion?() }
            return
        }

        appliedRecordingResolution = selectedRecordingResolution
        appliedRecordingFrameRate = selectedRecordingFrameRate
        recorder.configure(
            resolution: selectedRecordingResolution,
            frameRate: selectedRecordingFrameRate,
            aspectRatio: selectedRecordingAspectRatio
        )
        DispatchQueue.main.async { completion?() }
    }

    func supportedRecordingFrameRates() -> [RecordingResolution: Set<RecordingFrameRate>] {
        Dictionary(uniqueKeysWithValues: RecordingResolution.allCases.map { resolution in
            (resolution, Set(RecordingFrameRate.allCases))
        })
    }

    // MARK: - 재생 생명주기

    func startCamera() {
        guard UIApplication.shared.applicationState == .active,
              let source = mockCameraSource else { return }
        guard !source.isRunning else { return }

        videoFrameSink.resetStreamReadiness()
        isCameraStreamReady = false
        readyBtn.isEnabled = false
        source.start()
    }

    func stopCamera(completion: (() -> Void)? = nil) {
        mockCameraSource?.stop()
        DispatchQueue.main.async { completion?() }
    }

    func refreshReadyButtonAfterSessionRecovery() {
        guard mode == .camera,
              recState == .idle,
              !isBusy else { return }
        readyBtn.isEnabled = mockCameraSource?.isRunning == true && isCameraStreamReady
    }

    // MARK: - 방향과 확대

    func updateCameraOrientationIfPossible() {
        previewLayer?.frame = camView.bounds
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

    @objc func onCameraPinch(_ gesture: UIPinchGestureRecognizer) {
        guard mode == .camera, recState == .idle, !isBusy else { return }

        switch gesture.state {
        case .began:
            pinchStartZoomFactor = currentCameraZoomFactor
        case .changed, .ended:
            let desired = pinchStartZoomFactor * gesture.scale
            let clamped = max(1, min(desired, 4))
            currentCameraZoomFactor = clamped
            mockCameraSource?.setZoomFactor(clamped)
            updateZoomLabel()
        default:
            break
        }
    }

    // MARK: - 준비 완료

    @objc func onReady() {
        guard mode == .camera, recState == .idle, !isBusy else { return }
        guard isCameraReady, isCameraStreamReady else {
            showCameraError(L("error.camera_session_not_ready"))
            return
        }

        isBusy = true
        setLoading(true)
        readyBtn.isEnabled = false
        readyFlowRequestID &+= 1
        let requestID = readyFlowRequestID

        applySelectedCaptureFormatIfPossible { [weak self] in
            guard let self else { return }
            guard self.isReadyFlowValid(requestID) else { return }
            self.lockCurrentCameraOrientation()

            if !self.requiresTopHoldSelection && !self.isAutoClipDetectionEnabled {
                self.startRecordingWithoutSelection()
                return
            }

            if self.requiresTopHoldSelection && !self.isAutoClipDetectionEnabled {
                self.setLoading(false)
                self.showSelectUI(with: nil)
                return
            }

            self.videoFrameSink.captureStableFrame(
                aspectRatio: self.activeRecordingAspect,
                maxLongSide: CameraCfg.captureMaxLongSide
            ) { [weak self] image in
                guard let self else { return }
                guard self.isReadyFlowValid(requestID) else { return }
                guard let image else {
                    self.unlockCameraOrientation()
                    self.isBusy = false
                    self.setLoading(false)
                    self.readyBtn.isEnabled = self.isCameraStreamReady
                    self.showCameraError(L("camera.capture_failed"))
                    return
                }

                self.showSelectUI(with: image)
            }
        }
    }

    func cancelPendingReadyFlow() {
        readyFlowRequestID &+= 1
        guard mode == .camera, recState == .idle, isBusy else { return }
        isBusy = false
        setLoading(false)
        unlockCameraOrientation()
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
