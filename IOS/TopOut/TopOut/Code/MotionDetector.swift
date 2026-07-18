import UIKit
import Foundation
import CoreGraphics
import CoreMedia
import CoreVideo
import Vision
import CoreImage

// MARK: - Motion Events

enum MotionEvent {
    case start(pts: CMTime)
    case finish(pts: CMTime)
    case retry
}

enum MotionFinishReference: String {
    case closestTopHold
    case highestPosePoint
}

// MARK: - Motion Detector Pose Climb

/// MotionDetector Pose Climb
///
/// 기준:
/// - 3초 매트 보정 후 현재 프레임과 3개 분석 프레임 전을 비교해 매트 주변 움직임을 찾습니다.
/// - 움직임이 감지되면 0.5초 간격 pose tracking으로 전환합니다.
/// - 가장 낮은 pose 점이 매트 기준선 위로 올라가면 START, 다시 아래로 내려오면 FINISH로 봅니다.
/// - 탑홀드 마스킹 사용 시 FINISH는 탑홀드와 가장 가까웠던 pose 시점을 사용합니다.
/// - 탑홀드 마스킹 미사용 시에는 신체의 가장 높은 점이 화면상 가장 높았던 시점을 사용합니다.
/// - 녹화 중에는 원본 START/FINISH 시각만 저장하고, 정리 완료 후 3초 여백을 더합니다.
/// - 한 번 pose tracking에 들어가면 다음 START도 pose로 계속 찾고, 5초 이상 pose가 없으면 CV 탐색으로 복귀합니다.
final class MotionDetector {
    private enum State: String {
        case idle
        case calibrating
        case waiting
        case tracking
        case climbing
    }

    private struct SmallFrame {
        let width: Int
        let height: Int
        let luma: [UInt8]
    }

    private struct BandBin {
        let x0: CGFloat
        let x1: CGFloat
    }

    private struct CalibrationSample {
        let frame: SmallFrame
        let referenceFrame: SmallFrame
        let energy: CGFloat
    }

    private struct Calibration {
        let belowBaseline: [CGFloat]
        let upperBaseline: [CGFloat]
    }

    private struct ActiveRun {
        let indices: [Int]
        var count: Int { indices.count }
    }

    private struct RunScore {
        let indices: [Int]
        let energy: CGFloat
        var count: Int { indices.count }
    }

    private struct BoundaryMotion {
        let run: ActiveRun
        let energy: CGFloat
        let status: String
        let secondaryEnergy: CGFloat?
    }

    private struct PoseSnapshot {
        let observationCount: Int
        let allReliablePoints: [CGPoint]

        var hasPose: Bool { observationCount > 0 && !allReliablePoints.isEmpty }
    }

    var onEvent: ((MotionEvent) -> Void)?
    private let q = DispatchQueue(label: "topout.motion.detector.pose.v37", qos: .userInitiated)
    private let poseRequest = VNDetectHumanBodyPoseRequest()
    private let poseCIContext = CIContext(options: [.useSoftwareRenderer: false])
    private let reliablePoseJoints: [VNHumanBodyPoseObservation.JointName] = [
        .nose,
        .leftShoulder, .rightShoulder,
        .leftElbow, .rightElbow,
        .leftWrist, .rightWrist,
        .leftHip, .rightHip,
        .leftKnee, .rightKnee,
        .leftAnkle, .rightAnkle
    ]

    private var matLine: [CGPoint] = []
    private var topBox: CGRect = .zero
    private var finishReference: MotionFinishReference = .closestTopHold
    private var activeCropRect: CGRect = CGRect(x: 0, y: 0, width: 1, height: 1)
    private var horizontalBinCount = Cfg.motionBandBinCount
    private var cvFrameHistory: [SmallFrame] = []

    private var isActive = false
    private var state: State = .idle

    private var lastAnalyzedPTS: CMTime?
    private var motionAnalysisBoostUntilPTS: CMTime?
    private var calibrationStartedPTS: CMTime?
    private var calibrationSamples: [CalibrationSample] = []
    private var calibration: Calibration?

    private var belowBins: [BandBin] = []
    private var upperBins: [BandBin] = []

    private var lastPosePTS: CMTime?
    private var lastPoseSeenPTS: CMTime?
    private var poseTrackingStartedPTS: CMTime?
    private var lastLowestAboveMat: Bool?
    private var activeStartPTS: CMTime?
    private var activeBestTopHoldPTS: CMTime?
    private var activeBestTopHoldDistance: CGFloat?
    private var activeHighestPosePTS: CMTime?
    private var activeHighestPoseY: CGFloat?

    // MARK: Public

    func configure(
        matLine: [CGPoint],
        topBox: CGRect?,
        finishReference: MotionFinishReference,
        referenceImage _: UIImage?,
        activeCropRect: CGRect,
        horizontalBinCount: Int = Cfg.motionBandBinCount
    ) {
        q.async { [weak self] in
            guard let self else { return }
            self.matLine = matLine.sorted { $0.x < $1.x }
            self.topBox = topBox.map { self.clamped($0) } ?? .zero
            self.finishReference = finishReference
            self.activeCropRect = self.clamped(activeCropRect)
            self.horizontalBinCount = max(1, horizontalBinCount)
            self.buildRegionsLocked()
            self.resetStateLocked(keepConfig: true)
            self.debug("🎯 motion configured pose climb: matPoints=\(matLine.count), finishReference=\(finishReference.rawValue), topBox=\(self.topBox), crop=\(self.activeCropRect), bins=\(self.horizontalBinCount), cvLag=\(Cfg.motionCVComparisonFrameLag)")
        }
    }

