import UIKit
import AVFoundation
import AVKit
import Photos

final class ReviewVC: UIViewController {
    struct VerifiedSavedSelection: Hashable {
        let startSeconds: TimeInterval
        let endSeconds: TimeInterval
        let includeTopHoldOverlay: Bool
    }

    private let recordingURL: URL
    let duration: TimeInterval
    let topHoldOverlayImage: UIImage?
    let topHoldOverlayToggleLocked: Bool
    let autoClipDetectionEnabled: Bool
    let helpEnabled: Bool
    private let recordingAspectRatio: RecordingAspectRatio
    let detectedRanges: [ReviewClipRange]

    var selectedStart: TimeInterval
    var selectedEnd: TimeInterval
    var playhead: TimeInterval
    var isPlaying = false
    var includeTopHoldOverlay: Bool
    var selectedDetectedRangeIndex: Int?
    var sliderTimeBubbleHideWorkItem: DispatchWorkItem?
    var sliderTimeBubbleHideGeneration = 0
    var verifiedSavedAssets: [VerifiedSavedSelection: String] = [:]
    let galleryVideoRequest = PhotoVideoRequest()

    var player: AVPlayer?
    private var playerLayer: AVPlayerLayer?
    private var timeObserver: Any?
    private var playerItemStatusObservation: NSKeyValueObservation?
    private var playerTimeControlStatusObservation: NSKeyValueObservation?
    private var playbackEndNotificationObserver: NSObjectProtocol?
    var pendingPlaybackStart = false
    var playbackRequestID = 0
    private var hasRequestedInitialPlayback = false
    var activePlaybackStart: TimeInterval?
    var activePlaybackEnd: TimeInterval?
    var isWaitingForFirstPlaybackTimestamp = false

    let topHoldOverlayView: UIImageView = {
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

    let statusLabel: UILabel = {
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

    let previousRangeBtn: UIButton = {
        var config = UIButton.Configuration.gray()
        config.cornerStyle = .capsule
        config.image = UIImage(systemName: "chevron.left")
        config.contentInsets = NSDirectionalEdgeInsets(top: 6, leading: 10, bottom: 6, trailing: 10)
        let btn = UIButton(configuration: config)
        btn.accessibilityLabel = L("review.previous_range")
        btn.isHidden = true
        return btn
    }()

    let nextRangeBtn: UIButton = {
        var config = UIButton.Configuration.gray()
        config.cornerStyle = .capsule
        config.image = UIImage(systemName: "chevron.right")
        config.contentInsets = NSDirectionalEdgeInsets(top: 6, leading: 10, bottom: 6, trailing: 10)
        let btn = UIButton(configuration: config)
        btn.accessibilityLabel = L("review.next_range")
        btn.isHidden = true
        return btn
    }()

    let rangeLabel: UILabel = {
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

    let detectedRangeLabel: UILabel = {
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

    let trimSlider: TrimRangeSlider = {
        let slider = TrimRangeSlider()
        slider.minimumDistance = 0.1
        return slider
    }()

    let selectedRangeZoomLabel: UILabel = {
        let label = UILabel()
        label.text = L("review.selected_zoom")
        label.textColor = UIColor.white.withAlphaComponent(0.90)
        label.font = .systemFont(ofSize: 11, weight: .semibold)
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.68
        return label
    }()

    let selectedRangeZoomValueLabel: UILabel = {
        let label = UILabel()
        label.text = "1.0×"
        label.textColor = UIColor.white.withAlphaComponent(0.78)
        label.textAlignment = .right
        label.font = .monospacedDigitSystemFont(ofSize: 10, weight: .semibold)
        return label
    }()

    let selectedRangeZoomSlider: TrackTappableSlider = {
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

    let playPauseBtn: UIButton = {
        var config = UIButton.Configuration.gray()
        config.cornerStyle = .capsule
        config.image = UIImage(systemName: "play.fill")
        config.contentInsets = NSDirectionalEdgeInsets(top: 7, leading: 14, bottom: 7, trailing: 14)
        let btn = UIButton(configuration: config)
        btn.accessibilityLabel = L("review.play_pause")
        return btn
    }()

    let overlayToggleBtn: UIButton = {
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

    let saveBtn: UIButton = {
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

    let retakeBtn: UIButton = {
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

    let closeBtn: UIButton = {
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
        let wasLoadingSavedVideo = galleryVideoRequest.isLoading
        cancelGalleryAssetRequest()
        if wasLoadingSavedVideo {
            setStatus(L("review.gallery_verified"))
        }
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

    deinit {
        cancelGalleryAssetRequest()
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
