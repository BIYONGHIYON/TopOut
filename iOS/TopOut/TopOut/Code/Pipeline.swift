import UIKit
import CoreML

extension HoldVC {
    /// 탭한 위치의 탑홀드를 MobileSAM으로 분리해 외곽선 이미지를 만든다. 작업 큐에서 호출한다.
    /// 홀드를 고르는 규칙은 HoldOutlineExtractor에 있다.
    func makeTopHoldOverlay(
        at point: CGPoint,
        image: UIImage,
        cancellationToken: AnalysisCancellationToken
    ) throws -> UIImage {
        try cancellationToken.check()
        let embedding = try makeEmbedding(for: image, cancellationToken: cancellationToken)
        try cancellationToken.check()
        let decoded = try decode(point: point, embedding: embedding.values, scale: embedding.scale)
        try cancellationToken.check()

        let masks = MLShapedArray<Float>(converting: decoded.masks)
        let iou = MLShapedArray<Float>(converting: decoded.iou)
        let shape = masks.shape
        guard shape.count == 4, shape[2] == shape[3] else { throw AppError.encoderOutputMissing }

        let outline = HoldOutlineExtractor().extract(.init(
            logits: masks.scalars,
            channelCount: shape[1],
            lowResSize: shape[2],
            iouPredictions: iou.scalars,
            modelVisibleSize: CGSize(
                width: (image.size.width * embedding.scale).rounded(),
                height: (image.size.height * embedding.scale).rounded()
            ),
            imageSize: image.size,
            tap: point,
            minimumDiameter: minimumHoldDiameter(image: image),
            strokeWidth: outlineStyle(image: image).redWidth
        ))
        try cancellationToken.check()
        guard let overlay = makeHoldOverlay(outline, image: image) else { throw AppError.renderFailed }
        return overlay
    }

    /// 결과 화면에서 멈춘 프레임의 탑홀드를 고른다. 응답이 늦거나 실패하면 탭 위치에 최소 크기 원을 쓴다.
    func selectTopHoldForReview(
        in image: UIImage,
        at point: CGPoint,
        completion: @escaping (UIImage?) -> Void
    ) {
        reviewTopHoldToken?.cancel()
        let token = AnalysisCancellationToken()
        reviewTopHoldToken = token
        var isFinished = false
        let finish: (UIImage?) -> Void = { [weak self] overlay in
            guard !isFinished else { return }
            isFinished = true
            if self?.reviewTopHoldToken === token { self?.reviewTopHoldToken = nil }
            completion(overlay)
        }

        DispatchQueue.main.asyncAfter(deadline: .now() + Cfg.topHoldAnalysisTimeout) { [weak self] in
            guard !isFinished else { return }
            token.cancel()
            finish(self?.makeDefaultTouchOverlay(at: point, image: image))
        }

        workQ.async { [weak self] in
            let overlay: UIImage? = autoreleasepool {
                guard let self else { return nil }
                do {
                    return try self.makeTopHoldOverlay(at: point, image: image, cancellationToken: token)
                } catch {
                    return token.isCancelled ? nil : self.makeDefaultTouchOverlay(at: point, image: image)
                }
            }
            DispatchQueue.main.async {
                guard !token.isCancelled else { return }
                finish(overlay)
            }
        }
    }
}
