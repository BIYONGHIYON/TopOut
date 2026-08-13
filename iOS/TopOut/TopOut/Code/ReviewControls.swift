import UIKit

/// Zoom control that owns its track mapping so endpoint input never invokes UISlider haptics.
final class TrackTappableSlider: UIControl {
    private enum Layout {
        static let minimumTouchHeight: CGFloat = 44
        static let trackHeight: CGFloat = 4
        static let thumbDiameter: CGFloat = 14
    }

    private var storedValue: Float = 0
    private var thumbImage: UIImage?

    var minimumValue: Float = 0 {
        didSet { normalizeValue() }
    }

    var maximumValue: Float = 1 {
        didSet { normalizeValue() }
    }

    var value: Float {
        get { storedValue }
        set {
            let clampedValue = Swift.max(minimumValue, Swift.min(newValue, maximumValue))
            guard clampedValue != storedValue else { return }
            storedValue = clampedValue
            updateAccessibilityValue()
            setNeedsDisplay()
        }
    }

    var minimumTrackTintColor: UIColor = .systemBlue {
        didSet { setNeedsDisplay() }
    }

    var maximumTrackTintColor: UIColor = .systemGray {
        didSet { setNeedsDisplay() }
    }

    var thumbTintColor: UIColor = .white {
        didSet { setNeedsDisplay() }
    }

    override var isEnabled: Bool {
        didSet { alpha = isEnabled ? 1 : 0.45 }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isMultipleTouchEnabled = false
        isAccessibilityElement = true
        accessibilityTraits = [.adjustable]
        updateAccessibilityValue()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func setThumbImage(_ image: UIImage?) {
        thumbImage = image
        setNeedsDisplay()
    }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        let verticalExpansion = Swift.max(0, (Layout.minimumTouchHeight - bounds.height) * 0.5)
        return bounds.insetBy(dx: 0, dy: -verticalExpansion).contains(point)
    }

    override func draw(_ rect: CGRect) {
        let track = sliderTrackRect()
        let thumbX = track.minX + track.width * normalizedProgress

        maximumTrackTintColor.setFill()
        UIBezierPath(roundedRect: track, cornerRadius: track.height * 0.5).fill()

        let isRightToLeft = effectiveUserInterfaceLayoutDirection == .rightToLeft
        let activeTrack = CGRect(
            x: isRightToLeft ? thumbX : track.minX,
            y: track.minY,
            width: isRightToLeft
                ? Swift.max(0, track.maxX - thumbX)
                : Swift.max(0, thumbX - track.minX),
            height: track.height
        )
        minimumTrackTintColor.setFill()
        UIBezierPath(roundedRect: activeTrack, cornerRadius: track.height * 0.5).fill()

        let thumbRect = CGRect(
            x: thumbX - Layout.thumbDiameter * 0.5,
            y: track.midY - Layout.thumbDiameter * 0.5,
            width: Layout.thumbDiameter,
            height: Layout.thumbDiameter
        )
        if let thumbImage {
            thumbImage
                .withTintColor(thumbTintColor, renderingMode: .alwaysOriginal)
                .draw(in: thumbRect)
        } else {
            thumbTintColor.setFill()
            UIBezierPath(ovalIn: thumbRect).fill()
        }
    }

    override func beginTracking(_ touch: UITouch, with event: UIEvent?) -> Bool {
        guard isEnabled else { return false }
        updateValue(for: touch.location(in: self).x)
        return true
    }

    override func continueTracking(_ touch: UITouch, with event: UIEvent?) -> Bool {
        guard isEnabled else { return false }
        updateValue(for: touch.location(in: self).x)
        return true
    }

    override func accessibilityIncrement() {
        updateFromAccessibility(direction: 1)
    }

    override func accessibilityDecrement() {
        updateFromAccessibility(direction: -1)
    }

    private func updateValue(for x: CGFloat) {
        let track = sliderTrackRect()
        let rawProgress = Swift.max(0, Swift.min(1, (x - track.minX) / Swift.max(1, track.width)))
        let progress = effectiveUserInterfaceLayoutDirection == .rightToLeft
            ? 1 - rawProgress
            : rawProgress
        let previousValue = value
        value = minimumValue + Float(progress) * (maximumValue - minimumValue)
        guard value != previousValue else { return }
        sendActions(for: .valueChanged)
    }

    private func updateFromAccessibility(direction: Float) {
        guard isEnabled else { return }
        let step = Swift.max(0.01, (maximumValue - minimumValue) * 0.05)
        let previousValue = value
        value += direction * step
        guard value != previousValue else { return }
        sendActions(for: .valueChanged)
    }

    private func normalizeValue() {
        storedValue = maximumValue < minimumValue
            ? minimumValue
            : Swift.max(minimumValue, Swift.min(storedValue, maximumValue))
        updateAccessibilityValue()
        setNeedsDisplay()
    }

    private func updateAccessibilityValue() {
        accessibilityValue = String(format: "%.1f배", Double(storedValue))
    }

    private var normalizedProgress: CGFloat {
        let range = maximumValue - minimumValue
        guard range > 0 else { return 0 }
        let progress = CGFloat((value - minimumValue) / range)
        return effectiveUserInterfaceLayoutDirection == .rightToLeft ? 1 - progress : progress
    }

    private func sliderTrackRect() -> CGRect {
        let horizontalInset = Layout.thumbDiameter * 0.5
        return CGRect(
            x: horizontalInset,
            y: bounds.midY - Layout.trackHeight * 0.5,
            width: Swift.max(1, bounds.width - horizontalInset * 2),
            height: Layout.trackHeight
        )
    }
}

extension UIAlertController {
    /// Keeps the system alert layout and buttons while reducing only a long message's font.
    func applyCompactMessageFont(matching message: String) {
        loadViewIfNeeded()
        guard let messageLabel = view.descendantLabel(matching: message) else { return }
        messageLabel.font = UIFontMetrics(forTextStyle: .footnote).scaledFont(
            for: .systemFont(ofSize: 13)
        )
        messageLabel.adjustsFontForContentSizeCategory = true
        view.setNeedsLayout()
        view.layoutIfNeeded()
    }
}

private extension UIView {
    func descendantLabel(matching text: String) -> UILabel? {
        if let label = self as? UILabel {
            let displayedText = label.attributedText?.string ?? label.text
            if displayedText == text || label.accessibilityLabel == text {
                return label
            }
        }
        return subviews.lazy.compactMap { $0.descendantLabel(matching: text) }.first
    }
}
