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
                self.mode = .select
                self.isBusy = false
                self.retakeBtn.isEnabled = true
                self.recordBtn.isEnabled = true
                self.selectHint.text = "선택 완료 · 다시 탭해서 수정하거나 녹화 시작을 눌러주세요"
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
                    self.selectHint.text = "선택 완료 · 다시 탭해서 수정하거나 녹화 시작을 눌러주세요"
                } else {
                    self.recordBtn.isEnabled = self.pendingOverlay != nil
                    self.selectHint.text = self.pendingOverlay == nil
                        ? "선택 실패 · 다시 탭해보세요"
                        : "선택 실패 · 기존 선택을 유지합니다"
                }

                self.isBusy = false
                self.mode = .select
                self.retakeBtn.isEnabled = true
                self.setLoading(false)
            }
        }
    }
}
