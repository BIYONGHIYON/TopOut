import UIKit
import CoreImage
@preconcurrency import AVFoundation

// MARK: - 비디오 프레임 전달

final class VideoFrameSink: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate {
    private let recorder: ContinuousRecorder
    private let motionDetector: MotionDetector
    private let stateQ = DispatchQueue(label: "topout.video.frame.sink.state")
    private let ciContext = CIContext(options: [.useSoftwareRenderer: false])
    private var isRecording = false
    private var isMotionDetectionEnabled = false
    private var videoFramesSinceReset = 0
    private var didNotifyStreamReady = false
    private var streamReadinessHandler: (() -> Void)?

    private enum FrameCapturePolicy {
        case nextFrame
        case stableCamera(AVCaptureDevice?)
    }

    private struct FrameCaptureRequest {
        let id: UUID
        let aspectRatio: CGFloat
        let maxLongSide: CGFloat
        let completion: (UIImage?) -> Void
        let policy: FrameCapturePolicy
        let requestedAt: TimeInterval
        var observedFrames: Int
        var consecutiveStableFrames: Int
    }

    private var pendingFrameCapture: FrameCaptureRequest?

    init(recorder: ContinuousRecorder, motionDetector: MotionDetector) {
        self.recorder = recorder
        self.motionDetector = motionDetector
        super.init()
    }

    func setStreamReadinessHandler(_ handler: @escaping () -> Void) {
        stateQ.async { [weak self] in
            self?.streamReadinessHandler = handler
        }
    }

    func resetStreamReadiness() {
        stateQ.async { [weak self] in
            self?.videoFramesSinceReset = 0
            self?.didNotifyStreamReady = false
        }
    }


    func setRecording(_ recording: Bool) {
        stateQ.async { [weak self] in
            guard let self else { return }
            self.isRecording = recording
            if !recording {
                self.isMotionDetectionEnabled = false
            }
        }
    }

    func setMotionDetectionEnabled(_ enabled: Bool) {
        stateQ.async { [weak self] in
            self?.isMotionDetectionEnabled = enabled
        }
    }

    func captureCurrentFrame(
        aspectRatio: CGFloat,
        maxLongSide: CGFloat,
        completion: @escaping (UIImage?) -> Void
    ) {
        enqueueFrameCapture(
            aspectRatio: aspectRatio,
            maxLongSide: maxLongSide,
            policy: .nextFrame,
            completion: completion
        )
    }

    /// 카메라 자동 보정이 안정된 새 프레임을 우선 캡처한다.
    func captureStableFrame(
        aspectRatio: CGFloat,
        maxLongSide: CGFloat,
        camera: AVCaptureDevice?,
        completion: @escaping (UIImage?) -> Void
    ) {
        enqueueFrameCapture(
            aspectRatio: aspectRatio,
            maxLongSide: maxLongSide,
            policy: .stableCamera(camera),
            completion: completion
        )
    }

    private func enqueueFrameCapture(
        aspectRatio: CGFloat,
        maxLongSide: CGFloat,
        policy: FrameCapturePolicy,
        completion: @escaping (UIImage?) -> Void
    ) {
        let request = FrameCaptureRequest(
            id: UUID(),
            aspectRatio: aspectRatio,
            maxLongSide: maxLongSide,
            completion: completion,
            policy: policy,
            requestedAt: ProcessInfo.processInfo.systemUptime,
            observedFrames: 0,
            consecutiveStableFrames: 0
        )

        // 미리보기 프레임은 보관하지 않고 최신 캡처 요청만 유지한다.
        stateQ.async { [weak self] in
            self?.pendingFrameCapture = request
        }

        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) { [weak self] in
            guard let self else { return }
            self.stateQ.async { [weak self] in
                guard let self, self.pendingFrameCapture?.id == request.id else { return }
                self.pendingFrameCapture = nil
                DispatchQueue.main.async { request.completion(nil) }
            }
        }
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer) else { return }
        let mediaType = CMFormatDescriptionGetMediaType(formatDescription)

        if mediaType == kCMMediaType_Audio {
            let shouldRecord = stateQ.sync { isRecording }
            guard shouldRecord else { return }
            recorder.appendAudio(sampleBuffer)
            return
        }
        guard mediaType == kCMMediaType_Video else { return }

        let state = stateQ.sync { () -> (
            recording: Bool,
            motionDetection: Bool,
            capture: FrameCaptureRequest?,
            streamReadyHandler: (() -> Void)?
        ) in
            var capture: FrameCaptureRequest?
            var readyHandler: (() -> Void)?

            videoFramesSinceReset += 1
            if !didNotifyStreamReady, videoFramesSinceReset >= 6 {
                didNotifyStreamReady = true
                readyHandler = streamReadinessHandler
            }

            if var request = pendingFrameCapture {
                request.observedFrames += 1
                let shouldCapture: Bool
                switch request.policy {
                case .nextFrame:
                    shouldCapture = true
                case let .stableCamera(camera):
                    let elapsed = ProcessInfo.processInfo.systemUptime - request.requestedAt
                    let cameraIsSettled = camera.map {
                        !$0.isAdjustingExposure && !$0.isAdjustingFocus && !$0.isAdjustingWhiteBalance
                    } ?? true
                    request.consecutiveStableFrames = cameraIsSettled
                        ? request.consecutiveStableFrames + 1
                        : 0
                    let hasFreshFrames = request.observedFrames >= 4 && elapsed >= 0.20
                    let hasStableCamera = request.consecutiveStableFrames >= 2
                    shouldCapture = (hasFreshFrames && hasStableCamera) || elapsed >= 0.90
                }

                if shouldCapture {
                    capture = request
                    pendingFrameCapture = nil
                } else {
                    pendingFrameCapture = request
                }
            }
            return (isRecording, isMotionDetectionEnabled, capture, readyHandler)
        }
        let shouldRecord = state.recording
        let shouldRunMotionDetection = state.motionDetection

        if let readyHandler = state.streamReadyHandler {
            DispatchQueue.main.async(execute: readyHandler)
        }

        if let request = state.capture,
           let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) {
            DispatchQueue.global(qos: .userInitiated).async { [ciContext, pixelBuffer] in
                let image = autoreleasepool {
                    Self.makeCroppedImage(
                        from: pixelBuffer,
                        aspectRatio: request.aspectRatio,
                        maxLongSide: request.maxLongSide,
                        context: ciContext
                    )
                }
                DispatchQueue.main.async { request.completion(image) }
            }
        }

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
