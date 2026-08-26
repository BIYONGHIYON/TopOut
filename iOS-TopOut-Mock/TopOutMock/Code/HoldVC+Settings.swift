import UIKit

extension HoldVC {
    // MARK: - 설정
    @objc func onSettings() {
        guard recState == .idle, mode == .camera else { return }

        let vc = RecordingSettingsVC(
            resolution: selectedRecordingResolution,
            frameRate: selectedRecordingFrameRate,
            aspectRatio: selectedRecordingAspectRatio,
            autoClipDetectionEnabled: isAutoClipDetectionEnabled,
            topHoldMaskingEnabled: isTopHoldMaskingEnabled,
            helpEnabled: isHelpEnabled,
            developerModeEnabled: isDeveloperModeEnabled,
            previewIsLandscape: isActiveVideoLandscape,
            lockedOrientationMask: currentInterfaceOrientationMask,
            supportedFrameRates: supportedRecordingFrameRates()
        )
        vc.onApply = { [weak self] resolution, frameRate, aspectRatio, autoClipDetectionEnabled, topHoldMaskingEnabled, helpEnabled, developerModeEnabled in
            guard let self else { return }
            self.selectedRecordingResolution = resolution
            self.selectedRecordingFrameRate = frameRate
            self.selectedRecordingAspectRatio = aspectRatio
            self.isAutoClipDetectionEnabled = autoClipDetectionEnabled
            self.isTopHoldMaskingEnabled = topHoldMaskingEnabled
            self.isHelpEnabled = helpEnabled
            self.isDeveloperModeEnabled = developerModeEnabled
            self.saveUserSettings()
            self.view.setNeedsLayout()
            self.applySelectedCaptureFormatIfPossible { [weak self] in
                self?.showCameraUI()
            }
        }
        vc.onLanguageChanged = { [weak self] in
            guard let sceneDelegate = self?.view.window?.windowScene?.delegate as? SceneDelegate else { return }
            sceneDelegate.reloadRootForLanguageChange()
        }

        let nav = OrientationLockedNavigationController(rootViewController: vc)
        nav.modalPresentationStyle = .pageSheet
        // 설정 화면은 촬영 화면의 어두운 모드 대신 기기 화면 모드를 따른다.
        if let systemStyle = view.window?.windowScene?.traitCollection.userInterfaceStyle,
           systemStyle != .unspecified {
            vc.overrideUserInterfaceStyle = systemStyle
            nav.overrideUserInterfaceStyle = systemStyle
        } else {
            vc.overrideUserInterfaceStyle = .unspecified
            nav.overrideUserInterfaceStyle = .unspecified
        }
        if let sheet = nav.sheetPresentationController {
            sheet.detents = [.large()]
            sheet.selectedDetentIdentifier = .large
            sheet.prefersGrabberVisible = true
        }
        present(nav, animated: true)
    }

    func loadUserSettings() {
        let defaults = UserDefaults.standard

        if let raw = defaults.object(forKey: resolutionDefaultsKey) as? Int,
           let resolution = RecordingResolution(rawValue: raw) {
            selectedRecordingResolution = resolution
        } else {
            selectedRecordingResolution = .fhd
        }

        if let raw = defaults.object(forKey: frameRateDefaultsKey) as? Int,
           let frameRate = RecordingFrameRate(rawValue: raw) {
            selectedRecordingFrameRate = frameRate
        } else {
            selectedRecordingFrameRate = .fps60
        }

        if let raw = defaults.object(forKey: aspectRatioDefaultsKey) as? Int,
           let aspectRatio = RecordingAspectRatio(rawValue: raw) {
            selectedRecordingAspectRatio = aspectRatio
        } else {
            selectedRecordingAspectRatio = .story916
        }

        if defaults.object(forKey: autoClipDetectionDefaultsKey) == nil {
            isAutoClipDetectionEnabled = true
        } else {
            isAutoClipDetectionEnabled = defaults.bool(forKey: autoClipDetectionDefaultsKey)
        }

        if defaults.object(forKey: topHoldMaskingDefaultsKey) == nil {
            isTopHoldMaskingEnabled = false
        } else {
            isTopHoldMaskingEnabled = defaults.bool(forKey: topHoldMaskingDefaultsKey)
        }
        if defaults.object(forKey: helpDefaultsKey) == nil {
            isHelpEnabled = true
        } else {
            isHelpEnabled = defaults.bool(forKey: helpDefaultsKey)
        }

        if defaults.object(forKey: developerModeDefaultsKey) == nil {
            isDeveloperModeEnabled = false
        } else {
            isDeveloperModeEnabled = defaults.bool(forKey: developerModeDefaultsKey)
        }

        recorder.configure(
            resolution: selectedRecordingResolution,
            frameRate: selectedRecordingFrameRate,
            aspectRatio: selectedRecordingAspectRatio
        )
    }

    func saveUserSettings() {
        let defaults = UserDefaults.standard
        defaults.set(selectedRecordingResolution.rawValue, forKey: resolutionDefaultsKey)
        defaults.set(selectedRecordingFrameRate.rawValue, forKey: frameRateDefaultsKey)
        defaults.set(selectedRecordingAspectRatio.rawValue, forKey: aspectRatioDefaultsKey)
        defaults.set(isAutoClipDetectionEnabled, forKey: autoClipDetectionDefaultsKey)
        defaults.set(isTopHoldMaskingEnabled, forKey: topHoldMaskingDefaultsKey)
        defaults.set(isHelpEnabled, forKey: helpDefaultsKey)
        defaults.set(isDeveloperModeEnabled, forKey: developerModeDefaultsKey)
    }
}


extension HoldVC {
    var requiresTopHoldSelection: Bool {
        isTopHoldMaskingEnabled
    }
}
