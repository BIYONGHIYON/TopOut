import UIKit
import CoreMedia
import Photos


private final class PhotoLibrarySaveCompletionBox: @unchecked Sendable {
    let completion: (Result<Void, Error>) -> Void

    init(_ completion: @escaping (Result<Void, Error>) -> Void) {
        self.completion = completion
    }
}

extension HoldVC {
    // MARK: Recording

    func startRecording() {
        clipTimes = ClipTimes()
        cancelRecordingCountdown()
        stopRecordingElapsedTimer()
        stopRecordingStorageMonitor()
        pendingAutomaticStopSession = nil
        pendingAutomaticStopNotice = nil
        isAutomaticStopInProgress = false
        recState = .buffering
        recordingStatusMessage = nil

        videoFrameSink.setMotionDetectionEnabled(isAutoClipDetectionEnabled)
        showCameraUI()
        manualStopBtn.isEnabled = false

        if isAutoClipDetectionEnabled {
            motionDetector.resetForRecording()
        } else {
            motionDetector.stop()
        }

        videoFrameSink.setRecording(true)
        startRecorderAfterCountdown()
    }

    func startRecorderAfterCountdown() {
        let seconds = max(0, Int(ceil(Cfg.motionCountdownSeconds)))

        for i in 0...seconds {
            let item = DispatchWorkItem { [weak self] in
                guard let self, case .buffering = self.recState else { return }

                let remain = seconds - i
                if remain > 0 {
                    let countdownText = self.isAutoClipDetectionEnabled
                        ? "\(remain)초 후 녹화 시작 · 매트 보정 중"
                        : "\(remain)초 후 녹화 시작"
                    self.setRecordingStatusText(countdownText)
                    if !self.isDeveloperModeEnabled {
                        self.showPowerSavingCountdown(remain)
                    }
                    self.manualStopBtn.isEnabled = false
                    return
                }

                self.recorder.start()
                self.manualStopBtn.isEnabled = true
                self.setRecordingStatusText(self.defaultActiveRecordingStatusText())
                self.startRecordingElapsedTimer()
                self.startRecordingStorageMonitor()
                if self.isDeveloperModeEnabled {
                    self.showCameraUI()
                } else {
                    self.showPowerSavingRecording(elapsed: 0)
                }
            }
            recordingCountdownWorkItems.append(item)
            DispatchQueue.main.asyncAfter(deadline: .now() + Double(i), execute: item)
        }
    }

    func cancelRecordingCountdown() {
        recordingCountdownWorkItems.forEach { $0.cancel() }
        recordingCountdownWorkItems.removeAll()
    }

