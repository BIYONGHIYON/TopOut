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
            showTopHoldTapTipIfNeeded()
            return
        }

        if playhead >= selectedEnd - 0.03 {
            playhead = selectedStart
            trimSlider.playheadValue = playhead
        }
        playFromCurrentPlayhead()
    }

    @objc func onTapOverlayToggle() {
        guard topHoldOverlayImage != nil else {
            // 아직 마스크가 없으면 영상을 멈추고 탑홀드를 탭하라고 안내한다.
            pausePlayback()
            setStatus(L("review.top_hold_tap_tip"))
            return
        }
        includeTopHoldOverlay.toggle()
        updateOverlayToggleButton()
        updateSaveButtonMode()
        if includeTopHoldOverlay {
            showTopHoldZoomTipIfNeeded()
        } else {
            // 마스크를 끄면 확대 안내를 거두고 기본 안내로 돌아간다.
            updateDetectedRangeNavigationUI()
        }
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

    static let seekStepSeconds: TimeInterval = 5

    @objc func onTapSeekBackward() {
        seekPlayhead(by: -Self.seekStepSeconds)
    }

    @objc func onTapSeekForward() {
        seekPlayhead(by: Self.seekStepSeconds)
    }

    /// 선택 구간 안에서만 이동하고 경계에서 멈춘다. 재생 중이었다면 이동한 위치부터 이어서 재생한다.
    private func seekPlayhead(by delta: TimeInterval) {
        let wasPlaying = isPlaying || pendingPlaybackStart
        if wasPlaying { pausePlayback() }
        playhead = clamped(playhead + delta, min: selectedStart, max: selectedEnd)
        trimSlider.playheadValue = playhead
        showSliderTimeBubble(for: playhead)
        scheduleHideSliderTimeBubble(after: 1.0)
        if wasPlaying, playhead < selectedEnd - 0.03 {
            playFromCurrentPlayhead()
        } else {
            seek(to: playhead)
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
        present(alert, animated: true)
    }

    // MARK: - 탑홀드 보정

    /// 영상을 멈추고 현재 프레임에서 탭한 탑홀드로 마스크를 만든다. 다시 탭하면 새로 만든다.
    @objc func onVideoTap(_ gesture: UITapGestureRecognizer) {
        guard topHoldSelectionEnabled,
              onSelectTopHold != nil,
              !isSelectingTopHold,
              closeBtn.isEnabled,
              presentedViewController == nil else { return }
        // 확대 중이어도 영상 컨테이너 좌표로 바꾸면 원래 영상 위치가 된다.
        let videoRect = displayedVideoRect
        let location = gesture.location(in: videoContainerView)
        guard videoRect.width > 0, videoRect.height > 0, videoRect.contains(location) else { return }
        let normalized = CGPoint(
            x: (location.x - videoRect.minX) / videoRect.width,
            y: (location.y - videoRect.minY) / videoRect.height
        )

        pausePlayback()
        isSelectingTopHold = true
        overlayToggleBtn.isEnabled = false
        saveBtn.isEnabled = false
        setStatus(L("camera.analyzing_hold"))

        let time = player?.currentTime() ?? CMTime(seconds: playhead, preferredTimescale: 600)
        generateReviewFrame(at: time) { [weak self] image in
            guard let self else { return }
            guard let image, image.size.width > 0, image.size.height > 0,
                  let onSelectTopHold = self.onSelectTopHold else {
                self.finishTopHoldSelection(nil)
                return
            }
            let point = CGPoint(x: normalized.x * image.size.width, y: normalized.y * image.size.height)
            onSelectTopHold(image, point) { [weak self] overlay in
                self?.finishTopHoldSelection(overlay)
            }
        }
    }

    /// 녹화 화면과 같은 기준으로 쓰도록 회전을 적용한 원본 프레임을 정확한 시각에서 뽑는다.
    private func generateReviewFrame(at time: CMTime, completion: @escaping (UIImage?) -> Void) {
        reviewFrameGenerator?.cancelAllCGImageGeneration()
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: recordingURL))
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero
        generator.maximumSize = CGSize(width: CameraCfg.captureMaxLongSide, height: CameraCfg.captureMaxLongSide)
        reviewFrameGenerator = generator
        generator.generateCGImagesAsynchronously(forTimes: [NSValue(time: time)]) { _, cgImage, _, result, _ in
            let image = result == .succeeded ? cgImage.map { UIImage(cgImage: $0) } : nil
            DispatchQueue.main.async { completion(image) }
        }
    }

    private func finishTopHoldSelection(_ overlay: UIImage?) {
        isSelectingTopHold = false
        saveBtn.isEnabled = true
        guard let overlay else {
            updateOverlayToggleButton()
            setStatus(L("camera.selection_failed"))
            return
        }
        topHoldOverlayImage = overlay
        topHoldOverlayView.image = overlay
        includeTopHoldOverlay = true
        // 마스크가 바뀌었으므로 이전에 마스크를 넣어 저장한 영상은 지금 선택과 다르다.
        verifiedSavedAssets.removeAll()
        updateOverlayToggleButton()
        updateSaveButtonMode()
        view.setNeedsLayout()
        // 마스크가 켜진 상태에서는 확대해 다시 고를 수 있다고 알려준다. 도움말을 끄면 지정 결과만 알린다.
        if helpEnabled {
            showTopHoldZoomTipIfNeeded()
        } else {
            setStatus(L("review.top_hold_updated"))
        }
    }

    /// 탑홀드 마스크가 체크된 상태면 두 손가락으로 확대한 뒤 다시 탭할 수 있다고 알려준다.
    func showTopHoldZoomTipIfNeeded() {
        guard topHoldSelectionEnabled, helpEnabled, includeTopHoldOverlay, topHoldOverlayImage != nil else { return }
        setStatus(L("review.top_hold_zoom_tip"))
    }

    /// 탑홀드 마스킹을 켠 녹화에서 처음 멈췄을 때 탭으로 마스크를 지정한다는 안내를 한 번 보여준다.
    func showTopHoldTapTipIfNeeded() {
        guard topHoldSelectionEnabled, helpEnabled, !hasShownTopHoldTapTip, !isSelectingTopHold else { return }
        hasShownTopHoldTapTip = true
        setStatus(L("review.top_hold_tap_tip"))
    }

    private func dismissForClose() {
        cancelGalleryAssetRequest()
        dismiss(animated: true) { [weak self] in
            self?.onClose?()
        }
    }
}
