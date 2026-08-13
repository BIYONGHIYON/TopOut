import UIKit
import CoreML

/// MobileSAM으로 매트 상단 경계를 찾습니다.
///
/// Android와 동일한 단순 규칙을 사용합니다.
/// 1. 화면 중앙 x=50%, y=70%/80%/90%/95%에 각각 양성 프롬프트를 입력합니다.
/// 2. 프롬프트와 연결된 마스크의 가로폭이 화면의 40% 이상이고,
///    마스크 최상단이 화면 높이 50% 아래에 있는 후보만 유지합니다.
/// 3. 마스크 윗경계에 맞춘 직선의 기울기가 15도를 초과하면 제외합니다.
/// 4. 남은 후보 중 윗경계가 화면에서 가장 높은 후보를 선택합니다.
/// 5. 후보가 없거나 추론에 실패하면 화면 높이 75% 기본선을 사용합니다.
///
/// 검출된 경계는 별도의 수직 보정 없이 원본 위치 그대로 사용합니다.
extension HoldVC {
    private struct SAMMatCandidate {
        let topY: CGFloat
        let horizontalSpan: CGFloat
        let line: [CGPoint]
    }

    private struct SAMMatComponent {
        let topByX: [Int]
        let minX: Int
        let maxX: Int
        let minY: Int
        let maxY: Int
    }

    private struct SAMMatBoundaryPoint {
        let x: CGFloat
        let y: CGFloat
    }

    static let samMatDefaultLine: [CGPoint] = [
        CGPoint(x: 0.06, y: 0.75),
        CGPoint(x: 0.94, y: 0.75)
    ]

    private static let samMatPromptLevels: [CGFloat] = [0.70, 0.80, 0.90, 0.95]
    private static let samMatMinimumHorizontalSpan: CGFloat = 0.40
    private static let samMatMinimumTopY: CGFloat = 0.50
    private static let samMatMaximumBoundaryAngleDegrees: CGFloat = 15
    private static let samMatLeftX: CGFloat = 0.06
    private static let samMatRightX: CGFloat = 0.94

    /// UI를 막지 않도록 기존 SAM 작업 큐에서 실행합니다.
    /// UI 쪽 watchdog은 Core ML이 응답하지 않더라도 기본 경계로 촬영 흐름을 계속합니다.
    func detectMatBoundaryWithMobileSAM(
        in image: UIImage,
        completion: @escaping ([CGPoint]) -> Void
    ) {
        workQ.async { [weak self] in
            let result = autoreleasepool {
                self?.detectMatBoundaryWithMobileSAMSynchronously(in: image)
                    ?? Self.samMatDefaultLine
            }

            DispatchQueue.main.async {
                completion(result)
            }
        }
    }

    private func detectMatBoundaryWithMobileSAMSynchronously(in image: UIImage) -> [CGPoint] {
        guard image.size.width > 0, image.size.height > 0 else {
            return Self.samMatDefaultLine
        }

        do {
            // Mat analysis and top-hold selection intentionally use different
            // camera frames. Do not publish this Ready-time embedding to the
            // shared tap-selection cache.
            let embedding = try makeEmbedding(for: image)
            var bestCandidate: SAMMatCandidate?

            for promptY in Self.samMatPromptLevels {
                // Decoder outputs and connected-component work buffers are only
                // needed for one prompt at a time. Drain autoreleased Core ML
                // objects between prompts to lower the inference peak on small-RAM
                // devices while preserving the exact candidate ordering.
                let promptCandidates: [SAMMatCandidate] = try autoreleasepool {
                    let positive = CGPoint(
                        x: image.size.width * 0.50,
                        y: image.size.height * promptY
                    )
                    let negative = CGPoint(
                        x: image.size.width * 0.50,
                        y: image.size.height * 0.28
                    )

                    let mask = try decodeMatMask(
                        positive: positive,
                        negative: negative,
                        embedding: embedding
                    )

                    var candidates: [SAMMatCandidate] = []
                    let channelCount = max(1, mask.shape[1].intValue)
                    for channel in 0..<channelCount {
                        if let candidate = makeSAMMatCandidate(
                            from: mask,
                            channel: channel,
                            positivePoint: positive,
                            image: image
                        ) {
                            candidates.append(candidate)
                        }
                    }
                    return candidates
                }

                // Apply candidates in the original prompt/channel order so the
                // existing epsilon tie-breaking behavior remains bit-for-bit equivalent.
                for candidate in promptCandidates where isBetterSAMMatCandidate(candidate, than: bestCandidate) {
                    bestCandidate = candidate
                }
            }

            return bestCandidate?.line ?? Self.samMatDefaultLine
        } catch {
            return Self.samMatDefaultLine
        }
    }

