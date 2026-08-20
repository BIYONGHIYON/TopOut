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
/// - 별도 환경 보정 없이 녹화 시작 후 고정 2fps로 현재 프레임과 직전 분석 프레임을 비교합니다.
/// - CV 탐색 영역은 매트 경계선 바로 위쪽부터 화면 높이의 40% 구간입니다.
/// - 초기 CV 변화를 8열 격자로 보존하고 여러 Vision observation 중 가장 가까운 한 명을 선택합니다.
/// - 선택 후에는 직전 관절 경계와 중심의 연속성으로 같은 등반자를 추적합니다.
/// - 초기 탐색과 등반 중 pose 추적은 2fps를 유지합니다.
/// - pose를 잡았지만 아직 등반 전인 구간과 pose 유실 복구 구간은 4fps로 분석합니다.
/// - 가장 낮은 pose 점이 매트 기준선 위로 올라가면 START, 다시 아래로 내려오면 FINISH로 봅니다.
/// - 탑홀드 마스킹 사용 시 FINISH는 탑홀드와 가장 가까웠던 pose 시점을 사용합니다.
/// - 탑홀드 마스킹 미사용 시에는 신체의 가장 높은 점이 화면상 가장 높았던 시점을 사용합니다.
/// - 녹화 중에는 원본 START/FINISH 시각만 저장하고, 정리 완료 후 3초 여백을 더합니다.
/// - 정상 종료 후에는 pose가 보이는 동안 다음 START를 제한 없이 찾고, 2프레임 연속 미검출 시 CV 탐색으로 복귀합니다.
final class MotionDetector {
    private enum State: String {
        case idle
        case waiting
        case tracking
        case climbing
    }

    private struct SmallFrame {
        let width: Int
        let height: Int
        let luma: [UInt8]
    }

    private struct MotionCellScore {
        let bounds: CGRect
        let score: CGFloat
    }

    private struct MotionTarget {
        let weightedCenter: CGPoint
        let cells: [MotionCellScore]
    }

    private struct CVRegionMotion {
        let rawSignal: CGFloat
        let adjustedSignal: CGFloat
        let target: MotionTarget
    }

    private struct PoseCandidate {
        let observationIndex: Int
        let reliablePoints: [CGPoint]
        let bounds: CGRect
        let center: CGPoint
        let startEligible: Bool
    }

    private struct PoseSnapshot {
        let observationCount: Int
        let selectedObservationIndex: Int?
        let reliablePoints: [CGPoint]
        let startEligible: Bool

        var hasPose: Bool { selectedObservationIndex != nil && !reliablePoints.isEmpty }
    }

    var onEvent: ((MotionEvent) -> Void)?
    private let q = DispatchQueue(label: "topout.motion.detector.pose.v40", qos: .userInitiated)

    // 카메라 프레임(최대 60fps)이 포즈 분석 직렬 큐에 계속 쌓이지 않도록
    // 처리 대기 중에는 가장 최신 프레임 한 장만 유지합니다.
    private let pendingFrameLock = NSLock()
    private var latestPendingSampleBuffer: CMSampleBuffer?
    private var isPendingFrameTaskScheduled = false
    private var acceptsFrameUpdates = false
    private var frameUpdateGeneration: UInt64 = 0

