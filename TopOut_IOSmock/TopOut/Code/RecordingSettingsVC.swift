import UIKit

final class RecordingSettingsVC: UIViewController {
    var onApply: ((RecordingResolution, RecordingFrameRate, RecordingAspectRatio, Bool, Bool, Bool, Bool) -> Void)?

    private let resolutionSegment = UISegmentedControl(items: RecordingResolution.allCases.map { $0.title })
    private let frameRateSegment = UISegmentedControl(items: RecordingFrameRate.allCases.map { $0.title })
    private let aspectRatioSegment: UISegmentedControl

    private let aspectDescriptionLabel: UILabel = {
        let label = UILabel()
        label.textColor = .secondaryLabel
        label.font = .systemFont(ofSize: 13, weight: .medium)
        label.numberOfLines = 0
        return label
    }()

    private let autoClipDetectionSwitch = UISwitch()
    private let autoClipDetectionDescriptionLabel: UILabel = {
        let label = UILabel()
        label.text = "비활성화 시 배터리가 절약되지만 수동으로 구간 선택"
        label.textColor = .secondaryLabel
        label.font = .systemFont(ofSize: 13, weight: .medium)
        label.numberOfLines = 0
        return label
    }()

    private let topHoldMaskingSwitch = UISwitch()
    private let topHoldMaskingDescriptionLabel: UILabel = {
        let label = UILabel()
        label.text = "결과 영상에 선택 영역을 마스크로 표시해 저장 가능"
        label.textColor = .secondaryLabel
        label.font = .systemFont(ofSize: 13, weight: .medium)
        label.numberOfLines = 0
        return label
    }()

    private let helpSwitch = UISwitch()
    private let developerModeSwitch = UISwitch()
    private let developerModeDescriptionLabel: UILabel = {
        let label = UILabel()
        label.text = "비활성화 시 배터리 절약을 위해 녹화 중 검은 화면 출력"
        label.textColor = .secondaryLabel
        label.font = .systemFont(ofSize: 12.5, weight: .medium)
        label.numberOfLines = 0
        return label
    }()

    private let batteryWarningLabel: UILabel = {
        let label = UILabel()
        label.text = "4K 60fps는 배터리와 저장공간을 빠르게 소모할 수 있어요."
        label.textColor = .systemOrange
        label.font = .systemFont(ofSize: 13, weight: .semibold)
        label.textAlignment = .center
        label.numberOfLines = 0
        label.isHidden = true
        return label
    }()

    private var selectedResolution: RecordingResolution
    private var selectedFrameRate: RecordingFrameRate
    private var selectedAspectRatio: RecordingAspectRatio
    private let previewIsLandscape: Bool
    private let lockedOrientationMask: UIInterfaceOrientationMask
    private let supportedFrameRates: [RecordingResolution: Set<RecordingFrameRate>]
    private var autoClipDetectionEnabled: Bool
    private var topHoldMaskingEnabled: Bool
    private var helpEnabled: Bool
    private var developerModeEnabled: Bool

