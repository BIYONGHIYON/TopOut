import UIKit

extension HoldVC {
    func cancelAutomaticRecordingStart() {
        automaticRecordingStartWorkItem?.cancel()
        automaticRecordingStartWorkItem = nil
        automaticRecordingWarningWorkItem?.cancel()
        automaticRecordingWarningWorkItem = nil
    }

    private var isAutomaticRecordingStartEligible: Bool {
        guard mode == .select,
              recState == .idle,
              !isBusy,
              frameImage != nil else { return false }

        let hasRequiredBoundary = !isAutoClipDetectionEnabled || matLinePoints.count >= 2
        let hasRequiredTopHold = !requiresTopHoldSelection || (pendingOverlay != nil && pendingTopBox != nil)
        return (isAutoClipDetectionEnabled || requiresTopHoldSelection) &&
            hasRequiredBoundary && hasRequiredTopHold
    }

    func scheduleAutomaticRecordingStartIfEligible() {
        guard isAutomaticRecordingStartEligible else { return }
        guard automaticRecordingStartWorkItem == nil else { return }

        selectHint.text = L(requiresTopHoldSelection ? "camera.selection_complete" : "camera.selection_mat")
        let warningWorkItem = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.automaticRecordingWarningWorkItem = nil
            guard self.isAutomaticRecordingStartEligible else { return }
            self.selectHint.text = L("camera.auto_recording_soon")
        }
        let workItem = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.automaticRecordingStartWorkItem = nil
            self.automaticRecordingWarningWorkItem?.cancel()
            self.automaticRecordingWarningWorkItem = nil
            guard self.isAutomaticRecordingStartEligible else { return }
            self.onRecordStart()
        }
        automaticRecordingWarningWorkItem = warningWorkItem
        automaticRecordingStartWorkItem = workItem
        DispatchQueue.main.asyncAfter(
            deadline: .now() + (Cfg.automaticRecordingStartDelay - Cfg.automaticRecordingWarningLead),
            execute: warningWorkItem
        )
        DispatchQueue.main.asyncAfter(
            deadline: .now() + Cfg.automaticRecordingStartDelay,
            execute: workItem
        )
    }

    /// Invalidate callbacks before clearing UI or releasing models.
    func cancelSelectionAnalysis() {
        selectionRequestID += 1
        matBoundaryAnalysisTimeoutWorkItem?.cancel()
        matBoundaryAnalysisTimeoutWorkItem = nil
        matBoundaryAnalysisToken?.cancel()
        matBoundaryAnalysisToken = nil
        topHoldAnalysisTimeoutWorkItem?.cancel()
        topHoldAnalysisTimeoutWorkItem = nil
        topHoldAnalysisToken?.cancel()
        topHoldAnalysisToken = nil
    }

    /// Preserve completed selection work and leave an interrupted request editable on return.
    func interruptSelectionAnalysis() {
        guard (mode == .select || mode == .processing), isBusy else { return }
        cancelSelectionAnalysis()
        isBusy = false
        mode = .select
        setLoading(false)
        retakeBtn.isEnabled = true
        if isAutoClipDetectionEnabled, matLinePoints.count < 2 {
            matLinePoints = Self.samMatDefaultLine
            matLineView.setPoints(matLinePoints)
            matLineView.isHidden = false
        }
        if requiresTopHoldSelection {
            recordBtn.isEnabled = pendingOverlay != nil
            selectHint.text = L(isAutoClipDetectionEnabled ? "camera.selection_both" : "camera.selection_hold")
            selectTipLabel.text = ""
            selectTipLabel.isHidden = true
        } else {
            recordBtn.isEnabled = matLinePoints.count >= 2
            selectHint.text = L("camera.selection_mat")
            selectTipLabel.text = L("camera.tip_adjust_line")
            selectTipLabel.isHidden = !isHelpEnabled
        }
    }
}
