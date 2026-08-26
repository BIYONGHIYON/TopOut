import UIKit

// MARK: - 구간 선택 슬라이더

final class TrimRangeSlider: UIControl {
    enum ActiveHandle {
        case lower
        case upper
    }

    var minimumValue: TimeInterval = 0 {
        didSet {
            normalizeValues()
            visibleMinimumValue = minimumValue
            visibleMaximumValue = maximumValue
            setNeedsDisplay()
        }
    }

    var maximumValue: TimeInterval = 1 {
        didSet {
            normalizeValues()
            // 처음에는 전체 타임라인이 보이도록 초기화한다.
            visibleMinimumValue = minimumValue
            visibleMaximumValue = maximumValue
            setNeedsDisplay()
        }
    }

    var lowerValue: TimeInterval = 0 {
        didSet { normalizeValues() }
    }

    var upperValue: TimeInterval = 1 {
        didSet { normalizeValues() }
    }

    var playheadValue: TimeInterval = 0 {
        didSet { normalizeValues() }
    }

    var minimumDistance: TimeInterval = 0.1 {
        didSet { normalizeValues() }
    }

    var onRangeChanged: ((_ lower: TimeInterval, _ upper: TimeInterval, _ activeHandle: ActiveHandle) -> Void)?
    var onPlayheadChanged: ((_ value: TimeInterval) -> Void)?
    var onTrackingEnded: ((_ activeHandle: ActiveHandle?) -> Void)?

    override var isEnabled: Bool {
        didSet { alpha = isEnabled ? 1.0 : 0.45 }
    }

    private enum DragTarget {
        case lower
        case upper
        case playhead
    }

    private var dragTarget: DragTarget?
    private var isNormalizing = false
    private var isNormalizingViewport = false
    private var lastTrackingX: CGFloat?
    private var lastTrackingTimestamp: TimeInterval?
    private var rawDragValue: TimeInterval?
    private var smoothedDragSpeed: CGFloat = 0
    private var slowDragDuration: TimeInterval = 0
    private var isFineAdjustment = false

    private var visibleMinimumValue: TimeInterval = 0
    private var visibleMaximumValue: TimeInterval = 1

    private let fineAdjustmentFeedback = UIImpactFeedbackGenerator(style: .light)
    private let fineAdjustmentStep: TimeInterval = 0.1
    private let fineAdjustmentEnterSpeed: CGFloat = 35
    private let fineAdjustmentExitSpeed: CGFloat = 55
    private let fineAdjustmentActivationDuration: TimeInterval = 0.14
    private let maximumZoomFactor: Double = 12
    private let minimumZoomWindowSeconds: TimeInterval = 2
    // 손잡이는 작게 표시하고 터치 영역은 넉넉하게 유지한다.
    private let thumbRadius: CGFloat = 13
    private let playheadWidth: CGFloat = 3.5
    private let playheadHeight: CGFloat = 30
    private let trackHeight: CGFloat = 6
    private let hitRadius: CGFloat = 34

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isMultipleTouchEnabled = false
        isAccessibilityElement = true
        accessibilityLabel = L("review.timeline")
        accessibilityHint = L("review.timeline_hint")
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func draw(_ rect: CGRect) {
        guard maximumValue > minimumValue else { return }

        let track = trackRect()
        let centerY = track.midY

        drawTimelineTicks(track: track, centerY: centerY)

        UIColor.systemGray.withAlphaComponent(0.65).setFill()
        UIBezierPath(roundedRect: track, cornerRadius: track.height / 2).fill()

        let selectedVisibleStart = Swift.max(lowerValue, visibleMinimumValue)
        let selectedVisibleEnd = Swift.min(upperValue, visibleMaximumValue)
        if selectedVisibleEnd >= selectedVisibleStart {
            let lowerX = xPosition(for: selectedVisibleStart)
            let upperX = xPosition(for: selectedVisibleEnd)
            let selectedTrack = CGRect(
                x: lowerX,
                y: track.minY,
                width: Swift.max(CGFloat(0), upperX - lowerX),
                height: track.height
            )
            UIColor.systemBlue.setFill()
            UIBezierPath(roundedRect: selectedTrack, cornerRadius: track.height / 2).fill()
        }

        if isValueVisible(playheadValue) {
            drawPlayhead(x: xPosition(for: playheadValue), centerY: centerY)
        }

        if isValueVisible(lowerValue) {
            drawThumb(center: CGPoint(x: xPosition(for: lowerValue), y: centerY))
        } else if lowerValue < visibleMinimumValue {
            drawOffscreenMarker(x: track.minX, centerY: centerY)
        }

        if isValueVisible(upperValue) {
            drawThumb(center: CGPoint(x: xPosition(for: upperValue), y: centerY))
        } else if upperValue > visibleMaximumValue {
            drawOffscreenMarker(x: track.maxX, centerY: centerY)
        }

    }

