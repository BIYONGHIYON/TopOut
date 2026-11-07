import UIKit
import CoreMedia

extension HoldVC {
    // MARK: - 움직임 탐지
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
            setRecordingStatusText(L("camera.tracking_peak"))

        case .retry:
            clipTimes.startPTS = nil
            setRecordingStatusText(defaultActiveRecordingStatusText())

        case .finish(let pts):
            if let startPTS = clipTimes.startPTS, CMTimeCompare(pts, startPTS) > 0 {
                // 원본 탐지 시각만 저장하고 정리 뒤 여백을 넣는다.
                appendDetectedClipRange(ClipRange(startPTS: startPTS, endPTS: pts))
            }
            clipTimes.startPTS = nil
            setRecordingStatusText(L("camera.clip_saved"))
        }
    }

    func appendDetectedClipRange(_ range: ClipRange) {
        guard CMTimeCompare(range.endPTS, range.startPTS) > 0 else { return }

        if let lastIndex = clipTimes.completedRanges.indices.last {
            let last = clipTimes.completedRanges[lastIndex]
            let sameStart = abs(CMTimeGetSeconds(last.startPTS - range.startPTS)) < 0.10
            if sameStart {
                // 같은 시작점의 중지 스냅샷과 종료 이벤트는 더 늦은 끝점 하나로 합친다.
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

        // 모든 탐지 구간을 유지하고 녹화 마무리에서 짧은 오탐만 제거한다.
        clipTimes.completedRanges.append(range)
    }
}