    private let poseRequest = VNDetectHumanBodyPoseRequest()
    private let poseCIContext = CIContext(options: [.useSoftwareRenderer: false])
    // 시작/종료/완등 판단에는 얼굴 관절을 사용하지 않습니다.
    private let reliablePoseJoints: [VNHumanBodyPoseObservation.JointName] = [
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
    private var previousCVFrame: SmallFrame?

    private var isActive = false
    private var state: State = .idle

    private var lastAnalyzedPTS: CMTime?

    private var lastPosePTS: CMTime?
    private var lastPoseSeenPTS: CMTime?
    private var poseTrackingStartedPTS: CMTime?
    private var lastLowestAboveMat: Bool?
    private var activeStartPTS: CMTime?
    private var activeBestTopHoldPTS: CMTime?
    private var activeBestTopHoldDistance: CGFloat?
    private var activeHighestPosePTS: CMTime?
    private var activeHighestPoseY: CGFloat?
    private var isPostClimbTracking = false
    private var postClimbConsecutivePoseMisses = 0
    private var hasSeenReliablePose = false
    private var isPoseLossRecovery = false
    private var pendingMotionTarget: MotionTarget?
    private var lastSelectedPoseCenter: CGPoint?
    private var lastSelectedPoseBounds: CGRect?

    // MARK: Public

    func configure(
        matLine: [CGPoint],
        topBox: CGRect?,
        finishReference: MotionFinishReference,
        activeCropRect: CGRect
    ) {
        let generation = invalidateFrameUpdatesAndDiscardPending()
        q.async { [weak self] in
            guard let self else { return }
            self.matLine = matLine.sorted { $0.x < $1.x }
            self.topBox = topBox.map { self.clamped($0) } ?? .zero
            self.finishReference = finishReference
            self.activeCropRect = self.clamped(activeCropRect)
            self.resetStateLocked(keepConfig: true)
            self.setFrameUpdatesEnabled(false, for: generation)
            self.debug("🎯 motion configured pose climb: matPoints=\(matLine.count), finishReference=\(finishReference.rawValue), topBox=\(self.topBox), crop=\(self.activeCropRect), cvRegionAboveMat=\(Cfg.motionCVRegionAboveMatHeightRatio), analysisFPS=\(Cfg.motionAnalysisFPS)")
        }
    }

    /// 화면의 녹화 카운트다운이 시작될 때 이전 탐지 상태만 초기화합니다.
    func resetForRecording() {
        let generation = invalidateFrameUpdatesAndDiscardPending()
        q.async { [weak self] in
            guard let self else { return }
            self.resetStateLocked(keepConfig: true)
            self.setFrameUpdatesEnabled(false, for: generation)
            self.debug("👀 motion detector armed for recording")
        }
    }

    /// 실제 파일 녹화가 시작되는 순간 환경 보정 없이 모션 탐지를 시작합니다.
    func markRecordingStarted() {
        let generation = invalidateFrameUpdatesAndDiscardPending()
        q.async { [weak self] in
            guard let self else { return }
            self.resetStateLocked(keepConfig: true)
            self.isActive = true
            self.state = .waiting
            self.setFrameUpdatesEnabled(true, for: generation)
            self.debug("👀 motion detector started without baseline calibration")
        }
    }

    func activeClipRangeForRecordingStop() -> ClipRange? {
        q.sync { [weak self] in
            guard let self else { return nil }
            return self.openClipRangeLocked(reason: "recording stop")
        }
    }

    func stop() {
        let generation = invalidateFrameUpdatesAndDiscardPending()
        q.async { [weak self] in
            guard let self else { return }
            self.isActive = false
            self.resetStateLocked(keepConfig: true)
            self.setFrameUpdatesEnabled(false, for: generation)
            self.debug("🛑 motion detector stopped")
        }
    }

    func update(_ sampleBuffer: CMSampleBuffer) {
        pendingFrameLock.lock()

        guard acceptsFrameUpdates else {
            pendingFrameLock.unlock()
            return
        }

        latestPendingSampleBuffer = sampleBuffer

        guard !isPendingFrameTaskScheduled else {
            pendingFrameLock.unlock()
            return
        }

        isPendingFrameTaskScheduled = true
        pendingFrameLock.unlock()

        q.async { [weak self] in
            self?.processLatestPendingFrame()
        }
    }

    /// Closes admission immediately and gives the queued state mutation an ordering token.
    /// An older queued `markRecordingStarted` must never reopen the gate after a newer stop/reset.
    @discardableResult
    private func invalidateFrameUpdatesAndDiscardPending() -> UInt64 {
        pendingFrameLock.lock()
        frameUpdateGeneration &+= 1
        acceptsFrameUpdates = false
        latestPendingSampleBuffer = nil
        let generation = frameUpdateGeneration
        pendingFrameLock.unlock()
        return generation
    }

    private func setFrameUpdatesEnabled(_ enabled: Bool, for generation: UInt64) {
        pendingFrameLock.lock()
        guard generation == frameUpdateGeneration else {
            pendingFrameLock.unlock()
            return
        }
        acceptsFrameUpdates = enabled
        if !enabled {
            latestPendingSampleBuffer = nil
        }
        pendingFrameLock.unlock()
    }

    // MARK: Processing

    /// 한 번에 프레임 하나만 분석 큐로 넘깁니다.
    /// 분석 중 새 프레임이 들어오면 이전 대기 프레임을 교체하고 최신 프레임만 다음 작업으로 처리합니다.
    /// 다음 작업을 별도로 다시 enqueue하여 configure/stop 같은 상태 변경 작업이 프레임 처리 사이에 실행될 수 있게 합니다.
    private func processLatestPendingFrame() {
        pendingFrameLock.lock()
        let sampleBuffer = latestPendingSampleBuffer
        latestPendingSampleBuffer = nil
        pendingFrameLock.unlock()

        if let sampleBuffer {
            autoreleasepool {
                process(sampleBuffer)
            }
        }

        pendingFrameLock.lock()
        if latestPendingSampleBuffer != nil {
            pendingFrameLock.unlock()
            q.async { [weak self] in
                self?.processLatestPendingFrame()
            }
        } else {
            isPendingFrameTaskScheduled = false
            pendingFrameLock.unlock()
        }
    }

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

        let analysisInterval = currentAnalysisInterval()
        if let last = lastAnalyzedPTS {
            let dt = CMTimeGetSeconds(pts - last)
            if dt >= 0, dt < analysisInterval { return }
        }
        lastAnalyzedPTS = pts

        guard let frame = makeSmallFrame(from: sampleBuffer) else { return }
        let comparisonFrame = previousCVFrame
        previousCVFrame = frame

        guard state == .waiting else { return }
        guard let base = comparisonFrame else { return }
        guard base.width == frame.width, base.height == frame.height else { return }

        let cvMotion = cvSearchRegionMotion(frame: frame, base: base)

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

        handleWaitingCVRegion(cvMotion, pts: pts, pose: runPose)

        if Cfg.motionDebugLog, (cvMotion != nil || latestPose != nil) {
            debugState(
                pts: pts,
                cvMotion: cvMotion,
                latestPose: latestPose,
                poseReason: poseReason
            )
        }
    }

