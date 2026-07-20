import UIKit
import CoreML

/// MobileSAM을 자동 프롬프트 방식으로 사용해 매트 상단 경계를 찾습니다.
///
/// 새로 학습한 경계 전용 모델은 사용하지 않습니다.
/// 화면 중앙의 y=80% → 85% → 90% 순서로 양성 프롬프트를 내려가며 탐색합니다.
/// 각 높이에서 중앙과 인접 지점을 확인하고, 세 단계 모두 신뢰도가 낮으면 75% 기본선을 사용합니다.
/// 매트 경계는 화면 높이 50~100% 안에 있어야 하며 과도한 기울기는 거부합니다.
extension HoldVC {
    private struct SAMMatCandidate {
        let leftY: CGFloat
        let rightY: CGFloat
        let centerY: CGFloat
        let slope: CGFloat
        let score: CGFloat
        let binCoverage: CGFloat
        let horizontalSpan: CGFloat
    }

    private struct SAMMatFitPoint {
        let x: CGFloat
        let y: CGFloat
        let weight: CGFloat
    }

    private static let samMatDefaultLine: [CGPoint] = [
        CGPoint(x: 0.06, y: 0.75),
        CGPoint(x: 0.94, y: 0.75)
    ]

    private static let samMatPromptLevels: [CGFloat] = [0.80, 0.85, 0.90]
    private static let samMatMinimumBoundaryY: CGFloat = 0.50
    private static let samMatMaximumBoundaryY: CGFloat = 1.00

    /// 좌우 끝점의 세로 차이는 화면 높이의 14% 이내로 제한합니다.
    /// 제공된 정답 데이터의 최대 기울기보다 약간 여유를 둔 값입니다.
    private static let samMatMaximumEndpointDelta: CGFloat = 0.14

    /// UI를 막지 않도록 기존 SAM 작업 큐에서 실행합니다.
    /// getEmbedding이 만든 임베딩은 이후 탑홀드 선택에서도 그대로 재사용됩니다.
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
            let embedding = try getEmbedding(for: image)
            var mediumConfidenceByLevel: [SAMMatCandidate] = []
            mediumConfidenceByLevel.reserveCapacity(Self.samMatPromptLevels.count)

            for promptY in Self.samMatPromptLevels {
                var stageCandidates: [SAMMatCandidate] = []
                stageCandidates.reserveCapacity(9)

                func evaluatePrompt(x normalizedX: CGFloat) throws {
                    let positive = CGPoint(
                        x: normalizedX * image.size.width,
                        y: promptY * image.size.height
                    )

                    // 매트 경계는 50% 아래에만 존재하므로 상단 벽 영역을 음성 프롬프트로 둡니다.
                    let negative = CGPoint(
                        x: normalizedX * image.size.width,
                        y: 0.28 * image.size.height
                    )

                    let mask = try decodeMatMask(
                        positive: positive,
                        negative: negative,
                        embedding: embedding
                    )

                    let channelCount = max(1, mask.shape[1].intValue)
                    for channel in 0..<channelCount {
                        if let candidate = makeSAMMatCandidate(
                            from: mask,
                            channel: channel,
                            positivePoint: positive,
                            image: image
                        ) {
                            stageCandidates.append(candidate)
                        }
                    }
                }

                // 각 높이에서 중앙을 먼저 확인해 불필요한 decoder 호출을 줄입니다.
                try evaluatePrompt(x: 0.50)
                if let centerCandidate = combineSAMMatCandidates(stageCandidates),
                   isStrongSAMMatCandidate(centerCandidate, strict: true) {
                    return lineFromSAMMatCandidate(centerCandidate)
                }

                // 중앙이 사람·가방·볼륨에 가려졌을 때 같은 높이의 좌우 지점을 확인합니다.
                try evaluatePrompt(x: 0.42)
                try evaluatePrompt(x: 0.58)

                if let stageCandidate = combineSAMMatCandidates(stageCandidates) {
                    if isStrongSAMMatCandidate(stageCandidate, strict: false) {
                        return lineFromSAMMatCandidate(stageCandidate)
                    }

                    if isMediumSAMMatCandidate(stageCandidate) {
                        mediumConfidenceByLevel.append(stageCandidate)
                    }
                }
            }