    func startRecordingElapsedTimer() {
        stopRecordingElapsedTimer()
        recordingElapsedStartDate = Date()

        let timer = Timer(timeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, case .buffering = self.recState else { return }
                let elapsed = Date().timeIntervalSince(self.recordingElapsedStartDate ?? Date())
                if self.isDeveloperModeEnabled {
                    self.updateManualStopButton(elapsed: elapsed)
                } else {
                    self.showPowerSavingRecording(elapsed: elapsed)
                }
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        recordingElapsedTimer = timer
    }

    func stopRecordingElapsedTimer() {
        recordingElapsedTimer?.invalidate()
        recordingElapsedTimer = nil
        recordingElapsedStartDate = nil
    }

    func showReview(
        session: ContinuousRecorder.RecordingSession,
        notice: RecordingStopNotice? = nil
    ) {
        guard UIApplication.shared.applicationState == .active, view.window != nil else {
            pendingAutomaticStopSession = session
            pendingAutomaticStopNotice = notice
            recState = .exporting
            mode = .recording
            return
        }

        stopRecordingElapsedTimer()
        stopRecordingStorageMonitor()
        hidePowerSavingRecordingUI()
        recState = .reviewing
        mode = .reviewing

        // AVCaptureSession.stopRunning()은 동기 작업이지만 전용 sessionQ에서 실행됩니다.
        // 실제 종료가 끝나기 전에 AVPlayer를 만들면 일부 기기에서 재생 버튼을 눌러도
        // 플레이어가 waiting 상태에 머무를 수 있으므로, 종료 완료 후 결과 화면을 띄웁니다.
        stopCamera { [weak self] in
            guard let self, self.recState == .reviewing else { return }
            self.presentReviewAfterCameraStopped(session: session, notice: notice)
        }
    }

    private func presentReviewAfterCameraStopped(
        session: ContinuousRecorder.RecordingSession,
        notice: RecordingStopNotice?
    ) {
        let total = session.duration
        let detectedRanges = detectedClipRanges(for: session)
        let detectedRange = detectedRanges.last
        let defaultStart = detectedRange?.startSeconds ?? 0
        let defaultEnd = detectedRange?.endSeconds ?? total

        let hasValidDetectedRange = defaultEnd > defaultStart
        let safeStart = hasValidDetectedRange ? defaultStart : 0
        let safeEnd = hasValidDetectedRange ? defaultEnd : total

        let vc = ReviewVC(
            recordingURL: session.url,
            duration: total,
            defaultStart: safeStart,
            defaultEnd: safeEnd,
            detectedRanges: detectedRanges,
            topHoldOverlayImage: isTopHoldMaskingEnabled ? overlayView.image : nil,
            includeTopHoldOverlayByDefault: false,
            topHoldOverlayToggleLocked: false,
            autoClipDetectionEnabled: isAutoClipDetectionEnabled,
            helpEnabled: isHelpEnabled,
            recordingAspectRatio: selectedRecordingAspectRatio
        )
        vc.modalPresentationStyle = .fullScreen

        vc.onClose = { [weak self] in
            guard let self else { return }
            self.cancelRecordingCountdown()
            self.stopRecordingElapsedTimer()
            self.stopRecordingStorageMonitor()
            self.hidePowerSavingRecordingUI()
            self.videoFrameSink.setRecording(false)
            self.motionDetector.stop()
            self.recorder.deleteAll()
            self.recState = .idle
            self.mode = .camera
            self.overlayView.image = nil
            self.unlockCameraOrientation()
            self.showCameraUI()
            self.view.layoutIfNeeded()
            self.updateCameraOrientationIfPossible()
            self.prepareAudioSessionForCameraUse()
            self.startCamera()
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.layoutUI()
                self.updateCameraOrientationIfPossible()
            }
        }

        vc.onRetake = { [weak self] in
            self?.restartRecordingWithExistingSelection()
        }

        vc.onSaveTrim = { [weak self, weak vc] startSeconds, endSeconds, includeTopHoldOverlay in
            guard let self else { return }
            vc?.setSaving(true)
            vc?.setStatus(includeTopHoldOverlay ? "탑홀드 마스크를 입히는 중..." : "선택 구간 자르는 중...")

            self.recorder.exportTrimmedClip(
                fromOffset: startSeconds,
                toOffset: endSeconds,
                topHoldOverlayImage: includeTopHoldOverlay ? self.overlayView.image : nil
            ) { result in
                switch result {
                case .success(let url):
                    vc?.setStatus("사진 앱에 저장하는 중...")
                    self.saveVideoToPhotoLibrary(url) { saveResult in
                        vc?.setSaving(false)
                        switch saveResult {
                        case .success:
                            vc?.setStatus("구간 선택된 동영상을 저장했습니다")
                        case .failure(let error):
                            vc?.setStatus("사진 앱 저장 실패: \(error.localizedDescription)")
                        }
                    }
                    return
                case .failure(let error):
                    vc?.setSaving(false)
                    vc?.setStatus("구간 저장 실패: \(error.localizedDescription)")
                }
            }
        }

        present(vc, animated: true) { [weak self, weak vc] in
            guard let self, let vc, let notice else { return }
            self.presentPersistentRecordingNotice(notice, from: vc)
        }
    }

    private func saveVideoToPhotoLibrary(
        _ url: URL,
        completion: @escaping (Result<Void, Error>) -> Void
    ) {
        let completionBox = PhotoLibrarySaveCompletionBox(completion)

        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                let error = NSError(
                    domain: "TopOut.PhotoLibrary",
                    code: 1,
                    userInfo: [NSLocalizedDescriptionKey: "사진 앱에 추가할 권한이 없습니다. 설정에서 사진 추가 권한을 허용해주세요."]
                )
                DispatchQueue.main.async { completionBox.completion(.failure(error)) }
                return
            }