    override func beginTracking(_ touch: UITouch, with event: UIEvent?) -> Bool {
        guard isEnabled else { return false }

        let point = touch.location(in: self)

        let lowerVisible = isValueVisible(lowerValue)
        let upperVisible = isValueVisible(upperValue)
        let playheadVisible = isValueVisible(playheadValue)
        let lowerX = xPosition(for: lowerValue)
        let upperX = xPosition(for: upperValue)
        let playheadX = xPosition(for: playheadValue)

        let lowerDistance = lowerVisible ? abs(point.x - lowerX) : .greatestFiniteMagnitude
        let upperDistance = upperVisible ? abs(point.x - upperX) : .greatestFiniteMagnitude
        let playheadDistance = playheadVisible ? abs(point.x - playheadX) : .greatestFiniteMagnitude
        let isTouchNearExistingControl =
            lowerDistance <= hitRadius ||
            upperDistance <= hitRadius ||
            playheadDistance <= hitRadius

        let touchedValue = value(for: point.x)
        if isTouchNearExistingControl {
            let nearestDistance = min(lowerDistance, upperDistance, playheadDistance)
            if nearestDistance == lowerDistance {
                dragTarget = .lower
            } else if nearestDistance == upperDistance {
                dragTarget = .upper
            } else {
                dragTarget = .playhead
            }
        } else if touchedValue > lowerValue, touchedValue < upperValue {
            dragTarget = .playhead
        } else if touchedValue <= lowerValue {
            dragTarget = .lower
        } else {
            dragTarget = .upper
        }

        lastTrackingX = point.x
        lastTrackingTimestamp = touch.timestamp
        smoothedDragSpeed = 0
        slowDragDuration = 0
        setFineAdjustment(false)
        fineAdjustmentFeedback.prepare()

        if isTouchNearExistingControl {
            rawDragValue = currentDragValue()
        } else {
            // 빈 트랙을 누르면 해당 위치로 바로 이동한다.
            let absoluteValue = clampedValueForCurrentTarget(touchedValue)
            rawDragValue = absoluteValue
            applyDragValue(absoluteValue)
        }

        return true
    }

