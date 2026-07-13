import UIKit

/// Captured frame에서 매트/벽 경계선을 추정합니다.
///
/// Chroma 기반으로 매트/벽 경계를 찾고, 실패 시 brightness를 일부 반영해 fallback합니다.
/// 1. 중단~하단 영역에서 수평 직선을 y축으로 스캔합니다.
/// 2. 각 y에서 가로를 여러 bin으로 나누어 선 위/아래 색상 차이를 계산합니다.
/// 3. 최소 색상 차이를 만족하는 bin만 후보로 남깁니다.
/// 4. 후보 bin들의 "아래쪽 블럭 색상"이 가로 방향으로 가장 균일한 선을 선택합니다.
///    위/아래 색상 차이는 최소 기준 통과 여부에만 사용하고 점수에는 가산하지 않습니다.
///    아래 전체 영역이 아니라, 후보선 바로 아래쪽 블럭만 확인합니다.
/// 5. 선택된 y 주변에서 bin별 실제 경계 y를 재탐색합니다.
/// 6. 재탐색된 점들을 항상 2점 직선으로 근사합니다.
/// 7. Chroma 기준 탐지 실패 시, brightness 성분을 포함한 fallback 탐지를 한 번 더 수행합니다.
struct MatDetector {
    static func detect(in image: UIImage, horizontalBinCount: Int = Cfg.matBoundaryBins) -> [CGPoint] {
        guard let small = SmallImage(image: image, maxWidth: Cfg.matDetectWidth) else {
            return defaultLine()
        }

        let topY = max(4, Int(CGFloat(small.height) * Cfg.matSearchTopY))
        let bottomY = min(small.height - 5, Int(CGFloat(small.height) * Cfg.matSearchBottomY))
        guard topY < bottomY else { return defaultLine() }

        let bins = makeBins(width: small.width, count: horizontalBinCount)
        guard bins.count >= 4 else { return defaultLine() }

        let base: BaseCandidate
        if let chromaBase = findBestHorizontalBoundary(
            in: small,
            bins: bins,
            topY: topY,
            bottomY: bottomY,
            mode: .chromaOnly
        ) {
            base = chromaBase
        } else if let brightnessBase = findBestHorizontalBoundary(
            in: small,
            bins: bins,
            topY: topY,
            bottomY: bottomY,
            mode: .brightnessFallback
        ) {
            base = brightnessBase
        } else {
            return defaultLine()
        }

        let refined = refineBoundaryPoints(
            in: small,
            bins: bins,
            base: base,
            topY: topY,
            bottomY: bottomY
        )

        let simplified = simplify(points: refined)

        return simplified
    }

    // MARK: - Types

    private enum EdgeMode {
        case chromaOnly
        case brightnessFallback
    }

    private struct Bin {
        let index: Int
        let x0: Int
        let x1: Int
        let centerXNorm: CGFloat
    }

    private struct EdgeSample {
        let bin: Bin
        let y: Int
        let diff: CGFloat
        let lower: MatRGB
    }

    private struct BaseCandidate {
        let y: Int
        let allSamples: [EdgeSample]
        let run: [EdgeSample]
        let medianDiff: CGFloat
        let lowerUniformDev: CGFloat
        let score: CGFloat
        let edgeMode: EdgeMode
        var highCandidateCount: Int = 1
        var pickedUpperTie: Bool = false
    }

    private struct RefinedPoint {
        let x: CGFloat
        let y: CGFloat
        let diff: CGFloat
    }


    // MARK: - Step 1: Find horizontal boundary band

    private static func findBestHorizontalBoundary(
        in image: SmallImage,
        bins: [Bin],
        topY: Int,
        bottomY: Int,
        mode: EdgeMode
    ) -> BaseCandidate? {
        var candidates: [BaseCandidate] = []

        let upperBand = sampleBand(image.height)
        let lowerBand = sampleLowerBand(image.height)
        let gap = max(2, upperBand / 2)
        let minY = upperBand + gap + 1
        let maxY = image.height - lowerBand - gap - 2

        var y = topY
        while y <= bottomY {
            if y >= minY && y <= maxY,
               let candidate = evaluateHorizontalLine(y: y, in: image, bins: bins, upperBand: upperBand, lowerBand: lowerBand, gap: gap, mode: mode) {
                candidates.append(candidate)
            }
            y += 2
        }

        return chooseBoundaryCandidate(
            candidates,
            clusterGap: max(3, upperBand + gap)
        )
    }

