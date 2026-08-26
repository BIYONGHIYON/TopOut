import UIKit
import CoreMedia
import CoreVideo
@preconcurrency import AVFoundation

/// 번들 영상을 카메라 프레임처럼 반복 전달한다.
@MainActor
final class MockCameraSource: NSObject {
    let previewLayer: AVPlayerLayer

    private let player: AVPlayer
    private let videoOutput: AVPlayerItemVideoOutput
    private var displayLink: CADisplayLink?
    private var frameHandler: ((CMSampleBuffer) -> Void)?

    private(set) var isRunning = false

    init(videoURL: URL) {
        let asset = AVURLAsset(url: videoURL)
        let item = AVPlayerItem(asset: asset)
        let output = AVPlayerItemVideoOutput(pixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        ])
        item.videoComposition = AVMutableVideoComposition(propertiesOf: asset)
        item.add(output)

        player = AVPlayer(playerItem: item)
        player.isMuted = true
        player.actionAtItemEnd = .pause
        videoOutput = output
        previewLayer = AVPlayerLayer(player: player)
        previewLayer.videoGravity = .resizeAspectFill

        super.init()

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(restartFromBeginning),
            name: .AVPlayerItemDidPlayToEndTime,
            object: item
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
        displayLink?.invalidate()
    }

    func setFrameHandler(_ handler: @escaping (CMSampleBuffer) -> Void) {
        frameHandler = handler
    }

    func start() {
        guard !isRunning else {
            player.play()
            return
        }

        isRunning = true
        let link = CADisplayLink(target: self, selector: #selector(deliverFrame))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 24, maximum: 60, preferred: 60)
        link.add(to: .main, forMode: .common)
        displayLink = link
        player.play()
    }

    func stop() {
        isRunning = false
        player.pause()
        displayLink?.invalidate()
        displayLink = nil
    }

    func setZoomFactor(_ factor: CGFloat) {
        previewLayer.setAffineTransform(CGAffineTransform(scaleX: factor, y: factor))
    }

    @objc private func restartFromBeginning() {
        player.seek(to: .zero, toleranceBefore: .zero, toleranceAfter: .zero)
        if isRunning {
            player.play()
        }
    }

    @objc private func deliverFrame() {
        guard isRunning else { return }

        let itemTime = videoOutput.itemTime(forHostTime: CACurrentMediaTime())
        guard videoOutput.hasNewPixelBuffer(forItemTime: itemTime),
              let pixelBuffer = videoOutput.copyPixelBuffer(
                forItemTime: itemTime,
                itemTimeForDisplay: nil
              ),
              let sampleBuffer = makeSampleBuffer(from: pixelBuffer) else { return }

        frameHandler?(sampleBuffer)
    }

    /// 반복 재생 구간과 무관한 연속 시간축을 만든다.
    private func makeSampleBuffer(from pixelBuffer: CVPixelBuffer) -> CMSampleBuffer? {
        var formatDescription: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            formatDescriptionOut: &formatDescription
        ) == noErr,
        let formatDescription else { return nil }

        var timing = CMSampleTimingInfo(
            duration: CMTime(value: 1, timescale: 60),
            presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()),
            decodeTimeStamp: .invalid
        )
        var sampleBuffer: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            formatDescription: formatDescription,
            sampleTiming: &timing,
            sampleBufferOut: &sampleBuffer
        ) == noErr else { return nil }

        return sampleBuffer
    }
}