    override func continueTracking(_ touch: UITouch, with event: UIEvent?) -> Bool {
        guard isEnabled, dragTarget != nil else { return false }

        let point = touch.location(in: self)
        let timestamp = touch.timestamp
        guard let previousX = lastTrackingX,
              let previousTimestamp = lastTrackingTimestamp else {
            lastTrackingX = point.x
            lastTrackingTimestamp = timestamp
            rawDragValue = currentDragValue()
            return true
        }

        let deltaX = point.x - previousX
        let deltaTime = Swift.max(1.0 / 240.0, timestamp - previousTimestamp)
        let instantaneousSpeed = abs(deltaX) / CGFloat(deltaTime)

        if smoothedDragSpeed == 0 {
            smoothedDragSpeed = instantaneousSpeed
        } else {
            smoothedDragSpeed = smoothedDragSpeed * 0.62 + instantaneousSpeed * 0.38
        }

        if smoothedDragSpeed <= fineAdjustmentEnterSpeed {
            slowDragDuration += deltaTime
        } else {
            slowDragDuration = 0
        }

        let fineAdjustment: Bool
        if isFineAdjustment {
            fineAdjustment = smoothedDragSpeed <= fineAdjustmentExitSpeed
        } else {
            fineAdjustment = slowDragDuration >= fineAdjustmentActivationDuration
        }
        setFineAdjustment(fineAdjustment)

        let sensitivity = dragSensitivity(for: smoothedDragSpeed)
        let trackWidth = Swift.max(CGFloat(1), trackRect().width)
        let secondsPerPoint = visibleSpan / Double(trackWidth)
        let deltaValue = Double(deltaX) * secondsPerPoint * Double(sensitivity)
        let baseValue = rawDragValue ?? currentDragValue()
        let unclampedRawValue = baseValue + deltaValue
        let clampedRawValue = clampedValueForCurrentTarget(unclampedRawValue)
        rawDragValue = clampedRawValue

        let displayedValue: TimeInterval
        if fineAdjustment {
            displayedValue = snappedToFineStep(clampedRawValue)
        } else {
            displayedValue = clampedRawValue
        }
        applyDragValue(displayedValue)

        lastTrackingX = point.x
        lastTrackingTimestamp = timestamp
        return true
    }

    override func endTracking(_ touch: UITouch?, with event: UIEvent?) {
        let activeHandle: ActiveHandle? = switch dragTarget {
        case .lower: .lower
        case .upper: .upper
        case .playhead, .none: nil
        }
        resetTrackingState()
        _ = recenterSelectedRangePreservingCurrentZoom()
        onTrackingEnded?(activeHandle)
    }

    override func cancelTracking(with event: UIEvent?) {
        resetTrackingState()
        _ = recenterSelectedRangePreservingCurrentZoom()
        onTrackingEnded?(nil)
    }

    var currentSelectedRangeZoomFactor: Double {
        zoomFactor
    }

    var maximumAllowedSelectedRangeZoomFactor: Double {
        totalSpan / minimumSelectedRangeViewportSpan
    }

    @discardableResult
    func recenterSelectedRangePreservingCurrentZoom() -> Double {
        let currentSpan = visibleSpan
        let selectedSpan = Swift.max(minimumDistance, upperValue - lowerValue)
        guard selectedSpan <= currentSpan + 0.0001 else {
            return setSelectedRangeZoomFactor(zoomFactor)
        }
        let selectedCenter = (lowerValue + upperValue) * 0.5
        setVisibleWindow(start: selectedCenter - currentSpan * 0.5, span: currentSpan)
        return zoomFactor
    }

    @discardableResult
    func setSelectedRangeZoomFactor(_ requestedZoom: Double) -> Double {
        let maximumZoom = Swift.max(1.0, maximumAllowedSelectedRangeZoomFactor)
        let safeZoom = Swift.max(1.0, Swift.min(requestedZoom, maximumZoom))
        let span = Swift.max(minimumSelectedRangeViewportSpan, totalSpan / safeZoom)
        let selectedCenter = (lowerValue + upperValue) * 0.5
        setVisibleWindow(start: selectedCenter - span * 0.5, span: span)
        return totalSpan / visibleSpan
    }

    func ensureValueVisible(_ value: TimeInterval) {
        guard zoomFactor > 1.01 else { return }
        if value < visibleMinimumValue {
            setVisibleWindow(start: value - visibleSpan * 0.20, span: visibleSpan)
        } else if value > visibleMaximumValue {
            setVisibleWindow(start: value - visibleSpan * 0.80, span: visibleSpan)
        }
    }

    func resetZoom() {
        _ = setSelectedRangeZoomFactor(1.0)
    }