    private static func chooseBoundaryCandidate(
        _ candidates: [BaseCandidate],
        clusterGap: Int
    ) -> BaseCandidate? {
        guard !candidates.isEmpty else { return nil }

        let sorted = candidates.sorted { lhs, rhs in
            if lhs.y == rhs.y { return lhs.score > rhs.score }
            return lhs.y < rhs.y
        }

        // 같은 실제 경계 주변에서 여러 y가 비슷하게 잡히는 경우가 있으므로,
        // 가까운 y 후보들은 하나의 cluster로 묶고 cluster 안에서는 점수가 가장 높은 후보만 대표로 둡니다.
        var clusters: [[BaseCandidate]] = []
        var current: [BaseCandidate] = []
        var previousY: Int?

        for candidate in sorted {
            if let previousY, candidate.y - previousY <= clusterGap {
                current.append(candidate)
            } else {
                if !current.isEmpty { clusters.append(current) }
                current = [candidate]
            }
            previousY = candidate.y
        }
        if !current.isEmpty { clusters.append(current) }

        let representatives = clusters.compactMap { cluster in
            cluster.max { $0.score < $1.score }
        }
        guard let best = representatives.max(by: { $0.score < $1.score }) else { return nil }

        // 높은 점수를 받은 후보 cluster가 2개 이상이면 더 위쪽 경계를 선택합니다.
        // 단, 점수 차이가 tolerance 밖이면 명확한 1등 후보를 그대로 씁니다.
        let high = representatives.filter { best.score - $0.score <= Cfg.matUpperTieScoreTolerance }
        let selected = high.count >= 2
            ? (high.min { $0.y < $1.y } ?? best)
            : best

        var result = selected
        result.highCandidateCount = high.count
        result.pickedUpperTie = high.count >= 2 && selected.y != best.y

        return result
    }

    private static func evaluateHorizontalLine(
        y: Int,
        in image: SmallImage,
        bins: [Bin],
        upperBand: Int,
        lowerBand: Int,
        gap: Int,
        mode: EdgeMode
    ) -> BaseCandidate? {
        let samples: [EdgeSample] = bins.map { bin in
            let upper = averageRGB(
                in: image,
                x0: bin.x0,
                x1: bin.x1,
                y0: y - gap - upperBand,
                y1: y - gap
            )
            let lower = averageRGB(
                in: image,
                x0: bin.x0,
                x1: bin.x1,
                y0: y + gap,
                y1: y + gap + lowerBand
            )
            return EdgeSample(
                bin: bin,
                y: y,
                diff: edgeColorDistance(upper, lower, mode: mode),
                lower: lower
            )
        }

        // 1차 조건: 선 위/아래 색상 차이가 최소 기준을 넘는 bin만 후보로 남깁니다.
        let strong = samples.filter { $0.diff >= Cfg.matMinEdgeScore }
        let requiredCoverage = max(2, Int(ceil(CGFloat(bins.count) * Cfg.matBoundaryMinCoverageRatio)))
        guard let selected = bestLowerUniformRun(strong, requiredCoverage: requiredCoverage) else {
            return nil
        }

        let bestRun = selected.run
        let lowerUniformDev = selected.lowerUniformDev

        // 2차 조건: 후보선 아래쪽 strip을 bin별 블럭으로만 보고,
        // 그 블럭들의 색상이 충분히 균일해야 합니다.
        guard lowerUniformDev <= Cfg.matLowerUniformityTol else { return nil }

        let runDiffs = bestRun.map { $0.diff }
        let runMedian = median(runDiffs)
        let coverageRatio = CGFloat(bestRun.count) / CGFloat(max(bins.count, 1))

        // 선택 기준: 아래쪽 블럭 색상 균일도를 최우선으로 봅니다.
        // 위/아래 색상 차이는 matMinEdgeScore를 넘는지 확인하는 1차 필터로만 사용하고,
        // 점수에는 더 이상 가산하지 않습니다.
        let score = -lowerUniformDev * 4.0 + coverageRatio * 3.0

        return BaseCandidate(
            y: y,
            allSamples: samples,
            run: bestRun,
            medianDiff: runMedian,
            lowerUniformDev: lowerUniformDev,
            score: score,
            edgeMode: mode
        )
    }

    // MARK: - Step 2: Refine y per bin