            // 한 단계에서는 확신이 부족해도 서로 다른 두 프롬프트 높이가
            // 같은 경계를 반복해서 찾으면 일관성 있는 결과로 인정합니다.
            if let consensus = consistentSAMMatCandidate(from: mediumConfidenceByLevel) {
                return lineFromSAMMatCandidate(consensus)
            }

            // 80%, 85%, 90% 모두 신뢰도가 낮으면 잘못된 선 대신 75% 기본선을 사용합니다.
            return Self.samMatDefaultLine
        } catch {
            return Self.samMatDefaultLine
        }
    }

    /// MobileSAM decoder의 두 포인트 입력을 양성 1개 + 음성 1개로 사용합니다.

    private func isStrongSAMMatCandidate(_ candidate: SAMMatCandidate, strict: Bool) -> Bool {
        guard isSAMMatGeometryValid(candidate) else { return false }

        if strict {
            return candidate.score >= 4.8
                && candidate.binCoverage >= 0.55
                && candidate.horizontalSpan >= 0.55
        }

        return candidate.score >= 4.25
            && candidate.binCoverage >= 0.42
            && candidate.horizontalSpan >= 0.45
    }

    private func isMediumSAMMatCandidate(_ candidate: SAMMatCandidate) -> Bool {
        isSAMMatGeometryValid(candidate)
            && candidate.score >= 3.75
            && candidate.binCoverage >= 0.34
            && candidate.horizontalSpan >= 0.38
    }

    private func isSAMMatGeometryValid(_ candidate: SAMMatCandidate) -> Bool {
        candidate.leftY >= Self.samMatMinimumBoundaryY
            && candidate.leftY <= Self.samMatMaximumBoundaryY
            && candidate.rightY >= Self.samMatMinimumBoundaryY
            && candidate.rightY <= Self.samMatMaximumBoundaryY
            && candidate.centerY >= Self.samMatMinimumBoundaryY
            && candidate.centerY <= Self.samMatMaximumBoundaryY
            && abs(candidate.rightY - candidate.leftY) <= Self.samMatMaximumEndpointDelta
    }

    private func consistentSAMMatCandidate(from candidates: [SAMMatCandidate]) -> SAMMatCandidate? {
        guard candidates.count >= 2 else { return nil }

        let ordered = candidates.sorted { $0.score > $1.score }
        for anchor in ordered {
            let agreeing = ordered.filter { candidate in
                abs(candidate.centerY - anchor.centerY) <= 0.04
                    && abs(candidate.slope - anchor.slope) <= 0.10
            }

            guard agreeing.count >= 2,
                  let combined = combineSAMMatCandidates(agreeing),
                  isMediumSAMMatCandidate(combined) else {
                continue
            }
            return combined
        }
        return nil
    }

    private func lineFromSAMMatCandidate(_ candidate: SAMMatCandidate) -> [CGPoint] {
        guard isSAMMatGeometryValid(candidate) else { return Self.samMatDefaultLine }

        return [
            CGPoint(
                x: 0.06,
                y: clampSAMMat(
                    candidate.leftY,
                    Self.samMatMinimumBoundaryY,
                    Self.samMatMaximumBoundaryY
                )
            ),
            CGPoint(
                x: 0.94,
                y: clampSAMMat(
                    candidate.rightY,
                    Self.samMatMinimumBoundaryY,
                    Self.samMatMaximumBoundaryY
                )
            )
        ]
    }

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
        ) else {
            return nil
        }

        let component = connectedSAMMatComponent(
            in: positive,
            width: size,
            visibleWidth: visibleWidth,
            visibleHeight: visibleHeight,
            seed: componentSeed
        )

        let visibleArea = CGFloat(visibleWidth * visibleHeight)
        let areaRatio = CGFloat(component.indices.count) / max(visibleArea, 1)
        guard areaRatio >= 0.035, areaRatio <= 0.82 else { return nil }

        let horizontalSpan = CGFloat(component.maxX - component.minX + 1) / CGFloat(visibleWidth)
        let verticalSpan = CGFloat(component.maxY - component.minY + 1) / CGFloat(visibleHeight)
        guard horizontalSpan >= 0.28, verticalSpan >= 0.08 else { return nil }

        let boundaryPoints = extractSAMMatBoundaryPoints(
            componentMap: component.map,
            width: size,
            visibleWidth: visibleWidth,
            visibleHeight: visibleHeight,
            binCount: 24
        )
        guard boundaryPoints.count >= 7 else { return nil }

        guard let initial = weightedSAMMatFit(boundaryPoints) else { return nil }
        let residuals = boundaryPoints.map { abs($0.y - (initial.slope * $0.x + initial.intercept)) }
        let medianResidual = medianSAMMat(residuals)
        let tolerance = max(0.018, medianResidual * 2.8)
        let inliers = zip(boundaryPoints, residuals)
            .filter { $0.1 <= tolerance }
            .map(\.0)

        guard inliers.count >= 7 else { return nil }
        let fit = weightedSAMMatFit(inliers) ?? initial

        let leftX: CGFloat = 0.06
        let rightX: CGFloat = 0.94
        let leftY = fit.slope * leftX + fit.intercept
        let rightY = fit.slope * rightX + fit.intercept
        let centerY = (leftY + rightY) * 0.5

        guard leftY.isFinite, rightY.isFinite,
              leftY >= Self.samMatMinimumBoundaryY,
              leftY <= Self.samMatMaximumBoundaryY,
              rightY >= Self.samMatMinimumBoundaryY,
              rightY <= Self.samMatMaximumBoundaryY,
              centerY >= Self.samMatMinimumBoundaryY,
              centerY <= Self.samMatMaximumBoundaryY,
              abs(rightY - leftY) <= Self.samMatMaximumEndpointDelta else {
            return nil
        }

        // 프롬프트가 경계보다 충분히 아래쪽에 있어야 매트 후보로 봅니다.
        let normalizedSeedY = positivePoint.y / image.size.height
        guard centerY <= normalizedSeedY + 0.035 else { return nil }

        let binCoverage = CGFloat(inliers.count) / 24.0
        let lowerOccupancy = samMatLowerCentralOccupancy(
            componentMap: component.map,
            width: size,
            visibleWidth: visibleWidth,
            visibleHeight: visibleHeight,
            boundaryY: centerY
        )
        let bottomReach = CGFloat(component.maxY + 1) / CGFloat(visibleHeight)
        let depthBelowBoundary = max(0, bottomReach - centerY)

        // 넓고, 하단 중앙을 안정적으로 포함하며, 여러 가로 구간에서 같은 상단선을 만드는 마스크를 우선합니다.
        var score: CGFloat = 0
        score += 2.8 * horizontalSpan
        score += 2.4 * binCoverage
        score += 1.8 * lowerOccupancy
        score += 1.1 * min(depthBelowBoundary / 0.25, 1)
        score += 0.6 * min(areaRatio / 0.30, 1)
        score -= 1.4 * min(medianResidual / 0.05, 1)

        if horizontalSpan < 0.45 { score -= 0.8 }

        guard score >= 3.15 else { return nil }

        return SAMMatCandidate(
            leftY: leftY,
            rightY: rightY,
            centerY: centerY,
            slope: fit.slope,
            score: score,
            binCoverage: binCoverage,
            horizontalSpan: horizontalSpan
        )
    }

    private func combineSAMMatCandidates(_ candidates: [SAMMatCandidate]) -> SAMMatCandidate? {
        guard let best = candidates.max(by: { $0.score < $1.score }) else { return nil }

        // 서로 다른 프롬프트가 같은 경계를 가리키면 중앙값으로 결합해 사람/가방 가림에 대한 흔들림을 줄입니다.
        let compatible = candidates.filter {
            abs($0.centerY - best.centerY) <= 0.045
                && abs($0.slope - best.slope) <= 0.14
                && $0.score >= best.score - 1.15
        }

        guard !compatible.isEmpty else { return best }

        let weighted = compatible.map { candidate -> (SAMMatCandidate, CGFloat) in
            let weight = max(0.1, candidate.score)
                * max(0.25, candidate.binCoverage)
                * max(0.25, candidate.horizontalSpan)
            return (candidate, weight)
        }
        let totalWeight = weighted.reduce(CGFloat(0)) { $0 + $1.1 }
        guard totalWeight > 0 else { return best }

        let leftY = weighted.reduce(CGFloat(0)) { $0 + $1.0.leftY * $1.1 } / totalWeight
        let rightY = weighted.reduce(CGFloat(0)) { $0 + $1.0.rightY * $1.1 } / totalWeight
        let centerY = (leftY + rightY) * 0.5
        let slope = (rightY - leftY) / 0.88

        return SAMMatCandidate(
            leftY: leftY,
            rightY: rightY,
            centerY: centerY,
            slope: slope,
            score: best.score,
            binCoverage: best.binCoverage,
            horizontalSpan: best.horizontalSpan
        )
    }

    private func extractSAMMatBoundaryPoints(
        componentMap: [Bool],
        width: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        binCount: Int
    ) -> [SAMMatFitPoint] {
        var points: [SAMMatFitPoint] = []
        points.reserveCapacity(binCount)

        for bin in 0..<binCount {
            let startX = max(0, Int(CGFloat(bin) / CGFloat(binCount) * CGFloat(visibleWidth)))
            let endX = min(
                visibleWidth,
                max(startX + 1, Int(CGFloat(bin + 1) / CGFloat(binCount) * CGFloat(visibleWidth)))
            )

            var columnTops: [Int] = []
            columnTops.reserveCapacity(endX - startX)

            for x in startX..<endX {
                if let top = stableSAMMatColumnTop(
                    componentMap: componentMap,
                    width: width,
                    x: x,
                    visibleHeight: visibleHeight
                ) {
                    columnTops.append(top)
                }
            }

            guard columnTops.count >= max(1, (endX - startX) / 3) else { continue }
            columnTops.sort()

            // 가장 위의 단일 노이즈가 아니라, 해당 bin에서 반복되는 상단 위치를 사용합니다.
            let index = min(columnTops.count - 1, max(0, Int(CGFloat(columnTops.count - 1) * 0.28)))
            let topY = columnTops[index]
            let centerX = (CGFloat(startX + endX) * 0.5) / CGFloat(visibleWidth)
            let normalizedY = CGFloat(topY) / CGFloat(visibleHeight)
            guard normalizedY >= Self.samMatMinimumBoundaryY,
                  normalizedY <= Self.samMatMaximumBoundaryY else {
                continue
            }

            let weight = CGFloat(columnTops.count) / CGFloat(max(endX - startX, 1))
            points.append(SAMMatFitPoint(x: centerX, y: normalizedY, weight: max(weight, 0.15)))
        }

        return points
    }

    private func stableSAMMatColumnTop(
        componentMap: [Bool],
        width: Int,
        x: Int,
        visibleHeight: Int
    ) -> Int? {
        let runWindow = 7
        let required = 4
        guard visibleHeight >= runWindow else { return nil }

        for y in 0...(visibleHeight - runWindow) {
            var count = 0
            for offset in 0..<runWindow {
                if componentMap[(y + offset) * width + x] { count += 1 }
            }
            if count >= required { return y }
        }
        return nil
    }

    private func samMatLowerCentralOccupancy(
        componentMap: [Bool],
        width: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        boundaryY: CGFloat
    ) -> CGFloat {
        let x0 = clampSAMMatInt(Int(CGFloat(visibleWidth) * 0.34), 0, visibleWidth - 1)
        let x1 = clampSAMMatInt(Int(CGFloat(visibleWidth) * 0.66), x0 + 1, visibleWidth)

        // 고정된 74~90% 영역을 보면 경계가 낮은 장면에서 벽 부분까지 섞입니다.
        // 후보 경계 바로 아래부터 평가해 80/85/90 프롬프트 모두 같은 기준을 사용합니다.
        let y0Ratio = min(max(boundaryY + 0.025, 0.55), 0.94)
        let y1Ratio = min(max(y0Ratio + 0.10, boundaryY + 0.16), 0.99)
        let y0 = clampSAMMatInt(Int(CGFloat(visibleHeight) * y0Ratio), 0, visibleHeight - 1)
        let y1 = clampSAMMatInt(Int(CGFloat(visibleHeight) * y1Ratio), y0 + 1, visibleHeight)

        var positiveCount = 0
        var total = 0
        for y in y0..<y1 {
            for x in x0..<x1 {
                total += 1
                if componentMap[y * width + x] { positiveCount += 1 }
            }
        }
        return total > 0 ? CGFloat(positiveCount) / CGFloat(total) : 0
    }

    private func nearestSAMMatPositivePixel(
        in map: [Bool],
        width: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        originX: Int,
        originY: Int,
        maxRadius: Int
    ) -> Int? {
        if map[originY * width + originX] { return originY * width + originX }

        for radius in 1...maxRadius {
            let minX = max(0, originX - radius)
            let maxX = min(visibleWidth - 1, originX + radius)
            let minY = max(0, originY - radius)
            let maxY = min(visibleHeight - 1, originY + radius)

            var best: (index: Int, distance: Int)?
            for y in minY...maxY {
                for x in minX...maxX {
                    guard x == minX || x == maxX || y == minY || y == maxY else { continue }
                    let index = y * width + x
                    guard map[index] else { continue }
                    let distance = abs(x - originX) + abs(y - originY)
                    if best == nil || distance < best!.distance {
                        best = (index, distance)
                    }
                }
            }
            if let best { return best.index }
        }
        return nil
    }

    private func connectedSAMMatComponent(
        in source: [Bool],
        width: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        seed: Int
    ) -> (indices: [Int], map: [Bool], minX: Int, maxX: Int, minY: Int, maxY: Int) {
        var visited = [Bool](repeating: false, count: source.count)
        var queue = [seed]
        var indices: [Int] = []
        var head = 0
        visited[seed] = true

        var minX = Int.max
        var maxX = Int.min
        var minY = Int.max
        var maxY = Int.min

        let dx = [-1, 0, 1, -1, 1, -1, 0, 1]
        let dy = [-1, -1, -1, 0, 0, 1, 1, 1]

        while head < queue.count {
            let current = queue[head]
            head += 1
            indices.append(current)

            let x = current % width
            let y = current / width
            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)

            for direction in 0..<8 {
                let nx = x + dx[direction]
                let ny = y + dy[direction]
                guard nx >= 0, nx < visibleWidth, ny >= 0, ny < visibleHeight else { continue }
                let next = ny * width + nx
                guard source[next], !visited[next] else { continue }
                visited[next] = true
                queue.append(next)
            }
        }

        var componentMap = [Bool](repeating: false, count: source.count)
        for index in indices { componentMap[index] = true }

        return (indices, componentMap, minX, maxX, minY, maxY)
    }

    private func weightedSAMMatFit(_ points: [SAMMatFitPoint]) -> (slope: CGFloat, intercept: CGFloat)? {
        guard points.count >= 2 else { return nil }

        var sw: CGFloat = 0
        var sx: CGFloat = 0
        var sy: CGFloat = 0
        var sxx: CGFloat = 0
        var sxy: CGFloat = 0

        for point in points {
            sw += point.weight
            sx += point.weight * point.x
            sy += point.weight * point.y
            sxx += point.weight * point.x * point.x
            sxy += point.weight * point.x * point.y
        }

        let denominator = sw * sxx - sx * sx
        guard sw > 0, abs(denominator) > 0.000001 else { return nil }

        let slope = (sw * sxy - sx * sy) / denominator
        let intercept = (sy - slope * sx) / sw
        return (slope, intercept)
    }

    private func medianSAMMat(_ values: [CGFloat]) -> CGFloat {
        guard !values.isEmpty else { return 0 }
        let sorted = values.sorted()
        let middle = sorted.count / 2
        if sorted.count.isMultiple(of: 2) {
            return (sorted[middle - 1] + sorted[middle]) * 0.5
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