    /// Countdown 시작과 동시에 호출합니다.
    /// 이 상태에서 들어오는 프레임으로 모션 기준값을 안정화합니다.
    func resetForRecording() {
        q.async { [weak self] in
            guard let self else { return }
            self.resetStateLocked(keepConfig: true)
            self.isActive = true
            self.state = .calibrating
            self.debug(String(format: "👀 motion detector started: countdown calibration %.1fs", Cfg.motionCountdownSeconds))
        }
    }

    func activeClipRangeForRecordingStop() -> ClipRange? {
        q.sync { [weak self] in
            guard let self else { return nil }
            return self.openClipRangeLocked(reason: "recording stop")
        }
    }

    func stop() {
        q.async { [weak self] in
            guard let self else { return }
            self.isActive = false
            self.resetStateLocked(keepConfig: true)
            self.debug("🛑 motion detector stopped")
        }
    }

    func update(_ sampleBuffer: CMSampleBuffer) {
        q.async { [weak self] in
            self?.process(sampleBuffer)
        }
    }

    // MARK: Processing

    private func process(_ sampleBuffer: CMSampleBuffer) {
        guard isActive, !matLine.isEmpty else { return }
        if finishReference == .closestTopHold, topBox.isEmpty { return }
        guard CMSampleBufferDataIsReady(sampleBuffer) else { return }
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)

        if state == .tracking || state == .climbing {
            processPoseTrackingFrame(pixelBuffer: pixelBuffer, pts: pts)
            return
        }

        let analysisInterval = currentAnalysisInterval(at: pts)
        if let last = lastAnalyzedPTS {
            let dt = CMTimeGetSeconds(pts - last)
            if dt >= 0, dt < analysisInterval { return }
        }
        lastAnalyzedPTS = pts

        guard let frame = makeSmallFrame(from: sampleBuffer) else { return }
        let comparisonFrame = rollingCVReference(for: frame)

        if state == .calibrating {
            collectCalibration(frame: frame, referenceFrame: comparisonFrame, pts: pts)
            return
        }

        guard state == .waiting else { return }
        guard let base = comparisonFrame, let calibration else { return }
        guard base.width == frame.width, base.height == frame.height else { return }

        let belowValues = adjustedBandSignals(frame: frame, base: base, bins: belowBins, kind: .below, baseline: calibration.belowBaseline)
        let upperValues = adjustedBandSignals(frame: frame, base: base, bins: upperBins, kind: .upper, baseline: calibration.upperBaseline)
        let boundaryMotion = classifyBoundaryMotion(belowValues: belowValues, upperValues: upperValues)
        if boundaryMotion != nil {
            boostMotionAnalysis(until: pts + CMTime(seconds: Cfg.motionAnalysisBoostSeconds, preferredTimescale: 600))
        }

        var latestPose: PoseSnapshot?
        var poseReason = "-"

        func runPose(reason: String) -> PoseSnapshot? {
            guard canRunPose(at: pts, interval: Cfg.motionPoseStartVerificationInterval) else { return nil }
            lastPosePTS = pts
            let snapshot = detectPose(in: pixelBuffer)
            latestPose = snapshot
            poseReason = reason
            return snapshot
        }

        handleWaitingBoundary(boundaryMotion, pts: pts, pose: runPose)

