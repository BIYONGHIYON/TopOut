import UIKit
import QuartzCore
import CoreImage
import CoreMedia
import CoreVideo
@preconcurrency import AVFoundation

/// 번들에 포함된 test.mov를 실제 카메라 프레임처럼 반복 공급합니다.
/// 화면 표시, 기준 프레임 캡처, 녹화, 모션/포즈 분석이 모두 같은 프레임을 사용합니다.
final class MockCameraSource: NSObject, @unchecked Sendable {
    enum SourceError: LocalizedError {
        case videoTrackMissing

        var errorDescription: String? {
            switch self {
            case .videoTrackMissing:
                return "test.mov에서 비디오 트랙을 찾을 수 없습니다."
            }
        }
    }

    let previewLayer: AVPlayerLayer = {
        let layer = AVPlayerLayer()
        layer.videoGravity = .resizeAspectFill
        layer.backgroundColor = UIColor.black.cgColor
        return layer
    }()

    private weak var frameConsumer: MockVideoFrameConsumer?
    private let frameQueue = DispatchQueue(label: "topout.mock.camera.frame.queue", qos: .userInitiated)
    private let stateQ = DispatchQueue(label: "topout.mock.camera.state.queue")
    private let ciContext = CIContext()

    private var asset: AVAsset?
    private var videoTrack: AVAssetTrack?
    private var playerItem: AVPlayerItem?
    private var player: AVPlayer?
    private var videoOutput: AVPlayerItemVideoOutput?
    private var displayLink: CADisplayLink?
    private var endObserver: NSObjectProtocol?

    private var assetDuration: CMTime = .zero
    private var sourceNaturalSize: CGSize = .zero
    private var sourcePreferredTransform: CGAffineTransform = .identity
    private var sourceNominalFrameRate: Float = 0

    private var targetFPS: Double = 60
    private var zoomFactor: CGFloat = 1
    private var targetOrientation: TopOutVideoOrientation = .portrait
    private var isFrameProcessing = false
    private var latestSourcePixelBuffer: CVPixelBuffer?
    private var pixelBufferPool: CVPixelBufferPool?
    private var pixelBufferPoolSize: CGSize = .zero

    private(set) var isConfigured = false

    init(frameConsumer: MockVideoFrameConsumer) {
        self.frameConsumer = frameConsumer
        super.init()
    }

