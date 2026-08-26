//마스크 필터링, blob 추출, overlay 생성
import UIKit
import CoreML

extension HoldVC {
    // MARK: - 선택 결과 생성

    func makePick(from mask: MLMultiArray, at point: CGPoint, image: UIImage, tol: Float, origin: CGPoint) throws -> Pick {
        guard let pixels else { throw AppError.encoderOutputMissing }

        let target = pixels.avgRGB(at: point, in: image, radius: Cfg.colorSampleRadius)
        let valid = validMaskMap(mask, image: image, pixels: pixels, target: target, tol: tol)
        let blob = largestBlob(in: valid, width: Cfg.maskSize, height: Cfg.maskSize)
        let status = classify(blob, image: image)
        let dist = hypot(point.x - origin.x, point.y - origin.y)

        return Pick(point: point, tol: tol, blob: blob, status: status, dist: dist)
    }

    // MARK: - 마스크 표시

    /// 최종 결과는 mask fill 배열이 아니라 outline path만 사용합니다.
    /// 내부는 항상 투명합니다.
    func makeTransparentOverlay(from blob: Blob, image: UIImage) -> UIImage? {
        guard !blob.isEmpty else { return blankOverlay(image: image) }
        guard let path = outlinePath(for: blob, image: image) else { return blankOverlay(image: image) }

        let renderer = UIGraphicsImageRenderer(size: image.size)
        let style = outlineStyle(image: image)

        return renderer.image { _ in
            strokeOutline(path, style: style)
        }
    }

    /// 홀드 분리에 실패했을 때 사용하는 기본 원형 마스크다.
    /// 반환하는 maskBox는 기존 Blob.box와 같은 mask-space 좌표라서
    /// 이후 MotionDetector / 저장 overlay 흐름을 그대로 탈 수 있습니다.
    func makeDefaultTouchOverlay(at point: CGPoint, image: UIImage) -> (overlay: UIImage, maskBox: CGRect)? {
        guard image.size.width > 0, image.size.height > 0 else { return nil }

        let minSide = min(image.size.width, image.size.height)
        let ideal = minSide * Cfg.fallbackHoldMaskDiameterRatio
        let maxDiameter = minSide * Cfg.fallbackHoldMaskMaxDiameterRatio
        let diameter = min(max(Cfg.fallbackHoldMaskMinDiameter, ideal), maxDiameter)
        let half = diameter * 0.5

        let center = CGPoint(
            x: max(half, min(image.size.width - half, point.x)),
            y: max(half, min(image.size.height - half, point.y))
        )
        let imageRect = CGRect(
            x: center.x - half,
            y: center.y - half,
            width: diameter,
            height: diameter
        ).intersection(image.bounds)
        guard !imageRect.isNull, imageRect.width > 0, imageRect.height > 0 else { return nil }

        let path = UIBezierPath(ovalIn: imageRect)
        let renderer = UIGraphicsImageRenderer(size: image.size)
        let style = outlineStyle(image: image)
        let overlay = renderer.image { _ in
            strokeOutline(path, style: style)
        }

        let visible = visibleMaskSize(for: image)
        let maskBox = CGRect(
            x: imageRect.minX / max(image.size.width, 1) * visible.width,
            y: imageRect.minY / max(image.size.height, 1) * visible.height,
            width: imageRect.width / max(image.size.width, 1) * visible.width,
            height: imageRect.height / max(image.size.height, 1) * visible.height
        )

        return (overlay, maskBox)
    }

    /// 실제로 필요한 최종 영역 정보입니다.
    /// 경로를 보관해 터치 판정과 화면 표시에 다시 사용한다.
    func outlinePath(for blob: Blob, image: UIImage) -> UIBezierPath? {
        let baseRect = imageRect(for: blob, image: image)
        let roundedRect = roundedDrawingRect(base: baseRect, image: image)

        if shouldUseRoundedShape(for: blob, roundedRect: roundedRect, image: image) {
            let radius = min(roundedRect.width, roundedRect.height) * Cfg.roundedCornerRatio
            return UIBezierPath(roundedRect: roundedRect, cornerRadius: radius)
        }

        let points = contourPoints(from: blob, image: image)

        guard points.count >= Cfg.contourMinPoints,
              let path = smoothClosedPath(points) else {
            return fallbackRectPath(baseRect)
        }

        return path
    }