    private func processPoseTrackingFrame(pixelBuffer: CVPixelBuffer, pts: CMTime) {
        guard state == .tracking || state == .climbing else { return }
        let interval = currentPoseTrackingInterval()
        guard canRunPose(at: pts, interval: interval) else { return }

        lastPosePTS = pts
        let snapshot = detectPose(in: pixelBuffer)
        handlePoseTrackingSnapshot(snapshot, pts: pts)

        if Cfg.motionDebugLog {
            debugState(
                pts: pts,
                cvMotion: nil,
                latestPose: snapshot,
                poseReason: state == .climbing ? "climbingPose" : "trackingPose"
            )
        }
    }

    private func debugState(
        pts: CMTime,
        cvMotion: CVRegionMotion?,
        latestPose: PoseSnapshot?,
        poseReason: String
    ) {
        let poseText: String
        if let latestPose {
            let selected = latestPose.selectedObservationIndex.map(String.init) ?? "-"
            poseText = "obs=\(latestPose.observationCount) selected=\(selected) points=\(latestPose.reliablePoints.count)"
        } else {
            poseText = "-"
        }

        let cvText: String
        if let cvMotion {
            cvText = String(
                format: "raw=%.3f adjusted=%.3f threshold=%.3f cells=%d center=(%.3f,%.3f)",
                Double(cvMotion.rawSignal),
                Double(cvMotion.adjustedSignal),
                Double(Cfg.motionCVRegionActiveThreshold),
                cvMotion.target.cells.count,
                Double(cvMotion.target.weightedCenter.x),
                Double(cvMotion.target.weightedCenter.y)
            )
        } else {
            cvText = "-"
        }

        let noPoseAge: String
        if let lastPoseSeenPTS {
            noPoseAge = String(format: "%.1f", duration(from: lastPoseSeenPTS, to: pts))
        } else {
            noPoseAge = "-"
        }

        debug("📈 cv region=\(cvText) | pose=\(poseText) reason=\(poseReason) noPoseAge=\(noPoseAge) state=\(state.rawValue)")
    }

    private func currentAnalysisInterval() -> TimeInterval {
        1.0 / Swift.max(1.0, Cfg.motionAnalysisFPS)
    }

    /// Pre-start tracking and pose-loss recovery run at 4fps. Waiting CV search and stable climbing
    /// remain at 2fps.
    private func currentPoseTrackingInterval() -> TimeInterval {
        let preStartPoseTracking = state == .tracking && hasSeenReliablePose
        if preStartPoseTracking || isPoseLossRecovery {
            return Cfg.motionPoseFastTrackingInterval
        }
        return Cfg.motionPoseTrackingInterval
    }

    // MARK: State Machine

    private func handleWaitingCVRegion(
        _ motion: CVRegionMotion?,
        pts: CMTime,
        pose: (_ reason: String) -> PoseSnapshot?
    ) {
        guard let motion else { return }

        enterPoseTrackingFromCV(
            at: pts,
            motionTarget: motion.target,
            reason: String(format: "CV region changed adjusted=%.3f", Double(motion.adjustedSignal))
        )
        guard let snapshot = pose("cvMotionPose") else { return }
        handlePoseTrackingSnapshot(snapshot, pts: pts)
    }

