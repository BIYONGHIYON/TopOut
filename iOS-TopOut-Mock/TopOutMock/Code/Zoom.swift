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
        // 선택 화면은 1배로 유지해 정규화 좌표와 새 분석 프레임을 맞춘다.
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
