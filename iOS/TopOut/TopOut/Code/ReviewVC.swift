import UIKit
import AVFoundation
import AVKit
import Photos

final class ReviewVC: UIViewController {
    private struct VerifiedSavedSelection: Hashable {
        let startSeconds: TimeInterval
        let endSeconds: TimeInterval
        let includeTopHoldOverlay: Bool
    }

    private let recordingURL: URL
    private let duration: TimeInterval
    private let topHoldOverlayImage: UIImage?
    private let topHoldOverlayToggleLocked: Bool
    private let autoClipDetectionEnabled: Bool
    private let helpEnabled: Bool
    private let recordingAspectRatio: RecordingAspectRatio
    private let detectedRanges: [ReviewClipRange]

    private var selectedStart: TimeInterval
    private var selectedEnd: TimeInterval
    private var playhead: TimeInterval
    private var isPlaying = false
    private var includeTopHoldOverlay: Bool
    private var selectedDetectedRangeIndex: Int?
    private var sliderTimeBubbleHideWorkItem: DispatchWorkItem?
    private var sliderTimeBubbleHideGeneration = 0
    private var verifiedSavedAssets: [VerifiedSavedSelection: String] = [:]

    private var player: AVPlayer?
    private var playerLayer: AVPlayerLayer?
    private var timeObserver: Any?
    private var playerItemStatusObservation: NSKeyValueObservation?
    private var playerTimeControlStatusObservation: NSKeyValueObservation?
    private var playbackEndNotificationObserver: NSObjectProtocol?
    private var pendingPlaybackStart = false
    private var playbackRequestID = 0
    private var hasRequestedInitialPlayback = false
    private var activePlaybackStart: TimeInterval?
    private var activePlaybackEnd: TimeInterval?
    private var isWaitingForFirstPlaybackTimestamp = false

    private let topHoldOverlayView: UIImageView = {
        let view = UIImageView()
        view.contentMode = .scaleAspectFit
        view.clipsToBounds = true
        view.isUserInteractionEnabled = false
        view.isHidden = true
        return view
    }()

    var onClose: (() -> Void)?
    var onRetake: (() -> Void)?
    var onSaveTrim: ((_ startSeconds: TimeInterval, _ endSeconds: TimeInterval, _ includeTopHoldOverlay: Bool) -> Void)?

    private let statusLabel: UILabel = {
        let label = UILabel()
        label.text = "자동 감지된 시작~완등 구간을 확인해주세요"
        label.textColor = .white
        label.textAlignment = .center
        label.font = .systemFont(ofSize: 14, weight: .semibold)
        label.backgroundColor = .clear
        label.layer.shadowColor = UIColor.black.cgColor
        label.layer.shadowOpacity = 0.75
        label.layer.shadowRadius = 3
        label.layer.shadowOffset = CGSize(width: 0, height: 1)
        label.clipsToBounds = false
        return label
    }()

    private let previousRangeBtn: UIButton = {
        var config = UIButton.Configuration.gray()
        config.cornerStyle = .capsule
        config.image = UIImage(systemName: "chevron.left")
        config.contentInsets = NSDirectionalEdgeInsets(top: 6, leading: 10, bottom: 6, trailing: 10)
        let btn = UIButton(configuration: config)
        btn.accessibilityLabel = "이전 감지 구간"
        btn.isHidden = true
        return btn
    }()

    private let nextRangeBtn: UIButton = {
        var config = UIButton.Configuration.gray()
        config.cornerStyle = .capsule
        config.image = UIImage(systemName: "chevron.right")
        config.contentInsets = NSDirectionalEdgeInsets(top: 6, leading: 10, bottom: 6, trailing: 10)
        let btn = UIButton(configuration: config)
        btn.accessibilityLabel = "다음 감지 구간"
        btn.isHidden = true
        return btn
    }()

    private let rangeLabel: UILabel = {
        let label = UILabel()
        label.textColor = .white
        label.textAlignment = .center
        label.font = .monospacedDigitSystemFont(ofSize: 13, weight: .semibold)
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.82
        label.backgroundColor = UIColor.black.withAlphaComponent(0.58)
        label.layer.cornerRadius = 9
        label.clipsToBounds = true
        label.alpha = 0
        label.isHidden = true
        return label
    }()

    private let detectedRangeLabel: UILabel = {
        let label = UILabel()
        label.textColor = .white
        label.textAlignment = .center
        label.font = .monospacedDigitSystemFont(ofSize: 13, weight: .semibold)
        label.backgroundColor = UIColor.black.withAlphaComponent(0.42)
        label.layer.cornerRadius = 13
        label.clipsToBounds = true
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.82
        label.isHidden = true
        return label
    }()

    private let trimSlider: TrimRangeSlider = {
        let slider = TrimRangeSlider()
        slider.minimumDistance = 0.1
        return slider
    }()

    private let selectedRangeZoomLabel: UILabel = {
        let label = UILabel()
        label.text = "선택된 구간 확대"
        label.textColor = UIColor.white.withAlphaComponent(0.90)
        label.font = .systemFont(ofSize: 11, weight: .semibold)
        return label
    }()

    private let selectedRangeZoomValueLabel: UILabel = {
        let label = UILabel()
        label.text = "1.0×"
        label.textColor = UIColor.white.withAlphaComponent(0.78)
        label.textAlignment = .right
        label.font = .monospacedDigitSystemFont(ofSize: 10, weight: .semibold)
        return label
    }()

    private let selectedRangeZoomSlider: TrackTappableSlider = {
        let slider = TrackTappableSlider()
        slider.minimumValue = 1
        slider.maximumValue = 1.0001
        slider.value = 1
        slider.minimumTrackTintColor = .systemBlue
        slider.maximumTrackTintColor = UIColor.systemGray.withAlphaComponent(0.52)
        let compactThumb = UIImage(
            systemName: "circle.fill",
            withConfiguration: UIImage.SymbolConfiguration(pointSize: 11, weight: .semibold)
        )
        slider.setThumbImage(compactThumb)
        slider.thumbTintColor = .white
        slider.accessibilityLabel = "선택된 구간 확대"
        slider.accessibilityHint = "오른쪽으로 이동하면 선택된 시작과 끝을 화면 안에 유지한 채 확대합니다"
        return slider
    }()

    private let playPauseBtn: UIButton = {
        var config = UIButton.Configuration.gray()
        config.cornerStyle = .capsule
        config.image = UIImage(systemName: "play.fill")
        config.contentInsets = NSDirectionalEdgeInsets(top: 7, leading: 14, bottom: 7, trailing: 14)
        let btn = UIButton(configuration: config)
        btn.accessibilityLabel = "재생 또는 일시정지"
        return btn
    }()

