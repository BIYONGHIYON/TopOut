import UIKit
import CoreMedia

extension HoldVC {
    // MARK: Motion Detection
    func setupMotionCallbacks() {
        motionDetector.onEvent = { [weak self] event in
            DispatchQueue.main.async {
                self?.handleMotionEvent(event)
            }
        }
    }

    func handleMotionEvent(_ event: MotionEvent) {
        if recState != .buffering {
            // 녹화 종료 버튼을 누른 직후 motion queue에서 넘어온 finish 이벤트가
            // exporting 상태에서 도착할 수 있으므로 finish만 살립니다.
            guard recState == .exporting, case .finish = event else { return }
        }

        switch event {
        case .start(let pts):
            clipTimes.startPTS = pts
            setRecordingStatusText(
                isTopHoldMaskingEnabled
                    ? "등반 구간 감지 중 · 탑홀드 추적 중"
                    : "등반 구간 감지 중 · 최고점 추적 중"
            )

        case .retry:
            clipTimes.startPTS = nil
            setRecordingStatusText(defaultActiveRecordingStatusText())

        case .finish(let pts):
            if let startPTS = clipTimes.startPTS, CMTimeCompare(pts, startPTS) > 0 {
                // Store only the original detection boundaries. Padding is added after cleanup.
                appendDetectedClipRange(ClipRange(startPTS: startPTS, endPTS: pts))
            }
            clipTimes.startPTS = nil
            setRecordingStatusText("구간 저장됨 · 다음 등반 대기")
        }
    }

    func appendDetectedClipRange(_ range: ClipRange) {
        guard CMTimeCompare(range.endPTS, range.startPTS) > 0 else { return }

        if let lastIndex = clipTimes.completedRanges.indices.last {
            let last = clipTimes.completedRanges[lastIndex]
            let sameStart = abs(CMTimeGetSeconds(last.startPTS - range.startPTS)) < 0.10
            if sameStart {
                // Manual/automatic stop can snapshot the currently open range just before a
                // queued FINISH event arrives. Both ranges describe the same climb and share the
                // same start, so retain the later end instead of showing duplicate review clips.
                let laterEnd = CMTimeCompare(last.endPTS, range.endPTS) >= 0
                    ? last.endPTS
                    : range.endPTS
                clipTimes.completedRanges[lastIndex] = ClipRange(
                    startPTS: last.startPTS,
                    endPTS: laterEnd
                )
                return
            }
        }

        // Keep every detected range until recording finalization. Isolated short false positives
        // are cleaned up later, so valid early climbs are never discarded.
        clipTimes.completedRanges.append(range)
    }

    func normalizedMaskBox(_ box: CGRect, image: UIImage) -> CGRect {
        guard image.size.width > 0, image.size.height > 0 else { return .zero }

        let visible = visibleMaskSize(for: image)
        guard visible.width > 0, visible.height > 0 else { return .zero }

        let imageRect = CGRect(
            x: box.minX / visible.width * image.size.width,
            y: box.minY / visible.height * image.size.height,
            width: box.width / visible.width * image.size.width,
            height: box.height / visible.height * image.size.height
        ).standardized

        return CGRect(
            x: imageRect.minX / image.size.width,
            y: imageRect.minY / image.size.height,
            width: imageRect.width / image.size.width,
            height: imageRect.height / image.size.height
        ).standardized
    }
}