    private func enterPoseTrackingFromCV(at pts: CMTime, motionTarget: MotionTarget, reason: String) {
        previousCVFrame = nil
        state = .tracking
        poseTrackingStartedPTS = pts
        lastPoseSeenPTS = nil
        lastLowestAboveMat = nil
        activeStartPTS = nil
        isPostClimbTracking = false
        postClimbConsecutivePoseMisses = 0
        hasSeenReliablePose = false
        isPoseLossRecovery = false
        resetPoseSelectionTracking()
        pendingMotionTarget = motionTarget
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
        hasSeenReliablePose = true
        isPoseLossRecovery = false
        postClimbConsecutivePoseMisses = 0
        let lowestAboveStartBoundary = isAboveStartBoundary(lowestPoint)

        switch state {
        case .tracking:
            // A missing or side-edge torso must not become a new climb just because its remaining
            // joints all sit above the start line. A central torso keeps the existing start
            // behavior even when only a limb touches the side edge.
            guard snapshot.startEligible else { return }
            // CV 이후 첫 pose에서 이미 가장 낮은 점이 선택 영상 높이의 6% 시작선 위라면 즉시 start로 잡습니다.
            // 아니면 시작 경계의 아래→위 crossing 순간을 start로 잡습니다.
            let firstValidPose = lastLowestAboveMat == nil
            let crossedUp = lowestAboveStartBoundary && (lastLowestAboveMat != true)
            lastLowestAboveMat = lowestAboveStartBoundary
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
            lastLowestAboveMat = lowestAboveStartBoundary

            // 종료선은 실제 매트 경계보다 선택 영상 높이의 4% 위에 둡니다.
            // 가장 낮은 pose 점이 이 종료선 아래로 내려온 순간 등반 구간을 닫습니다.
            guard isBelowFinishBoundary(lowestPoint) else { return }

            // 완등 후보가 START와 같은 프레임에만 잡힌 경우에는 현재 매트 하강 시각을 사용합니다.
            // 0.1초를 인위적으로 더하지 않으면서도 FINISH가 무시된 채 CLIMBING에 고착되는 것을 막습니다.
            let finishBasePTS = validFinishReference(after: activeStartPTS, fallback: pts) ?? pts
            emitFinish(
                finishBasePTS,
                reason: "lowest pose point crossed below finish boundary; raw end from \(finishReferenceDescription())",
                normalFinish: true
            )

        default:
            return
        }
    }


    private func handlePoseMissing(at pts: CMTime) {
        isPoseLossRecovery = hasSeenReliablePose

        if state == .tracking, isPostClimbTracking {
            postClimbConsecutivePoseMisses += 1
            if postClimbConsecutivePoseMisses >= 2 {
                returnToCVSearch(reason: "two consecutive pose misses after normal finish")
            }
            return
        }

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
        previousCVFrame = nil
        lastAnalyzedPTS = nil
        state = .waiting
        lastPosePTS = nil
        lastPoseSeenPTS = nil
        poseTrackingStartedPTS = nil
        lastLowestAboveMat = nil
        activeStartPTS = nil
        isPostClimbTracking = false
        postClimbConsecutivePoseMisses = 0
        hasSeenReliablePose = false
        isPoseLossRecovery = false
        resetPoseSelectionTracking()
        resetFinishReferenceTracking()
    }

    private func openClipRangeLocked(reason: String) -> ClipRange? {
        guard state == .climbing else { return nil }
        guard let startPTS = activeStartPTS else { return nil }

        // 완등 후보가 START 시각과 같거나 더 이르면 마지막 유효 pose 시각으로 대체합니다.
        // 별도의 +0.1초 보정은 사용하지 않습니다.
        guard let finishBasePTS = validFinishReference(after: startPTS, fallback: lastPoseSeenPTS) else {
            return nil
        }

        debug("📌 raw open clip range salvaged: \(reason)")
        return ClipRange(startPTS: startPTS, endPTS: finishBasePTS)
    }

    private func emitStart(_ pts: CMTime, reason: String) {
        state = .climbing
        isPostClimbTracking = false
        postClimbConsecutivePoseMisses = 0
        isPoseLossRecovery = false
        activeStartPTS = pts
        resetFinishReferenceTracking()
        debug("🚀 raw start detected: \(reason)")
        onEvent?(.start(pts: pts))
    }

    private func emitRetry(reason: String) {
        guard state == .climbing else { return }
        debug("🔁 retry detected: \(reason)")
        onEvent?(.retry)
        previousCVFrame = nil
        lastAnalyzedPTS = nil
        state = .waiting
        lastPosePTS = nil
        lastPoseSeenPTS = nil
        poseTrackingStartedPTS = nil
        lastLowestAboveMat = nil
        activeStartPTS = nil
        isPostClimbTracking = false
        postClimbConsecutivePoseMisses = 0
        hasSeenReliablePose = false
        isPoseLossRecovery = false
        resetPoseSelectionTracking()
        resetFinishReferenceTracking()
    }