    private func isBetterSAMMatCandidate(
        _ candidate: SAMMatCandidate,
        than current: SAMMatCandidate?
    ) -> Bool {
        guard let current else { return true }
        let isHigher = candidate.topY < current.topY - 0.000_001
        let isWiderAtSameHeight =
            abs(candidate.topY - current.topY) <= 0.000_001 &&
            candidate.horizontalSpan > current.horizontalSpan
        return isHigher || isWiderAtSameHeight
    }

    /// MobileSAM decoder의 두 포인트 입력을 양성 1개 + 음성 1개로 사용합니다.
    private func decodeMatMask(
        positive: CGPoint,
        negative: CGPoint,
        embedding: MLMultiArray
    ) throws -> MLMultiArray {
        try loadModelsIfNeeded()
        guard let decoder else { throw AppError.modelNotReady }

        let coords = try MLMultiArray(shape: [1, 2, 2], dataType: .float32)
        let labels = try MLMultiArray(shape: [1, 2], dataType: .float32)

        coords[0] = NSNumber(value: Float(positive.x * samScale))
        coords[1] = NSNumber(value: Float(positive.y * samScale))
        labels[0] = 1.0

        coords[2] = NSNumber(value: Float(negative.x * samScale))
        coords[3] = NSNumber(value: Float(negative.y * samScale))
        labels[1] = 0.0

        let maskInput = try MLMultiArray(
            shape: [1, 1, NSNumber(value: Cfg.maskSize), NSNumber(value: Cfg.maskSize)],
            dataType: .float32
        )
        let hasMask = try MLMultiArray(shape: [1], dataType: .float32)
        hasMask[0] = 0.0

        let input = MobileSAM_DecoderInput(
            image_embeddings: embedding,
            point_coords: coords,
            point_labels: labels,
            mask_input: maskInput,
            has_mask_input: hasMask
        )

        return try decoder.prediction(input: input).low_res_masks
    }

    private func makeSAMMatCandidate(
        from mask: MLMultiArray,
        channel: Int,
        positivePoint: CGPoint,
        image: UIImage
    ) -> SAMMatCandidate? {
        let size = Cfg.maskSize
        let visible = visibleMaskSize(for: image)
        let visibleWidth = max(1, min(size, Int(floor(visible.width))))
        let visibleHeight = max(1, min(size, Int(floor(visible.height))))

        let seedModelX = positivePoint.x * samScale
        let seedModelY = positivePoint.y * samScale
        let seedX = clampSAMMatInt(
            Int(seedModelX / CGFloat(Cfg.samSize) * CGFloat(size)),
            0,
            visibleWidth - 1
        )
        let seedY = clampSAMMatInt(
            Int(seedModelY / CGFloat(Cfg.samSize) * CGFloat(size)),
            0,
            visibleHeight - 1
        )

        var positive = [Bool](repeating: false, count: size * size)
        for y in 0..<visibleHeight {
            for x in 0..<visibleWidth {
                positive[y * size + x] = maskValue(mask, x: x, y: y, channel: channel) > 0
            }
        }

        guard let componentSeed = nearestSAMMatPositivePixel(
            in: positive,
            width: size,
            visibleWidth: visibleWidth,
            visibleHeight: visibleHeight,
            originX: seedX,
            originY: seedY,
            maxRadius: 12
        ), let component = connectedSAMMatComponent(
            in: positive,
            width: size,
            visibleWidth: visibleWidth,
            visibleHeight: visibleHeight,
            seed: componentSeed
        ) else {
            return nil
        }

        let horizontalSpan = CGFloat(component.maxX - component.minX + 1) / CGFloat(visibleWidth)
        guard horizontalSpan >= Self.samMatMinimumHorizontalSpan else { return nil }

        let topY = CGFloat(component.minY) / CGFloat(visibleHeight)
        guard topY >= Self.samMatMinimumTopY else { return nil }

        let boundary = extractSAMMatUpperBoundary(
            component: component,
            visibleWidth: visibleWidth,
            visibleHeight: visibleHeight
        )
        guard boundary.count >= 2,
              let fit = fitSAMMatLine(boundary) else {
            return nil
        }

        // 정규화 좌표의 기울기를 실제 마스크 픽셀 좌표 기울기로 변환한 뒤 각도를 계산합니다.
        let pixelSlope = fit.slope * CGFloat(visibleHeight) / CGFloat(visibleWidth)
        let angleDegrees = CGFloat(atan(Double(abs(pixelSlope))) * 180.0 / Double.pi)
        guard angleDegrees <= Self.samMatMaximumBoundaryAngleDegrees else { return nil }

        let leftY = clampSAMMat(
            fit.slope * Self.samMatLeftX + fit.intercept,
            Self.samMatMinimumTopY,
            1
        )
        let rightY = clampSAMMat(
            fit.slope * Self.samMatRightX + fit.intercept,
            Self.samMatMinimumTopY,
            1
        )

        guard leftY.isFinite, rightY.isFinite else { return nil }

        return SAMMatCandidate(
            topY: topY,
            horizontalSpan: horizontalSpan,
            line: [
                CGPoint(x: Self.samMatLeftX, y: leftY),
                CGPoint(x: Self.samMatRightX, y: rightY)
            ]
        )
    }

