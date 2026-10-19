import UIKit
import AVFoundation
import AVKit
import Photos

extension ReviewVC {
    // MARK: - 버튼 동작과 화면 종료

    @objc func onTapPlayPause() {
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

    @objc func onTapOverlayToggle() {
        guard topHoldOverlayImage != nil, !topHoldOverlayToggleLocked else { return }
        includeTopHoldOverlay.toggle()
        updateOverlayToggleButton()
        updateSaveButtonMode()
    }

    @objc func onTapSave() {
        if let localIdentifier = currentVerifiedSavedAssetIdentifier {
            presentVerifiedSavedVideo(localIdentifier: localIdentifier)
            return
        }
        pausePlayback()
        onSaveTrim?(selectedStart, selectedEnd, includeTopHoldOverlay)
    }

    private func presentVerifiedSavedVideo(localIdentifier: String) {
        pausePlayback()
        setStatus(L("review.loading_gallery"))
        galleryVideoRequest.load(localIdentifier: localIdentifier) { [weak self] result in
            guard let self else { return }
            guard UIApplication.shared.applicationState == .active,
                  self.viewIfLoaded?.window != nil,
                  !self.isBeingDismissed,
                  self.presentedViewController == nil else {
                self.setStatus(L("review.gallery_verified"))
                return
            }
            guard case .success(let asset) = result else {
                self.setStatus(L("error.verify_saved_video"))
                return
            }
            let playerController = AVPlayerViewController()
            playerController.player = AVPlayer(playerItem: AVPlayerItem(asset: asset))
            self.setStatus(L("review.gallery_playing"))
            self.present(playerController, animated: true) { [weak self, weak playerController] in
                guard let self, let playerController,
                      UIApplication.shared.applicationState == .active,
                      self.presentedViewController === playerController,
                      playerController.viewIfLoaded?.window != nil else { return }
                playerController.player?.play()
            }
        }
    }

    func cancelGalleryAssetRequest() {
        galleryVideoRequest.cancel()
    }

    @objc func onTapRetake() {
        confirmDiscardUnsavedClipIfNeeded(
            message: L("review.retake_warning"),
            compactMessage: true
        ) { [weak self] in
            self?.dismissForRetake()
        }
    }

    @objc func onTapClose() {
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
        cancelGalleryAssetRequest()
        dismiss(animated: true) { [weak self] in
            self?.onRetake?()
        }
    }

    private func dismissForClose() {
        cancelGalleryAssetRequest()
        dismiss(animated: true) { [weak self] in
            self?.onClose?()
        }
    }
}
