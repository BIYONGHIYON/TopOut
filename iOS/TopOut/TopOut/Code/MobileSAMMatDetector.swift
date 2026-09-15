import UIKit
import CoreML

/// MobileSAM으로 매트 상단 경계를 찾습니다.
///
/// Android와 동일한 단순 규칙을 사용합니다.
/// 1. 화면 중앙 x=50%, y=70%/80%/90%/95%에 각각 양성 프롬프트를 입력합니다.
/// 2. 프롬프트와 연결된 성분을 기준으로, 분리된 상단 조각까지 동일한 강건 직선을
///    지지하는 열이 화면 너비의 60% 이상인 후보만 유지합니다.
///    빈 열과 위·아래로 튀는 이상치 열은 지지 비율에 포함하지 않습니다.
/// 3. 지지 픽셀의 최상단이 화면 높이 50% 아래에 있는 후보만 유지합니다.
/// 4. 마스크 윗경계에 맞춘 직선의 기울기가 15도를 초과하면 제외합니다.
/// 5. 남은 후보 중 윗경계가 화면에서 가장 높은 후보를 선택합니다.
/// 6. 후보가 없거나 추론에 실패하면 화면 높이 75% 기본선을 사용합니다.
///
/// 검출된 경계는 별도의 수직 보정 없이 원본 위치 그대로 사용합니다.
extension HoldVC {
    private struct SAMMatCandidate {
        let topY: CGFloat
        let lineSupportRatio: CGFloat
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
    private static let samMatMinimumLineSupportRatio: CGFloat = 0.60
    private static let samMatMinimumTopY: CGFloat = 0.50
    private static let samMatMaximumBoundaryAngleDegrees: CGFloat = 15
    private static let samMatBoundaryAlignmentTolerancePixels: CGFloat = 2
    private static let samMatLineRefinementPasses = 2
    private static let samMatLeftX: CGFloat = 0.06
    private static let samMatRightX: CGFloat = 0.94

    /// UI를 막지 않도록 기존 SAM 작업 큐에서 실행합니다.
    /// UI 쪽 watchdog은 Core ML이 응답하지 않더라도 기본 경계로 촬영 흐름을 계속합니다.
    func detectMatBoundaryWithMobileSAM(
        in image: UIImage,
        cancellationToken: AnalysisCancellationToken,
        completion: @escaping ([CGPoint]) -> Void
    ) {
        workQ.async { [weak self] in
            let result = autoreleasepool {
                self?.detectMatBoundaryWithMobileSAMSynchronously(in: image, cancellationToken: cancellationToken)
                    ?? Self.samMatDefaultLine
            }

            DispatchQueue.main.async {
                completion(result)
            }
        }
    }

    private func detectMatBoundaryWithMobileSAMSynchronously(in image: UIImage, cancellationToken: AnalysisCancellationToken) -> [CGPoint] {
        guard image.size.width > 0, image.size.height > 0 else {
            return Self.samMatDefaultLine
        }

        do {
            try cancellationToken.check()
            // 매트 분석 임베딩은 탑홀드 선택 캐시에 저장하지 않는다.
            let embedding = try makeEmbedding(for: image, cancellationToken: cancellationToken)
            try cancellationToken.check()
            var bestCandidate: SAMMatCandidate?

            for promptY in Self.samMatPromptLevels {
                try cancellationToken.check()
                // 프롬프트마다 임시 추론 객체를 비워 저용량 기기의 최대 메모리를 낮춘다.
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
                        embedding: embedding.values,
                        scale: embedding.scale
                    )

                    var candidates: [SAMMatCandidate] = []
                    let channelCount = max(1, mask.shape[1].intValue)
                    for channel in 0..<channelCount {
                        try cancellationToken.check()
                        if let candidate = makeSAMMatCandidate(
                            from: mask,
                            channel: channel,
                            positivePoint: positive,
                            scale: embedding.scale,
                            image: image
                        ) {
                            candidates.append(candidate)
                        }
                    }
                    return candidates
                }

                // 기존 동률 판정을 유지하도록 프롬프트와 채널 순서대로 후보를 적용한다.
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
            candidate.lineSupportRatio > current.lineSupportRatio
        return isHigher || isWiderAtSameHeight
    }