    /// decoder 좌표와 저해상도 마스크가 소수 픽셀 어긋날 수 있어 가까운 양성 픽셀을 찾습니다.
    private func nearestSAMMatPositivePixel(
        in map: [Bool],
        width: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        originX: Int,
        originY: Int,
        maxRadius: Int
    ) -> Int? {
        func positiveIndex(x: Int, y: Int) -> Int? {
            guard x >= 0, x < visibleWidth, y >= 0, y < visibleHeight else { return nil }
            let index = y * width + x
            return map[index] ? index : nil
        }

        if let index = positiveIndex(x: originX, y: originY) { return index }

        guard maxRadius > 0 else { return nil }
        for radius in 1...maxRadius {
            let left = originX - radius
            let right = originX + radius
            let top = originY - radius
            let bottom = originY + radius

            if left <= right {
                for x in left...right {
                    if let index = positiveIndex(x: x, y: top) { return index }
                    if let index = positiveIndex(x: x, y: bottom) { return index }
                }
            }
            if top + 1 < bottom {
                for y in (top + 1)..<bottom {
                    if let index = positiveIndex(x: left, y: y) { return index }
                    if let index = positiveIndex(x: right, y: y) { return index }
                }
            }
        }
        return nil
    }

    /// 프롬프트가 포함된 4방향 연결 성분만 유지합니다.
    private func connectedSAMMatComponent(
        in source: [Bool],
        width: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        seed: Int
    ) -> SAMMatComponent? {
        guard seed >= 0, seed < source.count, source[seed] else { return nil }

        var visited = [Bool](repeating: false, count: width * visibleHeight)
        var topByX = [Int](repeating: .max, count: visibleWidth)
        var queue = [seed]
        var head = 0
        visited[seed] = true

        var minX = visibleWidth
        var maxX = -1
        var minY = visibleHeight
        var maxY = -1

        while head < queue.count {
            let index = queue[head]
            head += 1

            let x = index % width
            let y = index / width
            guard x >= 0, x < visibleWidth, y >= 0, y < visibleHeight, source[index] else {
                continue
            }

            topByX[x] = min(topByX[x], y)
            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)

            func enqueue(_ nextX: Int, _ nextY: Int) {
                guard nextX >= 0, nextX < visibleWidth,
                      nextY >= 0, nextY < visibleHeight else { return }
                let next = nextY * width + nextX
                guard !visited[next], source[next] else { return }
                visited[next] = true
                queue.append(next)
            }

            enqueue(x - 1, y)
            enqueue(x + 1, y)
            enqueue(x, y - 1)
            enqueue(x, y + 1)
        }

        guard maxX >= minX, maxY >= minY else { return nil }
        return SAMMatComponent(
            topByX: topByX,
            minX: minX,
            maxX: maxX,
            minY: minY,
            maxY: maxY
        )
    }

    /// 연결 성분이 차지하는 각 x열에서 가장 위쪽 픽셀을 윗경계로 사용합니다.
    private func extractSAMMatUpperBoundary(
        component: SAMMatComponent,
        visibleWidth: Int,
        visibleHeight: Int
    ) -> [SAMMatBoundaryPoint] {
        var points: [SAMMatBoundaryPoint] = []
        points.reserveCapacity(component.maxX - component.minX + 1)

        for x in component.minX...component.maxX {
            let topY = component.topByX[x]
            guard topY != .max else { continue }
            points.append(
                SAMMatBoundaryPoint(
                    x: CGFloat(x) / CGFloat(max(1, visibleWidth - 1)),
                    y: CGFloat(topY) / CGFloat(max(1, visibleHeight - 1))
                )
            )
        }
        return points
    }

    /// 최소제곱 직선 y = slope*x + intercept를 계산합니다.
    private func fitSAMMatLine(
        _ points: [SAMMatBoundaryPoint]
    ) -> (slope: CGFloat, intercept: CGFloat)? {
        guard points.count >= 2 else { return nil }

        let count = CGFloat(points.count)
        let meanX = points.reduce(CGFloat(0)) { $0 + $1.x } / count
        let meanY = points.reduce(CGFloat(0)) { $0 + $1.y } / count

        var numerator: CGFloat = 0
        var denominator: CGFloat = 0
        for point in points {
            let dx = point.x - meanX
            numerator += dx * (point.y - meanY)
            denominator += dx * dx
        }

        let slope = denominator > 0.000_000_01 ? numerator / denominator : 0
        return (slope, meanY - slope * meanX)
    }

    private func clampSAMMat(_ value: CGFloat, _ lower: CGFloat, _ upper: CGFloat) -> CGFloat {
        max(lower, min(upper, value))
    }

    private func clampSAMMatInt(_ value: Int, _ lower: Int, _ upper: Int) -> Int {
        max(lower, min(upper, value))
    }
}