            PHPhotoLibrary.shared().performChanges {
                PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)
            } completionHandler: { success, error in
                DispatchQueue.main.async {
                    if success {
                        completionBox.completion(.success(()))
                    } else {
                        let resolvedError = error ?? NSError(
                            domain: "TopOut.PhotoLibrary",
                            code: 2,
                            userInfo: [NSLocalizedDescriptionKey: "동영상을 사진 앱에 저장하지 못했습니다."]
                        )
                        completionBox.completion(.failure(resolvedError))
                    }
                }
            }
        }
    }

    func restartRecordingWithExistingSelection() {
        cancelRecordingCountdown()
        stopRecordingElapsedTimer()
        stopRecordingStorageMonitor()
        hidePowerSavingRecordingUI()
        videoFrameSink.setRecording(false)
        motionDetector.stop()
        recorder.deleteAll()
        recState = .idle
        mode = .camera
        recorder.configure(
            resolution: selectedRecordingResolution,
            frameRate: selectedRecordingFrameRate,
            aspectRatio: selectedRecordingAspectRatio
        )
        setLoading(false)
        showCameraUI()
        view.layoutIfNeeded()
        updateCameraOrientationIfPossible()
        prepareAudioSessionForCameraUse()
        startCamera()
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.layoutUI()
            self.updateCameraOrientationIfPossible()
            self.startRecording()
        }
    }


    func startRecordingWithoutSelection() {
        guard case .idle = recState else { return }
        lockCurrentCameraOrientation()

        overlayView.image = nil
        motionDetector.stop()
        recorder.configure(
            resolution: selectedRecordingResolution,
            frameRate: selectedRecordingFrameRate,
            aspectRatio: selectedRecordingAspectRatio
        )
        clearSelectionCache(clearPending: true)
        setLoading(false)
        startRecording()
    }

    private func detectedClipRanges(for session: ContinuousRecorder.RecordingSession) -> [ReviewClipRange] {
        var ranges = clipTimes.completedRanges
        if let startPTS = clipTimes.startPTS, let endPTS = clipTimes.endPTS, endPTS > startPTS {
            ranges.append(ClipRange(startPTS: startPTS, endPTS: endPTS))
        }

        let reviewRanges: [ReviewClipRange] = ranges.compactMap { (range: ClipRange) -> ReviewClipRange? in
            let start = clampedSeconds(
                CMTimeGetSeconds(range.startPTS - session.startPTS),
                min: 0,
                max: session.duration
            )
            let end = clampedSeconds(
                CMTimeGetSeconds(range.endPTS - session.startPTS),
                min: 0,
                max: session.duration
            )
            guard end > start else { return nil }
            return ReviewClipRange(startSeconds: start, endSeconds: end)
        }

        return normalizedDetectedClipRanges(reviewRanges)
    }

    private func normalizedDetectedClipRanges(_ ranges: [ReviewClipRange]) -> [ReviewClipRange] {
        let minimumTotalDuration = Cfg.motionShortClipMinimumTotalSeconds
        let mergeGap = Cfg.motionShortClipMergeGapSeconds
        var working = ranges
            .filter { $0.endSeconds > $0.startSeconds }
            .sorted { lhs, rhs in
                if lhs.startSeconds == rhs.startSeconds { return lhs.endSeconds < rhs.endSeconds }
                return lhs.startSeconds < rhs.startSeconds
            }

        var didChange = true
        while didChange {
            didChange = false

            guard let shortIndex = working.firstIndex(where: { $0.endSeconds - $0.startSeconds < minimumTotalDuration }) else {
                break
            }

            let shortRange = working[shortIndex]
            let previousIndex = shortIndex > working.startIndex ? working.index(before: shortIndex) : nil
            let nextIndex = working.index(after: shortIndex) < working.endIndex ? working.index(after: shortIndex) : nil

            let previousGap: TimeInterval? = previousIndex.map { idx in
                Swift.max(0, shortRange.startSeconds - working[idx].endSeconds)
            }
            let nextGap: TimeInterval? = nextIndex.map { idx in
                Swift.max(0, working[idx].startSeconds - shortRange.endSeconds)
            }

            let canMergePrevious = previousGap.map { $0 <= mergeGap } ?? false
            let canMergeNext = nextGap.map { $0 <= mergeGap } ?? false

            if canMergePrevious || canMergeNext {
                let shouldMergePrevious: Bool
                if canMergePrevious && canMergeNext {
                    shouldMergePrevious = (previousGap ?? TimeInterval.greatestFiniteMagnitude) <= (nextGap ?? TimeInterval.greatestFiniteMagnitude)
                } else {
                    shouldMergePrevious = canMergePrevious
                }

                if shouldMergePrevious, let previousIndex = previousIndex {
                    working[previousIndex] = ReviewClipRange(
                        startSeconds: working[previousIndex].startSeconds,
                        endSeconds: Swift.max(working[previousIndex].endSeconds, shortRange.endSeconds)
                    )
                    working.remove(at: shortIndex)
                } else if let nextIndex = nextIndex {
                    working[shortIndex] = ReviewClipRange(
                        startSeconds: shortRange.startSeconds,
                        endSeconds: Swift.max(shortRange.endSeconds, working[nextIndex].endSeconds)
                    )
                    working.remove(at: nextIndex)
                }
            } else {
                working.remove(at: shortIndex)
            }

            didChange = true
        }

        // Preserve every valid range after the existing short-range cleanup/merge pass.
        // ReviewVC already navigates using detectedRanges.count, so it supports any count.
        return working.filter { $0.endSeconds > $0.startSeconds }
    }

    private func clampedSeconds(_ value: TimeInterval, min lower: TimeInterval, max upper: TimeInterval) -> TimeInterval {
        Swift.max(lower, Swift.min(value, upper))
    }
}
