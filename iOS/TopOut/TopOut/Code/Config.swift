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
    static let matDefaultY: CGFloat = 0.75
    static let matBoundaryAnalysisTimeout: TimeInterval = 5

    // MARK: Mat Line UI
    static let matLineWidth: CGFloat = 3
    static let matPointRadius: CGFloat = 8
    static let matHitRadius: CGFloat = 32

    // MARK: Review
    static let reviewEndPreviewLeadSeconds: TimeInterval = 3

    // MARK: Motion Detection
    static let motionDebugLog = false
    static let motionAnalysisFPS: Double = 2
    static let motionFrameWidth = 160
    static let motionPixelDiff: UInt8 = 20
    static let recordingCountdownSeconds: TimeInterval = 3.0
    // CV 탐색 영역: 매트 경계선 바로 위쪽부터 화면 높이의 40%
    static let motionCVRegionAboveMatHeightRatio: CGFloat = 0.40
    // 전체 탐색 영역에서 기준 노이즈를 제외한 변화 픽셀 비율
    static let motionCVRegionActiveThreshold: CGFloat = 0.02
    static let motionBaseDiffMargin: CGFloat = 0.025
    // Android와 같은 저해상도 격자 정책으로 초기 움직임의 위치를 보존합니다.
    static let motionSeedGridColumns = 8
    static let motionSeedMaximumSelectedCells = 8
    static let motionSeedRelativeCellThreshold: CGFloat = 0.45
    static let motionSeedMinimumCellChangedRatio: CGFloat = 0.02

    // MARK: Pose Detection
    static let motionPoseStartVerificationInterval: TimeInterval = 0.5
    static let motionPoseTrackingInterval: TimeInterval = 0.5
    static let motionPoseFastTrackingInterval: TimeInterval = 0.25
    static let motionPoseInputMaxLongSide: CGFloat = 384
    static let motionPoseMinConfidence: Float = 0.25
    static let motionPoseSelectionBoundsPaddingRatio: CGFloat = 0.04
    static let motionPoseSelectionMaxCenterDistanceRatio: CGFloat = 0.40
    static let motionPoseNoPoseRetrySeconds: TimeInterval = 6.0
    static let motionClipBoundaryPaddingSeconds: TimeInterval = 3.0
    static let motionShortClipMinimumDetectionSeconds: TimeInterval = 2.0
    static let motionShortClipMergeGapSeconds: TimeInterval = 3.0
    // 시작 판정: 선택 영상 높이의 6%만큼 실제 매트선 위에 설정한 시작선을 사용
    static let motionPoseStartAboveMatVideoHeightRatio: CGFloat = 0.06
    // 종료 판정: 선택 영상 높이의 4%만큼 실제 매트선 위에 설정한 종료선을 아래로 통과하면 종료
    static let motionPoseFinishAboveMatVideoHeightRatio: CGFloat = 0.04
    // 좌우 5% 가장자리에서는 무릎·발목 관절이 2개 이상 잡혀야 새 등반 시작 후보로 사용
    static let motionPoseStartSideEdgeMarginRatio: CGFloat = 0.05
    static let motionPoseStartMinimumLowerBodyPointsAtEdge = 2
}
