//벽/작은 영역 터치 보정 로직
import UIKit
import CoreML

extension HoldVC {
    func chooseBestPick(
        _ first: Pick,
        firstMask: MLMultiArray,
        image: UIImage,
        embedding: MLMultiArray,
        origin: CGPoint,
        zoom: CGFloat
    ) throws -> Pick {
        switch first.status {
        case .good:
            return first
        case .wall:
            return try correctedPick(
                from: first,
                firstMask: firstMask,
                reason: .wall,
                image: image,
                embedding: embedding,
                origin: origin,
                zoom: zoom
            ) ?? first
        case .tiny, .empty:
            return try correctedPick(
                from: first,
                firstMask: firstMask,
                reason: .tiny,
                image: image,
                embedding: embedding,
                origin: origin,
                zoom: zoom
            ) ?? first
        }
    }

    func correctedPick(
        from first: Pick,
        firstMask: MLMultiArray,
        reason: FixReason,
        image: UIImage,
        embedding: MLMultiArray,
        origin: CGPoint,
        zoom: CGFloat
    ) throws -> Pick? {
        if case .tiny = reason {
            var samePoint = [Pick]()

            for tol in uniqueTolerances(Cfg.looseHsvTols) where abs(tol - first.tol) > 0.0001 {
                let retry = try makePick(from: firstMask, at: first.point, image: image, tol: tol, origin: origin)
                if isUsable(retry, comparedTo: first, reason: reason, image: image) {
                    samePoint.append(retry)
                }
            }

            if let best = bestCandidate(samePoint, reason: reason, image: image, zoom: zoom) {
                return best
            }
        }

        let tpls = uniqueTolerances(tolerances(for: reason))
        let rings = nearbyPointRings(around: origin, image: image, zoom: zoom)
        var decodeCount = 0

        for ring in rings {
            var candidates = [Pick]()

            for point in ring {
                guard decodeCount < Cfg.maxCorrectionDecodes else { break }

                let mask = try decode(point: point, embedding: embedding)
                decodeCount += 1

                for tol in tpls {
                    let pick = try makePick(from: mask, at: point, image: image, tol: tol, origin: origin)
                    if isUsable(pick, comparedTo: first, reason: reason, image: image) {
                        candidates.append(pick)
                    }
                }
            }

            if let best = bestCandidate(candidates, reason: reason, image: image, zoom: zoom) {
                return best
            }

            if decodeCount >= Cfg.maxCorrectionDecodes { break }
        }

        return nil
    }

    func tolerances(for reason: FixReason) -> [Float] {
        switch reason {
        case .wall:
            return [Cfg.hsvTol]
        case .tiny:
            return [Cfg.hsvTol] + Cfg.looseHsvTols
        }
    }

    func uniqueTolerances(_ values: [Float]) -> [Float] {
        var result = [Float]()
        for value in values where !result.contains(where: { abs($0 - value) < 0.0001 }) {
            result.append(value)
        }
        return result
    }

    func isUsable(_ pick: Pick, comparedTo first: Pick, reason: FixReason, image: UIImage) -> Bool {
        guard pick.status == .good else { return false }

        switch reason {
        case .wall:
            return true
        case .tiny:
            let minArea = max(first.blob.area * 2, minHoldArea(image: image))
            return pick.blob.area >= minArea
        }
    }

    func bestCandidate(_ candidates: [Pick], reason: FixReason, image: UIImage, zoom: CGFloat) -> Pick? {
        guard !candidates.isEmpty else { return nil }

        let maxDist = searchRadius(for: Cfg.searchRadii.last ?? 0.03, image: image, zoom: zoom)
        let visibleArea = visibleMaskArea(for: image)

        return candidates.min {
            score($0, reason: reason, maxDist: maxDist, visibleArea: visibleArea)
            < score($1, reason: reason, maxDist: maxDist, visibleArea: visibleArea)
        }
    }

    func score(_ pick: Pick, reason: FixReason, maxDist: CGFloat, visibleArea: CGFloat) -> CGFloat {
        let distScore = maxDist > 0 ? pick.dist / maxDist : 0
        let areaScore = sqrt(CGFloat(pick.blob.area) / max(visibleArea, 1))

        switch reason {
        case .wall:
            return (distScore * 0.85) - (areaScore * 0.15)
        case .tiny:
            return (distScore * 0.55) - (areaScore * 0.45)
        }
    }

    func searchRadius(for ratio: CGFloat, image: UIImage, zoom: CGFloat) -> CGFloat {
        let base = min(image.size.width, image.size.height)
        return base * ratio / max(zoom, Cfg.minZoom)
    }

    func nearbyPointRings(around point: CGPoint, image: UIImage, zoom: CGFloat) -> [[CGPoint]] {
        let dirs: [(CGFloat, CGFloat)] = [
            (1, 0), (-1, 0), (0, 1), (0, -1),
            (0.707, 0.707), (-0.707, 0.707), (0.707, -0.707), (-0.707, -0.707)
        ]

        var rings = [[CGPoint]]()
        var seen = Set<String>()

        for ratio in Cfg.searchRadii {
            let r = searchRadius(for: ratio, image: image, zoom: zoom)
            var ring = [CGPoint]()

            for dir in dirs {
                let p = CGPoint(x: point.x + dir.0 * r, y: point.y + dir.1 * r)
                guard image.bounds.contains(p) else { continue }

                let key = "\(Int(p.x.rounded()))_\(Int(p.y.rounded()))"
                guard !seen.contains(key) else { continue }

                seen.insert(key)
                ring.append(p)
            }

            if !ring.isEmpty { rings.append(ring) }
        }

        return rings
    }

    func blobForRender(_ pick: Pick) -> Blob {
        if pick.status == .wall || pick.status == .empty { return .empty }
        return pick.blob
    }
}