        if Cfg.motionDebugLog, (boundaryMotion != nil || latestPose != nil) {
            debugState(
                pts: pts,
                belowActive: activeIndices(belowValues),
                upperActive: activeIndices(upperValues),
                boundaryMotion: boundaryMotion,
                latestPose: latestPose,
                poseReason: poseReason
            )
        }
    }

    private func processPoseTrackingFrame(pixelBuffer: CVPixelBuffer, pts: CMTime) {
        guard state == .tracking || state == .climbing else { return }
        guard canRunPose(at: pts, interval: Cfg.motionPoseTrackingInterval) else { return }

        lastPosePTS = pts
        let snapshot = detectPose(in: pixelBuffer)
        handlePoseTrackingSnapshot(snapshot, pts: pts)

        if Cfg.motionDebugLog {
            debugState(
                pts: pts,
                belowActive: [],
                upperActive: [],
                boundaryMotion: nil,
                latestPose: snapshot,
                poseReason: state == .climbing ? "climbingPose" : "trackingPose"
            )
        }
    }

    private func debugState(
        pts: CMTime,
        belowActive: [Int],
        upperActive: [Int],
        boundaryMotion: BoundaryMotion?,
        latestPose: PoseSnapshot?,
        poseReason: String
    ) {
        let poseText: String
        if let latestPose {
            poseText = "obs=\(latestPose.observationCount) points=\(latestPose.allReliablePoints.count)"
        } else {
            poseText = "-"
        }

        let boundaryText: String
        if let boundaryMotion {
            let secondary = boundaryMotion.secondaryEnergy.map { String(format: "%.2f", Double($0)) } ?? "-"
            boundaryText = "\(formatIndices(boundaryMotion.run.indices))/\(boundaryMotion.status)/e=\(String(format: "%.2f", Double(boundaryMotion.energy)))/s=\(secondary)"
        } else {
            boundaryText = "-"
        }

        let noPoseAge: String
        if let lastPoseSeenPTS {
            noPoseAge = String(format: "%.1f", duration(from: lastPoseSeenPTS, to: pts))
        } else {
            noPoseAge = "-"
        }

        debug(String(format: "📈 motion bins below=%@ upper=%@ | boundary=%@ | pose=%@ reason=%@ noPoseAge=%@ state=%@",
                     formatIndices(belowActive),
                     formatIndices(upperActive),
                     boundaryText,
                     poseText,
                     poseReason,
                     noPoseAge,
                     state.rawValue))
    }

    private func currentAnalysisInterval(at pts: CMTime) -> TimeInterval {
        let fps: Double
        switch state {
        case .calibrating:
            fps = Cfg.motionAnalysisCalibrationFPS
        case .waiting:
            if let until = motionAnalysisBoostUntilPTS, CMTimeCompare(pts, until) <= 0 {
                fps = Cfg.motionAnalysisBoostFPS
            } else {
                fps = Cfg.motionAnalysisIdleFPS
            }
        default:
            fps = Cfg.motionAnalysisIdleFPS
        }
        return 1.0 / Swift.max(1.0, fps)
    }

    private func boostMotionAnalysis(until pts: CMTime) {
        if let current = motionAnalysisBoostUntilPTS, CMTimeCompare(current, pts) >= 0 { return }
        motionAnalysisBoostUntilPTS = pts
    }

    // MARK: Calibration

    private func collectCalibration(frame: SmallFrame, referenceFrame: SmallFrame?, pts: CMTime) {
        if calibrationStartedPTS == nil { calibrationStartedPTS = pts }

        if let base = referenceFrame, base.width == frame.width, base.height == frame.height {
            let below = belowBins.map { baseDiffScore(frame: frame, base: base, bin: $0, kind: .below) }.reduce(CGFloat(0), +)
            let upper = upperBins.map { baseDiffScore(frame: frame, base: base, bin: $0, kind: .upper) }.reduce(CGFloat(0), +)
            calibrationSamples.append(
                CalibrationSample(frame: frame, referenceFrame: base, energy: below + upper)
            )
        }

        guard duration(from: calibrationStartedPTS, to: pts) >= Cfg.motionCountdownSeconds else { return }
        guard !calibrationSamples.isEmpty else { return }
        finishCalibration()
    }

    private func finishCalibration() {
        let samples = calibrationSamples
        guard !samples.isEmpty else { return }

        let energies = samples.map { $0.energy }
        let med = median(energies)
        let mad = median(energies.map { abs($0 - med) })
        let threshold = med + Swift.max(Cfg.motionCalibrationMinOutlierDelta, Cfg.motionCalibrationMADFactor * Swift.max(mad, 0.0001))
        let accepted = samples.filter { $0.energy <= threshold }
        let used = accepted.isEmpty ? samples : accepted

        let belowBase = makeBaseline(samples: used, bins: belowBins, kind: .below)
        let upperBase = makeBaseline(samples: used, bins: upperBins, kind: .upper)

        calibration = Calibration(
            belowBaseline: belowBase,
            upperBaseline: upperBase
        )

        state = .waiting
        calibrationStartedPTS = nil
        calibrationSamples.removeAll()

        debug(String(format: "🧪 rolling motion base ready: lag=%d samples=%d accepted=%d rejected=%d belowBase=%@ upperBase=%@",
                     Cfg.motionCVComparisonFrameLag, samples.count, used.count, samples.count - used.count,
                     shortValues(belowBase), shortValues(upperBase)))
    }

    // MARK: State Machine

    private func handleWaitingBoundary(
        _ motion: BoundaryMotion?,
        pts: CMTime,
        pose: (_ reason: String) -> PoseSnapshot?
    ) {
        guard let motion else { return }

        enterPoseTrackingFromCV(at: pts, reason: "boundary motion \(boundaryRunKey(motion.run))")
        guard let snapshot = pose("cvMotionPose") else { return }
        handlePoseTrackingSnapshot(snapshot, pts: pts)
    }

    private func enterPoseTrackingFromCV(at pts: CMTime, reason: String) {
        cvFrameHistory.removeAll(keepingCapacity: true)
        state = .tracking
        poseTrackingStartedPTS = pts
        lastPoseSeenPTS = nil
        lastLowestAboveMat = nil
        activeStartPTS = nil
        resetFinishReferenceTracking()
        debug("🏃 pose tracking started after CV: \(reason)")
    }

    private func handlePoseTrackingSnapshot(_ snapshot: PoseSnapshot, pts: CMTime) {
        guard state == .tracking || state == .climbing else { return }

        guard snapshot.hasPose, let lowestPoint = lowestReliablePoint(in: snapshot) else {
            handlePoseMissing(at: pts)
            return
        }

        lastPoseSeenPTS = pts
        poseTrackingStartedPTS = nil
        let lowestAboveMat = isAboveMatLine(lowestPoint, tolerance: Cfg.motionPoseMatCrossingTolerance)

        switch state {
        case .tracking:
            // CV 이후 첫 pose에서 이미 가장 낮은 점이 매트 위라면 즉시 start로 잡습니다.
            // 아니면 아래→위 crossing 순간을 start로 잡습니다.
            let firstValidPose = lastLowestAboveMat == nil
            let crossedUp = lowestAboveMat && (lastLowestAboveMat != true)
            lastLowestAboveMat = lowestAboveMat
            guard crossedUp else { return }

            // Store only the original crossing time. Review padding is added after all short-range
            // cleanup and merging when the recording is finalized.
            let startReason = firstValidPose
                ? "first pose already above mat"
                : "lowest pose point crossed above mat"
            emitStart(pts, reason: startReason)
            updateFinishReferenceMoment(snapshot: snapshot, pts: pts)

        case .climbing:
            updateFinishReferenceMoment(snapshot: snapshot, pts: pts)
            lastLowestAboveMat = lowestAboveMat

            // 가장 낮은 pose 점이 매트 아래로 다시 내려온 순간, 등반 구간을 닫습니다.
            guard !lowestAboveMat else { return }

            let finishBasePTS = bestFinishReferencePTS() ?? pts
            emitFinish(
                finishBasePTS,
                reason: "lowest pose point returned below mat; raw end from \(finishReferenceDescription())"
            )

        default:
            return
        }
    }


    private func handlePoseMissing(at pts: CMTime) {
        if lastPoseSeenPTS == nil, poseTrackingStartedPTS == nil {
            poseTrackingStartedPTS = pts
            return
        }

        let referencePTS = lastPoseSeenPTS ?? poseTrackingStartedPTS ?? pts
        let missingAge = duration(from: referencePTS, to: pts)
        guard missingAge >= Cfg.motionPoseNoPoseRetrySeconds else { return }

        if state == .climbing {
            if let range = openClipRangeLocked(reason: String(format: "pose missing for %.1fs", missingAge)) {
                emitFinish(
                    range.endPTS,
                    reason: String(format: "pose missing for %.1fs; raw close from %@", missingAge, finishReferenceDescription())
                )
            } else {
                emitRetry(reason: String(format: "pose missing for %.1fs and no finish reference", missingAge))
            }
        } else {
            returnToCVSearch(reason: String(format: "pose missing before start for %.1fs", missingAge))
        }
    }

    private func returnToCVSearch(reason: String) {
        debug("↩️ return to CV search: \(reason)")
        cvFrameHistory.removeAll(keepingCapacity: true)
        state = .waiting
        lastPosePTS = nil
        lastPoseSeenPTS = nil
        poseTrackingStartedPTS = nil
        lastLowestAboveMat = nil
        activeStartPTS = nil
        resetFinishReferenceTracking()
    }

    private func openClipRangeLocked(reason: String) -> ClipRange? {
        guard state == .climbing else { return nil }
        guard let startPTS = activeStartPTS else { return nil }

        // Keep the best raw finish-reference time. Padding is deferred to finalization.
        let finishBasePTS = bestFinishReferencePTS() ?? lastPoseSeenPTS
        guard let finishBasePTS, CMTimeCompare(finishBasePTS, startPTS) > 0 else { return nil }

        debug("📌 raw open clip range salvaged: \(reason)")
        return ClipRange(startPTS: startPTS, endPTS: finishBasePTS)
    }

    private func emitStart(_ pts: CMTime, reason: String) {
        state = .climbing
        activeStartPTS = pts
        resetFinishReferenceTracking()
        debug("🚀 raw start detected: \(reason)")
        onEvent?(.start(pts: pts))
    }

    private func emitRetry(reason: String) {
        guard state == .climbing else { return }
        debug("🔁 retry detected: \(reason)")
        onEvent?(.retry)
        cvFrameHistory.removeAll(keepingCapacity: true)
        state = .waiting
        lastPosePTS = nil
        lastPoseSeenPTS = nil
        poseTrackingStartedPTS = nil
        lastLowestAboveMat = nil
        activeStartPTS = nil
        resetFinishReferenceTracking()
    }

    private func emitFinish(_ pts: CMTime, reason: String) {
        guard state == .climbing else { return }
        if let start = activeStartPTS, CMTimeCompare(pts, start) <= 0 {
            debug("⚠️ ignored invalid finish before start")
            return
        }
        debug("🏁 raw finish detected: \(reason)")
        onEvent?(.finish(pts: pts))
        resetForNextStartSearchLocked()
    }

    private func resetForNextStartSearchLocked() {
        // finish 직후에는 CV로 돌아가지 않고, 같은 녹화 안에서 다음 등반 start를 찾기 위해 pose tracking을 유지합니다.
        state = .tracking
        lastPosePTS = nil
        lastPoseSeenPTS = nil
        poseTrackingStartedPTS = nil
        lastLowestAboveMat = nil
        activeStartPTS = nil
        resetFinishReferenceTracking()
    }

    // MARK: Pose

    private func canRunPose(at pts: CMTime, interval: TimeInterval) -> Bool {
        guard let lastPosePTS else { return true }
        return duration(from: lastPosePTS, to: pts) >= interval
    }

    private enum PoseCoordinateSpace {
        case activeCrop
        case fullFrame
    }

    private func detectPose(in pixelBuffer: CVPixelBuffer) -> PoseSnapshot {
        autoreleasepool {
            let coordinateSpace: PoseCoordinateSpace
            let handler: VNImageRequestHandler

            if let poseImage = makePoseInputCGImage(from: pixelBuffer) {
                coordinateSpace = .activeCrop
                handler = VNImageRequestHandler(cgImage: poseImage, orientation: .up, options: [:])
            } else {
                coordinateSpace = .fullFrame
                handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: .up, options: [:])
            }

            do {
                try handler.perform([poseRequest])
            } catch {
                debug("⚠️ pose failed: \(error.localizedDescription)")
                return emptyPoseSnapshot()
            }

            guard let observations = poseRequest.results, !observations.isEmpty else {
                return emptyPoseSnapshot()
            }

            return makePoseSnapshot(from: observations, coordinateSpace: coordinateSpace)
        }
    }

    private func makePoseSnapshot(
        from observations: [VNHumanBodyPoseObservation],
        coordinateSpace: PoseCoordinateSpace
    ) -> PoseSnapshot {
        var points: [CGPoint] = []

        for observation in observations {
            for joint in reliablePoseJoints {
                if let p = recognizedPoint(joint, in: observation, coordinateSpace: coordinateSpace) {
                    points.append(p)
                }
            }
        }

        return PoseSnapshot(
            observationCount: observations.count,
            allReliablePoints: uniquePoints(points)
        )
    }

    private func recognizedPoint(
        _ joint: VNHumanBodyPoseObservation.JointName,
        in observation: VNHumanBodyPoseObservation,
        coordinateSpace: PoseCoordinateSpace
    ) -> CGPoint? {
        guard let point = try? observation.recognizedPoint(joint) else { return nil }
        guard point.confidence >= Cfg.motionPoseMinConfidence else { return nil }

        let normalized = CGPoint(x: CGFloat(point.location.x), y: 1.0 - CGFloat(point.location.y))
        switch coordinateSpace {
        case .activeCrop:
            guard normalized.x >= 0, normalized.x <= 1, normalized.y >= 0, normalized.y <= 1 else { return nil }
            return normalized
        case .fullFrame:
            return pointInActiveCrop(normalized)
        }
    }

    private func pointInActiveCrop(_ point: CGPoint) -> CGPoint? {
        let crop = activeCropRect
        guard crop.width > 0, crop.height > 0, crop.contains(point) else { return nil }
        return CGPoint(
            x: (point.x - crop.minX) / crop.width,
            y: (point.y - crop.minY) / crop.height
        )
    }

    private func emptyPoseSnapshot() -> PoseSnapshot {
        PoseSnapshot(
            observationCount: 0,
            allReliablePoints: []
        )
    }

    private func makePoseInputCGImage(from pixelBuffer: CVPixelBuffer) -> CGImage? {
        let srcW = CVPixelBufferGetWidth(pixelBuffer)
        let srcH = CVPixelBufferGetHeight(pixelBuffer)
        guard srcW > 0, srcH > 0 else { return nil }

        let crop = clamped(activeCropRect)
        guard crop.width > 0.001, crop.height > 0.001 else { return nil }

        let cropX = clamp(crop.minX, min: 0, max: 1) * CGFloat(srcW)
        let cropW = clamp(crop.width, min: 0.001, max: 1) * CGFloat(srcW)
        let cropH = clamp(crop.height, min: 0.001, max: 1) * CGFloat(srcH)
        let cropTopY = clamp(crop.minY, min: 0, max: 1) * CGFloat(srcH)
        let cropY = CGFloat(srcH) - cropTopY - cropH

        let cropRect = CGRect(
            x: floor(cropX),
            y: floor(Swift.max(0, cropY)),
            width: ceil(Swift.min(CGFloat(srcW) - cropX, cropW)),
            height: ceil(Swift.min(CGFloat(srcH) - Swift.max(0, cropY), cropH))
        ).integral

        guard cropRect.width >= 32, cropRect.height >= 32 else { return nil }

        let maxLongSide = Swift.max(CGFloat(128), Cfg.motionPoseInputMaxLongSide)
        let scale = Swift.min(1.0, maxLongSide / Swift.max(cropRect.width, cropRect.height))
        let outputSize = CGSize(
            width: Swift.max(32, floor(cropRect.width * scale)),
            height: Swift.max(32, floor(cropRect.height * scale))
        )
        let outputRect = CGRect(origin: .zero, size: outputSize)

        let source = CIImage(cvPixelBuffer: pixelBuffer)
        let normalized = source
            .cropped(to: cropRect)
            .transformed(by: CGAffineTransform(translationX: -cropRect.minX, y: -cropRect.minY))
            .transformed(by: CGAffineTransform(scaleX: scale, y: scale))

        return poseCIContext.createCGImage(normalized, from: outputRect)
    }

    private func lowestReliablePoint(in snapshot: PoseSnapshot) -> CGPoint? {
        snapshot.allReliablePoints.max { lhs, rhs in
            lhs.y < rhs.y
        }
    }

    private func isAboveMatLine(_ point: CGPoint, tolerance: CGFloat) -> Bool {
        // 사용자가 그은 매트 경계선 바로 위가 아니라, CV에서 쓰는 upper band의 최상단을
        // 실질적인 통과 기준으로 사용합니다. 현재 band는 5%이며, y값은 위로 갈수록 작아지므로
        // 가장 낮은 pose 점이 이 선을 넘어야 start로 인정됩니다.
        let stricterBoundary = Swift.max(0.0, lineY(at: point.x) - Swift.max(0.02, Cfg.motionMatBandHeightRatio))
        return point.y <= stricterBoundary + tolerance
    }

    private func updateFinishReferenceMoment(snapshot: PoseSnapshot, pts: CMTime) {
        switch finishReference {
        case .closestTopHold:
            guard let distance = closestDistanceToTopHold(from: snapshot) else { return }
            if activeBestTopHoldDistance == nil || distance < (activeBestTopHoldDistance ?? .greatestFiniteMagnitude) {
                activeBestTopHoldDistance = distance
                activeBestTopHoldPTS = pts
            }

        case .highestPosePoint:
            // Vision 좌표를 화면 좌표로 정규화한 뒤 사용하므로 y가 작을수록 화면의 더 높은 위치입니다.
            // 각 프레임에서 가장 높은 관절점을 찾고, 등반 전체에서 그 y가 가장 작았던 시점을 저장합니다.
            guard let highestPoint = snapshot.allReliablePoints.min(by: { $0.y < $1.y }) else { return }
            if activeHighestPoseY == nil || highestPoint.y < (activeHighestPoseY ?? .greatestFiniteMagnitude) {
                activeHighestPoseY = highestPoint.y
                activeHighestPosePTS = pts
            }
        }
    }

    private func bestFinishReferencePTS() -> CMTime? {
        switch finishReference {
        case .closestTopHold:
            return activeBestTopHoldPTS
        case .highestPosePoint:
            return activeHighestPosePTS
        }
    }

    private func finishReferenceDescription() -> String {
        switch finishReference {
        case .closestTopHold:
            return "closest top-hold moment"
        case .highestPosePoint:
            return "highest pose-point moment"
        }
    }

    private func closestDistanceToTopHold(from snapshot: PoseSnapshot) -> CGFloat? {
        guard !snapshot.allReliablePoints.isEmpty else { return nil }
        return snapshot.allReliablePoints.map { distanceFromPoint($0, to: topBox) }.min()
    }

    private func distanceFromPoint(_ point: CGPoint, to rect: CGRect) -> CGFloat {
        let dx: CGFloat
        if point.x < rect.minX {
            dx = rect.minX - point.x
        } else if point.x > rect.maxX {
            dx = point.x - rect.maxX
        } else {
            dx = 0
        }

        let dy: CGFloat
        if point.y < rect.minY {
            dy = rect.minY - point.y
        } else if point.y > rect.maxY {
            dy = point.y - rect.maxY
        } else {
            dy = 0
        }

        return sqrt(dx * dx + dy * dy)
    }

    private func boundaryRunKey(_ run: ActiveRun) -> String {
        guard let first = run.indices.min(), let last = run.indices.max() else { return "-" }
        return "\(first)-\(last)"
    }

    private func uniquePoints(_ points: [CGPoint]) -> [CGPoint] {
        var result: [CGPoint] = []
        for point in points {
            let exists = result.contains { abs($0.x - point.x) < 0.0001 && abs($0.y - point.y) < 0.0001 }
            if !exists { result.append(point) }
        }
        return result
    }

    // MARK: Signals

    private enum BandKind { case below, upper }

    private func adjustedBandSignals(frame: SmallFrame, base: SmallFrame, bins: [BandBin], kind: BandKind, baseline: [CGFloat]) -> [CGFloat] {
        bins.enumerated().map { i, bin in
            let raw = baseDiffScore(frame: frame, base: base, bin: bin, kind: kind)
            let baseValue = i < baseline.count ? baseline[i] : 0
            return Swift.max(0, raw - baseValue - Cfg.motionBaseDiffMargin)
        }
    }

    private func activeIndices(_ values: [CGFloat]) -> [Int] {
        values.enumerated().compactMap { idx, value in
            value >= Cfg.motionBinActiveThreshold ? idx : nil
        }
    }

    private func classifyBoundaryMotion(belowValues: [CGFloat], upperValues: [CGFloat]) -> BoundaryMotion? {
        let count = Swift.min(belowValues.count, upperValues.count)
        guard count > 0 else { return nil }

        let combined = (0..<count).map { idx in
            Swift.max(belowValues[idx], upperValues[idx])
        }
        let active = combined.enumerated().compactMap { idx, value in
            value >= Cfg.motionBinActiveThreshold ? idx : nil
        }
        let runs = scoredRuns(indices: active, values: combined)
        guard !runs.isEmpty else { return nil }

        let candidates = runs.filter { $0.count >= 1 && $0.count <= Cfg.motionBoundaryPrimaryMaxBins }
        guard let primary = candidates.sorted(by: strongerRunFirst).first else { return nil }

        let others = runs.filter { $0.indices != primary.indices }.sorted(by: strongerRunFirst)
        guard let second = others.first else {
            return BoundaryMotion(run: ActiveRun(indices: primary.indices), energy: primary.energy, status: "single", secondaryEnergy: nil)
        }

        let secondIsSmallNoise = second.count <= Cfg.motionBoundaryNoiseMaxBins && second.energy <= primary.energy * Cfg.motionBoundaryNoiseEnergyRatio
        if secondIsSmallNoise {
            return BoundaryMotion(run: ActiveRun(indices: primary.indices), energy: primary.energy, status: "noiseIgnored", secondaryEnergy: second.energy)
        }

        let primaryDominates = primary.energy >= second.energy * Cfg.motionBoundaryDominanceRatio ||
            (primary.energy - second.energy) >= Cfg.motionBoundaryDominanceMargin
        if primaryDominates {
            return BoundaryMotion(run: ActiveRun(indices: primary.indices), energy: primary.energy, status: "dominantInShake", secondaryEnergy: second.energy)
        }

        // 비슷한 크기의 국소 움직임이 여러 곳에서 동시에 잡히면 전체 흔들림으로 봅니다.
        return nil
    }

    private func scoredRuns(indices: [Int], values: [CGFloat]) -> [RunScore] {
        guard !indices.isEmpty else { return [] }
        let sorted = Array(Set(indices)).sorted()
        var result: [RunScore] = []
        var current: [Int] = []
        var currentEnergy: CGFloat = 0

        func flush() {
            guard !current.isEmpty else { return }
            result.append(RunScore(indices: current, energy: currentEnergy))
            current.removeAll(keepingCapacity: true)
            currentEnergy = 0
        }

        for idx in sorted where values.indices.contains(idx) {
            if let last = current.last, idx != last + 1 {
                flush()
            }
            current.append(idx)
            currentEnergy += values[idx]
        }
        flush()
        return result
    }

    private func strongerRunFirst(_ lhs: RunScore, _ rhs: RunScore) -> Bool {
        if lhs.energy != rhs.energy { return lhs.energy > rhs.energy }
        if lhs.count != rhs.count { return lhs.count > rhs.count }
        return (lhs.indices.first ?? 0) < (rhs.indices.first ?? 0)
    }

    private func makeBaseline(samples: [CalibrationSample], bins: [BandBin], kind: BandKind) -> [CGFloat] {
        bins.map { bin in
            var values: [CGFloat] = []
            values.reserveCapacity(samples.count)
            for sample in samples {
                values.append(
                    baseDiffScore(frame: sample.frame, base: sample.referenceFrame, bin: bin, kind: kind)
                )
            }
            return median(values)
        }
    }

    // MARK: Region Geometry

    private func buildRegionsLocked() {
        belowBins = buildBandBins()
        upperBins = buildBandBins()
    }

    private func buildBandBins() -> [BandBin] {
        let count = Swift.max(1, horizontalBinCount)
        return (0..<count).map { i in
            BandBin(
                x0: CGFloat(i) / CGFloat(count),
                x1: CGFloat(i + 1) / CGFloat(count)
            )
        }
    }

    private func lineY(at x: CGFloat) -> CGFloat {
        guard !matLine.isEmpty else { return Cfg.matDefaultY }
        if matLine.count == 1 { return clampedY(matLine[0].y) }

        let pts = matLine
        if x <= pts[0].x { return clampedY(pts[0].y) }
        if x >= pts[pts.count - 1].x { return clampedY(pts[pts.count - 1].y) }

        for i in 0..<(pts.count - 1) {
            let a = pts[i]
            let b = pts[i + 1]
            if x >= a.x, x <= b.x {
                let t = (x - a.x) / Swift.max(0.0001, b.x - a.x)
                return clampedY(a.y + (b.y - a.y) * t)
            }
        }
        return clampedY(pts.last?.y ?? Cfg.matDefaultY)
    }

    private func expanded(_ rect: CGRect, by padding: CGFloat) -> CGRect {
        let r = rect.insetBy(dx: -padding, dy: -padding)
        let minX = clamp(r.minX, min: 0, max: 1)
        let minY = clamp(r.minY, min: 0, max: 1)
        let maxX = clamp(r.maxX, min: 0, max: 1)
        let maxY = clamp(r.maxY, min: 0, max: 1)
        return CGRect(x: minX, y: minY, width: Swift.max(0, maxX - minX), height: Swift.max(0, maxY - minY))
    }

    // MARK: Base Diff

    private func baseDiffScore(frame: SmallFrame, base: SmallFrame, bin: BandBin, kind: BandKind) -> CGFloat {
        guard frame.width == base.width, frame.height == base.height else { return 0 }

        let width = frame.width
        let height = frame.height
        let x0 = Swift.max(0, Swift.min(width - 1, Int(floor(bin.x0 * CGFloat(width)))))
        let x1 = Swift.max(x0 + 1, Swift.min(width, Int(ceil(bin.x1 * CGFloat(width)))))
        let bandHeight = Swift.max(0.02, Cfg.motionMatBandHeightRatio)
        let threshold = Int(Cfg.motionPixelDiff)

        var changed = 0
        var total = 0

        for x in x0..<x1 {
            let xNorm = (CGFloat(x) + 0.5) / CGFloat(width)
            let boundary = lineY(at: xNorm)
            let yStartNorm: CGFloat
            let yEndNorm: CGFloat

            switch kind {
            case .below:
                yStartNorm = boundary
                yEndNorm = Swift.min(1.0, boundary + bandHeight)
            case .upper:
                yStartNorm = Swift.max(0.0, boundary - bandHeight)
                yEndNorm = boundary
            }

            guard yEndNorm > yStartNorm else { continue }

            let y0 = Swift.max(0, Swift.min(height - 1, Int(floor(yStartNorm * CGFloat(height)))))
            let y1 = Swift.max(y0 + 1, Swift.min(height, Int(ceil(yEndNorm * CGFloat(height)))))

            for y in y0..<y1 {
                let idx = y * width + x
                if abs(Int(frame.luma[idx]) - Int(base.luma[idx])) >= threshold { changed += 1 }
                total += 1
            }
        }

        return total > 0 ? CGFloat(changed) / CGFloat(total) : 0
    }

    // MARK: Frame Conversion

    private func makeSmallFrame(from sampleBuffer: CMSampleBuffer) -> SmallFrame? {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return nil }
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        let plane = 0
        let srcW = CVPixelBufferGetWidthOfPlane(pixelBuffer, plane)
        let srcH = CVPixelBufferGetHeightOfPlane(pixelBuffer, plane)
        let row = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, plane)
        guard srcW > 0, srcH > 0, let base = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, plane) else { return nil }

        let ptr = base.assumingMemoryBound(to: UInt8.self)
        let crop = activeCropRect
        let cropX = Swift.max(0, Swift.min(srcW - 1, Int(floor(crop.minX * CGFloat(srcW)))))
        let cropY = Swift.max(0, Swift.min(srcH - 1, Int(floor(crop.minY * CGFloat(srcH)))))
        let cropW = Swift.max(1, Swift.min(srcW - cropX, Int(ceil(crop.width * CGFloat(srcW)))))
        let cropH = Swift.max(1, Swift.min(srcH - cropY, Int(ceil(crop.height * CGFloat(srcH)))))

        let dstW = Swift.max(Cfg.motionFrameWidth, 32)
        let dstH = Swift.max(Int(round(CGFloat(dstW) * CGFloat(cropH) / CGFloat(cropW))), 32)
        var out = [UInt8](repeating: 0, count: dstW * dstH)

        for y in 0..<dstH {
            let sy = Swift.min(srcH - 1, cropY + Int((CGFloat(y) + 0.5) * CGFloat(cropH) / CGFloat(dstH)))
            let srcRow = ptr + sy * row
            for x in 0..<dstW {
                let sx = Swift.min(srcW - 1, cropX + Int((CGFloat(x) + 0.5) * CGFloat(cropW) / CGFloat(dstW)))
                out[y * dstW + x] = srcRow[sx]
            }
        }
        return SmallFrame(width: dstW, height: dstH, luma: out)
    }

    private static func makeSmallFrame(from image: UIImage) -> SmallFrame? {
        guard let cg = image.cgImage else { return nil }
        let srcW = cg.width
        let srcH = cg.height
        let dstW = Swift.max(Cfg.motionFrameWidth, 32)
        let dstH = Swift.max(Int(round(CGFloat(dstW) * CGFloat(srcH) / CGFloat(srcW))), 32)

        var rgba = [UInt8](repeating: 0, count: dstW * dstH * 4)
        guard let ctx = CGContext(
            data: &rgba,
            width: dstW,
            height: dstH,
            bitsPerComponent: 8,
            bytesPerRow: dstW * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }

        ctx.interpolationQuality = .low
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: CGFloat(dstW), height: CGFloat(dstH)))

        var luma = [UInt8](repeating: 0, count: dstW * dstH)
        for i in 0..<(dstW * dstH) {
            let r = CGFloat(rgba[i * 4])
            let g = CGFloat(rgba[i * 4 + 1])
            let b = CGFloat(rgba[i * 4 + 2])
            let y = Int(round(r * 0.2126 + g * 0.7152 + b * 0.0722))
            luma[i] = UInt8(Swift.max(0, Swift.min(255, y)))
        }
        return SmallFrame(width: dstW, height: dstH, luma: luma)
    }

    // MARK: Helpers

    private func resetFinishReferenceTracking() {
        activeBestTopHoldPTS = nil
        activeBestTopHoldDistance = nil
        activeHighestPosePTS = nil
        activeHighestPoseY = nil
    }

    private func resetStateLocked(keepConfig: Bool) {
        isActive = false
        state = .idle
        lastAnalyzedPTS = nil
        motionAnalysisBoostUntilPTS = nil
        calibrationStartedPTS = nil
        calibrationSamples.removeAll()
        calibration = nil
        cvFrameHistory.removeAll(keepingCapacity: true)
        lastPosePTS = nil
        lastPoseSeenPTS = nil
        poseTrackingStartedPTS = nil
        lastLowestAboveMat = nil
        activeStartPTS = nil
        resetFinishReferenceTracking()

        if !keepConfig {
            matLine.removeAll()
            topBox = .zero
            finishReference = .closestTopHold
            activeCropRect = CGRect(x: 0, y: 0, width: 1, height: 1)
            horizontalBinCount = Cfg.motionBandBinCount
            belowBins.removeAll()
            upperBins.removeAll()
        }
    }

    private func rollingCVReference(for frame: SmallFrame) -> SmallFrame? {
        let lag = Swift.max(1, Cfg.motionCVComparisonFrameLag)

        if let previous = cvFrameHistory.last,
           (previous.width != frame.width || previous.height != frame.height) {
            cvFrameHistory.removeAll(keepingCapacity: true)
        }

        guard cvFrameHistory.count >= lag else {
            cvFrameHistory.append(frame)
            return nil
        }

        let reference = cvFrameHistory.removeFirst()
        cvFrameHistory.append(frame)
        return reference
    }

    private func duration(from start: CMTime?, to end: CMTime) -> TimeInterval {
        guard let start else { return 0 }
        return Swift.max(0, CMTimeGetSeconds(end - start))
    }

    private func median(_ values: [CGFloat]) -> CGFloat {
        guard !values.isEmpty else { return 0 }
        let sorted = values.sorted()
        let mid = sorted.count / 2
        if sorted.count % 2 == 0 { return (sorted[mid - 1] + sorted[mid]) * 0.5 }
        return sorted[mid]
    }

    private func clamped(_ rect: CGRect) -> CGRect { expanded(rect, by: 0) }
    private func clampedY(_ y: CGFloat) -> CGFloat { clamp(y, min: 0, max: 1) }
    private func clamp(_ v: CGFloat, min lo: CGFloat, max hi: CGFloat) -> CGFloat {
        Swift.max(lo, Swift.min(v, hi))
    }

    private func formatIndices(_ values: [Int]) -> String {
        values.isEmpty ? "-" : values.map(String.init).joined(separator: ",")
    }

    private func shortValues(_ values: [CGFloat]) -> String {
        "[" + values.map { String(format: "%.2f", Double($0)) }.joined(separator: ",") + "]"
    }

    private func debug(_ text: @autoclosure () -> String) {
        if Cfg.motionDebugLog { print(text()) }
    }
}
