import UIKit
import CoreImage
import CoreMedia
import CoreVideo
@preconcurrency import AVFoundation

final class VideoFrameSink: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate, MockVideoFrameConsumer {
    private let recorder: ContinuousRecorder
    private let motionDetector: MotionDetector
    private let stateLock = NSLock()
    private let ciContext = CIContext()
    private var isRecording = false
    private var isMotionDetectionEnabled = true
    private var usesMockVideoInput = false
    private var latestPixelBuffer: CVPixelBuffer?

    init(recorder: ContinuousRecorder, motionDetector: MotionDetector) {
        self.recorder = recorder
        self.motionDetector = motionDetector
        super.init()
    }


    func setRecording(_ recording: Bool) {
        stateLock.lock()
        isRecording = recording
        stateLock.unlock()
    }

    func setMotionDetectionEnabled(_ enabled: Bool) {
        stateLock.lock()
        isMotionDetectionEnabled = enabled
        stateLock.unlock()
    }

    func setUsesMockVideoInput(_ enabled: Bool) {
        stateLock.lock()
        usesMockVideoInput = enabled
        stateLock.unlock()
    }

    func consumeMockVideoSampleBuffer(_ sampleBuffer: CMSampleBuffer) {
        processVideoSampleBuffer(sampleBuffer)
    }

    func captureCurrentFrame(
        aspectRatio: CGFloat,
        maxLongSide: CGFloat,
        completion: @escaping (UIImage?) -> Void
    ) {
        stateLock.lock()
        let buffer = latestPixelBuffer
        stateLock.unlock()

        guard let buffer else {
            DispatchQueue.main.async { completion(nil) }
            return
        }

        DispatchQueue.global(qos: .userInitiated).async { [ciContext, buffer] in
            let image = autoreleasepool {
                Self.makeCroppedImage(
                    from: buffer,
                    aspectRatio: aspectRatio,
                    maxLongSide: maxLongSide,
                    context: ciContext
                )
            }
            DispatchQueue.main.async { completion(image) }
        }
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer) else { return }
        let mediaType = CMFormatDescriptionGetMediaType(formatDescription)

        stateLock.lock()
        let shouldRecord = isRecording
        let receivesMockVideoFrames = usesMockVideoInput
        stateLock.unlock()

        if mediaType == kCMMediaType_Audio {
            guard shouldRecord else { return }
            recorder.appendAudio(sampleBuffer)
            return
        }

        // 실제 AVCaptureSession은 카메라 사용 상태와 마이크 입력을 유지하기 위해 계속 실행합니다.
        // 화면/분석/녹화용 비디오 프레임은 test.mov에서만 받습니다.
        guard !receivesMockVideoFrames else { return }
        processVideoSampleBuffer(sampleBuffer)
    }

    private func processVideoSampleBuffer(_ sampleBuffer: CMSampleBuffer) {
        stateLock.lock()
        let shouldRecord = isRecording
        let shouldRunMotionDetection = isMotionDetectionEnabled
        if !shouldRecord, let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) {
            // 준비완료 캡처에 사용할 최신 프레임만 보관합니다.
            latestPixelBuffer = pixelBuffer
        }
        stateLock.unlock()

        guard shouldRecord else { return }
        recorder.append(sampleBuffer)
        if shouldRunMotionDetection {
            motionDetector.update(sampleBuffer)
        }
    }

    private static func makeCroppedImage(
        from pixelBuffer: CVPixelBuffer,
        aspectRatio: CGFloat,
        maxLongSide: CGFloat,
        context: CIContext
    ) -> UIImage? {
        let source = CIImage(cvPixelBuffer: pixelBuffer)
        let sourceExtent = source.extent.integral
        guard sourceExtent.width > 0, sourceExtent.height > 0, aspectRatio > 0 else { return nil }

        let currentAspect = sourceExtent.width / sourceExtent.height
        let crop: CGRect
        if currentAspect > aspectRatio {
            let width = sourceExtent.height * aspectRatio
            crop = CGRect(
                x: sourceExtent.midX - width * 0.5,
                y: sourceExtent.minY,
                width: width,
                height: sourceExtent.height
            )
        } else {
            let height = sourceExtent.width / aspectRatio
            crop = CGRect(
                x: sourceExtent.minX,
                y: sourceExtent.midY - height * 0.5,
                width: sourceExtent.width,
                height: height
            )
        }

        let normalized = source
            .cropped(to: crop.integral)
            .transformed(by: CGAffineTransform(translationX: -crop.integral.minX, y: -crop.integral.minY))

        let longSide = max(normalized.extent.width, normalized.extent.height)
        let scale = longSide > maxLongSide ? maxLongSide / longSide : 1
        let outputExtent = CGRect(
            x: 0,
            y: 0,
            width: floor(normalized.extent.width * scale),
            height: floor(normalized.extent.height * scale)
        )
        let output = normalized.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

        guard let cgImage = context.createCGImage(output, from: outputExtent) else { return nil }
        return UIImage(cgImage: cgImage, scale: 1, orientation: .up)
    }
}