    deinit {
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
        }
        displayLink?.invalidate()
    }

    /// AVFoundation의 iOS 16+ 비동기 로딩 API로 test.mov의 트랙 정보를 준비합니다.
    func configure(url: URL) async throws {
        cleanupPlayer()

        let asset = AVURLAsset(url: url)
        let tracks = try await asset.loadTracks(withMediaType: .video)
        guard let track = tracks.first else {
            throw SourceError.videoTrackMissing
        }

        // 필요한 값을 미리 로드해 재생 중 deprecated 동기 프로퍼티에 접근하지 않습니다.
        let naturalSize = try await track.load(.naturalSize)
        let preferredTransform = try await track.load(.preferredTransform)
        let nominalFrameRate = try await track.load(.nominalFrameRate)
        let duration = try await asset.load(.duration)

        let item = AVPlayerItem(asset: asset)
        // nil이면 AVFoundation이 가장 적합한 출력 픽셀 포맷을 선택합니다.
        // AnyHashable 기반 옵션 딕셔너리를 피해서 Swift 6 Sendable 진단도 발생하지 않습니다.
        let output = AVPlayerItemVideoOutput(pixelBufferAttributes: nil)
        item.add(output)

        let player = AVPlayer(playerItem: item)
        player.isMuted = true
        player.actionAtItemEnd = .pause
        player.automaticallyWaitsToMinimizeStalling = false

        self.asset = asset
        self.videoTrack = track
        self.assetDuration = duration
        self.sourceNaturalSize = naturalSize
        self.sourcePreferredTransform = preferredTransform
        self.sourceNominalFrameRate = nominalFrameRate
        self.playerItem = item
        self.videoOutput = output
        self.player = player
        previewLayer.player = player

        applyVideoComposition()
        installEndObserver(for: item)
        installDisplayLinkIfNeeded()
        updatePreviewZoom()
        isConfigured = true
    }

    func start() {
        guard isConfigured else { return }
        displayLink?.isPaused = false

        guard let player else { return }
        if player.currentItem?.status == .failed {
            return
        }

        let duration = assetDuration.seconds
        let current = player.currentTime().seconds
        if duration.isFinite, duration > 0, current.isFinite, current >= duration - 0.03 {
            player.seek(to: CMTime.zero, toleranceBefore: CMTime.zero, toleranceAfter: CMTime.zero) { [weak player] _ in
                player?.play()
            }
        } else {
            player.play()
        }
    }

    func pause() {
        displayLink?.isPaused = true
        player?.pause()
    }

    func setPreviewVisible(_ visible: Bool) {
        previewLayer.isHidden = !visible
    }

    func updatePreviewFrame(_ frame: CGRect) {
        let zoom = stateQ.sync { zoomFactor }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        previewLayer.setAffineTransform(.identity)
        previewLayer.frame = frame
        previewLayer.setAffineTransform(CGAffineTransform(scaleX: zoom, y: zoom))
        CATransaction.commit()
    }

    func setFrameRate(_ fps: Double) {
        targetFPS = max(1, fps)
        displayLink?.preferredFramesPerSecond = max(1, Int(targetFPS.rounded()))
    }

    func setZoomFactor(_ factor: CGFloat) {
        let clamped = max(Cfg.minZoom, min(factor, 8.0))
        stateQ.sync {
            zoomFactor = clamped
        }
        updatePreviewZoom(factor: clamped)
    }

    func setOrientation(_ orientation: TopOutVideoOrientation) {
        guard targetOrientation != orientation else { return }
        targetOrientation = orientation
        applyVideoComposition()
        resetPixelBufferPool()
    }

    private func installEndObserver(for item: AVPlayerItem) {
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            guard let self, let player = self.player else { return }
            self.latestSourcePixelBuffer = nil
            player.seek(to: CMTime.zero, toleranceBefore: CMTime.zero, toleranceAfter: CMTime.zero) { finished in
                guard finished else { return }
                player.play()
            }
        }
    }

    private func installDisplayLinkIfNeeded() {
        displayLink?.invalidate()
        let link = CADisplayLink(target: self, selector: #selector(onDisplayLink(_:)))
        link.preferredFramesPerSecond = max(1, Int(targetFPS.rounded()))
        link.add(to: .main, forMode: .common)
        link.isPaused = true
        displayLink = link
    }

    @objc private func onDisplayLink(_ link: CADisplayLink) {
        guard isConfigured,
              !isFrameProcessing,
              let videoOutput,
              let player,
              player.rate > 0 else { return }

        let itemTime = videoOutput.itemTime(forHostTime: link.timestamp + link.duration)
        if videoOutput.hasNewPixelBuffer(forItemTime: itemTime),
           let newPixelBuffer = videoOutput.copyPixelBuffer(
                forItemTime: itemTime,
                itemTimeForDisplay: nil
           ) {
            latestSourcePixelBuffer = newPixelBuffer
        }

        guard let pixelBuffer = latestSourcePixelBuffer else { return }
        isFrameProcessing = true
        let pts = CMClockGetTime(CMClockGetHostTimeClock())
        let fps = targetFPS

        frameQueue.async { [weak self, pixelBuffer] in
            guard let self else { return }
            autoreleasepool {
                let zoom = self.stateQ.sync { self.zoomFactor }
                let outputBuffer = self.zoomedPixelBuffer(pixelBuffer, zoomFactor: zoom) ?? pixelBuffer
                if let sampleBuffer = self.makeSampleBuffer(
                    pixelBuffer: outputBuffer,
                    presentationTime: pts,
                    fps: fps
                ) {
                    self.frameConsumer?.consumeMockVideoSampleBuffer(sampleBuffer)
                }
            }

            DispatchQueue.main.async { [weak self] in
                self?.isFrameProcessing = false
            }
        }
    }

    private func makeSampleBuffer(
        pixelBuffer: CVPixelBuffer,
        presentationTime: CMTime,
        fps: Double
    ) -> CMSampleBuffer? {
        var formatDescription: CMVideoFormatDescription?
        let formatStatus = CMVideoFormatDescriptionCreateForImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            formatDescriptionOut: &formatDescription
        )
        guard formatStatus == noErr, let formatDescription else { return nil }

        let timeScale = CMTimeScale(max(1, Int32(fps.rounded())))
        var timing = CMSampleTimingInfo(
            duration: CMTime(value: 1, timescale: timeScale),
            presentationTimeStamp: presentationTime,
            decodeTimeStamp: .invalid
        )
        var sampleBuffer: CMSampleBuffer?
        let status = CMSampleBufferCreateReadyWithImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            formatDescription: formatDescription,
            sampleTiming: &timing,
            sampleBufferOut: &sampleBuffer
        )
        guard status == noErr else { return nil }
        return sampleBuffer
    }

    private func zoomedPixelBuffer(_ sourceBuffer: CVPixelBuffer, zoomFactor: CGFloat) -> CVPixelBuffer? {
        let zoom = max(1, zoomFactor)
        guard zoom > 1.001 else { return sourceBuffer }

        let width = CVPixelBufferGetWidth(sourceBuffer)
        let height = CVPixelBufferGetHeight(sourceBuffer)
        guard width > 0, height > 0,
              let outputBuffer = makePixelBuffer(width: width, height: height) else { return nil }

        let sourceImage = CIImage(cvPixelBuffer: sourceBuffer)
        let extent = sourceImage.extent
        let cropWidth = extent.width / zoom
        let cropHeight = extent.height / zoom
        let cropRect = CGRect(
            x: extent.midX - cropWidth * 0.5,
            y: extent.midY - cropHeight * 0.5,
            width: cropWidth,
            height: cropHeight
        ).integral

        let normalized = sourceImage
            .cropped(to: cropRect)
            .transformed(by: CGAffineTransform(translationX: -cropRect.minX, y: -cropRect.minY))
            .transformed(by: CGAffineTransform(
                scaleX: CGFloat(width) / cropRect.width,
                y: CGFloat(height) / cropRect.height
            ))

        ciContext.render(
            normalized,
            to: outputBuffer,
            bounds: CGRect(x: 0, y: 0, width: width, height: height),
            colorSpace: CGColorSpaceCreateDeviceRGB()
        )
        return outputBuffer
    }

    private func makePixelBuffer(width: Int, height: Int) -> CVPixelBuffer? {
        let requestedSize = CGSize(width: width, height: height)
        if pixelBufferPool == nil || pixelBufferPoolSize != requestedSize {
            let attributes: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA),
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
                kCVPixelBufferIOSurfacePropertiesKey as String: [String: String](),
                kCVPixelBufferCGImageCompatibilityKey as String: true,
                kCVPixelBufferCGBitmapContextCompatibilityKey as String: true
            ]
            var pool: CVPixelBufferPool?
            let status = CVPixelBufferPoolCreate(
                kCFAllocatorDefault,
                nil,
                attributes as CFDictionary,
                &pool
            )
            guard status == kCVReturnSuccess, let pool else { return nil }
            pixelBufferPool = pool
            pixelBufferPoolSize = requestedSize
        }

        guard let pixelBufferPool else { return nil }
        var pixelBuffer: CVPixelBuffer?
        let status = CVPixelBufferPoolCreatePixelBuffer(
            kCFAllocatorDefault,
            pixelBufferPool,
            &pixelBuffer
        )
        guard status == kCVReturnSuccess else { return nil }
        return pixelBuffer
    }

    private func applyVideoComposition() {
        guard let track = videoTrack, let playerItem else { return }

        let naturalRect = CGRect(origin: .zero, size: sourceNaturalSize)
        guard naturalRect.width > 0, naturalRect.height > 0 else { return }

        let preferredRect = naturalRect.applying(sourcePreferredTransform).standardized
        let normalize = CGAffineTransform(
            translationX: -preferredRect.minX,
            y: -preferredRect.minY
        )
        var transform = sourcePreferredTransform.concatenating(normalize)
        var transformedRect = naturalRect.applying(transform).standardized

        let sourceIsLandscape = transformedRect.width > transformedRect.height
        let rotationAngle: CGFloat
        if sourceIsLandscape {
            switch targetOrientation {
            case .landscapeRight:
                rotationAngle = 0
            case .landscapeLeft:
                rotationAngle = .pi
            case .portrait:
                rotationAngle = -.pi / 2
            case .portraitUpsideDown:
                rotationAngle = .pi / 2
            }
        } else {
            switch targetOrientation {
            case .portrait:
                rotationAngle = 0
            case .portraitUpsideDown:
                rotationAngle = .pi
            case .landscapeRight:
                rotationAngle = .pi / 2
            case .landscapeLeft:
                rotationAngle = -.pi / 2
            }
        }

        if abs(rotationAngle) > 0.0001 {
            transform = transform.concatenating(CGAffineTransform(rotationAngle: rotationAngle))
            transformedRect = naturalRect.applying(transform).standardized
            transform = transform.concatenating(CGAffineTransform(
                translationX: -transformedRect.minX,
                y: -transformedRect.minY
            ))
            transformedRect = naturalRect.applying(transform).standardized
        }

        let renderSize = CGSize(
            width: evenDimension(abs(transformedRect.width)),
            height: evenDimension(abs(transformedRect.height))
        )
        guard renderSize.width > 0, renderSize.height > 0 else { return }

        let composition = AVMutableVideoComposition()
        composition.renderSize = renderSize
        let nominalFPS = sourceNominalFrameRate > 0 ? Double(sourceNominalFrameRate) : targetFPS
        composition.frameDuration = CMTime(
            value: 1,
            timescale: CMTimeScale(max(1, Int32(nominalFPS.rounded())))
        )

        let instruction = AVMutableVideoCompositionInstruction()
        instruction.timeRange = CMTimeRange(start: CMTime.zero, duration: assetDuration)
        let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: track)
        layerInstruction.setTransform(transform, at: CMTime.zero)
        instruction.layerInstructions = [layerInstruction]
        composition.instructions = [instruction]
        playerItem.videoComposition = composition
    }

    private func updatePreviewZoom(factor: CGFloat? = nil) {
        let resolved = max(Cfg.minZoom, min(factor ?? stateQ.sync { zoomFactor }, 8.0))
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        previewLayer.setAffineTransform(CGAffineTransform(scaleX: resolved, y: resolved))
        CATransaction.commit()
    }

    private func resetPixelBufferPool() {
        frameQueue.async { [weak self] in
            self?.pixelBufferPool = nil
            self?.pixelBufferPoolSize = CGSize.zero
        }
    }

    private func evenDimension(_ value: CGFloat) -> CGFloat {
        let rounded = max(2, Int(value.rounded()))
        return CGFloat(rounded - rounded % 2)
    }

    private func cleanupPlayer() {
        pause()
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
            self.endObserver = nil
        }
        player?.replaceCurrentItem(with: nil)
        previewLayer.player = nil
        asset = nil
        videoTrack = nil
        playerItem = nil
        player = nil
        videoOutput = nil
        assetDuration = CMTime.zero
        sourceNaturalSize = CGSize.zero
        sourcePreferredTransform = .identity
        sourceNominalFrameRate = 0
        latestSourcePixelBuffer = nil
        isConfigured = false
        resetPixelBufferPool()
    }
}
