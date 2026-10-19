import Foundation
import UIKit
@preconcurrency import CoreImage
@preconcurrency import AVFoundation
import CoreMedia

// MARK: - 연속 녹화기

/// 하나의 .mov 파일에 계속 쓰는 연속 녹화 recorder입니다.
/// 녹화 중에는 segment 파일을 만들지 않고, 사용자가 종료한 뒤 필요한 구간만 잘라 저장합니다.
final class ContinuousRecorder: @unchecked Sendable {
    struct RecordingSession {
        let url: URL
        let startPTS: CMTime
        let endPTS: CMTime

        var duration: TimeInterval {
            Swift.max(0, CMTimeGetSeconds(endPTS - startPTS))
        }
    }

    private final class URLCompletionBox: @unchecked Sendable {
        let completion: (Result<URL, Error>) -> Void

        init(_ completion: @escaping (Result<URL, Error>) -> Void) {
            self.completion = completion
        }
    }

    private let q = DispatchQueue(label: "topout.continuous.recorder.queue")
    private let fm = FileManager.default

    private var folder: URL?
    private var writer: AVAssetWriter?
    private var input: AVAssetWriterInput?
    private var audioInput: AVAssetWriterInput?
    private var currentURL: URL?
    private var recordingStartPTS: CMTime?
    private var recordingEndPTS: CMTime?
    private var recordingError: Error?
    private var outputResolution: RecordingResolution = .fhd
    private var outputFrameRate: RecordingFrameRate = .fps60
    private var outputAspectRatio: RecordingAspectRatio = .story916
    private var audioCaptureEnabled = false

    private(set) var isRunning = false
    private var isStopping = false

    // MARK: - 공개 동작

    func configure(resolution: RecordingResolution, frameRate: RecordingFrameRate, aspectRatio: RecordingAspectRatio = .story916) {
        q.async {
            guard !self.isRunning, self.writer == nil else { return }
            self.outputResolution = resolution
            self.outputFrameRate = frameRate
            self.outputAspectRatio = aspectRatio
        }
    }

    func setAudioCaptureEnabled(_ enabled: Bool) {
        q.async {
            // 현재 writer가 이미 시작된 경우에는 기존 트랙을 건드리지 않고,
            // 아직 첫 비디오 프레임 전이거나 다음 녹화부터 설정을 적용합니다.
            self.audioCaptureEnabled = enabled
        }
    }

    func start() {
        q.async {
            self.resetLocked(deleteFiles: true)
            self.folder = self.makeSessionFolder()
            self.isRunning = true
            self.isStopping = false
        }
    }


    func append(_ sampleBuffer: CMSampleBuffer) {
        // 고해상도 프레임이 무한히 쌓이지 않도록 캡처 큐에서 동기 순서로 기록한다.
        q.sync {
            guard self.isRunning, !self.isStopping else { return }
            guard CMSampleBufferDataIsReady(sampleBuffer) else { return }

            let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
            guard self.recordingError == nil else { return }

            if self.writer == nil {
                self.startWriterLocked(firstBuffer: sampleBuffer, startPTS: pts)
            }

            guard let input = self.input, input.isReadyForMoreMediaData else {
                if self.writer?.status == .failed {
                    self.recordingError = self.writer?.error ?? RecorderError.recordingFailed(L("error.recording_finish"))
                }
                return
            }
            if input.append(sampleBuffer) {
                self.recordingEndPTS = pts
            } else {
                self.recordingError = self.writer?.error ?? RecorderError.recordingFailed(L("error.recording_finish"))
            }
        }
    }

    func appendAudio(_ sampleBuffer: CMSampleBuffer) {
        // 오디오도 같은 기록 순서를 사용해 별도 버퍼 적체를 막는다.
        q.sync {
            guard self.isRunning, !self.isStopping else { return }
            guard CMSampleBufferDataIsReady(sampleBuffer) else { return }
            guard let writer = self.writer, writer.status == .writing else { return }
            guard let startPTS = self.recordingStartPTS else { return }

            let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
            guard pts >= startPTS else { return }

            guard let audioInput = self.audioInput, audioInput.isReadyForMoreMediaData else {
                if writer.status == .failed {
                    self.recordingError = writer.error ?? RecorderError.recordingFailed(L("error.recording_finish"))
                }
                return
            }
            if !audioInput.append(sampleBuffer), writer.status == .failed {
                self.recordingError = writer.error ?? RecorderError.recordingFailed(L("error.recording_finish"))
            }
        }
    }