    private static func refineBoundaryPoints(
        in image: SmallImage,
        bins: [Bin],
        base: BaseCandidate,
        topY: Int,
        bottomY: Int
    ) -> [RefinedPoint] {
        let upperBand = sampleBand(image.height)
        let lowerBand = sampleLowerBand(image.height)
        let gap = max(2, upperBand / 2)
        let window = max(4, Int(CGFloat(image.height) * Cfg.matLocalRefineWindowRatio))
        let minY = max(topY, upperBand + gap + 1, base.y - window)
        let maxY = min(bottomY, image.height - lowerBand - gap - 2, base.y + window)

        let supportIndices = Set(base.run.map { $0.bin.index })
        let supportBins = bins.filter { supportIndices.contains($0.index) }
        let refineBins = supportBins.isEmpty ? bins : supportBins

        var points: [RefinedPoint] = []

        for bin in refineBins {
            var bestY = base.y
            var bestDiff: CGFloat = 0
            var bestScore = -CGFloat.greatestFiniteMagnitude

            var y = minY
            while y <= maxY {
                let diff = edgeDiff(in: image, bin: bin, y: y, upperBand: upperBand, lowerBand: lowerBand, gap: gap, mode: base.edgeMode)
                if diff >= Cfg.matMinEdgeScore {
                    // 1차 후보의 medianDiff와 너무 다른 edge는 홀드/그림자일 수 있으니 약하게 감점합니다.
                    let consistencyPenalty = abs(diff - base.medianDiff) * 0.35
                    let score = diff - consistencyPenalty
                    if score > bestScore {
                        bestScore = score
                        bestY = y
                        bestDiff = diff
                    }
                }
                y += 1
            }

            if bestScore == -CGFloat.greatestFiniteMagnitude {
                bestY = base.y
                bestDiff = edgeDiff(in: image, bin: bin, y: base.y, upperBand: upperBand, lowerBand: lowerBand, gap: gap, mode: base.edgeMode)
            }

            points.append(
                RefinedPoint(
                    x: bin.centerXNorm,
                    y: CGFloat(bestY) / CGFloat(max(image.height - 1, 1)),
                    diff: bestDiff
                )
            )
        }

        return smooth(points)
    }

    private static func edgeDiff(in image: SmallImage, bin: Bin, y: Int, upperBand: Int, lowerBand: Int, gap: Int, mode: EdgeMode) -> CGFloat {
        let upper = averageRGB(
            in: image,
            x0: bin.x0,
            x1: bin.x1,
            y0: y - gap - upperBand,
            y1: y - gap
        )
        let lower = averageRGB(
            in: image,
            x0: bin.x0,
            x1: bin.x1,
            y0: y + gap,
            y1: y + gap + lowerBand
        )
        return edgeColorDistance(upper, lower, mode: mode)
    }

    // MARK: - Step 3: Simplify to 2 points only

    private static func simplify(points: [RefinedPoint]) -> [CGPoint] {
        guard points.count >= 2 else { return defaultLine() }

        // 3점/4점 polyline은 과적합 위험이 있어 사용하지 않습니다.
        // bin별 refined y를 하나의 2점 직선으로만 근사합니다.
        return fitLine(points)
    }

    private static func fitLine(_ points: [RefinedPoint]) -> [CGPoint] {
        let n = CGFloat(points.count)
        let sx = points.reduce(CGFloat(0)) { $0 + $1.x }
        let sy = points.reduce(CGFloat(0)) { $0 + $1.y }
        let sxx = points.reduce(CGFloat(0)) { $0 + $1.x * $1.x }
        let sxy = points.reduce(CGFloat(0)) { $0 + $1.x * $1.y }
        let denom = max(0.0001, n * sxx - sx * sx)
        let a = (n * sxy - sx * sy) / denom
        let b = (sy - a * sx) / n

        let leftX: CGFloat = 0.06
        let rightX: CGFloat = 0.94
        return [
            CGPoint(x: leftX, y: clamp(a * leftX + b, Cfg.matSearchTopY, Cfg.matSearchBottomY)),
            CGPoint(x: rightX, y: clamp(a * rightX + b, Cfg.matSearchTopY, Cfg.matSearchBottomY))
        ]
    }

    // MARK: - Helpers

    private static func sampleBand(_ height: Int) -> Int {
        max(3, Int(CGFloat(height) * Cfg.matSampleStripRatio))
    }

    private static func sampleLowerBand(_ height: Int) -> Int {
        max(sampleBand(height), Int(CGFloat(sampleBand(height)) * Cfg.matLowerSampleMultiplier))
    }

