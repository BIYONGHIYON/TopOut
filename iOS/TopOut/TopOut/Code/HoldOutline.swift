// 탑홀드 외곽선 추출. Android HoldOutlineExtractor.kt와 같은 순서·같은 값을 쓴다.
import CoreGraphics
import Foundation

/// 결과 화면에서 탭한 탑홀드의 최종 표시 모양. 좌표는 원본 프레임 픽셀 단위다.
nonisolated enum HoldOutline {
    /// 홀드가 최소 표시 크기보다 작거나 홀드를 찾지 못하면 최소 크기 원으로 표시한다.
    case circle(center: CGPoint, radius: CGFloat)
    /// 홀드 가장자리를 따라 바깥에 그리는 닫힌 외곽선.
    case polygon([CGPoint])
}

/// MobileSAM 후보 마스크 중 탭한 홀드 하나를 고르고, 깔끔한 외곽선으로 정리한다.
///
/// 1. 후보: 한 점 프롬프트의 다중 마스크(채널 1–3)를 모두 본다.
/// 2. 격자: 저해상도 logit을 긴 변 512칸 격자로 이중선형 확대해 계단 현상을 없앤다.
/// 3. 영역: 탭한 칸과 이어진 영역만 남기고 안쪽 구멍을 메운다. 색상으로 자르지 않는다.
/// 4. 거름: 화면의 25% 이상 펼쳐지거나 3.5% 이상 덮으면 벽·매트로 보고 버린다.
/// 5. 선택: 모델 신뢰도(IoU) + 0.5 × 안정도. 점수 차이가 0.03 이내면 더 작은 마스크(탭한 홀드 하나)를 고른다.
/// 6. 정리: 열림으로 잔가지를 떼고, 선 굵기 절반만큼 바깥으로 키운 뒤 닫힘으로 홈을 메운다.
/// 7. 모양: 최소 원 안에 들어가면 원, 아니면 윤곽선 → Douglas–Peucker → Chaikin.
nonisolated struct HoldOutlineExtractor {
    struct Input {
        /// 채널 × 256 × 256 저해상도 logit (행 우선).
        let logits: [Float]
        let channelCount: Int
        let lowResSize: Int
        /// 채널별 모델 신뢰도. 없으면 빈 배열.
        let iouPredictions: [Float]
        /// 1024 입력 안에서 실제 이미지가 차지한 크기.
        let modelVisibleSize: CGSize
        /// 원본 프레임 크기와 탭 위치(원본 픽셀).
        let imageSize: CGSize
        let tap: CGPoint
        /// 최소 표시 원 지름과 빨간 테두리 굵기(원본 픽셀).
        let minimumDiameter: CGFloat
        let strokeWidth: CGFloat
    }

    enum Tuning {
        static let gridLongSide = 512
        static let seedSearchRatio: CGFloat = 0.02
        static let maxSpanRatio: CGFloat = 0.25
        static let maxAreaRatio: CGFloat = 0.035
        static let stabilityWeight: Float = 0.5
        static let stabilityOffset: Float = 1
        static let preferSmallerMargin: Float = 0.03
        static let preferSmallerAreaRatio: CGFloat = 1.6
        static let openRadius: Float = 2
        static let closeRadius: Float = 3
        static let outlinePadStrokeRatio: CGFloat = 0.5
        static let simplifyTolerance: CGFloat = 1
        static let smoothIterations = 3
    }

    private struct Grid {
        let width: Int
        let height: Int
        var values: [Bool]

        init(width: Int, height: Int, values: [Bool]) {
            self.width = width
            self.height = height
            self.values = values
        }

        init(width: Int, height: Int) {
            self.init(width: width, height: height, values: [Bool](repeating: false, count: width * height))
        }

        subscript(x: Int, y: Int) -> Bool {
            get { values[y * width + x] }
            set { values[y * width + x] = newValue }
        }

        var count: Int { values.reduce(0) { $0 + ($1 ? 1 : 0) } }
    }

    private struct Candidate {
        let region: Grid
        let score: Float
        let areaRatio: CGFloat
    }

    func extract(_ input: Input) -> HoldOutline {
        let fallback = HoldOutline.circle(
            center: clampedCircleCenter(input.tap, radius: input.minimumDiameter / 2, in: input.imageSize),
            radius: input.minimumDiameter / 2
        )
        let modelLong = max(input.modelVisibleSize.width, input.modelVisibleSize.height)
        guard modelLong > 0, input.imageSize.width > 0, input.imageSize.height > 0,
              input.channelCount > 0 else { return fallback }

        // 격자 1칸 = 모델 입력 (1 / gridScale) px.
        let gridScale = CGFloat(Tuning.gridLongSide) / modelLong
        let gridWidth = max(1, Int((input.modelVisibleSize.width * gridScale).rounded()))
        let gridHeight = max(1, Int((input.modelVisibleSize.height * gridScale).rounded()))
        let imageToGridX = CGFloat(gridWidth) / input.imageSize.width
        let imageToGridY = CGFloat(gridHeight) / input.imageSize.height
        let tapX = min(gridWidth - 1, max(0, Int(input.tap.x * imageToGridX)))
        let tapY = min(gridHeight - 1, max(0, Int(input.tap.y * imageToGridY)))
        let seedSearch = Tuning.seedSearchRatio * CGFloat(min(gridWidth, gridHeight))

        let channels = input.channelCount >= 4 ? Array(1..<4) : Array(0..<input.channelCount)
        var candidates: [Candidate] = []
        for channel in channels {
            let field = sampledField(input, channel: channel, gridWidth: gridWidth, gridHeight: gridHeight, gridScale: gridScale)
            var positive = Grid(width: gridWidth, height: gridHeight)
            for index in field.indices where field[index] > 0 { positive.values[index] = true }
            guard var region = component(in: positive, nearX: tapX, y: tapY, search: seedSearch) else { continue }
            region = filledHoles(region)
            guard let box = bounds(of: region) else { continue }

            let spanX = CGFloat(box.maxX - box.minX + 1) / CGFloat(gridWidth)
            let spanY = CGFloat(box.maxY - box.minY + 1) / CGFloat(gridHeight)
            let areaRatio = CGFloat(region.count) / CGFloat(gridWidth * gridHeight)
            // 벽·매트·천장처럼 넓게 퍼진 마스크는 홀드가 아니다.
            guard spanX < Tuning.maxSpanRatio, spanY < Tuning.maxSpanRatio, areaRatio < Tuning.maxAreaRatio else { continue }

            let iou = channel < input.iouPredictions.count ? input.iouPredictions[channel] : 0
            let stability = stabilityScore(field, box: box, gridWidth: gridWidth, gridHeight: gridHeight)
            candidates.append(Candidate(region: region, score: iou + Tuning.stabilityWeight * stability, areaRatio: areaRatio))
        }
        guard let chosen = choose(candidates) else { return fallback }

        var region = opened(chosen.region, radius: Tuning.openRadius, seedX: tapX, seedY: tapY, search: seedSearch)
        guard let box = bounds(of: region) else { return fallback }

        let gridToImageX = input.imageSize.width / CGFloat(gridWidth)
        let gridToImageY = input.imageSize.height / CGFloat(gridHeight)
        let pad = input.strokeWidth * Tuning.outlinePadStrokeRatio
        let holdWidth = CGFloat(box.maxX - box.minX + 1) * gridToImageX
        let holdHeight = CGFloat(box.maxY - box.minY + 1) * gridToImageY
        if max(holdWidth, holdHeight) + pad * 2 <= input.minimumDiameter {
            // 작은 홀드는 최소 크기 원을 홀드 가운데에 둔다.
            let center = centroid(of: region, scaleX: gridToImageX, scaleY: gridToImageY)
            let radius = input.minimumDiameter / 2
            return .circle(center: clampedCircleCenter(center, radius: radius, in: input.imageSize), radius: radius)
        }

        // 테두리가 홀드를 가리지 않게 선 굵기 절반만큼 바깥으로 키우고, 닫힘으로 오목한 톱니를 메운다.
        let padCells = Float(pad / max(gridToImageX, gridToImageY))
        region = filledHoles(dilated(region, radius: padCells + Tuning.closeRadius))
        region = eroded(region, radius: Tuning.closeRadius)
        region = component(in: region, nearX: tapX, y: tapY, search: CGFloat.greatestFiniteMagnitude) ?? region

        let boundary = traceBoundary(region)
        guard boundary.count >= 4 else { return fallback }
        let simplified = simplifyClosed(boundary.map { CGPoint(x: CGFloat($0.x), y: CGFloat($0.y)) }, tolerance: Tuning.simplifyTolerance)
        let smoothed = chaikin(simplified, iterations: Tuning.smoothIterations)
        guard smoothed.count >= 4 else { return fallback }
        return .polygon(smoothed.map { CGPoint(x: ($0.x + 0.5) * gridToImageX, y: ($0.y + 0.5) * gridToImageY) })
    }

    // MARK: - 후보 선택

    /// 가장 점수가 높은 후보를 고르되, 점수가 거의 같고 넓이가 1.6배 이상 작은 후보가 있으면 그쪽을 쓴다.
    private func choose(_ candidates: [Candidate]) -> Candidate? {
        guard let best = candidates.max(by: { $0.score < $1.score }) else { return nil }
        let smaller = candidates
            .filter { $0.areaRatio * Tuning.preferSmallerAreaRatio <= best.areaRatio }
            .filter { best.score - $0.score <= Tuning.preferSmallerMargin }
            .min(by: { $0.areaRatio < $1.areaRatio })
        return smaller ?? best
    }

    /// 경계 근처 logit이 임계값에 따라 얼마나 흔들리는지. SAM 자동 마스크의 안정도와 같은 정의다.
    private func stabilityScore(_ field: [Float], box: (minX: Int, minY: Int, maxX: Int, maxY: Int), gridWidth: Int, gridHeight: Int) -> Float {
        var strict = 0
        var loose = 0
        for y in max(0, box.minY - 4)...min(gridHeight - 1, box.maxY + 4) {
            for x in max(0, box.minX - 4)...min(gridWidth - 1, box.maxX + 4) {
                let value = field[y * gridWidth + x]
                if value > Tuning.stabilityOffset { strict += 1 }
                if value > -Tuning.stabilityOffset { loose += 1 }
            }
        }
        return loose > 0 ? Float(strict) / Float(loose) : 0
    }

    // MARK: - logit 격자

    /// 저해상도 logit을 격자 칸 중심에서 이중선형으로 읽는다. 256 칸은 1024 입력의 4px에 해당한다.
    private func sampledField(_ input: Input, channel: Int, gridWidth: Int, gridHeight: Int, gridScale: CGFloat) -> [Float] {
        let size = input.lowResSize
        let plane = channel * size * size
        let lowPerModel = Float(size) / 1024
        let modelPerGrid = Float(1 / gridScale)
        var field = [Float](repeating: -20, count: gridWidth * gridHeight)
        input.logits.withUnsafeBufferPointer { logits in
            for y in 0..<gridHeight {
                let ly = (Float(y) + 0.5) * modelPerGrid * lowPerModel - 0.5
                let y0 = min(size - 1, max(0, Int(floor(ly))))
                let y1 = min(size - 1, y0 + 1)
                let fy = min(1, max(0, ly - Float(y0)))
                for x in 0..<gridWidth {
                    let lx = (Float(x) + 0.5) * modelPerGrid * lowPerModel - 0.5
                    let x0 = min(size - 1, max(0, Int(floor(lx))))
                    let x1 = min(size - 1, x0 + 1)
                    let fx = min(1, max(0, lx - Float(x0)))
                    let top = logits[plane + y0 * size + x0] * (1 - fx) + logits[plane + y0 * size + x1] * fx
                    let bottom = logits[plane + y1 * size + x0] * (1 - fx) + logits[plane + y1 * size + x1] * fx
                    field[y * gridWidth + x] = top * (1 - fy) + bottom * fy
                }
            }
        }
        return field
    }

    // MARK: - 영역 연산

    /// 탭한 칸과 4방향으로 이어진 영역. 탭이 영역 밖이면 search 칸 안의 가장 가까운 칸에서 시작한다.
    private func component(in grid: Grid, nearX tapX: Int, y tapY: Int, search: CGFloat) -> Grid? {
        var seed: (x: Int, y: Int)?
        if grid[tapX, tapY] {
            seed = (tapX, tapY)
        } else if search > 0 {
            let radius = Int(min(CGFloat(max(grid.width, grid.height)), ceil(search)))
            var bestDistance = Int.max
            for y in max(0, tapY - radius)...min(grid.height - 1, tapY + radius) {
                for x in max(0, tapX - radius)...min(grid.width - 1, tapX + radius) where grid[x, y] {
                    let distance = (x - tapX) * (x - tapX) + (y - tapY) * (y - tapY)
                    if CGFloat(distance) <= search * search, distance < bestDistance {
                        bestDistance = distance
                        seed = (x, y)
                    }
                }
            }
        }
        guard let seed else { return nil }

        var region = Grid(width: grid.width, height: grid.height)
        var stack = [seed.y * grid.width + seed.x]
        region.values[stack[0]] = true
        while let index = stack.popLast() {
            let x = index % grid.width
            let y = index / grid.width
            for (nx, ny) in [(x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)]
            where nx >= 0 && nx < grid.width && ny >= 0 && ny < grid.height {
                let next = ny * grid.width + nx
                if grid.values[next], !region.values[next] {
                    region.values[next] = true
                    stack.append(next)
                }
            }
        }
        return region
    }

    /// 바깥과 이어지지 않은 빈 칸(볼트 구멍, 초크 자국)을 채운다.
    private func filledHoles(_ grid: Grid) -> Grid {
        let width = grid.width
        let height = grid.height
        var outside = [Bool](repeating: false, count: width * height)
        var stack: [Int] = []
        func push(_ x: Int, _ y: Int) {
            let index = y * width + x
            if !grid.values[index], !outside[index] {
                outside[index] = true
                stack.append(index)
            }
        }
        for x in 0..<width { push(x, 0); push(x, height - 1) }
        for y in 0..<height { push(0, y); push(width - 1, y) }
        while let index = stack.popLast() {
            let x = index % width
            let y = index / width
            if x > 0 { push(x - 1, y) }
            if x < width - 1 { push(x + 1, y) }
            if y > 0 { push(x, y - 1) }
            if y < height - 1 { push(x, y + 1) }
        }
        var result = grid
        for index in result.values.indices where !outside[index] { result.values[index] = true }
        return result
    }

    /// 3-4 chamfer 거리. target 칸까지의 거리(칸 단위)를 돌려준다.
    private func distance(to target: [Bool], width: Int, height: Int) -> [Float] {
        let far = Int32.max / 2
        var distance = [Int32](repeating: far, count: width * height)
        for index in target.indices where target[index] { distance[index] = 0 }
        for y in 0..<height {
            for x in 0..<width {
                let index = y * width + x
                var value = distance[index]
                if value == 0 { continue }
                if x > 0 { value = min(value, distance[index - 1] + 3) }
                if y > 0 {
                    value = min(value, distance[index - width] + 3)
                    if x > 0 { value = min(value, distance[index - width - 1] + 4) }
                    if x < width - 1 { value = min(value, distance[index - width + 1] + 4) }
                }
                distance[index] = value
            }
        }
        for y in stride(from: height - 1, through: 0, by: -1) {
            for x in stride(from: width - 1, through: 0, by: -1) {
                let index = y * width + x
                var value = distance[index]
                if value == 0 { continue }
                if x < width - 1 { value = min(value, distance[index + 1] + 3) }
                if y < height - 1 {
                    value = min(value, distance[index + width] + 3)
                    if x < width - 1 { value = min(value, distance[index + width + 1] + 4) }
                    if x > 0 { value = min(value, distance[index + width - 1] + 4) }
                }
                distance[index] = value
            }
        }
        return distance.map { Float($0) / 3 }
    }

    private func dilated(_ grid: Grid, radius: Float) -> Grid {
        let distance = distance(to: grid.values, width: grid.width, height: grid.height)
        return Grid(width: grid.width, height: grid.height, values: distance.map { $0 <= radius })
    }

    private func eroded(_ grid: Grid, radius: Float) -> Grid {
        let distance = distance(to: grid.values.map { !$0 }, width: grid.width, height: grid.height)
        return Grid(width: grid.width, height: grid.height, values: distance.map { $0 > radius })
    }

    /// 열림: 반지름보다 가는 잔가지(옆 홀드로 새는 선, 볼트)를 뗀다. 영역이 절반 넘게 줄면 원래 영역을 쓴다.
    private func opened(_ grid: Grid, radius: Float, seedX: Int, seedY: Int, search: CGFloat) -> Grid {
        let core = eroded(grid, radius: radius)
        guard core.values.contains(true) else { return grid }
        var reopened = dilated(core, radius: radius + 0.5)
        for index in reopened.values.indices where !grid.values[index] { reopened.values[index] = false }
        guard let kept = component(in: reopened, nearX: seedX, y: seedY, search: search),
              kept.count * 2 > grid.count else { return grid }
        return kept
    }

    private func bounds(of grid: Grid) -> (minX: Int, minY: Int, maxX: Int, maxY: Int)? {
        var minX = Int.max, minY = Int.max, maxX = Int.min, maxY = Int.min
        for y in 0..<grid.height {
            for x in 0..<grid.width where grid[x, y] {
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        return minX == Int.max ? nil : (minX, minY, maxX, maxY)
    }

    private func centroid(of grid: Grid, scaleX: CGFloat, scaleY: CGFloat) -> CGPoint {
        var sumX = 0.0, sumY = 0.0, count = 0.0
        for y in 0..<grid.height {
            for x in 0..<grid.width where grid[x, y] {
                sumX += Double(x); sumY += Double(y); count += 1
            }
        }
        guard count > 0 else { return .zero }
        return CGPoint(x: (CGFloat(sumX / count) + 0.5) * scaleX, y: (CGFloat(sumY / count) + 0.5) * scaleY)
    }

    private func clampedCircleCenter(_ center: CGPoint, radius: CGFloat, in size: CGSize) -> CGPoint {
        CGPoint(
            x: max(radius, min(size.width - radius, center.x)),
            y: max(radius, min(size.height - radius, center.y))
        )
    }

    // MARK: - 윤곽선

    /// Moore 이웃 추적으로 바깥 경계 칸을 시계 방향 순서로 모은다.
    private func traceBoundary(_ grid: Grid) -> [(x: Int, y: Int)] {
        guard let start = firstCell(of: grid) else { return [] }
        let directions = [(1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0), (-1, -1), (0, -1), (1, -1)]
        func inside(_ x: Int, _ y: Int) -> Bool {
            x >= 0 && x < grid.width && y >= 0 && y < grid.height && grid[x, y]
        }
        var points = [start]
        var current = start
        var back = 4
        let limit = grid.width * grid.height * 2
        for _ in 0..<limit {
            var moved = false
            for step in 0..<8 {
                let direction = (back + 1 + step) % 8
                let next = (x: current.x + directions[direction].0, y: current.y + directions[direction].1)
                if inside(next.x, next.y) {
                    back = (direction + 4) % 8
                    current = next
                    moved = true
                    break
                }
            }
            if !moved || current == start { break }
            points.append(current)
        }
        return points
    }

    private func firstCell(of grid: Grid) -> (x: Int, y: Int)? {
        for y in 0..<grid.height {
            for x in 0..<grid.width where grid[x, y] { return (x, y) }
        }
        return nil
    }

    /// 닫힌 윤곽선을 시작점과 가장 먼 점으로 나눠 Douglas–Peucker로 줄인다.
    private func simplifyClosed(_ points: [CGPoint], tolerance: CGFloat) -> [CGPoint] {
        guard points.count >= 4 else { return points }
        let first = points[0]
        let farIndex = points.indices.max {
            hypot(points[$0].x - first.x, points[$0].y - first.y) < hypot(points[$1].x - first.x, points[$1].y - first.y)
        } ?? 0
        guard farIndex > 0 else { return points }
        let firstHalf = simplifyOpen(Array(points[0...farIndex]), tolerance: tolerance)
        let secondHalf = simplifyOpen(Array(points[farIndex...]) + [first], tolerance: tolerance)
        return Array(firstHalf.dropLast()) + Array(secondHalf.dropLast())
    }

    private func simplifyOpen(_ points: [CGPoint], tolerance: CGFloat) -> [CGPoint] {
        guard points.count > 2 else { return points }
        var keep = [Bool](repeating: false, count: points.count)
        keep[0] = true
        keep[points.count - 1] = true
        var ranges = [(0, points.count - 1)]
        while let (start, end) = ranges.popLast() {
            let a = points[start]
            let b = points[end]
            let dx = b.x - a.x
            let dy = b.y - a.y
            let length = hypot(dx, dy)
            var farthest = -1.0 as CGFloat
            var farthestIndex = -1
            for index in (start + 1)..<end {
                let p = points[index]
                let distance = length < 1e-6
                    ? hypot(p.x - a.x, p.y - a.y)
                    : abs(dy * (p.x - a.x) - dx * (p.y - a.y)) / length
                if distance > farthest {
                    farthest = distance
                    farthestIndex = index
                }
            }
            if farthest > tolerance, farthestIndex > 0 {
                keep[farthestIndex] = true
                ranges.append((start, farthestIndex))
                ranges.append((farthestIndex, end))
            }
        }
        return points.indices.filter { keep[$0] }.map { points[$0] }
    }

    private func chaikin(_ points: [CGPoint], iterations: Int) -> [CGPoint] {
        guard points.count >= 3 else { return points }
        var result = points
        for _ in 0..<iterations {
            var next: [CGPoint] = []
            next.reserveCapacity(result.count * 2)
            for index in result.indices {
                let a = result[index]
                let b = result[(index + 1) % result.count]
                next.append(CGPoint(x: a.x * 0.75 + b.x * 0.25, y: a.y * 0.75 + b.y * 0.25))
                next.append(CGPoint(x: a.x * 0.25 + b.x * 0.75, y: a.y * 0.25 + b.y * 0.75))
            }
            result = next
        }
        return result
    }
}