    /// 사용자가 직접 녹화 종료를 눌렀을 때만 호출합니다.
    /// 원본 전체 녹화 파일을 닫고, 결과 화면에서 잘라낼 수 있도록 RecordingSession을 반환합니다.
    func stop(completion: @escaping (Result<RecordingSession, Error>) -> Void) {
        q.async {
            guard self.isRunning || self.writer != nil else {
                DispatchQueue.main.async { completion(.failure(RecorderError.noRecording)) }
                return
            }

            self.isRunning = false
            self.isStopping = true

            guard let writer = self.writer,
                  let input = self.input,
                  let url = self.currentURL,
                  let start = self.recordingStartPTS else {
                let error = self.recordingError ?? RecorderError.noRecording
                self.resetLocked(deleteFiles: false)
                DispatchQueue.main.async { completion(.failure(error)) }
                return
            }

            let end = self.recordingEndPTS ?? start
            let sessionURL = url
            let sessionStart = start
            let sessionEnd = end

            guard writer.status == .writing else {
                let error = writer.error ?? self.recordingError ?? RecorderError.recordingFailed(L("error.recording_finish"))
                self.isStopping = false
                self.writer = nil
                self.input = nil
                self.audioInput = nil
                DispatchQueue.main.async { completion(.failure(error)) }
                return
            }

            input.markAsFinished()
            self.audioInput?.markAsFinished()

            // Swift 6에서 AVAssetWriter를 @Sendable completion closure에 직접 캡처하지 않도록,
            // 완료 후 내부 큐에서 self.writer의 최종 상태를 다시 읽습니다.
            writer.finishWriting { [weak self] in
                guard let self else { return }
                self.q.async {
                    self.isStopping = false

                    let finalStatus = self.writer?.status
                    let finalError = self.writer?.error ?? self.recordingError
                    let fileExists = self.fm.fileExists(atPath: sessionURL.path)
                    let hasValidDuration = sessionEnd > sessionStart

                    if finalStatus == .completed, fileExists, hasValidDuration {
                        let session = RecordingSession(url: sessionURL, startPTS: sessionStart, endPTS: sessionEnd)
                        DispatchQueue.main.async { completion(.success(session)) }
                    } else {
                        let error = finalError ?? RecorderError.recordingFailed(L("error.recording_finish"))
                        DispatchQueue.main.async { completion(.failure(error)) }
                    }

                    // writer는 닫혔지만 folder/url은 review에서 trimming할 수 있도록 유지합니다.
                    self.writer = nil
                    self.input = nil
                    self.audioInput = nil
                }
            }
        }
    }

    /// 종료된 원본 녹화 파일에서 선택 구간만 잘라 별도 파일로 export합니다.
    /// 시작과 끝은 녹화 파일 기준 초 단위다.
    func exportTrimmedClip(
        fromOffset startSeconds: TimeInterval,
        toOffset endSeconds: TimeInterval,
        topHoldOverlayImage: UIImage? = nil,
        completion: @escaping (Result<URL, Error>) -> Void
    ) {
        q.async {
            guard let sourceURL = self.currentURL else {
                DispatchQueue.main.async { completion(.failure(RecorderError.noRecording)) }
                return
            }

            let start = Swift.max(0, startSeconds)
            let end = Swift.max(start, endSeconds)
            guard end > start else {
                DispatchQueue.main.async { completion(.failure(RecorderError.invalidRange)) }
                return
            }

            let outURL = self.makeOutputURL(prefix: "topout_trimmed")
            try? self.fm.removeItem(at: outURL)

            let completionBox = URLCompletionBox(completion)

            Task.detached(priority: .userInitiated) { [sourceURL, outURL, start, end, topHoldOverlayImage, completionBox] in
                do {
                    let trimmedURL = try await Self.renderTrimmedClip(
                        sourceURL: sourceURL,
                        outURL: outURL,
                        startSeconds: start,
                        endSeconds: end,
                        topHoldOverlayImage: topHoldOverlayImage
                    )
                    DispatchQueue.main.async {
                        completionBox.completion(.success(trimmedURL))
                    }
                } catch {
                    DispatchQueue.main.async {
                        completionBox.completion(.failure(error))
                    }
                }
            }
        }
    }

