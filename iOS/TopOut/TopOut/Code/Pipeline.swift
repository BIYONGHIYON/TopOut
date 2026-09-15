import UIKit
import CoreML

extension HoldVC {
    func scheduleTopHoldAnalysisTimeout(
        at point: CGPoint,
        image: UIImage,
        requestID: Int,
        cancellationToken: AnalysisCancellationToken
    ) {
        topHoldAnalysisTimeoutWorkItem?.cancel()

        let timeoutWorkItem = DispatchWorkItem { [weak self] in
            guard let self,
                  self.selectionRequestID == requestID,
                  self.mode == .processing else { return }

            self.topHoldAnalysisTimeoutWorkItem = nil
            cancellationToken.cancel()
            self.selectionRequestID += 1

            if let fallback = self.makeDefaultTouchOverlay(at: point, image: image) {
                self.pendingOverlay = fallback.overlay
                self.pendingTopBox = fallback.maskBox
                self.selectOverlayView.image = fallback.overlay
                self.recordBtn.isEnabled = true
                self.selectHint.text = L("camera.selection_complete")
            } else {
                self.recordBtn.isEnabled = false
                self.selectHint.text = L("camera.selection_failed")
            }

            self.topHoldAnalysisToken = nil
            self.isBusy = false
            self.mode = .select
            self.retakeBtn.isEnabled = true
            self.setLoading(false)
            if self.pendingOverlay != nil {
                self.scheduleAutomaticRecordingStartIfEligible()
            }
        }

        topHoldAnalysisTimeoutWorkItem = timeoutWorkItem
        DispatchQueue.main.asyncAfter(
            deadline: .now() + Cfg.topHoldAnalysisTimeout,
            execute: timeoutWorkItem
        )
    }

    func runSAM(
        at point: CGPoint,
        image: UIImage,
        pixels: PixelMap?,
        zoom: CGFloat,
        requestID: Int,
        cancellationToken: AnalysisCancellationToken
    ) {
        do {
            try cancellationToken.check()
            guard let pixels else { throw AppError.encoderOutputMissing }
            let embedding = try makeEmbedding(for: image, cancellationToken: cancellationToken)
            try cancellationToken.check()

            let firstMask = try decode(point: point, embedding: embedding.values, scale: embedding.scale)
            try cancellationToken.check()
            let first = try makePick(from: firstMask, at: point, image: image, pixels: pixels, scale: embedding.scale, tol: Cfg.hsvTol, origin: point)

            let chosen = try chooseBestPick(
                first,
                firstMask: firstMask,
                image: image,
                pixels: pixels,
                embedding: embedding.values,
                scale: embedding.scale,
                origin: point,
                zoom: zoom,
                cancellationToken: cancellationToken
            )
            try cancellationToken.check()

            let blob = blobForRender(chosen)
            let selection: (overlay: UIImage, box: CGRect, usedFallback: Bool)

            if blob.isEmpty {
                guard let fallback = makeDefaultTouchOverlay(at: point, image: image) else {
                    throw AppError.renderFailed
                }
                selection = (fallback.overlay, fallback.maskBox, true)
            } else if let overlay = makeTransparentOverlay(from: blob, image: image) {
                selection = (overlay, blob.box, false)
            } else if let fallback = makeDefaultTouchOverlay(at: point, image: image) {
                selection = (fallback.overlay, fallback.maskBox, true)
            } else {
                throw AppError.renderFailed
            }

            DispatchQueue.main.async { [weak self] in
                guard let self, self.selectionRequestID == requestID, self.mode == .processing else { return }

                self.topHoldAnalysisTimeoutWorkItem?.cancel()
                self.topHoldAnalysisTimeoutWorkItem = nil
                self.topHoldAnalysisToken = nil
                self.pendingOverlay = selection.overlay
                self.pendingTopBox = selection.box
                self.selectOverlayView.image = selection.overlay
                // 선택 뒤 픽셀과 임베딩만 비우고 재촬영을 위해 모델은 유지한다.
                self.mode = .select
                self.isBusy = false
                self.retakeBtn.isEnabled = true
                self.recordBtn.isEnabled = true
                self.selectHint.text = L("camera.selection_complete")
                self.scheduleAutomaticRecordingStartIfEligible()
                self.setLoading(false)
            }
        } catch {
            DispatchQueue.main.async { [weak self] in
                guard let self, self.selectionRequestID == requestID, self.mode == .processing else { return }

                self.topHoldAnalysisTimeoutWorkItem?.cancel()
                self.topHoldAnalysisTimeoutWorkItem = nil
                self.topHoldAnalysisToken = nil
                if let fallback = self.makeDefaultTouchOverlay(at: point, image: image) {
                    self.pendingOverlay = fallback.overlay
                    self.pendingTopBox = fallback.maskBox
                    self.selectOverlayView.image = fallback.overlay
                    self.recordBtn.isEnabled = true
                    self.selectHint.text = L("camera.selection_complete")
                } else {
                    self.recordBtn.isEnabled = self.pendingOverlay != nil
                    self.selectHint.text = L("camera.selection_failed")
                }

                self.isBusy = false
                self.mode = .select
                self.retakeBtn.isEnabled = true
                self.setLoading(false)
                if self.pendingOverlay != nil {
                    self.scheduleAutomaticRecordingStartIfEligible()
                }
            }
        }
    }
}
