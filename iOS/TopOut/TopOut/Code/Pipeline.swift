import UIKit
import CoreML

extension HoldVC {
    func runSAM(at point: CGPoint, image: UIImage, zoom: CGFloat, requestID: Int) {
        do {
            let emb = try getEmbedding(for: image)

            let firstMask = try decode(point: point, embedding: emb)
            let first = try makePick(from: firstMask, at: point, image: image, tol: Cfg.hsvTol, origin: point)

            let chosen = try chooseBestPick(
                first,
                firstMask: firstMask,
                image: image,
                embedding: emb,
                origin: point,
                zoom: zoom
            )

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

                self.pendingOverlay = selection.overlay
                self.pendingTopBox = selection.box
                self.selectOverlayView.image = selection.overlay
                // 선택 뒤 픽셀과 임베딩만 비우고 재촬영을 위해 모델은 유지한다.
                self.pixels = nil
                self.emb = nil
                self.mode = .select
                self.isBusy = false
                self.retakeBtn.isEnabled = true
                self.recordBtn.isEnabled = true
                self.selectHint.text = L("camera.selection_complete")
                self.setLoading(false)
            }
        } catch {
            DispatchQueue.main.async { [weak self] in
                guard let self, self.selectionRequestID == requestID, self.mode == .processing else { return }

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

                self.pixels = nil
                self.emb = nil
                self.isBusy = false
                self.mode = .select
                self.retakeBtn.isEnabled = true
                self.setLoading(false)
            }
        }
    }
}
