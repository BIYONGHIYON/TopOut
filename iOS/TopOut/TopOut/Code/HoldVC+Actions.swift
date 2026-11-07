import UIKit

extension HoldVC {
    @objc func onRetake() {
        guard recState == .idle, mode == .select else { return }
        cancelMatMeasurement()
        isBusy = false
        unlockCameraOrientation()
        showCameraUI()
        startCamera()
    }

    @objc func onRecordStart() {
        guard recState == .idle else { return }
        if isAutoClipDetectionEnabled {
            // 자동 구간 탐지는 준비 완료로 측정하고 보정한 경계선이 있어야 녹화를 시작한다.
            guard mode == .select, !isBusy, matLinePoints.count >= 2 else { return }
        } else {
            guard mode == .camera else { return }
        }
        cancelMatMeasurement()
        // 녹화 시작 시점의 화면 방향을 녹화가 끝날 때까지 유지한다.
        lockCurrentCameraOrientation()

        let selectedMatLine = matLinePoints

        // 탑홀드는 결과 화면에서만 고르므로 녹화 중에는 가장 높이 올라간 순간을 완등으로 본다.
        if isAutoClipDetectionEnabled {
            motionDetector.configure(
                matLine: selectedMatLine,
                topBox: nil,
                finishReference: .highestPosePoint,
                activeCropRect: activeSourceCropRect
            )
        } else {
            motionDetector.stop()
        }

        recorder.configure(
            resolution: selectedRecordingResolution,
            frameRate: selectedRecordingFrameRate,
            aspectRatio: selectedRecordingAspectRatio
        )

        matLineView.clear()
        // 녹화 중에는 경계선을 자동으로 다시 재지 않으므로 녹화 메모리를 위해 모델을 내린다.
        releaseMobileSAMResources()
        showCameraUI()
        startCamera()
        startRecording()
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
