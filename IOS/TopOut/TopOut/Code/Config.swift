import UIKit

// MARK: - Global Config
enum Cfg {
    // MARK: SAM
    static let samSize = 1024
    static let maskSize = 256
    static let mean: [Float] = [123.675, 116.28, 103.53]
    static let std: [Float] = [58.395, 57.12, 57.375]

    // MARK: Hold Picking
    static let hsvTol: Float = 0.15
    static let looseHsvTols: [Float] = [0.18, 0.21]
    static let wallSpanRatio: CGFloat = 0.25
    static let tinyAreaRatio: CGFloat = 0.0006
    static let searchRadii: [CGFloat] = [0.01, 0.02, 0.03]
    static let maxCorrectionDecodes = 8
    static let colorSampleRadius = 1
    static let fallbackHoldMaskDiameterRatio: CGFloat = 0.075
    static let fallbackHoldMaskMinDiameter: CGFloat = 44
    static let fallbackHoldMaskMaxDiameterRatio: CGFloat = 0.12

    // MARK: Zoom
    static let minZoom: CGFloat = 1.0
    static let maxZoom: CGFloat = 5.0

    // MARK: Outline
    static let tinyRoundMaxSpan: CGFloat = 6
    static let tinyRoundMaxArea = 75
    static let roundAspectLimit: CGFloat = 1.8
    static let roundedMaxAreaRatio: CGFloat = 0.0625
    static let roundedPaddingRatio: CGFloat = 0.10
    static let roundedCornerRatio: CGFloat = 0.48
    static let contourRawPointLimit = 160
    static let contourPointLimit = 26
    static let contourSmoothIterations = 2
    static let contourUseConvexHull = true
    static let contourMinPoints = 8
    static let redLineRatio: CGFloat = 0.016
    static let whiteLineRatio: CGFloat = 0.006
    static let minRedLineWidth: CGFloat = 8
    static let minWhiteLineWidth: CGFloat = 2.5

    // MARK: Mat Detection
    static let matDetectWidth = 220
    static let matSearchTopY: CGFloat = 0.50
    static let matSearchBottomY: CGFloat = 0.96
    static let matDefaultY: CGFloat = 0.75
    static let matMinEdgeScore: CGFloat = 8
    static let matLowerUniformityTol: CGFloat = 15
    static let matBoundaryBins = 10
    static let matBoundaryMinCoverageRatio: CGFloat = 0.75
    static let matUpperTieScoreTolerance: CGFloat = 2.5
    static let matSampleStripRatio: CGFloat = 0.015
    static let matLowerSampleMultiplier: CGFloat = 2.0
    static let matLocalRefineWindowRatio: CGFloat = 0.03

    // MARK: Mat Line UI
    static let matLineWidth: CGFloat = 3
    static let matPointRadius: CGFloat = 8
    static let matHitRadius: CGFloat = 32

    // MARK: Motion Detection
    static let motionDebugLog = false
    static let motionAnalysisCalibrationFPS: Double = 8
    static let motionAnalysisIdleFPS: Double = 6
    static let motionAnalysisBoostFPS: Double = 12
    static let motionAnalysisBoostSeconds: TimeInterval = 1.2
    static let motionFrameWidth = 160
    static let motionPixelDiff: UInt8 = 20
    static let motionCountdownSeconds: TimeInterval = 3.0
    static let motionCalibrationMADFactor: CGFloat = 2.8
    static let motionCalibrationMinOutlierDelta: CGFloat = 0.035
    static let motionMatBandHeightRatio: CGFloat = 0.05
    static let motionBandBinCount = 10
    static let motionBinActiveThreshold: CGFloat = 0.08
    static let motionBaseDiffMargin: CGFloat = 0.025
    static let motionBoundaryPrimaryMaxBins = 3
    static let motionBoundaryNoiseMaxBins = 2
    static let motionBoundaryNoiseEnergyRatio: CGFloat = 0.55
    static let motionBoundaryDominanceRatio: CGFloat = 2.2
    static let motionBoundaryDominanceMargin: CGFloat = 0.10

    // MARK: Pose Detection
    static let motionPoseStartVerificationInterval: TimeInterval = 0.5
    static let motionPoseTrackingInterval: TimeInterval = 0.5
    static let motionPoseInputMaxLongSide: CGFloat = 384
    static let motionPoseMinConfidence: Float = 0.25
    static let motionPoseNoPoseRetrySeconds: TimeInterval = 5.0
    static let motionClipBoundaryPaddingSeconds: TimeInterval = 3.0
    static let motionMaxStoredClipRanges = 10
    static let motionShortClipMinimumTotalSeconds: TimeInterval = 9.0
    static let motionShortClipMergeGapSeconds: TimeInterval = 3.0
    static let motionPoseMatCrossingTolerance: CGFloat = 0.02
}
