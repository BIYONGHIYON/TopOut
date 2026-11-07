import UIKit

/// 탑홀드 마스킹을 켠 녹화는 결과 화면 영상을 두 손가락으로 확대·이동한 뒤 탑홀드를 탭할 수 있다.
/// 영상과 탑홀드 마스크는 같은 컨테이너에 있어 함께 확대되고, 탭 위치는 확대를 되돌린 영상 좌표로 바꾼다.
extension ReviewVC {
    static let maxVideoZoomScale: CGFloat = 5

    func installVideoZoomGestures() {
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(onVideoPinch(_:)))
        pinch.delegate = self
        view.addGestureRecognizer(pinch)
        videoZoomPinchRecognizer = pinch

        let pan = UIPanGestureRecognizer(target: self, action: #selector(onVideoPan(_:)))
        pan.maximumNumberOfTouches = 2
        pan.delegate = self
        view.addGestureRecognizer(pan)
        videoZoomPanRecognizer = pan
    }

    var isVideoZoomed: Bool { videoZoomScale > 1.01 }

    @objc func onVideoPinch(_ gesture: UIPinchGestureRecognizer) {
        guard topHoldSelectionEnabled else { return }
        switch gesture.state {
        case .began, .changed:
            let center = CGPoint(x: view.bounds.midX, y: view.bounds.midY)
            let location = gesture.location(in: view)
            let relative = CGPoint(x: location.x - center.x, y: location.y - center.y)
            let oldScale = videoZoomScale
            let newScale = min(Self.maxVideoZoomScale, max(1, oldScale * gesture.scale))
            gesture.scale = 1
            // 손가락 아래의 영상 지점이 그대로 머물도록 이동량을 맞춘다.
            let ratio = newScale / oldScale
            let offset = CGPoint(
                x: relative.x - ratio * (relative.x - videoZoomOffset.x),
                y: relative.y - ratio * (relative.y - videoZoomOffset.y)
            )
            setVideoZoom(scale: newScale, offset: offset)
        default:
            break
        }
    }

    @objc func onVideoPan(_ gesture: UIPanGestureRecognizer) {
        guard topHoldSelectionEnabled, isVideoZoomed else { return }
        switch gesture.state {
        case .began, .changed:
            let translation = gesture.translation(in: view)
            gesture.setTranslation(.zero, in: view)
            setVideoZoom(
                scale: videoZoomScale,
                offset: CGPoint(x: videoZoomOffset.x + translation.x, y: videoZoomOffset.y + translation.y)
            )
        default:
            break
        }
    }

    func resetVideoZoom() {
        setVideoZoom(scale: 1, offset: .zero)
    }

    /// 확대된 영상이 원래 영상 영역을 항상 덮도록 이동 범위를 제한한다.
    private func setVideoZoom(scale: CGFloat, offset: CGPoint) {
        let videoRect = displayedVideoRect
        let center = CGPoint(x: view.bounds.midX, y: view.bounds.midY)
        let videoCenter = CGPoint(x: videoRect.midX - center.x, y: videoRect.midY - center.y)
        let slackX = (scale - 1) * videoRect.width * 0.5
        let slackY = (scale - 1) * videoRect.height * 0.5
        let baseX = (1 - scale) * videoCenter.x
        let baseY = (1 - scale) * videoCenter.y
        let clampedOffset = scale <= 1.001
            ? .zero
            : CGPoint(
                x: min(baseX + slackX, max(baseX - slackX, offset.x)),
                y: min(baseY + slackY, max(baseY - slackY, offset.y))
            )
        videoZoomScale = scale <= 1.001 ? 1 : scale
        videoZoomOffset = clampedOffset
        videoContainerView.transform = CGAffineTransform(translationX: clampedOffset.x, y: clampedOffset.y)
            .scaledBy(x: videoZoomScale, y: videoZoomScale)
    }
}
