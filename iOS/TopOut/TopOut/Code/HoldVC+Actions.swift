import UIKit

extension HoldVC {
    @objc func onRetake() {
        guard recState == .idle else { return }
        unlockCameraOrientation()
        clearSelectionCache(clearPending: true)
        showCameraUI()
        startCamera()
    }

    @objc func onRecordStart() {
        guard recState == .idle, let referenceImage = frameImage else { return }
        lockCurrentCameraOrientation()

        let selectedMatLine = matLinePoints
        let selectedOverlay = pendingOverlay
        let normalizedTopBox = pendingTopBox.map { normalizedMaskBox($0, image: referenceImage) }

        if requiresTopHoldSelection {
            guard let selectedOverlay, let normalizedTopBox else { return }
            overlayView.image = selectedOverlay

            if isAutoClipDetectionEnabled, !selectedMatLine.isEmpty {
                motionDetector.configure(
                    matLine: selectedMatLine,
                    topBox: normalizedTopBox,
                    finishReference: .closestTopHold,
                    activeCropRect: activeSourceCropRect
                )
            } else {
                motionDetector.stop()
            }
        } else {
            overlayView.image = nil

            if isAutoClipDetectionEnabled, !selectedMatLine.isEmpty {
                motionDetector.configure(
                    matLine: selectedMatLine,
                    topBox: nil,
                    finishReference: .highestPosePoint,
                    activeCropRect: activeSourceCropRect
                )
            } else {
                motionDetector.stop()
            }
        }

        recorder.configure(
            resolution: selectedRecordingResolution,
            frameRate: selectedRecordingFrameRate,
            aspectRatio: selectedRecordingAspectRatio
        )

        clearSelectionCache(clearPending: true)
        showCameraUI()
        startCamera()
        startRecording()
    }

    @objc func onSelectTap(_ gesture: UITapGestureRecognizer) {
        guard requiresTopHoldSelection, mode == .select, !isBusy else { return }
        guard imgView.bounds.width > 0, imgView.bounds.height > 0 else { return }

        let viewPoint = gesture.location(in: imgView)
        guard imgView.bounds.contains(viewPoint) else { return }
        let normalizedTap = CGPoint(
            x: max(0, min(1, viewPoint.x / imgView.bounds.width)),
            y: max(0, min(1, viewPoint.y / imgView.bounds.height))
        )

        selectionRequestID += 1
        let requestID = selectionRequestID
        // Every tap represents a new live-camera selection. Discard the old FOV-dependent mask
        // immediately so camera movement between taps can never leave a stale overlay enabled.
        pendingOverlay = nil
        pendingTopBox = nil
        selectOverlayView.image = nil
        isBusy = true
        mode = .processing
        retakeBtn.isEnabled = false
        recordBtn.isEnabled = false
        selectHint.text = "현재 프레임에서 탑홀드 분석 중..."
        setLoading(true)

        // Capture a new video frame for every tap so MobileSAM follows any physical camera-angle
        // change while the Ready-time lens/zoom FOV remains locked.
        videoFrameSink.captureCurrentFrame(
            aspectRatio: activeRecordingAspect,
            maxLongSide: CameraCfg.captureMaxLongSide
        ) { [weak self] image in
            guard let self else { return }
            guard requestID == self.selectionRequestID, self.mode == .processing else { return }
            guard let image, image.size.width > 0, image.size.height > 0 else {
                self.isBusy = false
                self.mode = .select
                self.retakeBtn.isEnabled = true
                self.recordBtn.isEnabled = false
                self.selectHint.text = "현재 카메라 프레임을 캡처하지 못했습니다. 다시 탭해주세요"
                self.setLoading(false)
                return
            }

            let imagePoint = CGPoint(
                x: normalizedTap.x * image.size.width,
                y: normalizedTap.y * image.size.height
            )
            self.frameImage = image
            self.pixels = PixelMap(image: image)
            self.emb = nil

            self.workQ.async { [weak self] in
                autoreleasepool {
                    // No post-capture editor zoom is applied. The Ready-time camera zoom is already baked
                    // into this fresh image, so correction distances use the native 1x image scale.
                    self?.runSAM(at: imagePoint, image: image, zoom: 1, requestID: requestID)
                }
            }
        }
    }


    @objc func onManualStop() {
        guard recState == .buffering else { return }
        cancelRecordingCountdown()
        stopRecordingElapsedTimer()
        stopRecordingStorageMonitor()
        hidePowerSavingRecordingUI()
        videoFrameSink.setRecording(false)
        if let openRange = motionDetector.activeClipRangeForRecordingStop() {
            appendDetectedClipRange(openRange)
        }
        motionDetector.stop()
        recState = .exporting
        mode = .recording
        setRecordingStatusText("영상 정리 중...")
        manualStopBtn.isEnabled = false
        setLoading(true)
        beginRecordingStopBackgroundTask()

        recorder.stop { [weak self] result in
            guard let self else { return }
            self.setLoading(false)
            self.endRecordingStopBackgroundTask()

            switch result {
            case .success(let session):
                self.showReview(session: session)
            case .failure(let error):
                self.recorder.deleteAll()
                self.recState = .idle
                self.mode = .camera
                self.unlockCameraOrientation()
                self.showCameraUI()
                self.pendingAutomaticStopNotice = RecordingStopNotice(
                    title: "녹화 종료 실패",
                    message: error.localizedDescription
                )
                self.deliverPendingAutomaticStopResultIfPossible()
            }
        }
    }


    @objc func onToggleLivePreviewDuringRecording() {
        guard recState == .buffering else { return }

        isDeveloperModeEnabled.toggle()
        saveUserSettings()
        showCameraUI()

        let elapsed = Date().timeIntervalSince(recordingElapsedStartDate ?? Date())
        if isDeveloperModeEnabled {
            updateManualStopButton(elapsed: elapsed)
        } else {
            showPowerSavingRecording(elapsed: elapsed)
        }

        updateLivePreviewToggleButton()
        view.setNeedsLayout()
    }

}