    /// MobileSAM decoder의 두 포인트 입력을 양성 1개 + 음성 1개로 사용합니다.
    private func decodeMatMask(
        positive: CGPoint,
        negative: CGPoint,
        embedding: MLMultiArray,
        scale: CGFloat
    ) throws -> MLMultiArray {
        try loadModelsIfNeeded()
        guard let decoder else { throw AppError.modelNotReady }

        let coords = try MLMultiArray(shape: [1, 2, 2], dataType: .float32)
        let labels = try MLMultiArray(shape: [1, 2], dataType: .float32)

        coords[0] = NSNumber(value: Float(positive.x * scale))
        coords[1] = NSNumber(value: Float(positive.y * scale))
        labels[0] = 1.0

        coords[2] = NSNumber(value: Float(negative.x * scale))
        coords[3] = NSNumber(value: Float(negative.y * scale))
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
        scale: CGFloat,
        image: UIImage
    ) -> SAMMatCandidate? {
        let size = Cfg.maskSize
        let visible = visibleMaskSize(for: image)
        let visibleWidth = max(1, min(size, Int(floor(visible.width))))
        let visibleHeight = max(1, min(size, Int(floor(visible.height))))

        let seedModelX = positivePoint.x * scale
        let seedModelY = positivePoint.y * scale
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

        let anchorBoundary = extractSAMMatUpperBoundary(
            component: component,
            visibleWidth: visibleWidth,
            visibleHeight: visibleHeight
        )
        guard let firstAnchor = anchorBoundary.first else { return nil }

        // 프롬프트 연결 성분은 기준점으로만 사용하고, 떨어진 마스크 조각도 같은 상단
        // 직선을 지지하면 합산합니다. 중앙값 기반 직선은 일부 열이 위·아래로 튀어도
        // 경계를 끌어가지 않으며, 빈 열과 이상치 열은 60% 지지 비율에서 제외됩니다.
        var fit = fitRobustSAMMatLine(anchorBoundary) ?? (slope: 0, intercept: firstAnchor.y)
        for _ in 0..<Self.samMatLineRefinementPasses {
            let aligned = extractAlignedSAMMatUpperBoundary(
                in: positive,
                width: size,
                visibleWidth: visibleWidth,
                visibleHeight: visibleHeight,
                fit: fit
            )
            guard let refined = fitRobustSAMMatLine(aligned) else { return nil }
            fit = refined
        }

        let boundary = extractAlignedSAMMatUpperBoundary(
            in: positive,
            width: size,
            visibleWidth: visibleWidth,
            visibleHeight: visibleHeight,
            fit: fit
        )
        let lineSupportRatio = CGFloat(boundary.count) / CGFloat(visibleWidth)
        guard lineSupportRatio >= Self.samMatMinimumLineSupportRatio,
              let finalFit = fitRobustSAMMatLine(boundary),
              let topY = boundary.map(\.y).min(),
              topY >= Self.samMatMinimumTopY else { return nil }
        fit = finalFit

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
            lineSupportRatio: lineSupportRatio,
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

    /// 같은 직선에서 허용 오차 안에 있는 상단 픽셀을 x열마다 최대 하나 수집합니다.
    /// 분리된 조각은 함께 기여하지만 빈 열과 위·아래로 튀는 열은 비율에 포함되지 않습니다.
    private func extractAlignedSAMMatUpperBoundary(
        in source: [Bool],
        width: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        fit: (slope: CGFloat, intercept: CGFloat)
    ) -> [SAMMatBoundaryPoint] {
        var points: [SAMMatBoundaryPoint] = []
        points.reserveCapacity(visibleWidth)

        let widthDenominator = CGFloat(max(1, visibleWidth - 1))
        let heightDenominator = CGFloat(max(1, visibleHeight - 1))
        let normalizedTolerance = Self.samMatBoundaryAlignmentTolerancePixels / heightDenominator

        for x in 0..<visibleWidth {
            let normalizedX = CGFloat(x) / widthDenominator
            let expectedY = fit.slope * normalizedX + fit.intercept
            var closestY: Int?
            var closestDistance = CGFloat.greatestFiniteMagnitude

            for y in 0..<visibleHeight {
                let index = y * width + x
                guard source[index] else { continue }
                let isUpperEdge = y == 0 || !source[(y - 1) * width + x]
                guard isUpperEdge else { continue }

                let normalizedY = CGFloat(y) / heightDenominator
                let distance = abs(normalizedY - expectedY)
                if distance <= normalizedTolerance, distance < closestDistance {
                    closestY = y
                    closestDistance = distance
                }
            }

            if let closestY {
                points.append(
                    SAMMatBoundaryPoint(
                        x: normalizedX,
                        y: CGFloat(closestY) / heightDenominator
                    )
                )
            }
        }
        return points
    }

    /// Theil-Sen 중앙값 직선으로 일부 상단 픽셀의 위·아래 이상치 영향을 제한합니다.
    private func fitRobustSAMMatLine(
        _ points: [SAMMatBoundaryPoint]
    ) -> (slope: CGFloat, intercept: CGFloat)? {
        guard points.count >= 2 else { return nil }

        var slopes: [CGFloat] = []
        slopes.reserveCapacity(points.count * (points.count - 1) / 2)
        for firstIndex in 0..<points.count - 1 {
            let first = points[firstIndex]
            for secondIndex in (firstIndex + 1)..<points.count {
                let second = points[secondIndex]
                let dx = second.x - first.x
                if abs(dx) > 0.000_000_01 {
                    slopes.append((second.y - first.y) / dx)
                }
            }
        }
        guard let slope = medianSAMMatValue(slopes),
              let intercept = medianSAMMatValue(points.map { $0.y - slope * $0.x }) else {
            return nil
        }
        return (slope, intercept)
    }

    private func medianSAMMatValue(_ values: [CGFloat]) -> CGFloat? {
        guard !values.isEmpty else { return nil }
        let sorted = values.sorted()
        let middle = sorted.count / 2
        if sorted.count.isMultiple(of: 2) {
            return (sorted[middle - 1] + sorted[middle]) / 2
        }
        return sorted[middle]
    }

    private func clampSAMMat(_ value: CGFloat, _ lower: CGFloat, _ upper: CGFloat) -> CGFloat {
        max(lower, min(upper, value))
    }

    private func clampSAMMatInt(_ value: Int, _ lower: Int, _ upper: Int) -> Int {
        max(lower, min(upper, value))
    }
}
