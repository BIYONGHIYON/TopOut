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
        guard let image = frameImage else { return }

        let viewPoint = gesture.location(in: imgView)
        guard let imgPoint = imagePoint(from: viewPoint, image: image) else { return }

        let zoom = max(scrollView.zoomScale, Cfg.minZoom)
        selectionRequestID += 1
        let requestID = selectionRequestID
        isBusy = true
        mode = .processing
        retakeBtn.isEnabled = false
        recordBtn.isEnabled = false
        selectHint.text = "탑홀드 분석 중..."
        setLoading(true)

        workQ.async { [weak self] in
            autoreleasepool {
                self?.runSAM(at: imgPoint, image: image, zoom: zoom, requestID: requestID)
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
