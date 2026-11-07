// 탑홀드 외곽선 그리기와 MobileSAM 저해상도 마스크 도우미
import UIKit
import CoreML

extension HoldVC {
    // MARK: - 탑홀드 표시

    /// 외곽선을 원본 프레임 크기의 투명 이미지에 빨간 테두리 + 흰 안쪽 선으로 그린다. 안쪽은 비운다.
    func makeHoldOverlay(_ outline: HoldOutline, image: UIImage) -> UIImage? {
        guard image.size.width > 0, image.size.height > 0 else { return nil }
        let path: UIBezierPath
        switch outline {
        case .circle(let center, let radius):
            path = UIBezierPath(ovalIn: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
        case .polygon(let points):
            guard let smooth = smoothClosedPath(points) else { return nil }
            path = smooth
        }
        let style = outlineStyle(image: image)
        return pixelSizedOverlayRenderer(for: image).image { _ in
            strokeOutline(path, style: style)
        }
    }

    /// 홀드를 찾지 못했을 때 탭 위치에 그리는 최소 크기 원.
    func makeDefaultTouchOverlay(at point: CGPoint, image: UIImage) -> UIImage? {
        let radius = minimumHoldDiameter(image: image) / 2
        let center = CGPoint(
            x: max(radius, min(image.size.width - radius, point.x)),
            y: max(radius, min(image.size.height - radius, point.y))
        )
        return makeHoldOverlay(.circle(center: center, radius: radius), image: image)
    }

    /// 탑홀드 표시의 최소 지름. 짧은 변의 3.75%, 22px 이상, 짧은 변의 6% 이하.
    func minimumHoldDiameter(image: UIImage) -> CGFloat {
        let minSide = min(image.size.width, image.size.height)
        let ideal = minSide * Cfg.fallbackHoldMaskDiameterRatio
        return min(max(Cfg.fallbackHoldMaskMinDiameter, ideal), minSide * Cfg.fallbackHoldMaskMaxDiameterRatio)
    }

    func outlineStyle(image: UIImage) -> (redWidth: CGFloat, whiteWidth: CGFloat) {
        let minSide = min(image.size.width, image.size.height)
        let red = max(minSide * Cfg.redLineRatio, Cfg.minRedLineWidth)
        let white = max(minSide * Cfg.whiteLineRatio, Cfg.minWhiteLineWidth)
        return (red, white)
    }

    private func strokeOutline(_ path: UIBezierPath, style: (redWidth: CGFloat, whiteWidth: CGFloat)) {
        path.lineCapStyle = .round
        path.lineJoinStyle = .round
        UIColor.red.setStroke()
        path.lineWidth = style.redWidth
        path.stroke()
        UIColor.white.setStroke()
        path.lineWidth = style.whiteWidth
        path.stroke()
    }

    /// 점 사이 중점을 지나는 2차 곡선으로 닫힌 경로를 만든다.
    private func smoothClosedPath(_ points: [CGPoint]) -> UIBezierPath? {
        guard points.count >= 4 else { return nil }
        let path = UIBezierPath()
        path.move(to: midpoint(points[0], points[1]))
        for index in 1...points.count {
            let current = points[index % points.count]
            let next = points[(index + 1) % points.count]
            path.addQuadCurve(to: midpoint(current, next), controlPoint: current)
        }
        path.close()
        return path
    }

    private func midpoint(_ a: CGPoint, _ b: CGPoint) -> CGPoint {
        CGPoint(x: (a.x + b.x) * 0.5, y: (a.y + b.y) * 0.5)
    }

    private func pixelSizedOverlayRenderer(for image: UIImage) -> UIGraphicsImageRenderer {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        return UIGraphicsImageRenderer(size: image.size, format: format)
    }

    // MARK: - 저해상도 마스크

    /// 256 저해상도 마스크 안에서 실제 이미지가 차지하는 크기.
    func visibleMaskSize(for image: UIImage) -> CGSize {
        let maxDim = max(image.size.width, image.size.height)
        return CGSize(
            width: CGFloat(Cfg.maskSize) * image.size.width / maxDim,
            height: CGFloat(Cfg.maskSize) * image.size.height / maxDim
        )
    }

    func maskValue(_ mask: MLMultiArray, x: Int, y: Int, channel: Int) -> Float {
        let index = [0, NSNumber(value: channel), NSNumber(value: y), NSNumber(value: x)]
        return mask[index].floatValue
    }
}
