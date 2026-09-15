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
        cancelAutomaticRecordingStart()
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
        releaseMobileSAMResources()
        showCameraUI()
        startCamera()
        startRecording()
    }

    @objc func onSelectTap(_ gesture: UITapGestureRecognizer) {
        guard requiresTopHoldSelection, mode == .select, !isBusy else { return }
        guard imgView.bounds.width > 0, imgView.bounds.height > 0 else { return }

        let viewPoint = gesture.location(in: imgView)
        guard imgView.bounds.contains(viewPoint) else { return }
        cancelAutomaticRecordingStart()
        let normalizedTap = CGPoint(
            x: max(0, min(1, viewPoint.x / imgView.bounds.width)),
            y: max(0, min(1, viewPoint.y / imgView.bounds.height))
        )

        selectionRequestID += 1
        let requestID = selectionRequestID
        // 탭할 때마다 이전 화각의 마스크를 버리고 새 선택을 시작한다.
        pendingOverlay = nil
        pendingTopBox = nil
        selectOverlayView.image = nil
        isBusy = true
        mode = .processing
        retakeBtn.isEnabled = false
        recordBtn.isEnabled = false
        selectHint.text = L("camera.analyzing_hold")
        setLoading(true)

        // 카메라 화각은 고정하되 탭마다 새 영상 프레임을 캡처한다.
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
                self.selectHint.text = L("camera.capture_failed_retry")
                self.setLoading(false)
                return
            }

            let imagePoint = CGPoint(
                x: normalizedTap.x * image.size.width,
                y: normalizedTap.y * image.size.height
            )
            self.frameImage = image
            let pixels = PixelMap(image: image)
            self.topHoldAnalysisToken?.cancel()
            let cancellationToken = AnalysisCancellationToken()
            self.topHoldAnalysisToken = cancellationToken
            self.scheduleTopHoldAnalysisTimeout(
                at: imagePoint,
                image: image,
                requestID: requestID,
                cancellationToken: cancellationToken
            )

            self.workQ.async { [weak self] in
                autoreleasepool {
                    // 촬영 배율이 이미지에 반영되어 있으므로 보정 좌표는 이미지 1배 기준을 사용한다.
                    self?.runSAM(
                        at: imagePoint,
                        image: image,
                        pixels: pixels,
                        zoom: 1,
                        requestID: requestID,
                        cancellationToken: cancellationToken
                    )
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
        recState = .exporting
        mode = .recording
        setRecordingStatusText(L("camera.processing_video"))
        manualStopBtn.isEnabled = false
        setLoading(true)
        beginRecordingStopBackgroundTask()

        motionDetector.stopAndCaptureOpenRange { [weak self] openRange in
            guard let self, self.recState == .exporting else { return }
            if let openRange { self.appendDetectedClipRange(openRange) }
            self.recorder.stop { [weak self] result in
                guard let self else { return }
                self.setLoading(false)
                self.endRecordingStopBackgroundTask()
                switch result {
                case .success(let session):
                    self.showReview(session: session)
                case .failure:
                    self.recorder.deleteAll()
                    self.recState = .idle
                    self.mode = .camera
                    self.unlockCameraOrientation()
                    self.showCameraUI()
                    self.pendingAutomaticStopNotice = RecordingStopNotice(title: L("error.recording_finish"), message: L("error.recording_finish"))
                    self.deliverPendingAutomaticStopResultIfPossible()
                }
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