    private func applyDragValue(_ value: TimeInterval) {
        let safeDistance = Swift.max(0.01, minimumDistance)

        switch dragTarget {
        case .lower:
            lowerValue = Swift.max(minimumValue, Swift.min(value, upperValue - safeDistance))
            playheadValue = lowerValue
            onRangeChanged?(lowerValue, upperValue, .lower)
        case .upper:
            upperValue = Swift.min(maximumValue, Swift.max(value, lowerValue + safeDistance))
            playheadValue = upperValue
            onRangeChanged?(lowerValue, upperValue, .upper)
        case .playhead:
            playheadValue = Swift.max(lowerValue, Swift.min(value, upperValue))
            onPlayheadChanged?(playheadValue)
        case .none:
            return
        }
    }

    private func currentDragValue() -> TimeInterval {
        switch dragTarget {
        case .lower:
            return lowerValue
        case .upper:
            return upperValue
        case .playhead:
            return playheadValue
        case .none:
            return minimumValue
        }
    }

    private func clampedValueForCurrentTarget(_ value: TimeInterval) -> TimeInterval {
        let safeDistance = Swift.max(0.01, minimumDistance)

        switch dragTarget {
        case .lower:
            return Swift.max(minimumValue, Swift.min(value, upperValue - safeDistance))
        case .upper:
            return Swift.min(maximumValue, Swift.max(value, lowerValue + safeDistance))
        case .playhead:
            return Swift.max(lowerValue, Swift.min(value, upperValue))
        case .none:
            return value
        }
    }

    private func snappedToFineStep(_ value: TimeInterval) -> TimeInterval {
        let step = Swift.max(0.01, fineAdjustmentStep)
        let snapped = ((value - minimumValue) / step).rounded() * step + minimumValue
        return clampedValueForCurrentTarget(snapped)
    }

    private func dragSensitivity(for speed: CGFloat) -> CGFloat {
        let minimumGain = minimumFineAdjustmentGain()

        if speed <= 18 {
            return minimumGain
        }
        if speed <= fineAdjustmentEnterSpeed {
            return interpolatedGain(
                speed: speed,
                lowerSpeed: 18,
                upperSpeed: fineAdjustmentEnterSpeed,
                lowerGain: minimumGain,
                upperGain: 0.10
            )
        }
        if speed <= 60 {
            return interpolatedGain(
                speed: speed,
                lowerSpeed: fineAdjustmentEnterSpeed,
                upperSpeed: 60,
                lowerGain: 0.10,
                upperGain: 0.45
            )
        }
        if speed <= 110 {
            return interpolatedGain(
                speed: speed,
                lowerSpeed: 60,
                upperSpeed: 110,
                lowerGain: 0.45,
                upperGain: 0.85
            )
        }
        if speed <= 170 {
            return interpolatedGain(
                speed: speed,
                lowerSpeed: 110,
                upperSpeed: 170,
                lowerGain: 0.85,
                upperGain: 1.0
            )
        }
        return 1.0
    }

    private func minimumFineAdjustmentGain() -> CGFloat {
        let trackWidth = Swift.max(CGFloat(1), trackRect().width)
        let secondsPerPoint = visibleSpan / Double(trackWidth)
        guard secondsPerPoint > 0 else { return 0.03 }

        let gainForOneTenthSecond = fineAdjustmentStep / secondsPerPoint
        return CGFloat(Swift.max(0.008, Swift.min(0.08, gainForOneTenthSecond)))
    }

    private func interpolatedGain(
        speed: CGFloat,
        lowerSpeed: CGFloat,
        upperSpeed: CGFloat,
        lowerGain: CGFloat,
        upperGain: CGFloat
    ) -> CGFloat {
        let range = Swift.max(CGFloat(1), upperSpeed - lowerSpeed)
        let progress = Swift.max(CGFloat(0), Swift.min(CGFloat(1), (speed - lowerSpeed) / range))
        return lowerGain + (upperGain - lowerGain) * progress
    }

