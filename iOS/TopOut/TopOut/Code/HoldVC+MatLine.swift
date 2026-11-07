import UIKit
import AVFoundation

/// 준비 완료에서 매트 경계를 한 번 측정해 보정하게 하고, 녹화 중에는 실시간 영상에서 손으로 고친다.
/// 녹화 중 자동으로 다시 재지 않는다.
extension HoldVC {
    func configureMatLineCallbacks() {
        recordingMatLineView.onChange = { [weak self] points in
            guard let self, self.recState == .buffering else { return }
            self.matLinePoints = points
        }
        recordingMatLineView.onDragStateChange = { [weak self] dragging in
            guard let self, !dragging, self.recState == .buffering, self.matLinePoints.count >= 2 else { return }
            // 녹화 중 손으로 고친 경계선을 이어지는 등반 분석에 바로 쓴다.
            self.motionDetector.updateMatLine(self.matLinePoints)
        }
    }

    // MARK: - 녹화 전 버튼과 안내

    /// 카메라 화면은 자동 구간 탐지를 쓰면 준비 완료, 아니면 녹화 시작을 보여준다.
    /// 보정 화면은 다시 촬영과 녹화 시작을 나란히 둔다.
    func refreshRecordButtonAvailability() {
        guard recState == .idle else { return }
        let cameraReady = isCameraReady && isCameraStreamReady && launchImageView.isHidden
        switch mode {
        case .camera:
            readyBtn.isHidden = !isAutoClipDetectionEnabled
            recordBtn.isHidden = isAutoClipDetectionEnabled
            retakeBtn.isHidden = true
            readyBtn.isEnabled = cameraReady && !isBusy
            recordBtn.isEnabled = cameraReady && !isBusy
        case .select:
            readyBtn.isHidden = true
            recordBtn.isHidden = false
            retakeBtn.isHidden = false
            retakeBtn.isEnabled = true
            recordBtn.isEnabled = !isBusy && matLinePoints.count >= 2
        default:
            break
        }
    }

    /// 카메라 프레임이 끊기는 동안 준비 완료와 바로 녹화 시작을 막는다. 보정 화면 버튼은 그대로 둔다.
    func disableCameraStartButtons() {
        guard recState == .idle, mode == .camera else { return }
        readyBtn.isEnabled = false
        recordBtn.isEnabled = false
    }

    /// 녹화 전 카메라 화면 상단 안내.
    func refreshCameraHint() {
        guard recState == .idle, mode == .camera else { return }
        let text: String
        if isAutoClipDetectionEnabled {
            text = L(isTopHoldMaskingEnabled ? "camera.preview_both" : "camera.preview_mat")
        } else if isTopHoldMaskingEnabled {
            text = L("camera.preview_hold")
        } else {
            text = ""
        }
        selectHint.text = text
        selectHint.isHidden = !isHelpEnabled || text.isEmpty
        selectTipLabel.text = ""
        selectTipLabel.isHidden = true
        updatePreviewTipVisibility()
    }

    // MARK: - 보정 화면

    /// 준비 완료 프레임으로 매트 경계를 한 번 측정하고, 실시간 화면 위에서 보정하게 한다.
    func showSelectUI(with image: UIImage) {
        cancelMatMeasurement()
        mode = .select
        hidePowerSavingRecordingUI()
        imgView.image = nil
        resetZoom()

        // 추론 전에 75% 기본선을 그리면 완료 때 선이 순간 이동해 보이므로 결과가 나올 때까지 숨긴다.
        matLinePoints = []
        matLineView.clear()
        matLineView.isHidden = true
        matLineView.showsHandles = true
        selectHint.text = L("camera.analyzing_mat")
        selectTipLabel.text = L("camera.tip_analysis")
        isBusy = true
        setLoading(true)
        applySelectScreenChrome()

        let requestID = matMeasurementRequestID
        let token = AnalysisCancellationToken()
        matBoundaryAnalysisToken = token
        let timeout = DispatchWorkItem { [weak self] in
            token.cancel()
            self?.finishMatMeasurement(requestID: requestID, line: Self.samMatDefaultLine)
        }
        matBoundaryAnalysisTimeoutWorkItem = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + Cfg.matBoundaryAnalysisTimeout, execute: timeout)

        detectMatBoundaryWithMobileSAM(in: image, cancellationToken: token) { [weak self] line in
            self?.finishMatMeasurement(requestID: requestID, line: line)
        }
    }

    private func finishMatMeasurement(requestID: Int, line: [CGPoint]) {
        guard requestID == matMeasurementRequestID, mode == .select, isBusy else { return }
        matBoundaryAnalysisTimeoutWorkItem?.cancel()
        matBoundaryAnalysisTimeoutWorkItem = nil
        matBoundaryAnalysisToken = nil
        isBusy = false
        setLoading(false)
        // 경계를 찾지 못하면 MobileSAM이 75% 기본선을 돌려주고, 사용자가 보정한 뒤 녹화한다.
        showAdjustableMatLine(line.count >= 2 ? line : Self.samMatDefaultLine)
    }

    private func showAdjustableMatLine(_ line: [CGPoint]) {
        matLinePoints = line
        matLineView.setPoints(line)
        matLineView.isHidden = false
        selectHint.text = L("camera.selection_mat")
        selectTipLabel.text = L("camera.tip_adjust_line")
        applySelectScreenChrome()
    }

    /// 보정 화면에서는 화각과 화면 방향을 녹화가 끝나거나 다시 촬영할 때까지 고정한다.
    private func applySelectScreenChrome() {
        camView.isHidden = false
        recordingMatLineView.isHidden = true
        zoomLabel.isHidden = true
        settingsBtn.isHidden = true
        camHint.isHidden = true
        manualStopBtn.isHidden = true
        livePreviewToggleButton.isHidden = true
        selectView.isHidden = false
        selectHint.isHidden = !isHelpEnabled || (selectHint.text?.isEmpty ?? true)
        selectTipLabel.isHidden = !isHelpEnabled || (selectTipLabel.text?.isEmpty ?? true)
        updatePreviewTipVisibility()
        refreshRecordButtonAvailability()
        view.setNeedsLayout()
    }

    /// 분석 중 앱을 벗어나면 결과를 버리고, 돌아왔을 때 75% 선으로 바로 보정할 수 있게 한다.
    func interruptSelectionAnalysis() {
        guard mode == .select, recState == .idle, isBusy else { return }
        cancelMatMeasurement()
        isBusy = false
        setLoading(false)
        showAdjustableMatLine(Self.samMatDefaultLine)
    }

    /// 진행 중인 경계선 분석의 결과를 버린다.
    func cancelMatMeasurement() {
        matMeasurementRequestID &+= 1
        matBoundaryAnalysisTimeoutWorkItem?.cancel()
        matBoundaryAnalysisTimeoutWorkItem = nil
        matBoundaryAnalysisToken?.cancel()
        matBoundaryAnalysisToken = nil
    }
}
