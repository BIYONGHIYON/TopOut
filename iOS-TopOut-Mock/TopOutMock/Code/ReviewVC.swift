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
        label.text = L("review.auto_prompt")
        label.textColor = .white
        label.textAlignment = .center
        label.font = .systemFont(ofSize: 14, weight: .semibold)
        label.backgroundColor = .clear
        label.layer.shadowColor = UIColor.black.cgColor
        label.layer.shadowOpacity = 0.75
        label.layer.shadowRadius = 3
        label.layer.shadowOffset = CGSize(width: 0, height: 1)
        label.clipsToBounds = false
        label.numberOfLines = 1
        label.lineBreakMode = .byClipping
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.62
        label.allowsDefaultTighteningForTruncation = true
        return label
    }()

    private let previousRangeBtn: UIButton = {
        var config = UIButton.Configuration.gray()
        config.cornerStyle = .capsule
        config.image = UIImage(systemName: "chevron.left")
        config.contentInsets = NSDirectionalEdgeInsets(top: 6, leading: 10, bottom: 6, trailing: 10)
        let btn = UIButton(configuration: config)
        btn.accessibilityLabel = L("review.previous_range")
        btn.isHidden = true
        return btn
    }()

    private let nextRangeBtn: UIButton = {
        var config = UIButton.Configuration.gray()
        config.cornerStyle = .capsule
        config.image = UIImage(systemName: "chevron.right")
        config.contentInsets = NSDirectionalEdgeInsets(top: 6, leading: 10, bottom: 6, trailing: 10)
        let btn = UIButton(configuration: config)
        btn.accessibilityLabel = L("review.next_range")
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
        label.text = L("review.selected_zoom")
        label.textColor = UIColor.white.withAlphaComponent(0.90)
        label.font = .systemFont(ofSize: 11, weight: .semibold)
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.68
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
        slider.accessibilityLabel = L("review.selected_zoom")
        slider.accessibilityHint = L("review.selected_zoom_hint")
        return slider
    }()

    private let playPauseBtn: UIButton = {
        var config = UIButton.Configuration.gray()
        config.cornerStyle = .capsule
        config.image = UIImage(systemName: "play.fill")
        config.contentInsets = NSDirectionalEdgeInsets(top: 7, leading: 14, bottom: 7, trailing: 14)
        let btn = UIButton(configuration: config)
        btn.accessibilityLabel = L("review.play_pause")
        return btn
    }()

    private let overlayToggleBtn: UIButton = {
        var config = UIButton.Configuration.gray()
        config.cornerStyle = .capsule
        var title = AttributedString(L("review.top_hold_mask"))
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
        btn.accessibilityLabel = L("review.top_hold_mask_option")
        return btn
    }()

    private let saveBtn: UIButton = {
        var config = UIButton.Configuration.filled()
        config.title = L("review.save_clip")
        config.cornerStyle = .capsule
        config.titleAlignment = .center
        config.titleLineBreakMode = .byWordWrapping
        config.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16)
        let btn = UIButton(configuration: config)
        btn.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        btn.titleLabel?.numberOfLines = 2
        btn.titleLabel?.textAlignment = .center
        btn.titleLabel?.lineBreakMode = .byWordWrapping
        btn.titleLabel?.adjustsFontSizeToFitWidth = true
        btn.titleLabel?.minimumScaleFactor = 0.64
        return btn
    }()

    private let retakeBtn: UIButton = {
        var config = UIButton.Configuration.gray()
        config.title = L("camera.retake")
        config.cornerStyle = .capsule
        config.titleAlignment = .center
        config.titleLineBreakMode = .byWordWrapping
        config.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16)
        let btn = UIButton(configuration: config)
        btn.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        btn.titleLabel?.numberOfLines = 2
        btn.titleLabel?.textAlignment = .center
        btn.titleLabel?.lineBreakMode = .byWordWrapping
        btn.titleLabel?.adjustsFontSizeToFitWidth = true
        btn.titleLabel?.minimumScaleFactor = 0.78
        return btn
    }()

    private let closeBtn: UIButton = {
        var config = UIButton.Configuration.gray()
        config.title = L("common.close")
        config.cornerStyle = .capsule
        config.titleAlignment = .center
        config.titleLineBreakMode = .byWordWrapping
        config.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16)
        let btn = UIButton(configuration: config)
        btn.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        btn.titleLabel?.numberOfLines = 2
        btn.titleLabel?.textAlignment = .center
        btn.titleLabel?.lineBreakMode = .byWordWrapping
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
        // 기기 화면 모드와 관계없이 결과 화면은 어두운 색상을 사용한다.
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
        // 전환은 주 스레드를 막지 않도록 오디오 세션 큐에서 진행하고,
        // 실패하더라도 영상 타임라인은 진행하도록 음소거로 폴백합니다.
        AudioSessionQueue.activateForPlayback { [weak player] activated in
            player?.isMuted = !activated
        }

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
        let margin: CGFloat = isLandscape ? 12 : 18
        let panelWidthLimit: CGFloat = isLandscape ? 980 : 430
        let availablePanelWidth = max(0, w - safe.left - safe.right - margin * 2)
        let panelWidth = min(availablePanelWidth, panelWidthLimit)
        let panelX = safe.left + margin + (availablePanelWidth - panelWidth) * 0.5
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

        let statusHeight: CGFloat = isLandscape ? 30 : 32
        let statusOffsetBelowTopGuideBaseline: CGFloat = 4
        let statusGap: CGFloat = 5 - statusOffsetBelowTopGuideBaseline
        let preferredStatusY = referenceActiveFrame.minY - statusHeight - statusGap
        let statusY = referenceActiveFrame.minY > statusHeight + statusGap
            ? max(CGFloat(4), preferredStatusY)
            : safe.top + 2 + statusOffsetBelowTopGuideBaseline
        statusLabel.frame = CGRect(
            x: safe.left + margin,
            y: statusY,
            width: availablePanelWidth,
            height: statusHeight
        )
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
        let verticalInset: CGFloat = isLandscape ? 4 : 5
        let horizontalInset: CGFloat = isLandscape ? 7 : 9

        for button in [playPauseBtn, overlayToggleBtn, saveBtn, retakeBtn, closeBtn] {
            var config = button.configuration
            config?.cornerStyle = .capsule
            if button === overlayToggleBtn {
                config?.titleAlignment = .center
                config?.titleLineBreakMode = .byWordWrapping
                config?.imagePlacement = .leading
                config?.imagePadding = 3
                config?.contentInsets = NSDirectionalEdgeInsets(
                    top: verticalInset,
                    leading: 6,
                    bottom: verticalInset,
                    trailing: 6
                )
            } else {
                config?.titleAlignment = .center
                config?.titleLineBreakMode = .byWordWrapping
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
            button.titleLabel?.numberOfLines = 2
            button.titleLabel?.lineBreakMode = .byWordWrapping
            button.titleLabel?.adjustsFontSizeToFitWidth = true
            button.titleLabel?.minimumScaleFactor = button === saveBtn ? 0.64 : 0.68
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
        config.title = matchesSavedSelection ? L("review.view_saved") : L("review.save_clip")
        saveBtn.configuration = config
        saveBtn.accessibilityLabel = matchesSavedSelection ? L("review.view_saved") : L("review.save_clip")
        if !matchesSavedSelection {
            updateDetectedRangeNavigationUI()
        }
        normalizeReviewButtonContentLayout()
    }

    func setSaving(_ saving: Bool) {
        saveBtn.isEnabled = !saving
        if saving {
            var config = saveBtn.configuration ?? UIButton.Configuration.filled()
            config.title = L("review.saving")
            saveBtn.configuration = config
            saveBtn.accessibilityLabel = L("review.saving")
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
        selectedRangeZoomValueLabel.text = String(
            format: "%.1f×",
            locale: AppLanguageManager.locale,
            appliedZoom
        )
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
        selectedRangeZoomValueLabel.text = String(
            format: "%.1f×",
            locale: AppLanguageManager.locale,
            appliedZoom
        )
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
                ? L("review.auto_prompt")
                : L("review.manual_prompt")
        }

        if let index = selectedDetectedRangeIndex, detectedRanges.indices.contains(index) {
            detectedRangeLabel.text = L("review.detected_range", index + 1, detectedRanges.count)
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
        setStatus(L("review.loading_gallery"))

        let result = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil)
        guard let asset = result.firstObject, asset.mediaType == .video else {
            setStatus(L("error.find_saved_video"))
            return
        }

        let options = PHVideoRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = true
        PHImageManager.default().requestAVAsset(forVideo: asset, options: options) { [weak self] avAsset, _, info in
            DispatchQueue.main.async {
                guard let self else { return }
                guard let avAsset else {
                    self.setStatus(L("error.verify_saved_video"))
                    return
                }

                let playerController = AVPlayerViewController()
                playerController.player = AVPlayer(playerItem: AVPlayerItem(asset: avAsset))
                self.setStatus(L("review.gallery_playing"))
                self.present(playerController, animated: true) {
                    playerController.player?.play()
                }
            }
        }
    }

    @objc private func onTapRetake() {
        confirmDiscardUnsavedClipIfNeeded(
            message: L("review.retake_warning"),
            compactMessage: true
        ) { [weak self] in
            self?.dismissForRetake()
        }
    }

    @objc private func onTapClose() {
        confirmDiscardUnsavedClipIfNeeded(
            message: L("review.close_warning")
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

        alert.addAction(UIAlertAction(title: L("common.cancel"), style: .cancel))
        alert.addAction(UIAlertAction(title: L("common.ok"), style: .destructive) { _ in
            confirmedAction()
        })
        present(alert, animated: true) {
            guard compactMessage else { return }
            alert.applyCompactMessageFont(matching: warningMessage)
        }
        if compactMessage {
            // 경고창 본문이 생성된 다음 화면 주기에 글자 크기를 적용한다.
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
                statusLabel.text = L("error.play_video")
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
            // 재생 준비 알림이 오면 같은 요청을 이어서 실행한다.
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
                statusLabel.text = L("error.no_playable_duration")
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
        playPauseBtn.accessibilityLabel = showsPause ? L("review.pause") : L("review.play")
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
        let titleText = topHoldOverlayToggleLocked ? L("review.top_hold_mask_locked") : L("review.top_hold_mask")
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
        config.titleLineBreakMode = .byWordWrapping
        let isLandscape = view.bounds.width > view.bounds.height
        config.contentInsets = NSDirectionalEdgeInsets(
            top: isLandscape ? 4 : 5,
            leading: 6,
            bottom: isLandscape ? 4 : 5,
            trailing: 6
        )
        if includeTopHoldOverlay {
            config.baseForegroundColor = .white
        }
        overlayToggleBtn.configuration = config
        overlayToggleBtn.titleLabel?.font = .systemFont(ofSize: topHoldOverlayToggleLocked ? 12 : 13, weight: .semibold)
        normalizeReviewButtonContentLayout()
        if topHoldOverlayToggleLocked {
            overlayToggleBtn.accessibilityLabel = L("review.top_hold_mask_locked")
        } else {
            overlayToggleBtn.accessibilityLabel = includeTopHoldOverlay
                ? L("review.top_hold_mask_include")
                : L("review.top_hold_mask_exclude")
        }
    }

    private func formatSecondsBubble(_ seconds: TimeInterval) -> String {
        L(
            "time.seconds_short",
            String(
                format: "%.1f",
                locale: AppLanguageManager.locale,
                Swift.max(0, seconds)
            )
        )
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