    private let overlayToggleBtn: UIButton = {
        var config = UIButton.Configuration.gray()
        config.cornerStyle = .capsule
        var title = AttributedString("탑홀드 마스크")
        title.font = .systemFont(ofSize: 13, weight: .semibold)
        config.title = nil
        config.attributedTitle = title
        config.image = UIImage(systemName: "square")
        config.imagePlacement = .leading
        config.imagePadding = 3
        config.titleAlignment = .center
        config.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8)
        let btn = UIButton(configuration: config)
        btn.contentHorizontalAlignment = .center
        btn.titleLabel?.font = .systemFont(ofSize: 13, weight: .semibold)
        btn.titleLabel?.adjustsFontSizeToFitWidth = true
        btn.titleLabel?.minimumScaleFactor = 0.78
        btn.accessibilityLabel = "탑홀드 마스크 저장 옵션"
        return btn
    }()

    private let saveBtn: UIButton = {
        var config = UIButton.Configuration.filled()
        config.title = "선택 구간 저장"
        config.cornerStyle = .capsule
        config.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16)
        let btn = UIButton(configuration: config)
        btn.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        btn.titleLabel?.adjustsFontSizeToFitWidth = true
        btn.titleLabel?.minimumScaleFactor = 0.72
        return btn
    }()

    private let retakeBtn: UIButton = {
        var config = UIButton.Configuration.gray()
        config.title = "다시 촬영"
        config.cornerStyle = .capsule
        config.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16)
        let btn = UIButton(configuration: config)
        btn.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        btn.titleLabel?.adjustsFontSizeToFitWidth = true
        btn.titleLabel?.minimumScaleFactor = 0.78
        return btn
    }()

    private let closeBtn: UIButton = {
        var config = UIButton.Configuration.gray()
        config.title = "닫기"
        config.cornerStyle = .capsule
        config.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16)
        let btn = UIButton(configuration: config)
        btn.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        btn.titleLabel?.adjustsFontSizeToFitWidth = true
        btn.titleLabel?.minimumScaleFactor = 0.78
        return btn
    }()

    init(
        recordingURL: URL,
        duration: TimeInterval,
        defaultStart: TimeInterval,
        defaultEnd: TimeInterval,
        detectedRanges: [ReviewClipRange],
        topHoldOverlayImage: UIImage?,
        includeTopHoldOverlayByDefault: Bool,
        topHoldOverlayToggleLocked: Bool,
        autoClipDetectionEnabled: Bool,
        helpEnabled: Bool,
        recordingAspectRatio: RecordingAspectRatio
    ) {
        self.recordingURL = recordingURL
        self.duration = Swift.max(0.01, duration)
        self.topHoldOverlayImage = topHoldOverlayImage
        self.topHoldOverlayToggleLocked = topHoldOverlayToggleLocked && topHoldOverlayImage != nil
        self.autoClipDetectionEnabled = autoClipDetectionEnabled
        self.detectedRanges = detectedRanges
        self.includeTopHoldOverlay = includeTopHoldOverlayByDefault && topHoldOverlayImage != nil
        self.helpEnabled = helpEnabled
        self.recordingAspectRatio = recordingAspectRatio

        let s = Swift.max(0, Swift.min(defaultStart, self.duration))
        let e = Swift.max(0, Swift.min(defaultEnd, self.duration))
        if e > s {
            self.selectedStart = s
            self.selectedEnd = e
        } else {
            self.selectedStart = 0
            self.selectedEnd = self.duration
        }
        self.playhead = self.selectedStart
        if let idx = detectedRanges.indices.last {
            self.selectedDetectedRangeIndex = idx
        } else {
            self.selectedDetectedRangeIndex = nil
        }

        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        // Keep review controls on the dark palette even when the device is in Light Mode.
        overrideUserInterfaceStyle = .dark
        view.backgroundColor = .black

        view.addSubview(statusLabel)
        view.addSubview(previousRangeBtn)
        view.addSubview(nextRangeBtn)
        view.addSubview(topHoldOverlayView)
        view.addSubview(detectedRangeLabel)
        view.addSubview(rangeLabel)
        view.addSubview(selectedRangeZoomLabel)
        view.addSubview(selectedRangeZoomValueLabel)
        view.addSubview(selectedRangeZoomSlider)
        view.addSubview(trimSlider)
        view.addSubview(playPauseBtn)
        view.addSubview(overlayToggleBtn)
        view.addSubview(saveBtn)
        view.addSubview(retakeBtn)
        view.addSubview(closeBtn)

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(onAppDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )

        trimSlider.minimumValue = 0
        trimSlider.maximumValue = duration
        trimSlider.lowerValue = selectedStart
        trimSlider.upperValue = selectedEnd
        trimSlider.playheadValue = playhead
        trimSlider.onRangeChanged = { [weak self] lower, upper, activeHandle in
            self?.handleRangeChanged(lower: lower, upper: upper, activeHandle: activeHandle)
        }
        trimSlider.onPlayheadChanged = { [weak self] value in
            self?.handlePlayheadChanged(value)
        }
        trimSlider.onTrackingEnded = { [weak self] activeHandle in
            self?.refreshSelectedRangeZoomControl(recenter: true)
            if let activeHandle {
                self?.resumePlaybackAfterTrimEdit(activeHandle: activeHandle)
            }
            self?.scheduleHideSliderTimeBubble(after: 0.55)
        }
        selectedRangeZoomSlider.addTarget(self, action: #selector(onSelectedRangeZoomTouchDown), for: .touchDown)
        selectedRangeZoomSlider.addTarget(self, action: #selector(onSelectedRangeZoomChanged), for: .valueChanged)
        selectedRangeZoomSlider.addTarget(
            self,
            action: #selector(onSelectedRangeZoomTouchEnded),
            for: [.touchUpInside, .touchUpOutside, .touchCancel]
        )

        previousRangeBtn.addTarget(self, action: #selector(onTapPreviousRange), for: .touchUpInside)
        nextRangeBtn.addTarget(self, action: #selector(onTapNextRange), for: .touchUpInside)
        playPauseBtn.addTarget(self, action: #selector(onTapPlayPause), for: .touchUpInside)
        overlayToggleBtn.addTarget(self, action: #selector(onTapOverlayToggle), for: .touchUpInside)
        saveBtn.addTarget(self, action: #selector(onTapSave), for: .touchUpInside)
        retakeBtn.addTarget(self, action: #selector(onTapRetake), for: .touchUpInside)
        closeBtn.addTarget(self, action: #selector(onTapClose), for: .touchUpInside)

        updateDetectedRangeNavigationUI()
        refreshSelectedRangeZoomControl(recenter: true)
        topHoldOverlayView.image = topHoldOverlayImage
        updateSaveButtonMode()

        let playerItem = AVPlayerItem(url: recordingURL)
        let player = AVPlayer(playerItem: playerItem)
        player.actionAtItemEnd = .pause
        player.automaticallyWaitsToMinimizeStalling = false
        // 카메라 캡처가 끝난 뒤 재생용 오디오 세션으로 전환합니다.
        // 전환에 실패하더라도 영상 타임라인은 진행하도록 음소거로 폴백합니다.
        player.isMuted = !prepareReviewAudioPlaybackIfPossible()

        let layer = AVPlayerLayer(player: player)
        layer.videoGravity = .resizeAspect
        view.layer.insertSublayer(layer, at: 0)
        self.player = player
        self.playerLayer = layer

        playerItemStatusObservation = playerItem.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            DispatchQueue.main.async {
                self?.handlePlayerItemStatus(item)
            }
        }

        playerTimeControlStatusObservation = player.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] player, _ in
            DispatchQueue.main.async {
                self?.handlePlayerTimeControlStatus(player.timeControlStatus)
            }
        }

        playbackEndNotificationObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: playerItem,
            queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            self.finishPlayback(at: self.selectedEnd)
        }

        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.05, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            self?.handlePlaybackTime(time)
        }

        updateOverlayToggleButton()
        updatePlayButton()
    }


    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !hasRequestedInitialPlayback else { return }
        hasRequestedInitialPlayback = true
        playFromCurrentPlayhead()
    }

    @objc private func onAppDidEnterBackground() {
        pausePlayback()
        (presentedViewController as? AVPlayerViewController)?.player?.pause()
    }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        .allButUpsideDown
    }


    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        playerLayer?.frame = view.bounds
        topHoldOverlayView.frame = view.bounds

        let safe = view.safeAreaInsets
        let w = view.bounds.width
        let h = view.bounds.height
        let isLandscape = w > h
        let margin: CGFloat = isLandscape ? 24 : 18
        let panelWidthLimit: CGFloat = isLandscape ? 980 : 430
        let panelWidth = min(w - margin * 2, panelWidthLimit)
        let panelX = (w - panelWidth) * 0.5
        let referenceActiveFrame = recordingAspectRatio.displayRect(in: view.bounds, isLandscape: isLandscape)
        let panelBottom: CGFloat = isLandscape
            ? h - safe.bottom - 4
            : portraitReviewPanelBottom(screenHeight: h, safeBottom: safe.bottom, activeFrame: referenceActiveFrame)

        if isLandscape {
            layoutLandscapeReviewControls(panelX: panelX, panelWidth: panelWidth, panelBottom: panelBottom)
        } else {
            layoutPortraitReviewControls(panelX: panelX, panelWidth: panelWidth, panelBottom: panelBottom)
        }
        positionSliderTimeBubbleIfVisible()

        let statusHeight: CGFloat = isLandscape ? 26 : 30
        let statusOffsetBelowTopGuideBaseline: CGFloat = 5
        let statusGap: CGFloat = 5 - statusOffsetBelowTopGuideBaseline
        let preferredStatusY = referenceActiveFrame.minY - statusHeight - statusGap
        let statusY = referenceActiveFrame.minY > statusHeight + statusGap
            ? max(CGFloat(4), preferredStatusY)
            : safe.top + 2 + statusOffsetBelowTopGuideBaseline
        statusLabel.frame = CGRect(x: margin, y: statusY, width: w - margin * 2, height: statusHeight)
    }

    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)

        coordinator.animate(alongsideTransition: { [weak self] _ in
            guard let self else { return }
            self.view.setNeedsLayout()
            self.view.layoutIfNeeded()
            self.trimSlider.setNeedsDisplay()
            self.positionSliderTimeBubbleIfVisible()
            self.normalizeReviewButtonContentLayout()
        }, completion: { [weak self] _ in
            guard let self else { return }
            self.view.setNeedsLayout()
            self.view.layoutIfNeeded()
            self.trimSlider.setNeedsDisplay()
            self.positionSliderTimeBubbleIfVisible()
            self.normalizeReviewButtonContentLayout()
        })
    }

    private func portraitReviewPanelBottom(screenHeight: CGFloat, safeBottom: CGFloat, activeFrame: CGRect) -> CGFloat {
        let secondRowButtonHeight: CGFloat = 42
        let blackAreaGap: CGFloat = 10
        let minimumBottomForBlackRow = activeFrame.maxY + blackAreaGap + secondRowButtonHeight

        // 기존보다 컨트롤 묶음을 아래로 내려서 9:16 영상 하단 검은 영역에 버튼 2줄이 안정적으로 들어오게 합니다.
        let loweredBottomMargin = max(CGFloat(16), safeBottom - 12)
        let loweredBottom = screenHeight - loweredBottomMargin
        let lowestSafeBottom = screenHeight - max(CGFloat(12), min(safeBottom * 0.35, CGFloat(18)))

        return min(lowestSafeBottom, max(loweredBottom, minimumBottomForBlackRow))
    }

    private func layoutPortraitReviewControls(panelX: CGFloat, panelWidth: CGFloat, panelBottom: CGFloat) {
        let buttonHeight: CGFloat = 42
        let buttonGap: CGFloat = 8
        let playWidth: CGFloat = 58
        rangeLabel.font = .monospacedDigitSystemFont(ofSize: 13, weight: .semibold)
        applyReviewButtonInsets(isLandscape: false)

        let secondRowY = panelBottom - buttonHeight
        let halfWidth = (panelWidth - buttonGap) * 0.5
        retakeBtn.frame = CGRect(
            x: panelX,
            y: secondRowY,
            width: halfWidth,
            height: buttonHeight
        )
        closeBtn.frame = CGRect(
            x: retakeBtn.frame.maxX + buttonGap,
            y: secondRowY,
            width: halfWidth,
            height: buttonHeight
        )

        let firstRowY = secondRowY - buttonGap - buttonHeight
        let textButtonWidth = Swift.max(96, (panelWidth - playWidth - buttonGap * 2) * 0.5)
        playPauseBtn.frame = CGRect(
            x: panelX,
            y: firstRowY,
            width: playWidth,
            height: buttonHeight
        )
        overlayToggleBtn.frame = CGRect(
            x: playPauseBtn.frame.maxX + buttonGap,
            y: firstRowY,
            width: textButtonWidth,
            height: buttonHeight
        )
        saveBtn.frame = CGRect(
            x: overlayToggleBtn.frame.maxX + buttonGap,
            y: firstRowY,
            width: panelX + panelWidth - overlayToggleBtn.frame.maxX - buttonGap,
            height: buttonHeight
        )

        let zoomSliderHeight: CGFloat = 18
        let zoomLabelHeight: CGFloat = 11
        let zoomSliderWidth = panelWidth * 0.72
        let zoomSliderX = panelX + (panelWidth - zoomSliderWidth) * 0.5
        let zoomSliderY = firstRowY - buttonGap - zoomSliderHeight - 3
        selectedRangeZoomSlider.frame = CGRect(x: zoomSliderX, y: zoomSliderY, width: zoomSliderWidth, height: zoomSliderHeight)
        selectedRangeZoomLabel.frame = CGRect(x: panelX, y: zoomSliderY - zoomLabelHeight, width: panelWidth * 0.72, height: zoomLabelHeight)
        selectedRangeZoomValueLabel.frame = CGRect(x: panelX + panelWidth * 0.72, y: zoomSliderY - zoomLabelHeight, width: panelWidth * 0.28, height: zoomLabelHeight)

        let trimToZoomGap: CGFloat = 0
        let primarySliderHeight: CGFloat = 60
        trimSlider.frame = CGRect(
            x: panelX,
            y: selectedRangeZoomLabel.frame.minY - trimToZoomGap - primarySliderHeight,
            width: panelWidth,
            height: primarySliderHeight
        )
        layoutDetectedRangeNavigation(above: trimSlider.frame, panelX: panelX, panelWidth: panelWidth, isLandscape: false)
        positionSliderTimeBubbleIfVisible()
        normalizeReviewButtonContentLayout()
    }

    private func layoutLandscapeReviewControls(panelX: CGFloat, panelWidth: CGFloat, panelBottom: CGFloat) {
        let buttonHeight: CGFloat = 38
        let gap: CGFloat = 8
        applyReviewButtonInsets(isLandscape: true)

        let buttonPanelWidth = min(panelWidth, CGFloat(760))
        let buttonPanelX = panelX + (panelWidth - buttonPanelWidth) * 0.5
        let availableWidth = Swift.max(0, buttonPanelWidth - gap * 4)
        let playWidth = min(CGFloat(58), max(CGFloat(50), availableWidth * 0.10))
        let retakeWidth = min(CGFloat(110), max(CGFloat(84), availableWidth * 0.17))
        let closeWidth = min(CGFloat(90), max(CGFloat(70), availableWidth * 0.14))
        let remainingWidth = Swift.max(0, availableWidth - playWidth - retakeWidth - closeWidth)
        let overlayWidth = min(CGFloat(170), max(CGFloat(126), remainingWidth * 0.46))
        let saveWidth = Swift.max(CGFloat(130), remainingWidth - overlayWidth)
        let buttonY = panelBottom - buttonHeight

        playPauseBtn.frame = CGRect(x: buttonPanelX, y: buttonY, width: playWidth, height: buttonHeight)
        overlayToggleBtn.frame = CGRect(x: playPauseBtn.frame.maxX + gap, y: buttonY, width: overlayWidth, height: buttonHeight)
        saveBtn.frame = CGRect(x: overlayToggleBtn.frame.maxX + gap, y: buttonY, width: saveWidth, height: buttonHeight)
        retakeBtn.frame = CGRect(x: saveBtn.frame.maxX + gap, y: buttonY, width: retakeWidth, height: buttonHeight)
        closeBtn.frame = CGRect(x: retakeBtn.frame.maxX + gap, y: buttonY, width: closeWidth, height: buttonHeight)

        let zoomSliderHeight: CGFloat = 16
        let zoomLabelHeight: CGFloat = 10
        let zoomSliderWidth = panelWidth * 0.68
        let zoomSliderX = panelX + (panelWidth - zoomSliderWidth) * 0.5
        let zoomSliderY = buttonY - gap - zoomSliderHeight - 3
        selectedRangeZoomSlider.frame = CGRect(x: zoomSliderX, y: zoomSliderY, width: zoomSliderWidth, height: zoomSliderHeight)
        selectedRangeZoomLabel.frame = CGRect(x: panelX, y: zoomSliderY - zoomLabelHeight, width: panelWidth * 0.72, height: zoomLabelHeight)
        selectedRangeZoomValueLabel.frame = CGRect(x: panelX + panelWidth * 0.72, y: zoomSliderY - zoomLabelHeight, width: panelWidth * 0.28, height: zoomLabelHeight)

        let trimToZoomGap: CGFloat = 0
        let primarySliderHeight: CGFloat = 58
        trimSlider.frame = CGRect(
            x: panelX,
            y: selectedRangeZoomLabel.frame.minY - trimToZoomGap - primarySliderHeight,
            width: panelWidth,
            height: primarySliderHeight
        )
        rangeLabel.font = .monospacedDigitSystemFont(ofSize: 13, weight: .semibold)
        layoutDetectedRangeNavigation(above: trimSlider.frame, panelX: panelX, panelWidth: panelWidth, isLandscape: true)
        positionSliderTimeBubbleIfVisible()
        normalizeReviewButtonContentLayout()
    }

    private func layoutDetectedRangeNavigation(above sliderFrame: CGRect, panelX: CGFloat, panelWidth: CGFloat, isLandscape: Bool) {
        let hasRanges = !detectedRanges.isEmpty
        let hasMultipleRanges = detectedRanges.count > 1
        let rowHeight: CGFloat = isLandscape ? 26 : 28
        let rowGap: CGFloat = isLandscape ? 4 : 5
        let buttonSize = rowHeight
        let labelAvailableWidth = panelWidth - (hasMultipleRanges ? (buttonSize + 8) * 2 : 0)
        let labelWidth = min(Swift.max(CGFloat(96), labelAvailableWidth), CGFloat(isLandscape ? 190 : 170))
        let totalWidth = hasMultipleRanges ? labelWidth + (buttonSize + 8) * 2 : labelWidth
        let rowX = panelX + (panelWidth - totalWidth) * 0.5
        let rowY = max(view.safeAreaInsets.top + 2, sliderFrame.minY - rowGap - rowHeight)

        detectedRangeLabel.frame = CGRect(
            x: hasMultipleRanges ? rowX + buttonSize + 8 : rowX,
            y: rowY,
            width: labelWidth,
            height: rowHeight
        )
        detectedRangeLabel.isHidden = !hasRanges

        if hasMultipleRanges {
            previousRangeBtn.frame = CGRect(x: rowX, y: rowY, width: buttonSize, height: buttonSize)
            nextRangeBtn.frame = CGRect(x: detectedRangeLabel.frame.maxX + 8, y: rowY, width: buttonSize, height: buttonSize)
        } else {
            previousRangeBtn.frame = .zero
            nextRangeBtn.frame = .zero
        }
    }

    private func applyReviewButtonInsets(isLandscape: Bool) {
        let verticalInset: CGFloat = isLandscape ? 7 : 8
        let horizontalInset: CGFloat = isLandscape ? 12 : 14

        for button in [playPauseBtn, overlayToggleBtn, saveBtn, retakeBtn, closeBtn] {
            var config = button.configuration
            config?.cornerStyle = .capsule
            if button === overlayToggleBtn {
                config?.titleAlignment = .center
                config?.imagePlacement = .leading
                config?.imagePadding = 3
                config?.contentInsets = NSDirectionalEdgeInsets(
                    top: verticalInset,
                    leading: 8,
                    bottom: verticalInset,
                    trailing: 8
                )
            } else {
                config?.titleAlignment = .center
                config?.contentInsets = NSDirectionalEdgeInsets(
                    top: verticalInset,
                    leading: horizontalInset,
                    bottom: verticalInset,
                    trailing: horizontalInset
                )
            }
            button.configuration = config
        }
        normalizeReviewButtonContentLayout()
    }

    private func normalizeReviewButtonContentLayout() {
        for button in [playPauseBtn, overlayToggleBtn, saveBtn, retakeBtn, closeBtn] {
            button.contentHorizontalAlignment = .center
            button.contentVerticalAlignment = .center
            button.titleLabel?.textAlignment = .center
            button.titleLabel?.numberOfLines = 1
            button.titleLabel?.lineBreakMode = .byTruncatingTail
            button.titleLabel?.adjustsFontSizeToFitWidth = true
            button.titleLabel?.minimumScaleFactor = button === overlayToggleBtn ? 0.74 : 0.78
            button.imageView?.contentMode = .scaleAspectFit
            button.setNeedsLayout()
            button.layoutIfNeeded()
        }
    }

    func setStatus(_ text: String) {
        statusLabel.text = text
        statusLabel.isHidden = false
    }

    func setVerifiedSavedAsset(
        localIdentifier: String,
        startSeconds: TimeInterval,
        endSeconds: TimeInterval,
        includeTopHoldOverlay: Bool
    ) {
        let selection = VerifiedSavedSelection(
            startSeconds: startSeconds,
            endSeconds: endSeconds,
            includeTopHoldOverlay: includeTopHoldOverlay
        )
        verifiedSavedAssets[selection] = localIdentifier
        updateSaveButtonMode()
    }

    private var currentVerifiedSavedAssetIdentifier: String? {
        verifiedSavedAssets.first { selection, _ in
            abs(selection.startSeconds - selectedStart) < 0.01 &&
                abs(selection.endSeconds - selectedEnd) < 0.01 &&
                selection.includeTopHoldOverlay == includeTopHoldOverlay
        }?.value
    }

    private var verifiedSavedSelectionMatchesCurrent: Bool {
        currentVerifiedSavedAssetIdentifier != nil
    }

    private func updateSaveButtonMode() {
        let matchesSavedSelection = verifiedSavedSelectionMatchesCurrent
        var config = saveBtn.configuration ?? UIButton.Configuration.filled()
        config.title = matchesSavedSelection ? "저장된 영상 확인" : "선택 구간 저장"
        saveBtn.configuration = config
        saveBtn.accessibilityLabel = matchesSavedSelection ? "갤러리에 저장된 영상 확인" : "선택 구간 저장"
        if !matchesSavedSelection {
            updateDetectedRangeNavigationUI()
        }
        normalizeReviewButtonContentLayout()
    }

    func setSaving(_ saving: Bool) {
        saveBtn.isEnabled = !saving
        if saving {
            var config = saveBtn.configuration ?? UIButton.Configuration.filled()
            config.title = "저장 중..."
            saveBtn.configuration = config
            saveBtn.accessibilityLabel = "저장 중..."
        } else {
            updateSaveButtonMode()
        }
        playPauseBtn.isEnabled = !saving
        overlayToggleBtn.isEnabled = !saving && topHoldOverlayImage != nil && !topHoldOverlayToggleLocked
        trimSlider.isEnabled = !saving
        selectedRangeZoomSlider.isEnabled = !saving && trimSlider.maximumAllowedSelectedRangeZoomFactor > 1.01
        selectedRangeZoomLabel.alpha = saving ? 0.45 : 1.0
        selectedRangeZoomValueLabel.alpha = saving ? 0.45 : 1.0
        retakeBtn.isEnabled = !saving
        closeBtn.isEnabled = !saving
        if !saving {
            updateDetectedRangeNavigationUI()
        } else {
            previousRangeBtn.isEnabled = false
            nextRangeBtn.isEnabled = false
        }
    }

    private func refreshSelectedRangeZoomControl(recenter: Bool) {
        let currentZoom = trimSlider.currentSelectedRangeZoomFactor
        let selectionMaximumZoom = trimSlider.maximumAllowedSelectedRangeZoomFactor
        let sliderMaximumZoom = Swift.max(currentZoom, selectionMaximumZoom)
        selectedRangeZoomSlider.minimumValue = 1
        selectedRangeZoomSlider.maximumValue = Float(Swift.max(1.0001, sliderMaximumZoom))

        let appliedZoom: Double
        if recenter {
            appliedZoom = trimSlider.recenterSelectedRangePreservingCurrentZoom()
        } else {
            appliedZoom = currentZoom
        }

        selectedRangeZoomSlider.value = Float(appliedZoom)
        selectedRangeZoomSlider.isEnabled = trimSlider.isEnabled && sliderMaximumZoom > 1.01
        selectedRangeZoomValueLabel.text = String(format: "%.1f×", appliedZoom)
        selectedRangeZoomSlider.accessibilityValue = selectedRangeZoomValueLabel.text
        if recenter { positionSliderTimeBubbleIfVisible() }
    }

    @objc private func onSelectedRangeZoomTouchDown() {
        pausePlayback()
    }

    @objc private func onSelectedRangeZoomChanged() {
        pausePlayback()
        let appliedZoom = trimSlider.setSelectedRangeZoomFactor(Double(selectedRangeZoomSlider.value))
        selectedRangeZoomSlider.value = Float(appliedZoom)
        selectedRangeZoomValueLabel.text = String(format: "%.1f×", appliedZoom)
        selectedRangeZoomSlider.accessibilityValue = selectedRangeZoomValueLabel.text
        positionSliderTimeBubbleIfVisible()
    }

    @objc private func onSelectedRangeZoomTouchEnded() {
        refreshSelectedRangeZoomControl(recenter: true)
    }

    private func handleRangeChanged(lower: TimeInterval, upper: TimeInterval, activeHandle: TrimRangeSlider.ActiveHandle) {
        selectedStart = lower
        selectedEnd = upper
        updateSaveButtonMode()

        switch activeHandle {
        case .lower:
            playhead = selectedStart
        case .upper:
            playhead = selectedEnd
        }

        trimSlider.playheadValue = playhead
        refreshSelectedRangeZoomControl(recenter: false)
        showSliderTimeBubble(for: playhead)
        pausePlayback()
        seek(to: playhead)
    }

    private func handlePlayheadChanged(_ value: TimeInterval) {
        playhead = clamped(value, min: selectedStart, max: selectedEnd)
        trimSlider.playheadValue = playhead
        showSliderTimeBubble(for: playhead)
        pausePlayback()
        seek(to: playhead)
    }

    private func resumePlaybackAfterTrimEdit(activeHandle: TrimRangeSlider.ActiveHandle) {
        pausePlayback()
        switch activeHandle {
        case .lower:
            playhead = selectedStart
        case .upper:
            playhead = Swift.max(
                selectedStart,
                selectedEnd - Cfg.reviewEndPreviewLeadSeconds
            )
        }
        trimSlider.playheadValue = playhead
        trimSlider.ensureValueVisible(playhead)
        showSliderTimeBubble(for: playhead)
        playFromCurrentPlayhead()
    }

    private func showSliderTimeBubble(for seconds: TimeInterval) {
        let visibleSeconds = clamped(seconds, min: 0, max: duration)
        rangeLabel.text = formatSecondsBubble(visibleSeconds)
        rangeLabel.isHidden = false
        rangeLabel.alpha = 1
        positionSliderTimeBubble(for: visibleSeconds)
        scheduleHideSliderTimeBubble(after: 0.95)
    }

    private func positionSliderTimeBubbleIfVisible() {
        guard !rangeLabel.isHidden else { return }
        positionSliderTimeBubble(for: playhead)
    }

    private func positionSliderTimeBubble(for seconds: TimeInterval) {
        guard trimSlider.bounds.width > 0 else { return }

        let bubbleHeight: CGFloat = 28
        let maximumBubbleWidth: CGFloat = 92
        let fittingSize = rangeLabel.sizeThatFits(CGSize(width: maximumBubbleWidth, height: bubbleHeight))
        let bubbleWidth = Swift.max(CGFloat(56), Swift.min(maximumBubbleWidth, fittingSize.width + 18))
        let anchorX = trimSlider.frame.minX + trimSlider.xPositionForValue(seconds)
        let minX = view.safeAreaInsets.left + 8
        let maxX = view.bounds.width - view.safeAreaInsets.right - 8 - bubbleWidth
        let bubbleX = Swift.max(minX, Swift.min(anchorX - bubbleWidth / 2, maxX))
        let preferredBubbleY = detectedRangeLabel.isHidden
            ? trimSlider.frame.minY - bubbleHeight - 6
            : detectedRangeLabel.frame.minY - bubbleHeight - 4
        let bubbleY = Swift.max(view.safeAreaInsets.top + 4, preferredBubbleY)

        rangeLabel.frame = CGRect(x: bubbleX, y: bubbleY, width: bubbleWidth, height: bubbleHeight)
    }

    private func scheduleHideSliderTimeBubble(after delay: TimeInterval) {
        sliderTimeBubbleHideWorkItem?.cancel()
        sliderTimeBubbleHideGeneration &+= 1
        let generation = sliderTimeBubbleHideGeneration
        let workItem = DispatchWorkItem { [weak self] in
            guard let self, self.sliderTimeBubbleHideGeneration == generation else { return }
            UIView.animate(withDuration: 0.18, animations: {
                self.rangeLabel.alpha = 0
            }, completion: { _ in
                guard self.sliderTimeBubbleHideGeneration == generation else { return }
                self.rangeLabel.isHidden = true
                self.sliderTimeBubbleHideWorkItem = nil
            })
        }
        sliderTimeBubbleHideWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: workItem)
    }

    @objc private func onTapPreviousRange() {
        guard let current = selectedDetectedRangeIndex, current > 0 else { return }
        applyDetectedRange(at: current - 1)
    }

    @objc private func onTapNextRange() {
        guard let current = selectedDetectedRangeIndex, current < detectedRanges.count - 1 else { return }
        applyDetectedRange(at: current + 1)
    }

    private func applyDetectedRange(at index: Int) {
        guard detectedRanges.indices.contains(index) else { return }
        pausePlayback()
        selectedDetectedRangeIndex = index
        let range = detectedRanges[index]
        selectedStart = clamped(range.startSeconds, min: 0, max: duration)
        selectedEnd = clamped(range.endSeconds, min: selectedStart + 0.1, max: duration)
        updateSaveButtonMode()
        playhead = selectedStart
        trimSlider.lowerValue = selectedStart
        trimSlider.upperValue = selectedEnd
        trimSlider.playheadValue = playhead
        refreshSelectedRangeZoomControl(recenter: true)
        updateDetectedRangeNavigationUI()
        showSliderTimeBubble(for: playhead)
        playFromCurrentPlayhead()
    }

    private func updateDetectedRangeNavigationUI() {
        if helpEnabled {
            statusLabel.text = autoClipDetectionEnabled && !detectedRanges.isEmpty
                ? "자동 감지된 시작~완등 구간을 확인해주세요"
                : "저장할 구간을 직접 선택해주세요"
        }

        if let index = selectedDetectedRangeIndex, detectedRanges.indices.contains(index) {
            detectedRangeLabel.text = "감지 구간 \(index + 1)/\(detectedRanges.count)"
        } else {
            detectedRangeLabel.text = nil
        }

        let hasRanges = !detectedRanges.isEmpty
        let shouldShowRangeNavigation = detectedRanges.count > 1
        detectedRangeLabel.isHidden = !hasRanges
        previousRangeBtn.isHidden = !shouldShowRangeNavigation
        nextRangeBtn.isHidden = !shouldShowRangeNavigation
        statusLabel.isHidden = !helpEnabled

        if let index = selectedDetectedRangeIndex, shouldShowRangeNavigation {
            previousRangeBtn.isEnabled = index > 0
            nextRangeBtn.isEnabled = index < detectedRanges.count - 1
            previousRangeBtn.alpha = previousRangeBtn.isEnabled ? 1.0 : 0.45
            nextRangeBtn.alpha = nextRangeBtn.isEnabled ? 1.0 : 0.45
        }
    }

    @objc private func onTapPlayPause() {
        // 내부 플래그가 아니라 실제 재생 상태와 대기 중인 재생 요청을 함께 확인합니다.
        // 재생 준비 중에 다시 누르면 요청을 취소할 수 있고, 멈춰 있다면 항상 재생을 시도합니다.
        if isPlaying || pendingPlaybackStart {
            pausePlayback()
            return
        }

        if playhead >= selectedEnd - 0.03 {
            playhead = selectedStart
            trimSlider.playheadValue = playhead
        }
        playFromCurrentPlayhead()
    }

    @objc private func onTapOverlayToggle() {
        guard topHoldOverlayImage != nil, !topHoldOverlayToggleLocked else { return }
        includeTopHoldOverlay.toggle()
        updateOverlayToggleButton()
        updateSaveButtonMode()
    }

    @objc private func onTapSave() {
        if let localIdentifier = currentVerifiedSavedAssetIdentifier {
            presentVerifiedSavedVideo(localIdentifier: localIdentifier)
            return
        }
        onSaveTrim?(selectedStart, selectedEnd, includeTopHoldOverlay)
    }

    private func presentVerifiedSavedVideo(localIdentifier: String) {
        pausePlayback()
        setStatus("갤러리에서 저장된 영상을 불러오는 중...")

        let result = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil)
        guard let asset = result.firstObject, asset.mediaType == .video else {
            setStatus("갤러리에 저장된 영상을 다시 찾지 못했습니다")
            return
        }

        let options = PHVideoRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = true
        PHImageManager.default().requestAVAsset(forVideo: asset, options: options) { [weak self] avAsset, _, info in
            DispatchQueue.main.async {
                guard let self else { return }
                guard let avAsset else {
                    let error = info?[PHImageErrorKey] as? Error
                    self.setStatus("저장된 영상 확인 실패: \(error?.localizedDescription ?? "영상을 열 수 없습니다")")
                    return
                }

                let playerController = AVPlayerViewController()
                playerController.player = AVPlayer(playerItem: AVPlayerItem(asset: avAsset))
                self.setStatus("갤러리에서 불러온 영상을 재생합니다")
                self.present(playerController, animated: true) {
                    playerController.player?.play()
                }
            }
        }
    }

    @objc private func onTapRetake() {
        confirmDiscardUnsavedClipIfNeeded(
            message: "기존의 매트경계 위치를 유지하여 다시 촬영합니다.\n저장하지 않은 영상이 삭제됩니다.",
            compactMessage: true
        ) { [weak self] in
            self?.dismissForRetake()
        }
    }

    @objc private func onTapClose() {
        confirmDiscardUnsavedClipIfNeeded(
            message: "저장하지 않은 영상이 삭제됩니다."
        ) { [weak self] in
            self?.dismissForClose()
        }
    }

    private func confirmDiscardUnsavedClipIfNeeded(
        message warningMessage: String,
        compactMessage: Bool = false,
        _ confirmedAction: @escaping () -> Void
    ) {
        pausePlayback()

        guard helpEnabled else {
            confirmedAction()
            return
        }

        let alert = UIAlertController(
            title: nil,
            message: warningMessage,
            preferredStyle: .alert
        )

        alert.addAction(UIAlertAction(title: "취소", style: .cancel))
        alert.addAction(UIAlertAction(title: "확인", style: .destructive) { _ in
            confirmedAction()
        })
        present(alert, animated: true) {
            guard compactMessage else { return }
            alert.applyCompactMessageFont(matching: warningMessage)
        }
        if compactMessage {
            // UIAlertController creates its private message label during presentation.
            // The next run-loop applies the style before the first settled frame; the
            // presentation completion above is a fallback for slower OS versions.
            DispatchQueue.main.async {
                alert.applyCompactMessageFont(matching: warningMessage)
            }
        }
    }

    private func dismissForRetake() {
        dismiss(animated: true) { [weak self] in
            self?.onRetake?()
        }
    }

    private func dismissForClose() {
        dismiss(animated: true) { [weak self] in
            self?.onClose?()
        }
    }

    private func prepareReviewAudioPlaybackIfPossible() -> Bool {
        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(
                .playback,
                mode: .moviePlayback,
                options: [.mixWithOthers]
            )
            try audioSession.setActive(true)
            return true
        } catch {
            // 통화 등으로 오디오 장치를 점유한 상태에서는 소리만 포기하고
            // AVPlayer의 영상 타임라인은 정상적으로 진행시킵니다.
            return false
        }
    }

    private func handlePlayerItemStatus(_ item: AVPlayerItem) {
        switch item.status {
        case .readyToPlay:
            if pendingPlaybackStart {
                playFromCurrentPlayhead()
            } else {
                updatePlayButton()
            }
        case .failed:
            pendingPlaybackStart = false
            isPlaying = false
            updatePlayButton()
            if helpEnabled {
                let detail = item.error?.localizedDescription ?? "녹화 파일을 불러오지 못했습니다"
                statusLabel.text = "영상을 재생할 수 없습니다: \(detail)"
                statusLabel.isHidden = false
            }
        case .unknown:
            updatePlayButton()
        @unknown default:
            updatePlayButton()
        }
    }

    private func handlePlayerTimeControlStatus(_ status: AVPlayer.TimeControlStatus) {
        switch status {
        case .playing:
            pendingPlaybackStart = false
            isPlaying = true
        case .paused:
            isPlaying = false
        case .waitingToPlayAtSpecifiedRate:
            isPlaying = false
        @unknown default:
            isPlaying = false
        }
        updatePlayButton()
    }

    private func playFromCurrentPlayhead() {
        pendingPlaybackStart = true
        updatePlayButton()

        guard let player,
              let item = player.currentItem else {
            pendingPlaybackStart = false
            updatePlayButton()
            return
        }

        guard item.status == .readyToPlay else {
            // readyToPlay KVO가 도착하면 같은 요청을 이어서 실행합니다.
            return
        }

        playbackRequestID += 1
        let requestID = playbackRequestID
        let rangeStart = selectedStart
        let rangeEnd = resolvedPlaybackEnd(for: item)

        guard rangeEnd > rangeStart + 0.01 else {
            pendingPlaybackStart = false
            updatePlayButton()
            if helpEnabled {
                statusLabel.text = "재생할 수 있는 영상 길이가 없습니다"
                statusLabel.isHidden = false
            }
            return
        }

        let targetSeconds = clamped(playhead, min: rangeStart, max: rangeEnd)
        let target = CMTime(seconds: targetSeconds, preferredTimescale: 600)
        let seekTolerance = CMTime(seconds: 0.03, preferredTimescale: 600)

        activePlaybackStart = targetSeconds
        activePlaybackEnd = rangeEnd
        isWaitingForFirstPlaybackTimestamp = true

        player.pause()
        item.cancelPendingSeeks()
        item.forwardPlaybackEndTime = .invalid
        player.seek(
            to: target,
            toleranceBefore: seekTolerance,
            toleranceAfter: seekTolerance
        ) { [weak self, weak player] finished in
            DispatchQueue.main.async {
                guard let self,
                      let player,
                      self.pendingPlaybackStart,
                      self.playbackRequestID == requestID,
                      abs(self.selectedStart - rangeStart) < 0.001 else { return }

                // 로컬 녹화 파일에서도 다른 seek와 순간적으로 겹치면 finished가 false로
                // 올 수 있습니다. 현재 요청이 여전히 유효하면 넓은 기본 seek로 폴백하고
                // 재생 명령을 반드시 전달합니다.
                if !finished {
                    player.seek(to: target)
                }
                player.playImmediately(atRate: 1.0)
            }
        }
    }

    private func resolvedPlaybackEnd(for item: AVPlayerItem) -> TimeInterval {
        let itemDuration = CMTimeGetSeconds(item.duration)
        guard itemDuration.isFinite, itemDuration > 0 else { return selectedEnd }
        return clamped(selectedEnd, min: selectedStart, max: itemDuration)
    }

    private func pausePlayback() {
        pendingPlaybackStart = false
        playbackRequestID += 1
        player?.currentItem?.cancelPendingSeeks()
        player?.pause()
        player?.currentItem?.forwardPlaybackEndTime = .invalid
        activePlaybackStart = nil
        activePlaybackEnd = nil
        isWaitingForFirstPlaybackTimestamp = false
        isPlaying = false
        updatePlayButton()
    }

    private func finishPlayback(at endSeconds: TimeInterval) {
        let safeEnd = clamped(endSeconds, min: selectedStart, max: selectedEnd)
        playhead = safeEnd
        trimSlider.playheadValue = playhead
        trimSlider.ensureValueVisible(playhead)
        pausePlayback()
        seek(to: safeEnd)
    }

    private func seek(to seconds: TimeInterval, completion: (() -> Void)? = nil) {
        guard let player else {
            completion?()
            return
        }

        player.currentItem?.cancelPendingSeeks()
        let target = CMTime(seconds: clamped(seconds, min: 0, max: duration), preferredTimescale: 600)
        let seekTolerance = CMTime(seconds: 0.03, preferredTimescale: 600)
        player.seek(to: target, toleranceBefore: seekTolerance, toleranceAfter: seekTolerance) { _ in
            DispatchQueue.main.async { completion?() }
        }
    }

    private func handlePlaybackTime(_ time: CMTime) {
        guard isPlaying || pendingPlaybackStart else { return }

        let current = CMTimeGetSeconds(time)
        guard current.isFinite else { return }

        let playbackStart = activePlaybackStart ?? selectedStart
        let playbackEnd = activePlaybackEnd ?? selectedEnd
        let callbackTolerance: TimeInterval = 0.12

        if isWaitingForFirstPlaybackTimestamp {
            // 새 seek 전에 예약된 이전 위치 콜백은 무시합니다.
            guard current >= playbackStart - callbackTolerance,
                  current <= playbackEnd + callbackTolerance else { return }
            isWaitingForFirstPlaybackTimestamp = false
        }

        if current >= playbackEnd - 0.015 {
            finishPlayback(at: playbackEnd)
            return
        }

        guard current >= playbackStart - callbackTolerance,
              current <= playbackEnd + callbackTolerance else { return }

        playhead = clamped(current, min: selectedStart, max: selectedEnd)
        trimSlider.playheadValue = playhead
        trimSlider.ensureValueVisible(playhead)
    }

    private func updatePlayButton() {
        let showsPause = isPlaying || pendingPlaybackStart
        var config = playPauseBtn.configuration ?? UIButton.Configuration.gray()
        config.cornerStyle = .capsule
        config.titleAlignment = .center
        config.image = UIImage(systemName: showsPause ? "pause.fill" : "play.fill")
        config.baseBackgroundColor = nil
        config.baseForegroundColor = nil
        playPauseBtn.configuration = config
        playPauseBtn.accessibilityLabel = showsPause ? "일시정지" : "재생"
        applyReviewButtonInsets(isLandscape: view.bounds.width > view.bounds.height)
    }

    private func updateOverlayToggleButton() {
        let hasOverlay = topHoldOverlayImage != nil
        overlayToggleBtn.isEnabled = hasOverlay && !topHoldOverlayToggleLocked
        overlayToggleBtn.alpha = hasOverlay ? (topHoldOverlayToggleLocked ? 0.75 : 1.0) : 0.45
        topHoldOverlayView.isHidden = !(hasOverlay && includeTopHoldOverlay)

        var config = includeTopHoldOverlay
            ? UIButton.Configuration.filled()
            : UIButton.Configuration.gray()
        let titleText = topHoldOverlayToggleLocked ? "탑홀드 마스크 고정" : "탑홀드 마스크"
        var title = AttributedString(titleText)
        title.font = .systemFont(ofSize: topHoldOverlayToggleLocked ? 12 : 13, weight: .semibold)
        config.title = nil
        config.attributedTitle = title
        config.cornerStyle = .capsule
        if topHoldOverlayToggleLocked {
            config.image = UIImage(systemName: "lock.fill")
        } else {
            config.image = UIImage(systemName: includeTopHoldOverlay ? "checkmark.square.fill" : "square")
        }
        config.imagePlacement = .leading
        config.imagePadding = 3
        config.titleAlignment = .center
        let isLandscape = view.bounds.width > view.bounds.height
        config.contentInsets = NSDirectionalEdgeInsets(
            top: isLandscape ? 7 : 8,
            leading: isLandscape ? 8 : 8,
            bottom: isLandscape ? 7 : 8,
            trailing: isLandscape ? 8 : 8
        )
        if includeTopHoldOverlay {
            config.baseForegroundColor = .white
        }
        overlayToggleBtn.configuration = config
        overlayToggleBtn.titleLabel?.font = .systemFont(ofSize: topHoldOverlayToggleLocked ? 12 : 13, weight: .semibold)
        normalizeReviewButtonContentLayout()
        if topHoldOverlayToggleLocked {
            overlayToggleBtn.accessibilityLabel = "자동 구간 탐지로 탑홀드 마스크 포함 고정"
        } else {
            overlayToggleBtn.accessibilityLabel = includeTopHoldOverlay ? "탑홀드 마스크 포함" : "탑홀드 마스크 미포함"
        }
    }

    private func formatSecondsBubble(_ seconds: TimeInterval) -> String {
        String(format: "%.1f초", Swift.max(0, seconds))
    }

    private func clamped(_ value: TimeInterval, min lower: TimeInterval, max upper: TimeInterval) -> TimeInterval {
        Swift.max(lower, Swift.min(value, upper))
    }

    deinit {
        sliderTimeBubbleHideWorkItem?.cancel()
        NotificationCenter.default.removeObserver(self)
        playerItemStatusObservation?.invalidate()
        playerTimeControlStatusObservation?.invalidate()
        if let playbackEndNotificationObserver {
            NotificationCenter.default.removeObserver(playbackEndNotificationObserver)
        }
        if let timeObserver {
            player?.removeTimeObserver(timeObserver)
        }
        player?.pause()
    }
}

// MARK: - Range Slider with Two Trim Handles + Playhead

private final class TrimRangeSlider: UIControl {
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
            // The duration is configured once during ReviewVC setup. Start with the
            // entire timeline visible rather than an accidentally zoomed initial window.
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
    // 시각적 크기는 줄이되 터치 판정 영역은 넉넉하게 유지합니다.
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
        accessibilityLabel = "영상 구간 타임라인"
        accessibilityHint = "시작점, 종료점 또는 재생 위치를 한 손가락으로 조정합니다"
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
            // 트랙의 빈 영역을 누른 경우에는 해당 위치로 즉시 이동합니다.
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