    /// 작은 compact 홀드만 rounded로 처리합니다.
    /// 둥근 외곽선이 너무 커지면 윤곽선 방식으로 전환한다.
    func shouldUseRoundedShape(for blob: Blob, roundedRect: CGRect, image: UIImage) -> Bool {
        let maxSpan = max(blob.box.width, blob.box.height)
        let minSpan = max(min(blob.box.width, blob.box.height), 1)
        let aspect = maxSpan / minSpan

        if aspect >= Cfg.roundAspectLimit { return false }
        if outlineRectExceedsWall(roundedRect, image: image) { return false }

        if maxSpan <= Cfg.tinyRoundMaxSpan { return true }
        if blob.area <= Cfg.tinyRoundMaxArea { return true }

        return false
    }

    func outlineRectExceedsWall(_ rect: CGRect, image: UIImage) -> Bool {
        guard image.size.width > 0, image.size.height > 0 else { return true }

        let spanX = rect.width / image.size.width
        let spanY = rect.height / image.size.height
        let areaRatio = (rect.width * rect.height) / max(image.size.width * image.size.height, 1)

        return spanX >= Cfg.wallSpanRatio
            || spanY >= Cfg.wallSpanRatio
            || areaRatio >= Cfg.roundedMaxAreaRatio
    }

    func roundedDrawingRect(base rect: CGRect, image: UIImage) -> CGRect {
        let style = outlineStyle(image: image)
        let pad = style.redWidth * Cfg.roundedPaddingRatio
        let padded = rect.insetBy(dx: -pad, dy: -pad)
        let bounds = CGRect(origin: .zero, size: image.size)
        let clipped = padded.intersection(bounds)
        return clipped.isNull ? rect : clipped
    }

    func fallbackRectPath(_ rect: CGRect) -> UIBezierPath {
        let radius = min(rect.width, rect.height) * 0.12
        return UIBezierPath(roundedRect: rect, cornerRadius: radius)
    }

    func strokeOutline(_ path: UIBezierPath, style: (redWidth: CGFloat, whiteWidth: CGFloat)) {
        UIColor.red.setStroke()
        path.lineWidth = style.redWidth
        path.lineCapStyle = .round
        path.lineJoinStyle = .round
        path.stroke()

        UIColor.white.setStroke()
        path.lineWidth = style.whiteWidth
        path.lineCapStyle = .round
        path.lineJoinStyle = .round
        path.stroke()
    }

    func outlineStyle(image: UIImage) -> (redWidth: CGFloat, whiteWidth: CGFloat) {
        let minSide = min(image.size.width, image.size.height)
        let red = max(minSide * Cfg.redLineRatio, Cfg.minRedLineWidth)
        let white = max(minSide * Cfg.whiteLineRatio, Cfg.minWhiteLineWidth)
        return (red, white)
    }

    // MARK: - 윤곽선 점

    /// 둥근 형태가 아닌 홀드는 하나의 윤곽선 로직을 사용한다.
    /// 크기별 세분화 없음.
    func contourPoints(from blob: Blob, image: UIImage) -> [CGPoint] {
        let size = Cfg.maskSize
        let inside = insideMap(from: blob, size: size)
        let edge = edgeIndices(from: blob, inside: inside, size: size)
        guard !edge.isEmpty else { return [] }

        let visible = visibleMaskSize(for: image)
        var raw = edge.map { maskIndexToImagePoint($0, image: image, visible: visible) }

        // 연산량 방지용 1차 샘플링.
        raw = decimate(raw, maxCount: Cfg.contourRawPointLimit)

        let ordered: [CGPoint]
        if Cfg.contourUseConvexHull {
            ordered = convexHull(raw)
        } else {
            let center = centroid(of: raw)
            ordered = raw.sorted {
                atan2($0.y - center.y, $0.x - center.x) < atan2($1.y - center.y, $1.x - center.x)
            }
        }

        let reduced = decimate(ordered, maxCount: Cfg.contourPointLimit)
        let smoothed = chaikinSmooth(reduced, iterations: Cfg.contourSmoothIterations)

        return smoothed
    }