    private func emitFinish(_ pts: CMTime, reason: String, normalFinish: Bool = false) {
        guard state == .climbing else { return }
        if let start = activeStartPTS, CMTimeCompare(pts, start) <= 0 {
            debug("⚠️ ignored invalid finish before start")
            return
        }
        debug("🏁 raw finish detected: \(reason)")
        onEvent?(.finish(pts: pts))
        resetForNextStartSearchLocked(normalFinish: normalFinish)
    }

    private func resetForNextStartSearchLocked(normalFinish: Bool) {
        // finish 직후에는 CV로 돌아가지 않고, 같은 녹화 안에서 다음 등반 start를 찾기 위해 pose tracking을 유지합니다.
        state = .tracking
        lastPosePTS = nil
        lastPoseSeenPTS = nil
        poseTrackingStartedPTS = nil
        lastLowestAboveMat = nil
        activeStartPTS = nil
        isPostClimbTracking = normalFinish
        postClimbConsecutivePoseMisses = 0
        isPoseLossRecovery = false
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
        var candidates: [PoseCandidate] = []
        candidates.reserveCapacity(observations.count)
        let sideMargin = clamp(Cfg.motionPoseStartTorsoSideEdgeMarginRatio, min: 0, max: 0.25)

        // Vision 추론은 한 번만 수행합니다. 각 observation의 관절은 섞지 않고, 초기 움직임
        // 격자 또는 직전 선택 대상과 가장 잘 이어지는 한 사람만 상태 판단에 사용합니다.
        for (index, observation) in observations.enumerated() {
            var points: [CGPoint] = []
            var shoulderPointCount = 0
            var hipPointCount = 0
            var torsoTouchesSideEdge = false
            var lowerBodyPointCount = 0
            var anklePointCount = 0
            points.reserveCapacity(reliablePoseJoints.count)
            for joint in reliablePoseJoints {
                if let point = recognizedPoint(joint, in: observation, coordinateSpace: coordinateSpace) {
                    if !points.contains(where: {
                        abs($0.x - point.x) < 0.0001 && abs($0.y - point.y) < 0.0001
                    }) {
                        points.append(point)
                    }
                    switch joint {
                    case .leftShoulder, .rightShoulder:
                        shoulderPointCount += 1
                        torsoTouchesSideEdge = torsoTouchesSideEdge ||
                            point.x <= sideMargin || point.x >= 1 - sideMargin
                    case .leftHip, .rightHip:
                        hipPointCount += 1
                        torsoTouchesSideEdge = torsoTouchesSideEdge ||
                            point.x <= sideMargin || point.x >= 1 - sideMargin
                    case .leftKnee, .rightKnee:
                        lowerBodyPointCount += 1
                    case .leftAnkle, .rightAnkle:
                        lowerBodyPointCount += 1
                        anklePointCount += 1
                    default:
                        break
                    }
                }
            }
            guard let bounds = pointBounds(points) else { continue }
            let hasShoulderAndHip = shoulderPointCount > 0 && hipPointCount > 0
            let needsLowerBodyGuard = !hasShoulderAndHip || torsoTouchesSideEdge
            let hasRequiredLowerBody =
                lowerBodyPointCount >= Cfg.motionPoseStartMinimumLowerBodyPointsWhenGuarded &&
                anklePointCount >= Cfg.motionPoseStartMinimumAnklePointsWhenGuarded
            let startEligible = !needsLowerBodyGuard || hasRequiredLowerBody
            candidates.append(
                PoseCandidate(
                    observationIndex: index,
                    reliablePoints: points,
                    bounds: bounds,
                    center: CGPoint(x: bounds.midX, y: bounds.midY),
                    startEligible: startEligible
                )
            )
        }

        guard let selected = selectPoseCandidate(candidates) else {
            return PoseSnapshot(
                observationCount: observations.count,
                selectedObservationIndex: nil,
                reliablePoints: [],
                startEligible: false
            )
        }
        lastSelectedPoseCenter = selected.center
        lastSelectedPoseBounds = selected.bounds
        pendingMotionTarget = nil

        return PoseSnapshot(
            observationCount: observations.count,
            selectedObservationIndex: selected.observationIndex,
            reliablePoints: selected.reliablePoints,
            startEligible: selected.startEligible
        )
    }