    init(
        resolution: RecordingResolution,
        frameRate: RecordingFrameRate,
        aspectRatio: RecordingAspectRatio,
        autoClipDetectionEnabled: Bool,
        topHoldMaskingEnabled: Bool,
        helpEnabled: Bool,
        developerModeEnabled: Bool,
        previewIsLandscape: Bool,
        lockedOrientationMask: UIInterfaceOrientationMask,
        supportedFrameRates: [RecordingResolution: Set<RecordingFrameRate>]
    ) {
        self.selectedResolution = resolution
        self.selectedFrameRate = frameRate
        self.selectedAspectRatio = aspectRatio
        self.autoClipDetectionEnabled = autoClipDetectionEnabled
        self.topHoldMaskingEnabled = topHoldMaskingEnabled
        self.helpEnabled = helpEnabled
        self.developerModeEnabled = developerModeEnabled
        self.previewIsLandscape = previewIsLandscape
        self.lockedOrientationMask = lockedOrientationMask
        if supportedFrameRates.values.contains(where: { !$0.isEmpty }) {
            self.supportedFrameRates = supportedFrameRates
        } else {
            self.supportedFrameRates = Dictionary(
                uniqueKeysWithValues: RecordingResolution.allCases.map {
                    ($0, Set(RecordingFrameRate.allCases))
                }
            )
        }
        self.aspectRatioSegment = UISegmentedControl(
            items: RecordingAspectRatio.allCases.map { $0.title(isLandscape: previewIsLandscape) }
        )
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        lockedOrientationMask
    }


    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        title = "설정"

        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: "닫기",
            style: .plain,
            target: self,
            action: #selector(onClose)
        )

        let saveButtonStyle: UIBarButtonItem.Style
        if #available(iOS 26.0, *) {
            saveButtonStyle = .prominent
        } else {
            saveButtonStyle = .done
        }

        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: "저장",
            style: saveButtonStyle,
            target: self,
            action: #selector(onSave)
        )

        resolutionSegment.selectedSegmentIndex = RecordingResolution.allCases.firstIndex(of: selectedResolution) ?? 0
        frameRateSegment.selectedSegmentIndex = RecordingFrameRate.allCases.firstIndex(of: selectedFrameRate) ?? 0
        aspectRatioSegment.selectedSegmentIndex = RecordingAspectRatio.allCases.firstIndex(of: selectedAspectRatio) ?? 0
        autoClipDetectionSwitch.isOn = autoClipDetectionEnabled
        topHoldMaskingSwitch.isOn = topHoldMaskingEnabled
        helpSwitch.isOn = helpEnabled
        developerModeSwitch.isOn = developerModeEnabled

        [resolutionSegment, frameRateSegment, aspectRatioSegment].forEach {
            $0.addTarget(self, action: #selector(onFormatChanged(_:)), for: .valueChanged)
        }
        updateResolutionAvailability()
        updateFrameRateAvailability(selectFallbackIfNeeded: true)
        updateCurrentFormatSelection()
        updateBatteryWarning()
        updateAspectDescription()

        let videoTitle = sectionTitle("영상 포맷")
        let resolutionLabel = itemLabel("해상도")
        let fpsLabel = itemLabel("프레임")
        let aspectTitle = sectionTitle("화면 비율")
        let autoClipTitle = sectionTitle("자동 구간 탐지")
        let topHoldMaskingTitle = sectionTitle("탑홀드 마스킹")
        let helpTitle = sectionTitle("도움말")
        let helpLabel = itemLabel("촬영 안내 문구와 Tip 표시")
        let developerTitle = sectionTitle("녹화중 실시간 영상 표시")

        let autoClipRow = UIStackView(arrangedSubviews: [autoClipDetectionDescriptionLabel, autoClipDetectionSwitch])
        autoClipRow.axis = .horizontal
        autoClipRow.alignment = .center
        autoClipRow.spacing = 12

        let topHoldMaskingRow = UIStackView(arrangedSubviews: [topHoldMaskingDescriptionLabel, topHoldMaskingSwitch])
        topHoldMaskingRow.axis = .horizontal
        topHoldMaskingRow.alignment = .center
        topHoldMaskingRow.spacing = 12

        let helpRow = UIStackView(arrangedSubviews: [helpLabel, helpSwitch])
        helpRow.axis = .horizontal
        helpRow.alignment = .center
        helpRow.spacing = 12

        developerModeDescriptionLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        developerModeSwitch.setContentHuggingPriority(.required, for: .horizontal)
        developerModeSwitch.setContentCompressionResistancePriority(.required, for: .horizontal)

        let developerRow = UIStackView(arrangedSubviews: [developerModeDescriptionLabel, developerModeSwitch])
        developerRow.axis = .horizontal
        developerRow.alignment = .center
        developerRow.spacing = 12
        developerRow.distribution = .fill

        let stack = UIStackView(arrangedSubviews: [
            videoTitle,
            resolutionLabel,
            resolutionSegment,
            fpsLabel,
            frameRateSegment,
            batteryWarningLabel,
            aspectTitle,
            aspectRatioSegment,
            aspectDescriptionLabel,
            autoClipTitle,
            autoClipRow,
            topHoldMaskingTitle,
            topHoldMaskingRow,
            helpTitle,
            helpRow,
            developerTitle,
            developerRow
        ])
        stack.axis = .vertical
        stack.spacing = 14
        stack.translatesAutoresizingMaskIntoConstraints = false

        let scrollView = UIScrollView()
        scrollView.alwaysBounceVertical = false
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scrollView)
        scrollView.addSubview(stack)

        NSLayoutConstraint.activate([
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            stack.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor, constant: -24),
            stack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor, constant: 24),
            stack.bottomAnchor.constraint(lessThanOrEqualTo: scrollView.contentLayoutGuide.bottomAnchor, constant: -24),
            stack.widthAnchor.constraint(equalTo: scrollView.frameLayoutGuide.widthAnchor, constant: -48)
        ])
    }

    private func sectionTitle(_ text: String) -> UILabel {
        let label = UILabel()
        label.text = text
        label.font = .systemFont(ofSize: 18, weight: .bold)
        label.textColor = .label
        return label
    }

    private func itemLabel(_ text: String) -> UILabel {
        let label = UILabel()
        label.text = text
        label.font = .systemFont(ofSize: 14, weight: .semibold)
        label.textColor = .secondaryLabel
        return label
    }

    @objc private func onClose() {
        dismiss(animated: true)
    }

    @objc private func onFormatChanged(_ sender: UISegmentedControl) {
        if sender === resolutionSegment {
            updateSelectedResolution()
            updateFrameRateAvailability(selectFallbackIfNeeded: true)
        } else if sender === frameRateSegment {
            updateSelectedFrameRate()
        } else if sender === aspectRatioSegment {
            updateSelectedAspectRatio()
        }

        updateCurrentFormatSelection()
        updateBatteryWarning()
        updateAspectDescription()
    }

    private func updateCurrentFormatSelection() {
        updateSelectedResolution()
        updateSelectedFrameRate()
        updateSelectedAspectRatio()
    }

    private func updateSelectedResolution() {
        let resolutions = RecordingResolution.allCases
        if resolutionSegment.selectedSegmentIndex >= 0,
           resolutionSegment.selectedSegmentIndex < resolutions.count {
            selectedResolution = resolutions[resolutionSegment.selectedSegmentIndex]
        }
    }

    private func updateSelectedFrameRate() {
        let fpsCases = RecordingFrameRate.allCases
        if frameRateSegment.selectedSegmentIndex >= 0,
           frameRateSegment.selectedSegmentIndex < fpsCases.count {
            selectedFrameRate = fpsCases[frameRateSegment.selectedSegmentIndex]
        }
    }

    private func updateSelectedAspectRatio() {
        let aspectCases = RecordingAspectRatio.allCases
        if aspectRatioSegment.selectedSegmentIndex >= 0,
           aspectRatioSegment.selectedSegmentIndex < aspectCases.count {
            selectedAspectRatio = aspectCases[aspectRatioSegment.selectedSegmentIndex]
        }
    }

    private func updateResolutionAvailability() {
        for (index, resolution) in RecordingResolution.allCases.enumerated() {
            let hasSupportedFrameRate = !(supportedFrameRates[resolution] ?? []).isEmpty
            resolutionSegment.setEnabled(hasSupportedFrameRate, forSegmentAt: index)
        }

        if !resolutionSegment.isEnabledForSegment(at: resolutionSegment.selectedSegmentIndex),
           let fallbackIndex = RecordingResolution.allCases.indices.first(where: {
               resolutionSegment.isEnabledForSegment(at: $0)
           }) {
            resolutionSegment.selectedSegmentIndex = fallbackIndex
            selectedResolution = RecordingResolution.allCases[fallbackIndex]
        }
    }

    private func updateFrameRateAvailability(selectFallbackIfNeeded: Bool) {
        let supported = supportedFrameRates[selectedResolution] ?? []

        for (index, frameRate) in RecordingFrameRate.allCases.enumerated() {
            frameRateSegment.setEnabled(supported.contains(frameRate), forSegmentAt: index)
        }

        let currentIndex = frameRateSegment.selectedSegmentIndex
        let currentIsSupported = currentIndex >= 0 && frameRateSegment.isEnabledForSegment(at: currentIndex)
        guard selectFallbackIfNeeded, !currentIsSupported else { return }

        let preferredOrder: [RecordingFrameRate] = [.fps30, .fps60]
        guard let fallback = preferredOrder.first(where: { supported.contains($0) }),
              let fallbackIndex = RecordingFrameRate.allCases.firstIndex(of: fallback) else {
            frameRateSegment.selectedSegmentIndex = UISegmentedControl.noSegment
            return
        }

        selectedFrameRate = fallback
        frameRateSegment.selectedSegmentIndex = fallbackIndex
    }

    private func updateBatteryWarning() {
        batteryWarningLabel.isHidden = !(selectedResolution == .uhd && selectedFrameRate == .fps60)
    }

    private func updateAspectDescription() {
        let output = selectedAspectRatio.displayOutputSize(
            for: selectedResolution,
            isLandscape: previewIsLandscape
        )
        aspectDescriptionLabel.text = "\(selectedAspectRatio.subtitle) · 저장 크기 \(Int(output.width))×\(Int(output.height))"
    }

    @objc private func onSave() {
        updateFrameRateAvailability(selectFallbackIfNeeded: true)
        updateCurrentFormatSelection()
        autoClipDetectionEnabled = autoClipDetectionSwitch.isOn
        topHoldMaskingEnabled = topHoldMaskingSwitch.isOn
        helpEnabled = helpSwitch.isOn
        developerModeEnabled = developerModeSwitch.isOn
        onApply?(selectedResolution, selectedFrameRate, selectedAspectRatio, autoClipDetectionEnabled, topHoldMaskingEnabled, helpEnabled, developerModeEnabled)
        dismiss(animated: true)
    }
}


final class OrientationLockedNavigationController: UINavigationController {
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        topViewController?.supportedInterfaceOrientations ?? .portrait
    }

}