    func smoothClosedPath(_ points: [CGPoint]) -> UIBezierPath? {
        guard points.count >= 4 else { return nil }

        let path = UIBezierPath()
        let firstMid = midpoint(points[0], points[1])
        path.move(to: firstMid)

        for i in 1..<points.count {
            let current = points[i]
            let next = points[(i + 1) % points.count]
            let mid = midpoint(current, next)
            path.addQuadCurve(to: mid, controlPoint: current)
        }

        path.close()
        return path
    }

    func chaikinSmooth(_ points: [CGPoint], iterations: Int) -> [CGPoint] {
        guard points.count >= 4, iterations > 0 else { return points }
        var result = points

        for _ in 0..<iterations {
            var next: [CGPoint] = []
            next.reserveCapacity(result.count * 2)

            for i in 0..<result.count {
                let p0 = result[i]
                let p1 = result[(i + 1) % result.count]

                next.append(CGPoint(
                    x: p0.x * 0.75 + p1.x * 0.25,
                    y: p0.y * 0.75 + p1.y * 0.25
                ))
                next.append(CGPoint(
                    x: p0.x * 0.25 + p1.x * 0.75,
                    y: p0.y * 0.25 + p1.y * 0.75
                ))
            }

            result = next
        }

        return result
    }

    func convexHull(_ points: [CGPoint]) -> [CGPoint] {
        guard points.count > 3 else { return points }

        let sorted = points.sorted {
            if $0.x == $1.x { return $0.y < $1.y }
            return $0.x < $1.x
        }

        func cross(_ o: CGPoint, _ a: CGPoint, _ b: CGPoint) -> CGFloat {
            (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        }

        var lower: [CGPoint] = []
        for p in sorted {
            while lower.count >= 2 && cross(lower[lower.count - 2], lower[lower.count - 1], p) <= 0 {
                lower.removeLast()
            }
            lower.append(p)
        }

        var upper: [CGPoint] = []
        for p in sorted.reversed() {
            while upper.count >= 2 && cross(upper[upper.count - 2], upper[upper.count - 1], p) <= 0 {
                upper.removeLast()
            }
            upper.append(p)
        }

        lower.removeLast()
        upper.removeLast()
        return lower + upper
    }

    func imageRect(for blob: Blob, image: UIImage) -> CGRect {
        let visible = visibleMaskSize(for: image)
        return CGRect(
            x: blob.box.minX / max(visible.width, 1) * image.size.width,
            y: blob.box.minY / max(visible.height, 1) * image.size.height,
            width: blob.box.width / max(visible.width, 1) * image.size.width,
            height: blob.box.height / max(visible.height, 1) * image.size.height
        )
    }

    func blankOverlay(image: UIImage) -> UIImage? {
        let renderer = UIGraphicsImageRenderer(size: image.size)
        return renderer.image { _ in }
    }

    func maskIndexToImagePoint(_ idx: Int, image: UIImage, visible: CGSize) -> CGPoint {
        let x = CGFloat(idx % Cfg.maskSize) + 0.5
        let y = CGFloat(idx / Cfg.maskSize) + 0.5

        return CGPoint(
            x: x / max(visible.width, 1) * image.size.width,
            y: y / max(visible.height, 1) * image.size.height
        )
    }

    func centroid(of points: [CGPoint]) -> CGPoint {
        guard !points.isEmpty else { return .zero }
        let sum = points.reduce(CGPoint.zero) { partial, point in
            CGPoint(x: partial.x + point.x, y: partial.y + point.y)
        }
        return CGPoint(x: sum.x / CGFloat(points.count), y: sum.y / CGFloat(points.count))
    }

    func midpoint(_ a: CGPoint, _ b: CGPoint) -> CGPoint {
        CGPoint(x: (a.x + b.x) * 0.5, y: (a.y + b.y) * 0.5)
    }

    func decimate(_ points: [CGPoint], maxCount: Int) -> [CGPoint] {
        guard maxCount > 0, points.count > maxCount else { return points }
        let step = max(1, Int(ceil(Double(points.count) / Double(maxCount))))
        return stride(from: 0, to: points.count, by: step).map { points[$0] }
    }

    func insideMap(from blob: Blob, size: Int) -> [Bool] {
        var inside = [Bool](repeating: false, count: size * size)
        for idx in blob.indices where idx >= 0 && idx < inside.count {
            inside[idx] = true
        }
        return inside
    }

    func edgeIndices(from blob: Blob, inside: [Bool], size: Int) -> [Int] {
        let dx = [-1, 1, 0, 0, -1, -1, 1, 1]
        let dy = [0, 0, -1, 1, -1, 1, -1, 1]
        var edge: [Int] = []
        edge.reserveCapacity(min(blob.indices.count, Cfg.contourRawPointLimit))

        for idx in blob.indices {
            let x = idx % size
            let y = idx / size
            var isEdge = false

            for i in 0..<8 {
                let nx = x + dx[i]
                let ny = y + dy[i]

                if nx < 0 || nx >= size || ny < 0 || ny >= size {
                    isEdge = true
                    break
                }

                if !inside[ny * size + nx] {
                    isEdge = true
                    break
                }
            }

            if isEdge { edge.append(idx) }
        }

        return edge
    }

    // MARK: - 마스크 필터

    func validMaskMap(_ mask: MLMultiArray, image: UIImage, pixels: PixelMap, target: RGB, tol: Float) -> [Bool] {
        let size = Cfg.maskSize
        let channel = maskChannel(mask)
        let targetHSV = Self.rgbToHSV(target)
        var valid = [Bool](repeating: false, count: size * size)

        for y in 0..<size {
            for x in 0..<size {
                guard maskValue(mask, x: x, y: y, channel: channel) > 0 else { continue }

                let point = imagePointFromMask(x: x, y: y, image: image)
                guard image.bounds.contains(point) else { continue }

                let rgb = pixels.rgb(at: point, in: image)
                let hsv = Self.rgbToHSV(rgb)

                if Self.hsvDistance(targetHSV, hsv) <= tol {
                    valid[y * size + x] = true
                }
            }
        }

        return valid
    }

    func largestBlob(in valid: [Bool], width: Int, height: Int) -> Blob {
        var visited = [Bool](repeating: false, count: valid.count)
        var best = [Int]()

        let dx = [-1, 1, 0, 0, -1, -1, 1, 1]
        let dy = [0, 0, -1, 1, -1, 1, -1, 1]

        for y in 0..<height {
            for x in 0..<width {
                let start = y * width + x
                guard valid[start], !visited[start] else { continue }

                var blob = [Int]()
                var queue = [start]
                var head = 0
                visited[start] = true

                while head < queue.count {
                    let cur = queue[head]
                    head += 1
                    blob.append(cur)

                    let cx = cur % width
                    let cy = cur / width

                    for i in 0..<8 {
                        let nx = cx + dx[i]
                        let ny = cy + dy[i]
                        guard nx >= 0, nx < width, ny >= 0, ny < height else { continue }

                        let next = ny * width + nx
                        guard valid[next], !visited[next] else { continue }

                        visited[next] = true
                        queue.append(next)
                    }
                }

                if blob.count > best.count { best = blob }
            }
        }

        return makeBlob(from: best, width: width)
    }

    func makeBlob(from indices: [Int], width: Int) -> Blob {
        guard !indices.isEmpty else { return .empty }

        var minX = Int.max
        var minY = Int.max
        var maxX = Int.min
        var maxY = Int.min

        for idx in indices {
            let x = idx % width
            let y = idx / width
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
        }

        let box = CGRect(
            x: CGFloat(minX),
            y: CGFloat(minY),
            width: CGFloat(maxX - minX + 1),
            height: CGFloat(maxY - minY + 1)
        )

        return Blob(indices: indices, box: box)
    }

    func classify(_ blob: Blob, image: UIImage) -> HitStatus {
        guard !blob.isEmpty else { return .empty }

        let visible = visibleMaskSize(for: image)
        let spanX = blob.box.width / max(visible.width, 1)
        let spanY = blob.box.height / max(visible.height, 1)
        let areaRatio = CGFloat(blob.area) / max(visible.width * visible.height, 1)

        if spanX >= Cfg.wallSpanRatio || spanY >= Cfg.wallSpanRatio { return .wall }
        if areaRatio <= Cfg.tinyAreaRatio { return .tiny }
        return .good
    }

    func visibleMaskSize(for image: UIImage) -> CGSize {
        let maxDim = max(image.size.width, image.size.height)
        return CGSize(
            width: CGFloat(Cfg.maskSize) * image.size.width / maxDim,
            height: CGFloat(Cfg.maskSize) * image.size.height / maxDim
        )
    }

    func visibleMaskArea(for image: UIImage) -> CGFloat {
        let size = visibleMaskSize(for: image)
        return size.width * size.height
    }

    func minHoldArea(image: UIImage) -> Int {
        Int(visibleMaskArea(for: image) * Cfg.tinyAreaRatio)
    }

    // MARK: - 좌표 변환

    func imagePoint(from viewPoint: CGPoint, image: UIImage) -> CGPoint? {
        let viewSize = imgView.bounds.size
        guard viewSize.width > 0, viewSize.height > 0, image.size.width > 0, image.size.height > 0 else { return nil }

        let widthScale = viewSize.width / image.size.width
        let heightScale = viewSize.height / image.size.height
        let scale: CGFloat
        switch imgView.contentMode {
        case .scaleAspectFit:
            scale = min(widthScale, heightScale)
        case .scaleAspectFill:
            scale = max(widthScale, heightScale)
        default:
            scale = min(widthScale, heightScale)
        }

        let shownW = image.size.width * scale
        let shownH = image.size.height * scale
        let offsetX = (viewSize.width - shownW) / 2
        let offsetY = (viewSize.height - shownH) / 2

        let x = (viewPoint.x - offsetX) / scale
        let y = (viewPoint.y - offsetY) / scale

        guard x >= 0, x <= image.size.width, y >= 0, y <= image.size.height else { return nil }
        return CGPoint(x: x, y: y)
    }

    func imagePointFromMask(x: Int, y: Int, image: UIImage) -> CGPoint {
        let modelX = (CGFloat(x) + 0.5) * CGFloat(Cfg.samSize) / CGFloat(Cfg.maskSize)
        let modelY = (CGFloat(y) + 0.5) * CGFloat(Cfg.samSize) / CGFloat(Cfg.maskSize)
        return CGPoint(x: modelX / samScale, y: modelY / samScale)
    }

    // MARK: - 배열 도우미

    func maskChannel(_ mask: MLMultiArray) -> Int {
        let channels = mask.shape[1].intValue
        return channels > 1 ? 1 : 0
    }

    func maskValue(_ mask: MLMultiArray, x: Int, y: Int, channel: Int) -> Float {
        let index = [0, NSNumber(value: channel), NSNumber(value: y), NSNumber(value: x)]
        return mask[index].floatValue
    }

    // MARK: - 색상

    static func rgbToHSV(_ rgb: RGB) -> HSV {
        let r = Float(rgb.r) / 255
        let g = Float(rgb.g) / 255
        let b = Float(rgb.b) / 255

        let maxC = max(r, max(g, b))
        let minC = min(r, min(g, b))
        let delta = maxC - minC

        var h: Float = 0
        if delta != 0 {
            if maxC == r {
                h = 60 * ((g - b) / delta).truncatingRemainder(dividingBy: 6)
            } else if maxC == g {
                h = 60 * (((b - r) / delta) + 2)
            } else {
                h = 60 * (((r - g) / delta) + 4)
            }
        }

        if h < 0 { h += 360 }
        let s = maxC == 0 ? 0 : delta / maxC
        return (h, s, maxC)
    }

    static func hsvDistance(_ a: HSV, _ b: HSV) -> Float {
        var dh = abs(a.h - b.h)
        if dh > 180 { dh = 360 - dh }
        dh /= 180

        let ds = abs(a.s - b.s)
        let dv = abs(a.v - b.v)
        return (dh * 0.8) + (ds * 0.1) + (dv * 0.1)
    }
}
