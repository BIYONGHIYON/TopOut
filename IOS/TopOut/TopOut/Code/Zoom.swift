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
        scrollView.minimumZoomScale = Cfg.minZoom
        scrollView.maximumZoomScale = Cfg.maxZoom
        scrollView.setZoomScale(Cfg.minZoom, animated: false)
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