    private func setFineAdjustment(_ enabled: Bool) {
        guard isFineAdjustment != enabled else { return }
        isFineAdjustment = enabled

        if enabled {
            fineAdjustmentFeedback.impactOccurred(intensity: 0.65)
            fineAdjustmentFeedback.prepare()
        }
    }

    private func resetTrackingState() {
        dragTarget = nil
        lastTrackingX = nil
        lastTrackingTimestamp = nil
        rawDragValue = nil
        smoothedDragSpeed = 0
        slowDragDuration = 0
        setFineAdjustment(false)
    }

    private var totalSpan: TimeInterval {
        Swift.max(0.01, maximumValue - minimumValue)
    }

    private var visibleSpan: TimeInterval {
        Swift.max(0.01, visibleMaximumValue - visibleMinimumValue)
    }

    private var minimumVisibleSpan: TimeInterval {
        Swift.min(totalSpan, Swift.max(minimumZoomWindowSeconds, totalSpan / maximumZoomFactor))
    }

    private var minimumSelectedRangeViewportSpan: TimeInterval {
        let selectedSpan = Swift.max(minimumDistance, upperValue - lowerValue)
        let handleMargin = Swift.max(0.35, selectedSpan * 0.16)
        return Swift.min(totalSpan, Swift.max(minimumVisibleSpan, selectedSpan + handleMargin * 2.0))
    }

    private var zoomFactor: Double {
        totalSpan / visibleSpan
    }

    private func normalizeValues() {
        guard !isNormalizing else { return }
        isNormalizing = true

        if maximumValue <= minimumValue {
            maximumValue = minimumValue + 0.01
        }

        let safeDistance = Swift.max(0.01, minimumDistance)
        lowerValue = Swift.max(minimumValue, Swift.min(lowerValue, maximumValue - safeDistance))
        upperValue = Swift.min(maximumValue, Swift.max(upperValue, lowerValue + safeDistance))
        playheadValue = Swift.max(lowerValue, Swift.min(playheadValue, upperValue))

        isNormalizing = false
        normalizeViewport()
        setNeedsDisplay()
    }

    private func normalizeViewport() {
        guard !isNormalizingViewport else { return }
        isNormalizingViewport = true

        let span = Swift.max(minimumVisibleSpan, Swift.min(totalSpan, visibleSpan))
        let maximumStart = maximumValue - span
        visibleMinimumValue = Swift.max(minimumValue, Swift.min(visibleMinimumValue, maximumStart))
        visibleMaximumValue = visibleMinimumValue + span

        isNormalizingViewport = false
        setNeedsDisplay()
    }

    private func setVisibleWindow(start: TimeInterval, span: TimeInterval) {
        let safeSpan = Swift.max(minimumVisibleSpan, Swift.min(totalSpan, span))
        let maximumStart = maximumValue - safeSpan
        visibleMinimumValue = Swift.max(minimumValue, Swift.min(start, maximumStart))
        visibleMaximumValue = visibleMinimumValue + safeSpan
        setNeedsDisplay()
    }

    private func isValueVisible(_ value: TimeInterval) -> Bool {
        value >= visibleMinimumValue && value <= visibleMaximumValue
    }

    private func trackRect() -> CGRect {
        let x = thumbRadius
        let width = Swift.max(1, bounds.width - thumbRadius * 2)
        let centerY = bounds.midY + 2
        return CGRect(x: x, y: centerY - trackHeight / 2, width: width, height: trackHeight)
    }

    func xPositionForValue(_ value: TimeInterval) -> CGFloat {
        xPosition(for: value)
    }

    private func xPosition(for value: TimeInterval) -> CGFloat {
        let track = trackRect()
        let ratio = CGFloat((value - visibleMinimumValue) / visibleSpan)
        return track.minX + Swift.max(CGFloat(0), Swift.min(CGFloat(1), ratio)) * track.width
    }

