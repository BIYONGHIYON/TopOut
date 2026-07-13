import UIKit

final class MatLineView: UIView {
    private(set) var points: [CGPoint] = [] // normalized, 0...1
    var onChange: (([CGPoint]) -> Void)?

    private var activeIndex: Int?
    private lazy var pan = UIPanGestureRecognizer(target: self, action: #selector(onPan(_:)))

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = true
        addGestureRecognizer(pan)
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = true
        addGestureRecognizer(pan)
    }

    func setPoints(_ newPoints: [CGPoint], notify: Bool = true) {
        points = newPoints.map { CGPoint(x: max(0, min(1, $0.x)), y: max(0, min(1, $0.y))) }
        setNeedsDisplay()
        if notify { onChange?(points) }
    }

    func clear() {
        points = []
        activeIndex = nil
        setNeedsDisplay()
    }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        // 파란 보정점을 잡을 때만 터치를 가로채고,
        // 그 외 영역은 imgView의 홀드 선택 tap이 받을 수 있게 둡니다.
        nearestHandle(to: point, maxDist: Cfg.matHitRadius) != nil
    }

    override func draw(_ rect: CGRect) {
        guard points.count >= 2, let ctx = UIGraphicsGetCurrentContext() else { return }

        let viewPts = points.map { viewPoint(from: $0) }

        ctx.saveGState()
        ctx.setLineCap(.round)
        ctx.setLineJoin(.round)

        // 얇은 어두운 stroke를 먼저 깔아 밝은 매트/벽에서도 선이 보이게 합니다.
        ctx.setStrokeColor(UIColor.black.withAlphaComponent(0.55).cgColor)
        ctx.setLineWidth(Cfg.matLineWidth + 2)
        ctx.beginPath()
        ctx.move(to: viewPts[0])
        for p in viewPts.dropFirst() { ctx.addLine(to: p) }
        ctx.strokePath()

        ctx.setStrokeColor(UIColor.systemBlue.cgColor)
        ctx.setLineWidth(Cfg.matLineWidth)
        ctx.beginPath()
        ctx.move(to: viewPts[0])
        for p in viewPts.dropFirst() { ctx.addLine(to: p) }
        ctx.strokePath()

        ctx.restoreGState()

        for (i, p) in viewPts.enumerated() {
            drawHandle(at: p, isActive: i == activeIndex)
        }
    }

    private func drawHandle(at point: CGPoint, isActive: Bool) {
        let r = isActive ? Cfg.matPointRadius + 2 : Cfg.matPointRadius
        let rect = CGRect(x: point.x - r, y: point.y - r, width: r * 2, height: r * 2)

        let path = UIBezierPath(ovalIn: rect)
        UIColor.systemBlue.setFill()
        path.fill()

        UIColor.white.setStroke()
        path.lineWidth = 2
        path.stroke()
    }

    @objc private func onPan(_ gesture: UIPanGestureRecognizer) {
        let loc = gesture.location(in: self)

        switch gesture.state {
        case .began:
            activeIndex = nearestHandle(to: loc, maxDist: Cfg.matHitRadius)
            setNeedsDisplay()

        case .changed:
            guard let idx = activeIndex, points.indices.contains(idx), bounds.height > 0 else { return }

            // x는 자동 인식된 위치를 유지하고, 사용자는 상하 보정만 하게 합니다.
            var p = points[idx]
            p.y = max(0, min(1, loc.y / bounds.height))
            points[idx] = p

            setNeedsDisplay()
            onChange?(points)

        case .ended, .cancelled, .failed:
            activeIndex = nil
            setNeedsDisplay()
            onChange?(points)

        default:
            break
        }
    }

    private func nearestHandle(to point: CGPoint, maxDist: CGFloat) -> Int? {
        guard !points.isEmpty else { return nil }

        var bestIndex: Int?
        var bestDist = maxDist

        for (idx, normalized) in points.enumerated() {
            let p = viewPoint(from: normalized)
            let d = hypot(p.x - point.x, p.y - point.y)
            if d <= bestDist {
                bestDist = d
                bestIndex = idx
            }
        }

        return bestIndex
    }

    private func viewPoint(from normalized: CGPoint) -> CGPoint {
        CGPoint(x: normalized.x * bounds.width, y: normalized.y * bounds.height)
    }
}
