import UIKit
import AVFoundation
import CoreML

final class HoldVC: UIViewController {
    // MARK: - 카메라 화면
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
        button.accessibilityLabel = L("camera.stop_recording")
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
        label.text = L("camera.preview_both")
        label.textColor = .white
        label.textAlignment = .center
        label.font = .systemFont(ofSize: 14, weight: .semibold)
        label.backgroundColor = .clear
        label.layer.shadowColor = UIColor.black.cgColor
        label.layer.shadowOpacity = 0.75
        label.layer.shadowRadius = 3
        label.layer.shadowOffset = CGSize(width: 0, height: 1)
        label.clipsToBounds = false
        label.numberOfLines = 2
        label.lineBreakMode = .byWordWrapping
        return label
    }()

    let previewTipLabel: UILabel = {
        let label = UILabel()
        label.text = L("camera.tip_rotation")
        label.textColor = UIColor.white.withAlphaComponent(0.92)
        label.textAlignment = .center
        label.font = .systemFont(ofSize: 13, weight: .medium)
        label.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        label.layer.cornerRadius = 10
        label.clipsToBounds = true
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.72
        label.isHidden = true
        return label
    }()

    let readyBtn: UIButton = {
        var config = UIButton.Configuration.filled()
        config.title = L("camera.ready")
        config.cornerStyle = .capsule
        config.titleAlignment = .center
        config.titleLineBreakMode = .byWordWrapping
        config.contentInsets = NSDirectionalEdgeInsets(top: 14, leading: 28, bottom: 14, trailing: 28)
        let btn = UIButton(configuration: config)
        btn.titleLabel?.font = .systemFont(ofSize: 17, weight: .semibold)
        btn.titleLabel?.numberOfLines = 2
        btn.titleLabel?.textAlignment = .center
        btn.titleLabel?.lineBreakMode = .byWordWrapping
        btn.titleLabel?.adjustsFontSizeToFitWidth = true
        btn.titleLabel?.minimumScaleFactor = 0.72
        return btn
    }()

    let manualStopBtn: UIButton = {
        var config = UIButton.Configuration.filled()
        config.title = L("camera.stop_recording")
        config.cornerStyle = .capsule
        config.baseBackgroundColor = .systemRed
        config.titleAlignment = .center
        config.titleLineBreakMode = .byWordWrapping
        config.contentInsets = NSDirectionalEdgeInsets(top: 14, leading: 24, bottom: 14, trailing: 24)
        let btn = UIButton(configuration: config)
        btn.titleLabel?.font = .monospacedDigitSystemFont(ofSize: 16, weight: .semibold)
        btn.titleLabel?.numberOfLines = 2
        btn.titleLabel?.textAlignment = .center
        btn.titleLabel?.lineBreakMode = .byWordWrapping
        btn.titleLabel?.adjustsFontSizeToFitWidth = true
        btn.titleLabel?.minimumScaleFactor = 0.72
        btn.isHidden = true
        return btn
    }()

    let livePreviewToggleButton: UIButton = {
        var config = UIButton.Configuration.filled()
        config.title = L("camera.show_live_preview")
        config.cornerStyle = .capsule
        config.baseBackgroundColor = UIColor.white.withAlphaComponent(0.18)
        config.baseForegroundColor = .white
        config.titleAlignment = .center
        config.titleLineBreakMode = .byWordWrapping
        config.contentInsets = NSDirectionalEdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16)
        let btn = UIButton(configuration: config)
        btn.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        btn.titleLabel?.numberOfLines = 2
        btn.titleLabel?.textAlignment = .center
        btn.titleLabel?.lineBreakMode = .byWordWrapping
        btn.titleLabel?.adjustsFontSizeToFitWidth = true
        btn.titleLabel?.minimumScaleFactor = 0.68
        btn.isHidden = true
        btn.accessibilityLabel = L("camera.live_preview_toggle")
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
        btn.accessibilityLabel = L("common.settings")
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

    // MARK: - 선택 화면
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

    /// 자동 구간 탐지 중 실시간 화면에 표시하는 읽기 전용 매트 경계선이다.
    let recordingMatLineView: MatLineView = {
        let view = MatLineView()
        view.showsHandles = false
        view.lineAlpha = 0.48
        view.isHidden = true
        return view
    }()

    let selectHint: UILabel = {
        let label = UILabel()
        label.text = L("camera.selection_both")
        label.textColor = .white
        label.textAlignment = .center
        label.font = .systemFont(ofSize: 14, weight: .semibold)
        label.backgroundColor = .clear
        label.layer.shadowColor = UIColor.black.cgColor
        label.layer.shadowOpacity = 0.75
        label.layer.shadowRadius = 3
        label.layer.shadowOffset = CGSize(width: 0, height: 1)
        label.clipsToBounds = false
        label.numberOfLines = 2
        label.lineBreakMode = .byWordWrapping
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
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.72
        return label
    }()

    let retakeBtn: UIButton = {
        var config = UIButton.Configuration.gray()
        config.title = L("camera.retake")
        config.cornerStyle = .capsule
        config.titleAlignment = .center
        config.titleLineBreakMode = .byWordWrapping
        let btn = UIButton(configuration: config)
        btn.titleLabel?.font = .systemFont(ofSize: 14, weight: .medium)
        btn.titleLabel?.numberOfLines = 2
        btn.titleLabel?.textAlignment = .center
        btn.titleLabel?.lineBreakMode = .byWordWrapping
        btn.titleLabel?.adjustsFontSizeToFitWidth = true
        btn.titleLabel?.minimumScaleFactor = 0.72
        return btn
    }()

    let recordBtn: UIButton = {
        var config = UIButton.Configuration.filled()
        config.title = L("camera.start_recording")
        config.cornerStyle = .capsule
        config.titleAlignment = .center
        config.titleLineBreakMode = .byWordWrapping
        config.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16)
        let btn = UIButton(configuration: config)
        btn.titleLabel?.font = .systemFont(ofSize: 17, weight: .semibold)
        btn.titleLabel?.numberOfLines = 2
        btn.titleLabel?.textAlignment = .center
        btn.titleLabel?.lineBreakMode = .byWordWrapping
        btn.titleLabel?.adjustsFontSizeToFitWidth = true
        btn.titleLabel?.minimumScaleFactor = 0.68
        btn.isEnabled = false
        return btn
    }()

    let spinner = UIActivityIndicatorView(style: .large)

    // MARK: - 카메라 상태
    let session = AVCaptureSession()
    let sessionQ = DispatchQueue(label: "camera.session.queue")
    let videoOutput = AVCaptureVideoDataOutput()
    let audioOutput = AVCaptureAudioDataOutput()
    let videoOutputQ = DispatchQueue(label: "camera.video.output.queue", qos: .userInitiated)
    let audioOutputQ = DispatchQueue(label: "camera.audio.output.queue", qos: .userInitiated)
    var isCameraReady = false
    /// 촬영 세션에서 실제 영상 프레임이 충분히 도착했을 때만 참이다.
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

    // MARK: - 이미지 분할 상태
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

    // MARK: - 녹화 상태
    let recorder = ContinuousRecorder()
    let motionDetector = MotionDetector()
    lazy var videoFrameSink = VideoFrameSink(recorder: recorder, motionDetector: motionDetector)
    var recState: RecordingState = .idle
    var clipTimes = ClipTimes()
    var recordingCountdownWorkItems: [DispatchWorkItem] = []
    /// 이전 녹화 요청의 지연 카운트다운을 무효화한다.
    var recordingRequestGeneration = 0
    /// 촬영 흐름을 벗어나면 비동기 준비 완료 결과를 무효화한다.
    var readyFlowRequestID = 0
    var recordingElapsedTimer: Timer?
    var recordingElapsedStartDate: Date?
    var recordingStatusMessage: String?
    var hasHiddenLaunchScreen = false

    // MARK: - 녹화 안정성
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

    // MARK: - 화면 방향
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

    // MARK: - 생명주기
    override func viewDidLoad() {
        super.viewDidLoad()

        // 시작 이미지는 기기 화면 모드를 따르고 카메라 준비 뒤 어두운 모드로 전환한다.

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
        // 카메라 권한과 첫 화면은 모델 로딩을 기다리지 않는다.
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
        // 결과 화면은 촬영 화면의 자동 잠금 방지 설정을 이어받지 않는다.
        setScreenAwake(false)
        stopCamera()
    }

    override func didReceiveMemoryWarning() {
        super.didReceiveMemoryWarning()
        // 진행 중인 추론 뒤 모델을 해제하고 다음 분석에서 필요할 때 다시 불러온다.
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
