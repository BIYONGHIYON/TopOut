import UIKit
import AVFoundation

extension HoldVC {
    // MARK: - 앱과 촬영 중단

    func registerRecordingStabilityObservers() {
        let center = NotificationCenter.default

        center.addObserver(
            self,
            selector: #selector(onAppDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(onAppDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(onCaptureSessionInterrupted(_:)),
            name: AVCaptureSession.wasInterruptedNotification,
            object: session
        )
        center.addObserver(
            self,
            selector: #selector(onCaptureSessionInterruptionEnded(_:)),
            name: AVCaptureSession.interruptionEndedNotification,
            object: session
        )
        center.addObserver(
            self,
            selector: #selector(onCaptureSessionRuntimeError(_:)),
            name: AVCaptureSession.runtimeErrorNotification,
            object: session
        )
    }

    @objc private func onAppDidEnterBackground() {
        cancelPendingReadyFlow()
        interruptSelectionAnalysis()
        stopCamera()
        releaseMobileSAMResources()

        if recState == .exporting {
            beginRecordingStopBackgroundTask()
            return
        }

        guard recState == .buffering else { return }
        requestAutomaticRecordingStop(notice: .background)
    }

    @objc private func onAppDidBecomeActive() {
        deliverPendingAutomaticStopResultIfPossible()

        guard pendingAutomaticStopSession == nil,
              pendingAutomaticStopNotice == nil,
              !isAutomaticStopInProgress else { return }

        if recState == .idle, mode == .camera || mode == .select {
            prepareAudioSessionForCameraUse()
            startCamera()
        }
    }

    @objc private func onCaptureSessionInterrupted(_ notification: Notification) {
        let reason: AVCaptureSession.InterruptionReason? = {
            guard let value = notification.userInfo?[AVCaptureSessionInterruptionReasonKey] as? NSNumber else {
                return nil
            }
            return AVCaptureSession.InterruptionReason(rawValue: value.intValue)
        }()

        Task { @MainActor [weak self] in
            guard let self else { return }

            if self.recState == .idle, self.mode == .camera {
                self.cancelPendingReadyFlow()
            }

            if reason == .audioDeviceInUseByAnotherClient {
                // 통화 중 마이크만 사용할 수 없는 경우에는 녹화를 종료하지 않고
                // 오디오 입력/출력만 제거한 뒤 영상 녹화를 계속합니다.
                self.disableAudioCaptureForCurrentSession()
                return
            }

            guard self.recState == .buffering else { return }
            self.requestAutomaticRecordingStop(notice: .captureInterruption)
        }
    }

    @objc private func onCaptureSessionInterruptionEnded(_ notification: Notification) {
        Task { @MainActor [weak self] in
            guard let self,
                  UIApplication.shared.applicationState == .active else { return }

            self.deliverPendingAutomaticStopResultIfPossible()

            if self.mode == .camera,
               self.recState == .idle,
               !self.isAutomaticStopInProgress {
                self.prepareAudioSessionForCameraUse()
                self.startCamera()
                self.refreshReadyButtonAfterSessionRecovery()
            } else if self.mode == .select, self.recState == .idle {
                self.startCamera()
            }
        }
    }

