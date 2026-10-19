import UIKit
import AVFoundation
import AVKit
import Photos

extension ReviewVC {
    // MARK: - 결과 재생

    func handlePlayerItemStatus(_ item: AVPlayerItem) {
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

    func handlePlayerTimeControlStatus(_ status: AVPlayer.TimeControlStatus) {
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

    func playFromCurrentPlayhead() {
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

    func pausePlayback() {
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

    func finishPlayback(at endSeconds: TimeInterval) {
        let safeEnd = clamped(endSeconds, min: selectedStart, max: selectedEnd)
        playhead = safeEnd
        trimSlider.playheadValue = playhead
        trimSlider.ensureValueVisible(playhead)
        pausePlayback()
        seek(to: safeEnd)
    }

    func seek(to seconds: TimeInterval, completion: (() -> Void)? = nil) {
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

    func handlePlaybackTime(_ time: CMTime) {
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

    func updatePlayButton() {
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
}