    private static func makeBins(width: Int, count: Int) -> [Bin] {
        let count = max(4, count)
        let left: CGFloat = 0.06
        let right: CGFloat = 0.94
        let usable = right - left

        return (0..<count).map { i in
            let l = left + usable * CGFloat(i) / CGFloat(count)
            let r = left + usable * CGFloat(i + 1) / CGFloat(count)
            return Bin(
                index: i,
                x0: max(0, Int(CGFloat(width) * l)),
                x1: min(width - 1, Int(CGFloat(width) * r)),
                centerXNorm: (l + r) * 0.5
            )
        }
    }

    private static func bestLowerUniformRun(
        _ samples: [EdgeSample],
        requiredCoverage: Int
    ) -> (run: [EdgeSample], lowerUniformDev: CGFloat)? {
        let sorted = samples.sorted { $0.bin.index < $1.bin.index }
        guard !sorted.isEmpty else { return nil }

        var runs: [[EdgeSample]] = []
        var current: [EdgeSample] = []
        var previousIndex: Int?

        for sample in sorted {
            if let previous = previousIndex, sample.bin.index == previous + 1 {
                current.append(sample)
            } else {
                if !current.isEmpty { runs.append(current) }
                current = [sample]
            }
            previousIndex = sample.bin.index
        }
        if !current.isEmpty { runs.append(current) }

        var bestRun: [EdgeSample]?
        var bestUniformDev = CGFloat.greatestFiniteMagnitude
        for run in runs where run.count >= requiredCoverage {
            let lowerUniformDev = lowerColorUniformity(run.map { $0.lower })

            let isBetter = lowerUniformDev < bestUniformDev - 0.001
                || (abs(lowerUniformDev - bestUniformDev) <= 0.001 && run.count > (bestRun?.count ?? 0))

            if isBetter {
                bestRun = run
                bestUniformDev = lowerUniformDev
            }
        }

        guard let bestRun else { return nil }
        return (bestRun, bestUniformDev)
    }

    private static func lowerColorUniformity(_ colors: [MatRGB]) -> CGFloat {
        guard !colors.isEmpty else { return .greatestFiniteMagnitude }

        // V13: 후보선 아래쪽이 "전체적으로" 균일한지 봅니다.
        // V12에서는 median deviation을 사용해서 일부 bin이 크게 달라도 무시되는 문제가 있었습니다.
        // 예: [어두운 매트 끝, 밝은 매트 중간]이 섞여도 중앙값 기준으로는 좋아 보일 수 있음.
        // 그래서 각 bin의 lower color가 전체 중앙 색상에서 얼마나 벗어나는지 평균 편차로 계산합니다.
        let center: MatRGB = (
            Int(median(colors.map { CGFloat($0.r) })),
            Int(median(colors.map { CGFloat($0.g) })),
            Int(median(colors.map { CGFloat($0.b) }))
        )

        let deviations = colors.map { lowerUniformColorDistance($0, center) }
        let meanDev = deviations.reduce(CGFloat(0), +) / CGFloat(max(deviations.count, 1))
        return meanDev
    }

    private static func longestContiguousRun(_ samples: [EdgeSample]) -> [EdgeSample] {
        let sorted = samples.sorted { $0.bin.index < $1.bin.index }
        var best: [EdgeSample] = []
        var current: [EdgeSample] = []
        var previousIndex: Int?

        for sample in sorted {
            if let previous = previousIndex, sample.bin.index == previous + 1 {
                current.append(sample)
            } else {
                if current.count > best.count { best = current }
                current = [sample]
            }
            previousIndex = sample.bin.index
        }

        if current.count > best.count { best = current }
        return best
    }

    private static func smooth(_ points: [RefinedPoint]) -> [RefinedPoint] {
        guard points.count >= 3 else { return points }
        var result = points
        for i in 1..<(points.count - 1) {
            let ys = [points[i - 1].y, points[i].y, points[i + 1].y]
            result[i] = RefinedPoint(x: points[i].x, y: median(ys), diff: points[i].diff)
        }
        return result
    }