    @objc private func onCaptureSessionRuntimeError(_ notification: Notification) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            if self.recState == .idle, self.mode == .camera {
                self.cancelPendingReadyFlow()
                self.stopCamera { [weak self] in
                    guard let self,
                          UIApplication.shared.applicationState == .active,
                          self.recState == .idle,
                          self.mode == .camera else { return }
                    self.startCamera()
                }
                return
            }
            guard self.recState == .buffering else { return }
            self.requestAutomaticRecordingStop(notice: .sessionError)
        }
    }

    // MARK: - 저장 공간 확인

    func startRecordingStorageMonitor() {
        stopRecordingStorageMonitor()
        checkAvailableStorageForRecording()

        let timer = Timer(timeInterval: 60.0, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.checkAvailableStorageForRecording()
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        recordingStorageTimer = timer
    }

    func stopRecordingStorageMonitor() {
        recordingStorageTimer?.invalidate()
        recordingStorageTimer = nil
        isStorageCheckInFlight = false
    }

    private func checkAvailableStorageForRecording() {
        guard recState == .buffering,
              recordingElapsedStartDate != nil,
              !isAutomaticStopInProgress,
              !isStorageCheckInFlight else { return }

        isStorageCheckInFlight = true
        let minimumAvailableBytes = recordingStorageSafetyThresholdBytes()
        let volumeURL = FileManager.default.temporaryDirectory

        DispatchQueue.global(qos: .utility).async { [weak self] in
            let availableBytes: Int64?
            do {
                let values = try volumeURL.resourceValues(
                    forKeys: [.volumeAvailableCapacityForImportantUsageKey]
                )
                availableBytes = values.volumeAvailableCapacityForImportantUsage
            } catch {
                availableBytes = nil
            }

            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.isStorageCheckInFlight = false

                guard self.recState == .buffering,
                      !self.isAutomaticStopInProgress,
                      let availableBytes,
                      availableBytes <= minimumAvailableBytes else { return }

                self.requestAutomaticRecordingStop(
                    notice: .lowStorage(availableBytes: availableBytes)
                )
            }
        }
    }

    private func recordingStorageSafetyThresholdBytes() -> Int64 {
        let videoBitsPerSecond = selectedRecordingResolution.bitRate(
            for: selectedRecordingFrameRate,
            aspectRatio: selectedRecordingAspectRatio
        )
        let audioBitsPerSecond = 96_000
        let estimatedBytesForTwoMinutes = Int64(videoBitsPerSecond + audioBitsPerSecond) * 120 / 8
        let finalizationReserve = Int64(256 * 1_024 * 1_024)
        let absoluteMinimum = Int64(1_024 * 1_024 * 1_024)
        return max(absoluteMinimum, estimatedBytesForTwoMinutes + finalizationReserve)
    }

    // MARK: - 자동 종료

    func requestAutomaticRecordingStop(notice: RecordingStopNotice) {
        guard recState == .buffering, !isAutomaticStopInProgress else { return }
        isAutomaticStopInProgress = true
        pendingAutomaticStopNotice = notice

        let hadStartedWriting = recordingElapsedStartDate != nil

        cancelRecordingCountdown()
        stopRecordingElapsedTimer()
        stopRecordingStorageMonitor()
        hidePowerSavingRecordingUI()
        videoFrameSink.setRecording(false)

        stopCamera()

        manualStopBtn.isEnabled = false
        setRecordingStatusText(L("camera.finishing"))
        setLoading(true)
        beginRecordingStopBackgroundTask()

        guard hadStartedWriting else {
            motionDetector.stop()
            recorder.deleteAll()
            finishAutomaticStopWithoutRecording(notice: notice)
            return
        }

        recState = .exporting
        mode = .recording

        motionDetector.stopAndCaptureOpenRange { [weak self] openRange in
            guard let self, self.recState == .exporting else { return }
            if let openRange { self.appendDetectedClipRange(openRange) }
            self.recorder.stop { [weak self] result in
                guard let self else { return }
                self.setLoading(false)
                self.isAutomaticStopInProgress = false
                self.endRecordingStopBackgroundTask()

                switch result {
                case .success(let session):
                    self.pendingAutomaticStopSession = session
                    self.pendingAutomaticStopNotice = notice
                    self.deliverPendingAutomaticStopResultIfPossible()

                case .failure(let error):
                    if let recorderError = error as? RecorderError, case .noRecording = recorderError {
                        self.recorder.deleteAll()
                        self.recState = .idle
                        self.mode = .camera
                        self.unlockCameraOrientation()
                        self.showCameraUI()
                        self.pendingAutomaticStopSession = nil
                        self.pendingAutomaticStopNotice = RecordingStopNotice(
                            title: notice.title,
                            message: "\(notice.message)\n\n\(L("notice.no_video_frames"))"
                        )
                        self.restoreIdleCameraAfterAutomaticStopIfActive()
                        self.deliverPendingAutomaticStopResultIfPossible()
                        return
                    }

                    self.recorder.deleteAll()
                    self.recState = .idle
                    self.mode = .camera
                    self.unlockCameraOrientation()
                    self.showCameraUI()

                    self.pendingAutomaticStopSession = nil
                    self.pendingAutomaticStopNotice = RecordingStopNotice(
                        title: notice.title,
                        message: "\(notice.message)\n\n\(L("error.recording_finish"))"
                    )
                    self.restoreIdleCameraAfterAutomaticStopIfActive()
                    self.deliverPendingAutomaticStopResultIfPossible()
                }
            }
        }
    }

    private func finishAutomaticStopWithoutRecording(notice: RecordingStopNotice) {
        setLoading(false)
        isAutomaticStopInProgress = false
        endRecordingStopBackgroundTask()
        recState = .idle
        mode = .camera
        unlockCameraOrientation()
        showCameraUI()
        pendingAutomaticStopSession = nil
        pendingAutomaticStopNotice = RecordingStopNotice(
            title: notice.title,
            message: "\(notice.message)\n\n\(L("notice.recording_not_started"))"
        )
        restoreIdleCameraAfterAutomaticStopIfActive()
        deliverPendingAutomaticStopResultIfPossible()
    }

    private func restoreIdleCameraAfterAutomaticStopIfActive() {
        guard UIApplication.shared.applicationState == .active,
              view.window != nil,
              recState == .idle,
              mode == .camera else { return }

        // 녹화 종료 중 복귀해도 다음 촬영을 위해 오디오와 카메라 복구를 준비한다.
        prepareAudioSessionForCameraUse()
        startCamera()
    }

    func deliverPendingAutomaticStopResultIfPossible() {
        guard UIApplication.shared.applicationState == .active,
              view.window != nil,
              !isAutomaticStopInProgress else { return }

        if let session = pendingAutomaticStopSession {
            let notice = pendingAutomaticStopNotice
            pendingAutomaticStopSession = nil
            pendingAutomaticStopNotice = nil
            showReview(session: session, notice: notice)
            return
        }

        guard let notice = pendingAutomaticStopNotice,
              presentedViewController == nil else { return }

        pendingAutomaticStopNotice = nil
        presentPersistentRecordingNotice(notice, from: self)
    }

    func presentPersistentRecordingNotice(
        _ notice: RecordingStopNotice,
        from presenter: UIViewController
    ) {
        let alert = UIAlertController(
            title: notice.title,
            message: notice.message,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: L("common.ok"), style: .default))
        presenter.present(alert, animated: true)
    }

    // MARK: - 백그라운드 작업

    func beginRecordingStopBackgroundTask() {
        guard recordingStopBackgroundTask == .invalid else { return }

        recordingStopBackgroundTask = UIApplication.shared.beginBackgroundTask(
            withName: "TopOut.FinishRecording"
        ) { [weak self] in
            DispatchQueue.main.async {
                self?.endRecordingStopBackgroundTask()
            }
        }
    }

    func endRecordingStopBackgroundTask() {
        guard recordingStopBackgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(recordingStopBackgroundTask)
        recordingStopBackgroundTask = .invalid
    }
}