    private static func renderTrimmedClip(
        sourceURL: URL,
        outURL: URL,
        startSeconds: TimeInterval,
        endSeconds: TimeInterval,
        topHoldOverlayImage: UIImage?
    ) async throws -> URL {
        let source = AVURLAsset(url: sourceURL)
        let sourceDuration = try await source.load(.duration)

        let clampedStart = maxCMTimeStatic(
            CMTime(seconds: startSeconds, preferredTimescale: 600),
            .zero
        )
        let clampedEnd = minCMTimeStatic(
            CMTime(seconds: endSeconds, preferredTimescale: 600),
            sourceDuration
        )
        guard clampedEnd > clampedStart else { throw RecorderError.invalidRange }

        let range = CMTimeRange(start: clampedStart, duration: clampedEnd - clampedStart)

        let overlayImage: CIImage?
        if let topHoldOverlayImage {
            guard let overlayCG = resizedOverlayCGImage(topHoldOverlayImage, maxLongSide: 1280) else {
                throw RecorderError.exportFailed
            }
            overlayImage = CIImage(cgImage: overlayCG)
        } else {
            overlayImage = nil
        }

        // 선택한 시작점에서 영상과 오디오가 함께 시작하도록 고화질 재인코딩한다.
        return try await exportComposedTrim(
            source: source,
            outURL: outURL,
            range: range,
            overlayImage: overlayImage
        )
    }

    private static func exportComposedTrim(
        source: AVAsset,
        outURL: URL,
        range: CMTimeRange,
        overlayImage: CIImage?
    ) async throws -> URL {
        let tracks = try await source.loadTracks(withMediaType: .video)
        guard let sourceTrack = tracks.first else { throw RecorderError.exportFailed }
        let transform = try await sourceTrack.load(.preferredTransform)

        let composition = AVMutableComposition()
        guard let compTrack = composition.addMutableTrack(
            withMediaType: .video,
            preferredTrackID: kCMPersistentTrackID_Invalid
        ) else {
            throw RecorderError.exportFailed
        }

        try compTrack.insertTimeRange(range, of: sourceTrack, at: .zero)
        compTrack.preferredTransform = transform

        let audioTracks = try await source.loadTracks(withMediaType: .audio)
        for audioTrack in audioTracks {
            guard let compAudioTrack = composition.addMutableTrack(
                withMediaType: .audio,
                preferredTrackID: kCMPersistentTrackID_Invalid
            ) else { continue }
            try? compAudioTrack.insertTimeRange(range, of: audioTrack, at: .zero)
        }

        guard let exporter = AVAssetExportSession(
            asset: composition,
            presetName: AVAssetExportPresetHighestQuality
        ) else {
            throw RecorderError.exportFailed
        }

        exporter.shouldOptimizeForNetworkUse = false
        if let overlayImage {
            exporter.videoComposition = try await makeOverlayVideoComposition(
                asset: composition,
                overlayImage: overlayImage
            )
        }

        try await exporter.export(to: outURL, as: .mov)
        return outURL
    }

    private final class OverlayImageBox: @unchecked Sendable {
        let image: CIImage

        init(_ image: CIImage) {
            self.image = image
        }
    }