    private func selectPoseCandidate(_ candidates: [PoseCandidate]) -> PoseCandidate? {
        guard var best = candidates.first else { return nil }

        if let target = pendingMotionTarget {
            var bestMetrics = motionMatchMetrics(candidate: best, target: target)
            for candidate in candidates.dropFirst() {
                let metrics = motionMatchMetrics(candidate: candidate, target: target)
                if metrics.overlap > bestMetrics.overlap + 0.000_001 ||
                    (abs(metrics.overlap - bestMetrics.overlap) <= 0.000_001 &&
                        (metrics.distance < bestMetrics.distance - 0.000_001 ||
                            (abs(metrics.distance - bestMetrics.distance) <= 0.000_001 &&
                                candidate.reliablePoints.count > best.reliablePoints.count))) {
                    best = candidate
                    bestMetrics = metrics
                }
            }
            return best
        }

        if let previousCenter = lastSelectedPoseCenter {
            var bestDistance = squaredDistance(best.center, previousCenter)
            var bestOverlap = temporalOverlap(candidate: best)
            for candidate in candidates.dropFirst() {
                let distance = squaredDistance(candidate.center, previousCenter)
                let overlap = temporalOverlap(candidate: candidate)
                if overlap > bestOverlap + 0.000_001 ||
                    (abs(overlap - bestOverlap) <= 0.000_001 &&
                        (distance < bestDistance - 0.000_001 ||
                            (abs(distance - bestDistance) <= 0.000_001 &&
                                candidate.reliablePoints.count > best.reliablePoints.count))) {
                    best = candidate
                    bestDistance = distance
                    bestOverlap = overlap
                }
            }
            let maximumDistance = Cfg.motionPoseSelectionMaxCenterDistanceRatio
            // A distant bystander must not silently replace a lost climber. Returning no pose here
            // lets the existing two-miss post-finish or six-second active-climb recovery policy
            // return to Motion search before choosing a new person.
            guard bestOverlap > 0 || bestDistance <= maximumDistance * maximumDistance else {
                return nil
            }
            return best
        }

        return candidates.max {
            if $0.reliablePoints.count == $1.reliablePoints.count {
                return $0.observationIndex > $1.observationIndex
            }
            return $0.reliablePoints.count < $1.reliablePoints.count
        }
    }

    private func motionMatchMetrics(
        candidate: PoseCandidate,
        target: MotionTarget
    ) -> (overlap: CGFloat, distance: CGFloat) {
        let candidateBounds = expanded(
            candidate.bounds,
            by: Cfg.motionPoseSelectionBoundsPaddingRatio
        )
        var overlap: CGFloat = 0
        for cell in target.cells {
            let intersection = candidateBounds.intersection(cell.bounds)
            guard !intersection.isNull, intersection.width > 0, intersection.height > 0 else { continue }
            let cellArea = Swift.max(cell.bounds.width * cell.bounds.height, 0.000_001)
            overlap += cell.score * (intersection.width * intersection.height / cellArea)
        }
        return (
            overlap: overlap,
            distance: squaredDistance(from: target.weightedCenter, to: candidateBounds)
        )
    }

    private func temporalOverlap(candidate: PoseCandidate) -> CGFloat {
        guard let previousBounds = lastSelectedPoseBounds else { return 0 }
        let previous = expanded(
            previousBounds,
            by: Cfg.motionPoseSelectionBoundsPaddingRatio
        )
        let current = expanded(
            candidate.bounds,
            by: Cfg.motionPoseSelectionBoundsPaddingRatio
        )
        let intersection = previous.intersection(current)
        guard !intersection.isNull, intersection.width > 0, intersection.height > 0 else { return 0 }
        let intersectionArea = intersection.width * intersection.height
        let unionArea = previous.width * previous.height + current.width * current.height - intersectionArea
        return intersectionArea / Swift.max(unionArea, 0.000_001)
    }

    private func pointBounds(_ points: [CGPoint]) -> CGRect? {
        guard let first = points.first else { return nil }
        var minX = first.x
        var minY = first.y
        var maxX = first.x
        var maxY = first.y
        for point in points.dropFirst() {
            minX = Swift.min(minX, point.x)
            minY = Swift.min(minY, point.y)
            maxX = Swift.max(maxX, point.x)
            maxY = Swift.max(maxY, point.y)
        }
        return CGRect(
            x: minX,
            y: minY,
            width: Swift.max(0.000_001, maxX - minX),
            height: Swift.max(0.000_001, maxY - minY)
        )
    }

    private func squaredDistance(_ lhs: CGPoint, _ rhs: CGPoint) -> CGFloat {
        let dx = lhs.x - rhs.x
        let dy = lhs.y - rhs.y
        return dx * dx + dy * dy
    }