    private func value(for x: CGFloat) -> TimeInterval {
        let track = trackRect()
        let ratio = Swift.max(CGFloat(0), Swift.min(CGFloat(1), (x - track.minX) / track.width))
        return visibleMinimumValue + Double(ratio) * visibleSpan
    }

    private func drawTimelineTicks(track: CGRect, centerY: CGFloat) {
        let baseMajorStep = niceTimelineStep(totalSpan / 8.0)
        let majorSpacing = CGFloat(baseMajorStep / visibleSpan) * track.width
        let subdivisions: Int
        if majorSpacing >= 180 {
            subdivisions = 10
        } else if majorSpacing >= 90 {
            subdivisions = 5
        } else if majorSpacing >= 48 {
            subdivisions = 2
        } else {
            subdivisions = 1
        }

        let tickStep = baseMajorStep / Double(subdivisions)
        guard tickStep > 0 else { return }
        var tick = ceil(Swift.max(minimumValue, visibleMinimumValue) / tickStep) * tickStep
        let tickEnd = Swift.min(maximumValue, visibleMaximumValue)
        var guardCount = 0

        while tick <= tickEnd + tickStep * 0.001, guardCount < 500 {
            let majorIndex = tick / baseMajorStep
            let isMajor = abs(majorIndex - majorIndex.rounded()) < 0.001
            let tickHeight: CGFloat = isMajor ? 7 : 3
            let tickWidth: CGFloat = isMajor ? 1.2 : 0.8
            let tickColor = UIColor.white.withAlphaComponent(isMajor ? 0.32 : 0.16)
            tickColor.setStroke()
            let path = UIBezierPath()
            path.lineWidth = tickWidth
            path.lineCapStyle = .round
            let x = xPosition(for: tick)
            path.move(to: CGPoint(x: x, y: centerY - tickHeight))
            path.addLine(to: CGPoint(x: x, y: centerY + tickHeight))
            path.stroke()
            tick += tickStep
            guardCount += 1
        }
    }

    private func niceTimelineStep(_ raw: Double) -> Double {
        guard raw > 0 else { return 1 }
        let magnitude = pow(10.0, floor(log10(raw)))
        let normalized = raw / magnitude
        let rounded: Double
        if normalized <= 1 {
            rounded = 1
        } else if normalized <= 2 {
            rounded = 2
        } else if normalized <= 5 {
            rounded = 5
        } else {
            rounded = 10
        }
        return rounded * magnitude
    }

    private func drawThumb(center: CGPoint) {
        let rect = CGRect(
            x: center.x - thumbRadius,
            y: center.y - thumbRadius,
            width: thumbRadius * 2,
            height: thumbRadius * 2
        )

        UIColor.white.setFill()
        UIBezierPath(ovalIn: rect).fill()

        UIColor.black.withAlphaComponent(0.25).setStroke()
        let border = UIBezierPath(ovalIn: rect.insetBy(dx: 0.5, dy: 0.5))
        border.lineWidth = 1
        border.stroke()
    }

    private func drawOffscreenMarker(x: CGFloat, centerY: CGFloat) {
        let path = UIBezierPath()
        path.move(to: CGPoint(x: x, y: centerY - 8))
        path.addLine(to: CGPoint(x: x, y: centerY + 8))
        path.lineWidth = 2
        path.lineCapStyle = .round
        UIColor.white.setStroke()
        path.stroke()
    }

    private func drawPlayhead(x: CGFloat, centerY: CGFloat) {
        let rect = CGRect(
            x: x - playheadWidth / 2,
            y: centerY - playheadHeight / 2,
            width: playheadWidth,
            height: playheadHeight
        )

        UIColor.black.withAlphaComponent(0.45).setFill()
        UIBezierPath(roundedRect: rect.insetBy(dx: -2, dy: -2), cornerRadius: 3).fill()

        UIColor.white.setFill()
        UIBezierPath(roundedRect: rect, cornerRadius: 2).fill()
    }
}
