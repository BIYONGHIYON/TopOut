//두 손가락 확대/축소
import UIKit

extension HoldVC: UIScrollViewDelegate {
    func viewForZooming(in scrollView: UIScrollView) -> UIView? {
        imgView
    }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        updateZoomInset()
    }

    func resetZoom() {
        // Selection is drawn over a live camera preview. Keep the overlay at 1x so normalized
        // mat/hold coordinates always match the fresh frame captured for MobileSAM.
        scrollView.minimumZoomScale = 1
        scrollView.maximumZoomScale = 1
        scrollView.setZoomScale(1, animated: false)
        scrollView.contentOffset = .zero
        updateZoomInset()
    }

    func updateZoomInset() {
        let bounds = scrollView.bounds.size
        let content = scrollView.contentSize
        let insetX = max((bounds.width - content.width) / 2, 0)
        let insetY = max((bounds.height - content.height) / 2, 0)
        scrollView.contentInset = UIEdgeInsets(top: insetY, left: insetX, bottom: insetY, right: insetX)
    }
}