    private static func makeOverlayVideoComposition(
        asset: AVAsset,
        overlayImage: CIImage
    ) async throws -> AVVideoComposition {
        let overlayBox = OverlayImageBox(overlayImage)
        let ciContext = CIContext(options: nil)

        return try await withCheckedThrowingContinuation { continuation in
            AVVideoComposition.videoComposition(
                with: asset,
                applyingCIFiltersWithHandler: { request in
                    autoreleasepool {
                        let source = request.sourceImage
                        let targetExtent = source.extent
                        let overlay = overlayBox.image
                        let scale = Swift.max(
                            targetExtent.width / overlay.extent.width,
                            targetExtent.height / overlay.extent.height
                        )
                        let scaledOverlay = overlay.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
                        let centeredOverlay = scaledOverlay.transformed(
                            by: CGAffineTransform(
                                translationX: targetExtent.midX - scaledOverlay.extent.midX,
                                y: targetExtent.midY - scaledOverlay.extent.midY
                            )
                        )
                        let output = centeredOverlay
                            .composited(over: source)
                            .cropped(to: targetExtent)
                        request.finish(with: output, context: ciContext)
                    }
                },
                completionHandler: { videoComposition, error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else if let videoComposition {
                        continuation.resume(returning: videoComposition)
                    } else {
                        continuation.resume(throwing: RecorderError.exportFailed)
                    }
                }
            )
        }
    }

    private static func resizedOverlayCGImage(_ image: UIImage, maxLongSide: CGFloat) -> CGImage? {
        autoreleasepool {
            let pixelSize = CGSize(
                width: image.size.width * image.scale,
                height: image.size.height * image.scale
            )
            let longSide = Swift.max(pixelSize.width, pixelSize.height)
            guard longSide > maxLongSide else { return image.cgImage }

            let ratio = maxLongSide / longSide
            let targetSize = CGSize(
                width: Swift.max(1, pixelSize.width * ratio),
                height: Swift.max(1, pixelSize.height * ratio)
            )

            let format = UIGraphicsImageRendererFormat.default()
            format.scale = 1
            format.opaque = false
            let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
            let resized = renderer.image { _ in
                image.draw(in: CGRect(origin: .zero, size: targetSize))
            }
            return resized.cgImage ?? image.cgImage
        }
    }

    private static func maxCMTimeStatic(_ a: CMTime, _ b: CMTime) -> CMTime {
        a >= b ? a : b
    }

    private static func minCMTimeStatic(_ a: CMTime, _ b: CMTime) -> CMTime {
        a <= b ? a : b
    }

    func deleteAll() {
        q.async {
            self.resetLocked(deleteFiles: true)
        }
    }

    // MARK: - 기록기 생명주기

    private func startWriterLocked(firstBuffer: CMSampleBuffer, startPTS: CMTime) {
        guard let folder else { return }

        let url = folder.appendingPathComponent("topout_full_\(Int(Date().timeIntervalSince1970)).mov")
        try? fm.removeItem(at: url)

        do {
            let writer = try AVAssetWriter(outputURL: url, fileType: .mov)
            let settings = videoSettings(from: firstBuffer)
            let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
            input.expectsMediaDataInRealTime = true

            // 목업 입력이 화면 방향으로 전달되므로 별도 회전 변환을 적용하지 않는다.
            input.transform = .identity

            guard writer.canAdd(input) else {
                recordingError = RecorderError.recordingFailed(L("error.recording_finish"))
                return
            }

            writer.add(input)

            if audioCaptureEnabled {
                let audioInput = AVAssetWriterInput(mediaType: .audio, outputSettings: audioSettings())
                audioInput.expectsMediaDataInRealTime = true
                if writer.canAdd(audioInput) {
                    writer.add(audioInput)
                    self.audioInput = audioInput
                }
            }

            guard writer.startWriting() else {
                recordingError = writer.error ?? RecorderError.recordingFailed(L("error.recording_finish"))
                return
            }
            writer.startSession(atSourceTime: startPTS)

            self.writer = writer
            self.input = input
            self.currentURL = url
            self.recordingStartPTS = startPTS
            self.recordingEndPTS = startPTS
        } catch {
            recordingError = error
        }
    }

    // MARK: - 설정과 파일