    private static func averageRGB(in image: SmallImage, x0: Int, x1: Int, y0: Int, y1: Int) -> MatRGB {
        let x0 = max(0, min(x0, image.width - 1))
        let x1 = max(x0, min(x1, image.width - 1))
        let y0 = max(0, min(y0, image.height - 1))
        let y1 = max(y0, min(y1, image.height - 1))
        let strideX = 2
        let strideY = 2

        var r = 0
        var g = 0
        var b = 0
        var n = 0

        var y = y0
        while y <= y1 {
            var x = x0
            while x <= x1 {
                let c = image.rgb(x: x, y: y)
                r += c.r
                g += c.g
                b += c.b
                n += 1
                x += strideX
            }
            y += strideY
        }

        guard n > 0 else { return (0, 0, 0) }
        return (r / n, g / n, b / n)
    }

    private static func median(_ values: [CGFloat]) -> CGFloat {
        guard !values.isEmpty else { return 0 }
        let sorted = values.sorted()
        let mid = sorted.count / 2
        if sorted.count % 2 == 0 {
            return (sorted[mid - 1] + sorted[mid]) * 0.5
        }
        return sorted[mid]
    }

    private static func edgeColorDistance(_ a: MatRGB, _ b: MatRGB, mode: EdgeMode) -> CGFloat {
        switch mode {
        case .chromaOnly:
            // 1차 탐지는 밝기 차이를 제거하고 색상 차이 중심으로 봅니다.
            // 그림자처럼 RGB 전체가 어두워진 경우는 경계로 잡히지 않도록 chroma 차이를 사용합니다.
            return chromaDistance(a, b)
        case .brightnessFallback:
            // 1차 chroma 탐지가 실패했을 때만 사용합니다.
            // 색상 차이 + 밝기 차이를 함께 보되, 밝기 성분은 fallback에서만 강하게 반영합니다.
            return chromaDistance(a, b) + lumaDistance(a, b) * 0.35
        }
    }

    private static func lowerUniformColorDistance(_ a: MatRGB, _ b: MatRGB) -> CGFloat {
        // 아래쪽 매트 블럭 균일도도 색상 차이 중심으로 계산합니다.
        // 조명/그림자에 의한 밝기 변화는 약하게 보거나 무시합니다.
        return chromaDistance(a, b)
    }

    private static func chromaDistance(_ a: MatRGB, _ b: MatRGB) -> CGFloat {
        let la = luma(a)
        let lb = luma(b)

        let ar = CGFloat(a.r) - la
        let ag = CGFloat(a.g) - la
        let ab = CGFloat(a.b) - la

        let br = CGFloat(b.r) - lb
        let bg = CGFloat(b.g) - lb
        let bb = CGFloat(b.b) - lb

        return (abs(ar - br) + abs(ag - bg) + abs(ab - bb)) / 3
    }

    private static func lumaDistance(_ a: MatRGB, _ b: MatRGB) -> CGFloat {
        abs(luma(a) - luma(b))
    }

    private static func luma(_ c: MatRGB) -> CGFloat {
        // 단순 평균보다 그림자/밝기 성분 분리에 안정적인 Rec.709 계열 가중치입니다.
        CGFloat(c.r) * 0.2126 + CGFloat(c.g) * 0.7152 + CGFloat(c.b) * 0.0722
    }


    private static func clamp(_ value: CGFloat, _ minValue: CGFloat, _ maxValue: CGFloat) -> CGFloat {
        max(minValue, min(maxValue, value))
    }

    private static func defaultLine() -> [CGPoint] {
        [
            CGPoint(x: 0.06, y: Cfg.matDefaultY),
            CGPoint(x: 0.94, y: Cfg.matDefaultY)
        ]
    }
}

private typealias MatRGB = (r: Int, g: Int, b: Int)

private struct SmallImage {
    let width: Int
    let height: Int
    private let data: [UInt8]

    init?(image: UIImage, maxWidth: Int) {
        guard let cg = image.cgImage else { return nil }

        let srcW = cg.width
        let srcH = cg.height
        let scale = min(1, CGFloat(maxWidth) / CGFloat(srcW))
        width = max(1, Int(CGFloat(srcW) * scale))
        height = max(1, Int(CGFloat(srcH) * scale))

        var raw = [UInt8](repeating: 0, count: width * height * 4)
        guard let ctx = CGContext(
            data: &raw,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }

        ctx.interpolationQuality = .low
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: CGFloat(width), height: CGFloat(height)))
        data = raw
    }

    func rgb(x: Int, y: Int) -> MatRGB {
        let x = max(0, min(x, width - 1))
        let y = max(0, min(y, height - 1))
        let i = (y * width + x) * 4
        return (Int(data[i]), Int(data[i + 1]), Int(data[i + 2]))
    }
}
