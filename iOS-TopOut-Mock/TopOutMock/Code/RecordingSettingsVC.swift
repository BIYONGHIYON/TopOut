import UIKit

final class RecordingSettingsVC: UIViewController {
    var onApply: ((RecordingResolution, RecordingFrameRate, RecordingAspectRatio, Bool, Bool, Bool, Bool) -> Void)?
    var onLanguageChanged: (() -> Void)?

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
        label.text = L("settings.auto_clip_description")
        label.textColor = .secondaryLabel
        label.font = .systemFont(ofSize: 13, weight: .medium)
        label.numberOfLines = 0
        return label
    }()

    private let topHoldMaskingSwitch = UISwitch()
    private let topHoldMaskingDescriptionLabel: UILabel = {
        let label = UILabel()
        label.text = L("settings.top_hold_masking_description")
        label.textColor = .secondaryLabel
        label.font = .systemFont(ofSize: 13, weight: .medium)
        label.numberOfLines = 0
        return label
    }()

    private let helpSwitch = UISwitch()
    private let developerModeSwitch = UISwitch()
    private let developerModeDescriptionLabel: UILabel = {
        let label = UILabel()
        label.text = L("settings.live_preview_description")
        label.textColor = .secondaryLabel
        label.font = .systemFont(ofSize: 12.5, weight: .medium)
        label.numberOfLines = 0
        return label
    }()

    private let batteryWarningLabel: UILabel = {
        let label = UILabel()
        label.text = L("settings.battery_warning")
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
    private var selectedLanguage = AppLanguageManager.selected
    private lazy var languageButton: UIButton = makeLanguageButton()
    private var localizedLabels: [(label: UILabel, key: String)] = []
    private weak var openSourceButton: UIButton?
    private weak var appVersionLabel: UILabel?

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
        title = T("common.settings")

        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: T("common.close"),
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
            title: T("common.save"),
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

        let videoTitle = localizedSectionTitle("settings.video_format")
        let resolutionLabel = localizedItemLabel("settings.resolution")
        let fpsLabel = localizedItemLabel("settings.frame_rate")
        let aspectTitle = localizedSectionTitle("settings.aspect_ratio")
        let autoClipTitle = localizedSectionTitle("settings.auto_clip")
        let topHoldMaskingTitle = localizedSectionTitle("settings.top_hold_masking")
        let helpTitle = localizedSectionTitle("settings.help")
        let helpLabel = localizedItemLabel("settings.help_description")
        let developerTitle = localizedSectionTitle("settings.live_preview")
        let languageTitle = localizedSectionTitle("language.title")
        let appInfoTitle = localizedSectionTitle("settings.app_info")
        let openSourceButton = navigationButton(
            title: T("licenses.title"),
            action: #selector(onOpenSourceLicenses)
        )
        self.openSourceButton = openSourceButton
        let appVersionLabel = itemLabel(T("settings.version", appVersionDescription))
        self.appVersionLabel = appVersionLabel

        let autoClipRow = switchRow(
            label: autoClipDetectionDescriptionLabel,
            toggle: autoClipDetectionSwitch
        )
        let topHoldMaskingRow = switchRow(
            label: topHoldMaskingDescriptionLabel,
            toggle: topHoldMaskingSwitch
        )
        let helpRow = switchRow(label: helpLabel, toggle: helpSwitch)
        let developerRow = switchRow(label: developerModeDescriptionLabel, toggle: developerModeSwitch)

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
            developerRow,
            languageTitle,
            languageButton,
            appInfoTitle,
            openSourceButton,
            appVersionLabel
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
        refreshLocalizedContent()
    }

    private func sectionTitle(_ text: String) -> UILabel {
        let label = UILabel()
        label.text = text
        label.font = .systemFont(ofSize: 18, weight: .bold)
        label.textColor = .label
        label.numberOfLines = 0
        return label
    }

    private func localizedSectionTitle(_ key: String) -> UILabel {
        let label = sectionTitle(T(key))
        localizedLabels.append((label, key))
        return label
    }

    private func itemLabel(_ text: String) -> UILabel {
        let label = UILabel()
        label.text = text
        label.font = .systemFont(ofSize: 14, weight: .semibold)
        label.textColor = .secondaryLabel
        label.numberOfLines = 0
        return label
    }

    private func localizedItemLabel(_ key: String) -> UILabel {
        let label = itemLabel(T(key))
        localizedLabels.append((label, key))
        return label
    }

    private func switchRow(label: UILabel, toggle: UISwitch) -> UIStackView {
        label.lineBreakMode = .byWordWrapping
        label.setContentHuggingPriority(.defaultLow, for: .horizontal)
        label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        toggle.setContentHuggingPriority(.required, for: .horizontal)
        toggle.setContentCompressionResistancePriority(.required, for: .horizontal)

        let row = UIStackView(arrangedSubviews: [label, toggle])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = 12
        row.distribution = .fill
        return row
    }

    private func T(_ key: String, _ arguments: Any...) -> String {
        L10n.text(key, arguments, language: selectedLanguage)
    }

    private func refreshLocalizedContent() {
        title = T("common.settings")
        navigationItem.leftBarButtonItem?.title = T("common.close")
        navigationItem.rightBarButtonItem?.title = T("common.save")
        localizedLabels.forEach { $0.label.text = T($0.key) }
        autoClipDetectionDescriptionLabel.text = T("settings.auto_clip_description")
        topHoldMaskingDescriptionLabel.text = T("settings.top_hold_masking_description")
        developerModeDescriptionLabel.text = T("settings.live_preview_description")
        batteryWarningLabel.text = T("settings.battery_warning")
        appVersionLabel?.text = T("settings.version", appVersionDescription)

        if let openSourceButton,
           var configuration = openSourceButton.configuration {
            configuration.title = T("licenses.title")
            openSourceButton.configuration = configuration
        }

        updateAspectDescription()
        updateLanguageButton()
        view.setNeedsLayout()
    }

    private func navigationButton(title: String, action: Selector) -> UIButton {
        let button = UIButton(type: .system)
        var configuration = UIButton.Configuration.gray()
        configuration.title = title
        configuration.titleAlignment = .leading
        configuration.titleLineBreakMode = .byWordWrapping
        configuration.image = UIImage(systemName: "chevron.right")
        configuration.imagePlacement = .trailing
        configuration.imagePadding = 10
        configuration.baseForegroundColor = .label
        configuration.contentInsets = NSDirectionalEdgeInsets(
            top: 13,
            leading: 16,
            bottom: 13,
            trailing: 14
        )
        button.configuration = configuration
        button.contentHorizontalAlignment = .fill
        button.titleLabel?.numberOfLines = 2
        button.titleLabel?.lineBreakMode = .byWordWrapping
        button.addTarget(self, action: action, for: .touchUpInside)
        return button
    }

    private func makeLanguageButton() -> UIButton {
        let button = UIButton(type: .system)
        button.contentHorizontalAlignment = .fill
        button.showsMenuAsPrimaryAction = true
        updateLanguageButton(button)
        return button
    }

    private func updateLanguageButton(_ button: UIButton? = nil) {
        let target = button ?? languageButton
        var configuration = UIButton.Configuration.gray()
        configuration.title = AppLanguageManager.displayName(
            for: selectedLanguage,
            localizedUsing: selectedLanguage
        )
        configuration.titleAlignment = .leading
        configuration.titleLineBreakMode = .byWordWrapping
        configuration.image = UIImage(systemName: "chevron.up.chevron.down")
        configuration.imagePlacement = .trailing
        configuration.imagePadding = 10
        configuration.baseForegroundColor = .label
        configuration.contentInsets = NSDirectionalEdgeInsets(top: 13, leading: 16, bottom: 13, trailing: 14)
        target.configuration = configuration
        target.titleLabel?.numberOfLines = 2
        target.titleLabel?.lineBreakMode = .byWordWrapping
        target.titleLabel?.adjustsFontSizeToFitWidth = true
        target.titleLabel?.minimumScaleFactor = 0.75
        target.menu = UIMenu(children: AppLanguage.allCases.map { language in
            UIAction(
                title: AppLanguageManager.displayName(
                    for: language,
                    localizedUsing: selectedLanguage
                ),
                state: language == selectedLanguage ? .on : .off
            ) { [weak self] _ in
                self?.selectedLanguage = language
                self?.refreshLocalizedContent()
            }
        })
    }

    private var appVersionDescription: String {
        let info = Bundle.main.infoDictionary
        return info?["CFBundleShortVersionString"] as? String ?? "-"
    }

    @objc private func onOpenSourceLicenses() {
        navigationController?.pushViewController(
            OpenSourceLicensesVC(language: selectedLanguage),
            animated: true
        )
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
        aspectDescriptionLabel.text = T(
            "settings.output_size",
            selectedAspectSubtitle,
            Int(output.width),
            Int(output.height)
        )
    }

    private var selectedAspectSubtitle: String {
        switch selectedAspectRatio {
        case .story916: return T("settings.story_reels")
        case .feed45: return T("settings.instagram_feed")
        case .portrait34: return T("settings.portrait_post")
        case .square11: return T("settings.square_post")
        }
    }

    @objc private func onSave() {
        updateFrameRateAvailability(selectFallbackIfNeeded: true)
        updateCurrentFormatSelection()
        autoClipDetectionEnabled = autoClipDetectionSwitch.isOn
        topHoldMaskingEnabled = topHoldMaskingSwitch.isOn
        helpEnabled = helpSwitch.isOn
        developerModeEnabled = developerModeSwitch.isOn
        let languageChanged = selectedLanguage != AppLanguageManager.selected
        AppLanguageManager.select(selectedLanguage)
        onApply?(selectedResolution, selectedFrameRate, selectedAspectRatio, autoClipDetectionEnabled, topHoldMaskingEnabled, helpEnabled, developerModeEnabled)
        dismiss(animated: true) { [weak self] in
            if languageChanged { self?.onLanguageChanged?() }
        }
    }
}


final class OrientationLockedNavigationController: UINavigationController {
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        topViewController?.supportedInterfaceOrientations ?? .portrait
    }

}
