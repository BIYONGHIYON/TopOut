// MobileSAM 모델 준비와 전처리
import UIKit
import CoreML

extension HoldVC {
    /// 첫 카메라 프레임이 안정된 뒤 MobileSAM을 백그라운드에서 준비합니다.
    /// 앱 진입과 카메라 권한 흐름은 Core ML 로딩 성공 여부와 독립적으로 진행됩니다.
    func preloadMobileSAMAfterCameraStarts() {
        guard !hasStartedMobileSAMPreload else { return }
        hasStartedMobileSAMPreload = true

        workQ.async { [weak self] in
            guard let self else { return }

            do {
                try self.loadModelsIfNeeded()
            } catch {
                // 실제 분석 시 다시 로드를 시도하며, 계속 실패하면 기존 fallback을 사용합니다.
            }
        }
    }

    func loadModelsIfNeeded() throws {
        if encoder != nil, decoder != nil { return }

        let config = MLModelConfiguration()
        // 구형 기기의 메모리 급증을 줄이기 위해 CPU와 신경망 엔진만 사용한다.
        config.computeUnits = .cpuAndNeuralEngine

        if encoder == nil {
            encoder = try MobileSAM_Encoder(configuration: config)
        }
        if decoder == nil {
            decoder = try MobileSAM_Decoder(configuration: config)
        }

    }

    /// Each analysis owns its embedding and scale so a timed-out request cannot poison the next one.
    func makeEmbedding(for image: UIImage, cancellationToken: AnalysisCancellationToken) throws -> (values: MLMultiArray, scale: CGFloat) {
        try cancellationToken.check()
        try loadModelsIfNeeded()
        try cancellationToken.check()
        guard let encoder else { throw AppError.modelNotReady }

        let (input, scale) = try preprocess(image)
        try cancellationToken.check()
        let output = try encoder.prediction(input: MobileSAM_EncoderInput(image: input))
        try cancellationToken.check()
        return (output.var_1415, scale)
    }

    /// 추론 큐 순서를 지키며 모델과 임베딩을 안전하게 해제한다.
    func releaseMobileSAMResources() {
        // 예열 상태를 먼저 초기화하고 같은 작업 큐에서 해제와 다음 예열 순서를 보장한다.
        hasStartedMobileSAMPreload = false
        workQ.async { [weak self] in
            guard let self else { return }
            self.encoder = nil
            self.decoder = nil
        }
    }

    /// 한 점 프롬프트의 다중 마스크 logit과 채널별 신뢰도를 돌려준다.
    func decode(point: CGPoint, embedding: MLMultiArray, scale: CGFloat) throws -> (masks: MLMultiArray, iou: MLMultiArray) {
        guard let decoder else { throw AppError.modelNotReady }

        let x = Float(point.x * scale)
        let y = Float(point.y * scale)

        let coords = try MLMultiArray(shape: [1, 2, 2], dataType: .float32)
        let labels = try MLMultiArray(shape: [1, 2], dataType: .float32)

        coords[0] = NSNumber(value: x)
        coords[1] = NSNumber(value: y)
        labels[0] = 1.0

        coords[2] = 0.0
        coords[3] = 0.0
        labels[1] = -1.0

        let maskInput = try MLMultiArray(shape: [1, 1, 256, 256], dataType: .float32)
        let hasMask = try MLMultiArray(shape: [1], dataType: .float32)
        hasMask[0] = 0.0

        let input = MobileSAM_DecoderInput(
            image_embeddings: embedding,
            point_coords: coords,
            point_labels: labels,
            mask_input: maskInput,
            has_mask_input: hasMask
        )

        let output = try decoder.prediction(input: input)
        return (output.low_res_masks, output.iou_predictions)
    }

    func preprocess(_ image: UIImage) throws -> (MLMultiArray, CGFloat) {
        let size = Cfg.samSize
        let scale = CGFloat(size) / max(image.size.width, image.size.height)

        let newW = Int((image.size.width * scale).rounded())
        let newH = Int((image.size.height * scale).rounded())
        let padded = makePaddedImage(image, width: newW, height: newH, canvas: size)

        guard let cgImage = padded.cgImage else { throw AppError.encoderOutputMissing }

        guard let ctx = CGContext(
            data: nil,
            width: size,
            height: size,
            bitsPerComponent: 8,
            bytesPerRow: size * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else {
            throw AppError.renderFailed
        }

        ctx.draw(cgImage, in: CGRect(x: 0, y: 0, width: CGFloat(size), height: CGFloat(size)))
        guard let contextData = ctx.data else {
            throw AppError.renderFailed
        }
        let raw = contextData.bindMemory(to: UInt8.self, capacity: size * size * 4)

        let arr = try MLMultiArray(shape: [1, 3, NSNumber(value: size), NSNumber(value: size)], dataType: .float32)
        let ptr = arr.dataPointer.bindMemory(to: Float.self, capacity: arr.count)
        let plane = size * size

        for y in 0..<size {
            for x in 0..<size {
                let src = (y * size + x) * 4
                let dst = y * size + x

                ptr[0 * plane + dst] = (Float(raw[src]) - Cfg.mean[0]) / Cfg.std[0]
                ptr[1 * plane + dst] = (Float(raw[src + 1]) - Cfg.mean[1]) / Cfg.std[1]
                ptr[2 * plane + dst] = (Float(raw[src + 2]) - Cfg.mean[2]) / Cfg.std[2]
            }
        }

        return (arr, scale)
    }

    func makePaddedImage(_ image: UIImage, width: Int, height: Int, canvas: Int) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true

        let renderer = UIGraphicsImageRenderer(size: CGSize(width: canvas, height: canvas), format: format)
        return renderer.image { ctx in
            UIColor.black.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: CGFloat(canvas), height: CGFloat(canvas)))
            image.draw(in: CGRect(x: 0, y: 0, width: CGFloat(width), height: CGFloat(height)))
        }
    }
}
