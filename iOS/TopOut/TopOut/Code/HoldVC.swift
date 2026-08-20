import UIKit
import AVFoundation
import CoreML

final class HoldVC: UIViewController {
    // MARK: Camera UI
    let camView = UIView()
    var previewLayer: AVCaptureVideoPreviewLayer?

    let overlayView: UIImageView = {
        let view = UIImageView()
        view.contentMode = .scaleAspectFit
        view.clipsToBounds = true
        view.isUserInteractionEnabled = false
        return view
    }()

    let launchImageView: UIImageView = {
        let view = UIImageView()
        view.contentMode = .scaleAspectFill
        view.clipsToBounds = true
        view.isUserInteractionEnabled = false
        view.backgroundColor = .black
        return view
    }()


    let powerSavingOverlayView: UIView = {
        let view = UIView()
        view.backgroundColor = .black
        view.isHidden = true
        view.isUserInteractionEnabled = true
        return view
    }()

    let powerSavingCountdownLabel: UILabel = {
        let label = UILabel()
        label.textColor = .white
        label.textAlignment = .center
        label.font = .monospacedDigitSystemFont(ofSize: 180, weight: .black)
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.6
        label.isHidden = true
        return label
    }()

    let powerSavingRecordButton: UIButton = {
        var config = UIButton.Configuration.plain()
        config.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8)
        config.baseForegroundColor = .white
        let button = UIButton(configuration: config)
        button.isHidden = true
        button.accessibilityLabel = "녹화 종료"
        return button
    }()

    let powerSavingElapsedLabel: UILabel = {
        let label = UILabel()
        label.text = "00:00"
        label.textColor = .white
        label.textAlignment = .center
        label.font = .monospacedDigitSystemFont(ofSize: 64, weight: .bold)
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.7
        label.isHidden = true
        return label
    }()
    var cachedPowerSavingStopIcon: UIImage?
    var cachedPowerSavingStopIconSize: CGFloat = 0
    var cachedPowerSavingStopIconScale: CGFloat = 0
    var cachedManualStopIcon: UIImage?
    var cachedManualStopIconSize: CGFloat = 0
    var cachedManualStopIconScale: CGFloat = 0
    var manualStopButtonElapsedText: String?

    let camHint: UILabel = {
        let label = UILabel()
        label.text = "벽과 매트 경계선 · 탑 홀드가 모두 보이게 맞춘 뒤 준비 완료"
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

    let previewTipLabel: UILabel = {
        let label = UILabel()
        label.text = "Tip. 자동 회전 잠금을 해제하면 가로로 촬영할 수 있어요"
        label.textColor = UIColor.white.withAlphaComponent(0.92)
        label.textAlignment = .center
        label.font = .systemFont(ofSize: 13, weight: .medium)
        label.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        label.layer.cornerRadius = 10
        label.clipsToBounds = true
        label.isHidden = true
        return label
    }()

    let readyBtn: UIButton = {
        var config = UIButton.Configuration.filled()
        config.title = "준비 완료"
        config.cornerStyle = .capsule
        config.contentInsets = NSDirectionalEdgeInsets(top: 14, leading: 28, bottom: 14, trailing: 28)
        let btn = UIButton(configuration: config)
        btn.titleLabel?.font = .systemFont(ofSize: 17, weight: .semibold)
        return btn
    }()

    let manualStopBtn: UIButton = {
        var config = UIButton.Configuration.filled()
        config.title = "영상 녹화 종료"
        config.cornerStyle = .capsule
        config.baseBackgroundColor = .systemRed
        config.contentInsets = NSDirectionalEdgeInsets(top: 14, leading: 24, bottom: 14, trailing: 24)
        let btn = UIButton(configuration: config)
        btn.titleLabel?.font = .monospacedDigitSystemFont(ofSize: 16, weight: .semibold)
        btn.isHidden = true
        return btn
    }()

    let livePreviewToggleButton: UIButton = {
        var config = UIButton.Configuration.filled()
        config.title = "실시간 영상 표시"
        config.cornerStyle = .capsule
        config.baseBackgroundColor = UIColor.white.withAlphaComponent(0.18)
        config.baseForegroundColor = .white
        config.contentInsets = NSDirectionalEdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16)
        let btn = UIButton(configuration: config)
        btn.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        btn.isHidden = true
        btn.accessibilityLabel = "실시간 영상 표시 전환"
        return btn
    }()

    let settingsBtn: UIButton = {
        var config = UIButton.Configuration.plain()
        config.image = UIImage(systemName: "gearshape.fill")
        config.baseForegroundColor = .white
        config.background.backgroundColor = .black
        config.background.cornerRadius = 22
        config.contentInsets = NSDirectionalEdgeInsets(top: 10, leading: 10, bottom: 10, trailing: 10)
        let btn = UIButton(configuration: config)
        btn.accessibilityLabel = "설정"
        return btn
    }()

    let zoomLabel: UILabel = {
        let label = UILabel()
        label.text = "1.0x"
        label.textColor = .white
        label.textAlignment = .center
        label.font = .monospacedDigitSystemFont(ofSize: 13, weight: .semibold)
        label.backgroundColor = .black
        label.layer.cornerRadius = 13
        label.clipsToBounds = true
        label.isUserInteractionEnabled = false
        return label
    }()

    // MARK: Selection UI
    let selectView = UIView()

    let scrollView: UIScrollView = {
        let view = UIScrollView()
        view.backgroundColor = .clear
        view.minimumZoomScale = 1
        view.maximumZoomScale = 1
        view.bouncesZoom = true
        view.showsHorizontalScrollIndicator = false
        view.showsVerticalScrollIndicator = false
        view.delaysContentTouches = false
        return view
    }()

    let imgView: UIImageView = {
        let view = UIImageView()
        view.contentMode = .scaleAspectFit
        view.clipsToBounds = true
        view.isUserInteractionEnabled = true
        return view
    }()

    let selectOverlayView: UIImageView = {
        let view = UIImageView()
        view.contentMode = .scaleAspectFit
        view.clipsToBounds = true
        view.isUserInteractionEnabled = false
        return view
    }()

    let matLineView = MatLineView()

    /// Read-only mat guide shown over the live recording preview when automatic clip detection is enabled.
    let recordingMatLineView: MatLineView = {
        let view = MatLineView()
        view.showsHandles = false
        view.lineAlpha = 0.48
        view.isHidden = true
        return view
    }()

    let selectHint: UILabel = {
        let label = UILabel()
        label.text = "탑홀드를 탭하세요 · 벽과 매트 경계를 보정하세요"
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

    let selectTipLabel: UILabel = {
        let label = UILabel()
        label.text = ""
        label.textColor = UIColor.white.withAlphaComponent(0.92)
        label.textAlignment = .center
        label.font = .systemFont(ofSize: 13, weight: .medium)
        label.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        label.layer.cornerRadius = 10
        label.clipsToBounds = true
        return label
    }()

    let retakeBtn: UIButton = {
        var config = UIButton.Configuration.gray()
        config.title = "다시 촬영"
        config.cornerStyle = .capsule
        let btn = UIButton(configuration: config)
        btn.titleLabel?.font = .systemFont(ofSize: 14, weight: .medium)
        return btn
    }()

    let recordBtn: UIButton = {
        var config = UIButton.Configuration.filled()
        config.title = "녹화 시작"
        config.cornerStyle = .capsule
        config.contentInsets = NSDirectionalEdgeInsets(top: 14, leading: 36, bottom: 14, trailing: 36)
        let btn = UIButton(configuration: config)
        btn.titleLabel?.font = .systemFont(ofSize: 17, weight: .semibold)
        btn.isEnabled = false
        return btn
    }()

    let spinner = UIActivityIndicatorView(style: .large)

    // MARK: Camera State
    let session = AVCaptureSession()
    let sessionQ = DispatchQueue(label: "camera.session.queue")
    let videoOutput = AVCaptureVideoDataOutput()
    let audioOutput = AVCaptureAudioDataOutput()
    let videoOutputQ = DispatchQueue(label: "camera.video.output.queue", qos: .userInitiated)
    let audioOutputQ = DispatchQueue(label: "camera.audio.output.queue", qos: .userInitiated)
    var isCameraReady = false
    /// True only after the configured capture session has delivered enough real video frames.
    /// `isCameraReady` alone means only that the AVCapture graph was committed.
    var isCameraStreamReady = false
    var appliedRecordingResolution: RecordingResolution?
    var appliedRecordingFrameRate: RecordingFrameRate?
    /// 실제 오디오 세션 활성화와 캡처 입력/출력 연결이 모두 성공한 경우에만 true입니다.
    /// 통화 중처럼 마이크를 다른 클라이언트가 점유하면 false로 내려가고 영상만 녹화합니다.
    var isAudioCaptureEnabled = false
    var activeCameraDevice: AVCaptureDevice?
    var selectedRecordingResolution: RecordingResolution = .fhd
    var selectedRecordingFrameRate: RecordingFrameRate = .fps60
    var selectedRecordingAspectRatio: RecordingAspectRatio = .story916
    var currentCameraZoomFactor: CGFloat = 1.0
    var pinchStartZoomFactor: CGFloat = 1.0

    // MARK: SAM State
    var mode: ScreenMode = .camera
    var frameImage: UIImage?
    var pixels: PixelMap?
    var emb: MLMultiArray?
    var pendingOverlay: UIImage?
    var pendingTopBox: CGRect?
    var matLinePoints: [CGPoint] = []
    var selectionRequestID = 0
    var matBoundaryAnalysisTimeoutWorkItem: DispatchWorkItem?
    var samScale: CGFloat = 1
    var isBusy = false

    var encoder: MobileSAM_Encoder?
    var decoder: MobileSAM_Decoder?
    let workQ = DispatchQueue(label: "sam.work.queue", qos: .userInitiated)
    var hasStartedMobileSAMPreload = false

    // MARK: Recording State
    let recorder = ContinuousRecorder()
    let motionDetector = MotionDetector()
    lazy var videoFrameSink = VideoFrameSink(recorder: recorder, motionDetector: motionDetector)
    var recState: RecordingState = .idle
    var clipTimes = ClipTimes()
    var recordingCountdownWorkItems: [DispatchWorkItem] = []
    /// Invalidates delayed countdown callbacks from an older recording request.
    var recordingRequestGeneration = 0
    /// Invalidates asynchronous Ready completions when capture leaves the foreground flow.
    var readyFlowRequestID = 0
    var recordingElapsedTimer: Timer?
    var recordingElapsedStartDate: Date?
    var recordingStatusMessage: String?
    var hasHiddenLaunchScreen = false

    // MARK: Recording Stability
    var recordingStorageTimer: Timer?
    var isStorageCheckInFlight = false
    var isAutomaticStopInProgress = false
    var pendingAutomaticStopSession: ContinuousRecorder.RecordingSession?
    var pendingAutomaticStopNotice: RecordingStopNotice?
    var recordingStopBackgroundTask: UIBackgroundTaskIdentifier = .invalid

    let launchLightImageName = "LaunchLight"
    let launchDarkImageName = "LaunchDark"
    let resolutionDefaultsKey = "topout.recording.resolution.v2"
    let frameRateDefaultsKey = "topout.recording.fps.v2"
    let aspectRatioDefaultsKey = "topout.recording.aspectRatio.v1"
    let helpDefaultsKey = "topout.help.enabled"
    let autoClipDetectionDefaultsKey = "topout.autoClipDetection.enabled"
    let topHoldMaskingDefaultsKey = "topout.topHoldMasking.enabled"
    let developerModeDefaultsKey = "topout.developerMode.enabled"
    var isHelpEnabled = true
    var isAutoClipDetectionEnabled = true
    var isTopHoldMaskingEnabled = false
    var isDeveloperModeEnabled = false

    // MARK: Orientation
    var lockedVideoOrientation: TopOutVideoOrientation?

    var isLandscapeLayout: Bool {
        view.bounds.width > view.bounds.height
    }

    var isActiveVideoLandscape: Bool {
        lockedVideoOrientation?.isLandscape ?? isLandscapeLayout
    }

    var canRotatePreview: Bool {
        mode == .camera && recState == .idle && lockedVideoOrientation == nil
    }

    var activeRecordingAspect: CGFloat {
        selectedRecordingAspectRatio.widthOverHeight(isLandscape: isActiveVideoLandscape)
    }

    var activeSourceCropRect: CGRect {
        selectedRecordingAspectRatio.normalizedSourceCropRect(isLandscape: isActiveVideoLandscape)
    }

    var lockedInterfaceOrientationMask: UIInterfaceOrientationMask {
        lockedVideoOrientation?.interfaceMask ?? currentInterfaceOrientationMask
    }

    var currentInterfaceOrientationMask: UIInterfaceOrientationMask {
        currentVideoOrientation().interfaceMask
    }

    func refreshSupportedOrientations() {
        if #available(iOS 16.0, *) {
            setNeedsUpdateOfSupportedInterfaceOrientations()
        } else {
            UIViewController.attemptRotationToDeviceOrientation()
        }
    }

    // MARK: Lifecycle
    override func viewDidLoad() {
        super.viewDidLoad()

        // Do not force Dark Mode here.
        // The custom launch image must read the device/system appearance first.
        // The launch image follows the device appearance.
        // The main camera UI switches to Dark Mode as soon as the camera is ready.

        setScreenAwake(true)
        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(onDeviceOrientationChanged),
            name: UIDevice.orientationDidChangeNotification,
            object: nil
        )
        registerRecordingStabilityObservers()
        view.backgroundColor = .black

        setupUI()
        setupMotionCallbacks()
        registerTraitChangeHandlers()
        showLaunchScreen()
        // Camera access and the first preview must never wait for Core ML model loading.
        // Older devices can otherwise remain on the launch image while Core ML allocates
        // its execution resources, or be terminated under transient memory pressure.
        requestCameraAccess()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        layoutUI()
        updateCameraOrientationIfPossible()
    }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        canRotatePreview ? .allButUpsideDown : lockedInterfaceOrientationMask
    }


    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)

        coordinator.animate(alongsideTransition: { [weak self] _ in
            self?.layoutUI()
        }, completion: { [weak self] _ in
            guard let self else { return }
            if self.canRotatePreview {
                self.updateCameraOrientationIfPossible()
            }
            self.view.setNeedsLayout()
        })
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        setScreenAwake(true)
        if mode == .camera || mode == .recording { startCamera() }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // Review follows the user's normal auto-lock timeout instead of inheriting capture mode.
        setScreenAwake(false)
        stopCamera()
    }

    override func didReceiveMemoryWarning() {
        super.didReceiveMemoryWarning()
        // Model teardown is serialized behind any in-flight inference. The next
        // Ready/tap analysis reloads MobileSAM lazily, so this is safe in every UI mode.
        releaseMobileSAMResources()
    }


    @objc private func onDeviceOrientationChanged() {
        guard canRotatePreview else { return }
        updateCameraOrientationIfPossible()
        refreshSupportedOrientations()
        view.setNeedsLayout()
        view.layoutIfNeeded()
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
        matBoundaryAnalysisTimeoutWorkItem?.cancel()
        cancelRecordingCountdown()
        recordingElapsedTimer?.invalidate()
        recordingStorageTimer?.invalidate()
        endRecordingStopBackgroundTask()
        UIDevice.current.endGeneratingDeviceOrientationNotifications()
        setScreenAwake(false)
    }

    private func setScreenAwake(_ enabled: Bool) {
        UIApplication.shared.isIdleTimerDisabled = enabled
    }
}