    private func squaredDistance(from point: CGPoint, to rect: CGRect) -> CGFloat {
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
        return dx * dx + dy * dy
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
            selectedObservationIndex: nil,
            reliablePoints: [],
            startEligible: false
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
        snapshot.reliablePoints.max { lhs, rhs in
            lhs.y < rhs.y
        }
    }

    private func isAboveStartBoundary(_ point: CGPoint) -> Bool {
        // Both values are normalized inside activeCropRect, the selected output video's crop.
        let startBoundary = Swift.max(
            0.0,
            lineY(at: point.x) - Cfg.motionPoseStartAboveMatVideoHeightRatio
        )
        return point.y <= startBoundary
    }

    private func isBelowFinishBoundary(_ point: CGPoint) -> Bool {
        let finishBoundary = Swift.max(
            0.0,
            lineY(at: point.x) - Cfg.motionPoseFinishAboveMatVideoHeightRatio
        )
        return point.y > finishBoundary
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
            guard let highestPoint = snapshot.reliablePoints.min(by: { $0.y < $1.y }) else { return }
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

    private func validFinishReference(after startPTS: CMTime?, fallback: CMTime?) -> CMTime? {
        guard let startPTS else { return fallback }

        if let best = bestFinishReferencePTS(), CMTimeCompare(best, startPTS) > 0 {
            return best
        }
        if let fallback, CMTimeCompare(fallback, startPTS) > 0 {
            return fallback
        }
        return nil
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
        guard !snapshot.reliablePoints.isEmpty else { return nil }
        var closest = CGFloat.greatestFiniteMagnitude
        for point in snapshot.reliablePoints {
            closest = Swift.min(closest, distanceFromPoint(point, to: topBox))
        }
        return closest
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


    // MARK: CV Search Region

    /// 매트 경계선 바로 위쪽에서 화면 높이의 40%만큼을 전 너비로 비교합니다.
    /// 현재 프레임과 직전 2fps 분석 프레임의 변화량을 반환합니다.
    private func cvSearchRegionMotion(frame: SmallFrame, base: SmallFrame) -> CVRegionMotion? {
        guard frame.width == base.width, frame.height == base.height else { return nil }

        let width = frame.width
        let height = frame.height
        let regionHeight = clamp(Cfg.motionCVRegionAboveMatHeightRatio, min: 0.02, max: 1.0)
        let threshold = Int(Cfg.motionPixelDiff)
        let columns = Swift.max(2, Swift.min(width, Cfg.motionSeedGridColumns))
        let rows = Swift.max(
            2,
            Swift.min(
                height,
                Int((CGFloat(columns) * CGFloat(height) / CGFloat(width)).rounded())
            )
        )
        var cellChanged = [Int](repeating: 0, count: columns * rows)
        var cellTotal = [Int](repeating: 0, count: columns * rows)

        var changed = 0
        var total = 0

        for x in 0..<width {
            let cellX = Swift.min(columns - 1, x * columns / width)
            let xNorm = (CGFloat(x) + 0.5) / CGFloat(width)
            let boundary = lineY(at: xNorm)
            let yStartNorm = Swift.max(0.0, boundary - regionHeight)
            let yEndNorm = boundary
            guard yEndNorm > yStartNorm else { continue }

            let y0 = Swift.max(0, Swift.min(height - 1, Int(floor(yStartNorm * CGFloat(height)))))
            let y1 = Swift.max(y0 + 1, Swift.min(height, Int(ceil(yEndNorm * CGFloat(height)))))

            for y in y0..<y1 {
                let idx = y * width + x
                let cellY = Swift.min(rows - 1, y * rows / height)
                let cellIndex = cellY * columns + cellX
                if abs(Int(frame.luma[idx]) - Int(base.luma[idx])) >= threshold {
                    changed += 1
                    cellChanged[cellIndex] += 1
                }
                total += 1
                cellTotal[cellIndex] += 1
            }
        }

        guard total > 0, changed > 0 else { return nil }
        let rawSignal = CGFloat(changed) / CGFloat(total)
        let adjustedSignal = Swift.max(0, rawSignal - Cfg.motionBaseDiffMargin)
        guard adjustedSignal >= Cfg.motionCVRegionActiveThreshold else { return nil }

        var cells: [MotionCellScore] = []
        cells.reserveCapacity(columns * rows)
        for row in 0..<rows {
            for column in 0..<columns {
                let index = row * columns + column
                guard cellTotal[index] > 0 else { continue }
                let score = CGFloat(cellChanged[index]) / CGFloat(cellTotal[index])
                let left = CGFloat(column) / CGFloat(columns)
                let top = CGFloat(row) / CGFloat(rows)
                cells.append(
                    MotionCellScore(
                        bounds: CGRect(
                            x: left,
                            y: top,
                            width: 1.0 / CGFloat(columns),
                            height: 1.0 / CGFloat(rows)
                        ),
                        score: score
                    )
                )
            }
        }
        let sorted = cells.sorted { $0.score > $1.score }
        guard let strongest = sorted.first,
              strongest.score >= Cfg.motionSeedMinimumCellChangedRatio else { return nil }
        let cutoff = Swift.max(
            Cfg.motionSeedMinimumCellChangedRatio,
            strongest.score * Cfg.motionSeedRelativeCellThreshold
        )
        let maximumSelectedCells = Swift.max(1, Cfg.motionSeedMaximumSelectedCells)
        var selected: [MotionCellScore] = []
        selected.reserveCapacity(Swift.min(maximumSelectedCells, sorted.count))
        for cell in sorted {
            guard cell.score >= cutoff, selected.count < maximumSelectedCells else { break }
            selected.append(cell)
        }
        guard !selected.isEmpty else { return nil }

        var totalWeight: CGFloat = 0
        var weightedX: CGFloat = 0
        var weightedY: CGFloat = 0
        for cell in selected {
            let weight = Swift.max(cell.score, 0.000_001)
            totalWeight += weight
            weightedX += cell.bounds.midX * weight
            weightedY += cell.bounds.midY * weight
        }
        let target = MotionTarget(
            weightedCenter: CGPoint(
                x: weightedX / Swift.max(totalWeight, 0.000_001),
                y: weightedY / Swift.max(totalWeight, 0.000_001)
            ),
            cells: selected
        )
        return CVRegionMotion(rawSignal: rawSignal, adjustedSignal: adjustedSignal, target: target)
    }

    // MARK: Region Geometry

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
        var sourceXByColumn = [Int](repeating: cropX, count: dstW)
        for x in 0..<dstW {
            sourceXByColumn[x] = Swift.min(
                srcW - 1,
                cropX + Int((CGFloat(x) + 0.5) * CGFloat(cropW) / CGFloat(dstW))
            )
        }

        for y in 0..<dstH {
            let sy = Swift.min(srcH - 1, cropY + Int((CGFloat(y) + 0.5) * CGFloat(cropH) / CGFloat(dstH)))
            let srcRow = ptr + sy * row
            for x in 0..<dstW {
                out[y * dstW + x] = srcRow[sourceXByColumn[x]]
            }
        }
        return SmallFrame(width: dstW, height: dstH, luma: out)
    }


    // MARK: Helpers

    private func resetFinishReferenceTracking() {
        activeBestTopHoldPTS = nil
        activeBestTopHoldDistance = nil
        activeHighestPosePTS = nil
        activeHighestPoseY = nil
    }

    private func resetPoseSelectionTracking() {
        pendingMotionTarget = nil
        lastSelectedPoseCenter = nil
        lastSelectedPoseBounds = nil
    }

    private func resetStateLocked(keepConfig: Bool) {
        isActive = false
        state = .idle
        lastAnalyzedPTS = nil
        previousCVFrame = nil
        lastPosePTS = nil
        lastPoseSeenPTS = nil
        poseTrackingStartedPTS = nil
        lastLowestAboveMat = nil
        activeStartPTS = nil
        isPostClimbTracking = false
        postClimbConsecutivePoseMisses = 0
        hasSeenReliablePose = false
        isPoseLossRecovery = false
        resetPoseSelectionTracking()
        resetFinishReferenceTracking()

        if !keepConfig {
            matLine.removeAll()
            topBox = .zero
            finishReference = .closestTopHold
            activeCropRect = CGRect(x: 0, y: 0, width: 1, height: 1)
        }
    }

    private func duration(from start: CMTime?, to end: CMTime) -> TimeInterval {
        guard let start else { return 0 }
        return Swift.max(0, CMTimeGetSeconds(end - start))
    }

    private func clamped(_ rect: CGRect) -> CGRect { expanded(rect, by: 0) }
    private func clampedY(_ y: CGFloat) -> CGFloat { clamp(y, min: 0, max: 1) }
    private func clamp(_ v: CGFloat, min lo: CGFloat, max hi: CGFloat) -> CGFloat {
        Swift.max(lo, Swift.min(v, hi))
    }



    private func debug(_ text: @autoclosure () -> String) {
        if Cfg.motionDebugLog { print(text()) }
    }
}