    private func videoSettings(from sampleBuffer: CMSampleBuffer) -> [String: Any] {
        guard let fmt = CMSampleBufferGetFormatDescription(sampleBuffer) else {
            return defaultVideoSettings()
        }

        let dims = CMVideoFormatDescriptionGetDimensions(fmt)
        let sourceWidth = Int(dims.width)
        let sourceHeight = Int(dims.height)
        let target = outputSizeMatchingSourceOrientation(sourceWidth: sourceWidth, sourceHeight: sourceHeight)
        let bitRate = outputResolution.bitRate(for: outputFrameRate, aspectRatio: outputAspectRatio)

        return [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: Int(target.width),
            AVVideoHeightKey: Int(target.height),
            AVVideoScalingModeKey: AVVideoScalingModeResizeAspectFill,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: bitRate,
                AVVideoExpectedSourceFrameRateKey: outputFrameRate.rawValue,
                AVVideoMaxKeyFrameIntervalKey: outputFrameRate.rawValue * 2,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel
            ]
        ]
    }

    private func outputSizeMatchingSourceOrientation(sourceWidth: Int, sourceHeight: Int) -> CGSize {
        let portrait = outputAspectRatio.outputSize(for: outputResolution)
        if sourceHeight >= sourceWidth {
            return portrait
        } else {
            return CGSize(width: portrait.height, height: portrait.width)
        }
    }

    private func audioSettings() -> [String: Any] {
        [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVNumberOfChannelsKey: 1,
            AVSampleRateKey: 44_100,
            AVEncoderBitRateKey: 96_000
        ]
    }

    private func defaultVideoSettings() -> [String: Any] {
        let target = outputAspectRatio.outputSize(for: outputResolution)
        return [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: Int(target.width),
            AVVideoHeightKey: Int(target.height),
            AVVideoScalingModeKey: AVVideoScalingModeResizeAspectFill,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: outputResolution.bitRate(for: outputFrameRate, aspectRatio: outputAspectRatio),
                AVVideoExpectedSourceFrameRateKey: outputFrameRate.rawValue,
                AVVideoMaxKeyFrameIntervalKey: outputFrameRate.rawValue * 2,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel
            ]
        ]
    }

    private static func sessionBaseFolder(_ fm: FileManager) -> URL {
        fm.temporaryDirectory.appendingPathComponent("TopOutContinuous", isDirectory: true)
    }

    /// 이전 실행이 강제 종료되며 남긴 원본·내보내기 폴더를 앱 시작 시 정리합니다.
    static func purgeStaleSessionFolders() {
        let fm = FileManager.default
        // 이번 실행의 녹화 폴더가 생기기 전에 목록을 고정하고, 대용량 삭제는 백그라운드에서 진행합니다.
        guard let staleFolders = try? fm.contentsOfDirectory(
            at: sessionBaseFolder(fm),
            includingPropertiesForKeys: nil
        ), !staleFolders.isEmpty else { return }
        DispatchQueue.global(qos: .utility).async {
            staleFolders.forEach { try? FileManager.default.removeItem(at: $0) }
        }
    }

    private func makeSessionFolder() -> URL {
        let folder = Self.sessionBaseFolder(fm).appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? fm.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    private func makeOutputURL(prefix: String) -> URL {
        let folder = folder ?? fm.temporaryDirectory
        let name = "\(prefix)_\(Int(Date().timeIntervalSince1970)).mov"
        return folder.appendingPathComponent(name)
    }


    private func resetLocked(deleteFiles: Bool) {
        if let input, writer != nil, isRunning {
            input.markAsFinished()
        }

        if deleteFiles, let folder {
            try? fm.removeItem(at: folder)
        }

        folder = nil
        writer = nil
        input = nil
        audioInput = nil
        currentURL = nil
        recordingStartPTS = nil
        recordingEndPTS = nil
        recordingError = nil
        isRunning = false
        isStopping = false
    }
}


enum RecorderError: LocalizedError {
    case noRecording
    case invalidRange
    case exportFailed
    case recordingFailed(String)

    var errorDescription: String? {
        switch self {
        case .noRecording: return L("error.recording_file_missing")
        case .invalidRange: return L("error.invalid_clip_range")
        case .exportFailed: return L("error.save_clip")
        case .recordingFailed(let message): return message
        }
    }
}
