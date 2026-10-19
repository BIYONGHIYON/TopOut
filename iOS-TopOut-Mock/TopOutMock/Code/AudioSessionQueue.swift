import Foundation
@preconcurrency import AVFoundation

/// AVAudioSession 구성·활성화는 오디오 서버 응답을 기다리며 오래 막힐 수 있어 주 스레드에서 호출하지 않는다.
/// 녹화용 구성, 마이크 해제, 결과 재생용 구성이 같은 직렬 큐를 사용해 호출 순서가 섞이지 않게 한다.
enum AudioSessionQueue {
    private static let queue = DispatchQueue(label: "topout.audio.session.queue", qos: .userInitiated)

    /// 녹화용 오디오 세션을 활성화하고 성공 여부를 반환한다. 주 스레드가 아닌 큐에서 호출한다.
    static func activateForRecording() -> Bool {
        #if DEBUG
        dispatchPrecondition(condition: .notOnQueue(.main))
        #endif
        return queue.sync {
            do {
                let audioSession = AVAudioSession.sharedInstance()
                try audioSession.setCategory(
                    .playAndRecord,
                    mode: .videoRecording,
                    options: [.defaultToSpeaker, .allowBluetoothHFP]
                )
                try audioSession.setActive(true)
                return true
            } catch {
                // 통화 등으로 오디오 세션 우선권을 얻지 못하면 마이크 없이 진행합니다.
                return false
            }
        }
    }

    /// 마이크 입력을 뗀 뒤 다른 앱 오디오가 이어지도록 세션을 비활성화한다. 주 스레드가 아닌 큐에서 호출한다.
    static func deactivateNotifyingOthers() {
        #if DEBUG
        dispatchPrecondition(condition: .notOnQueue(.main))
        #endif
        queue.sync {
            // 이미 다른 오디오 클라이언트가 세션을 소유한 경우 실패를 무시합니다.
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    /// 결과 재생용 오디오 세션으로 전환하고, 성공 여부를 주 스레드에서 전달한다.
    static func activateForPlayback(completion: @escaping (Bool) -> Void) {
        queue.async {
            let activated: Bool
            do {
                let audioSession = AVAudioSession.sharedInstance()
                try audioSession.setCategory(
                    .playback,
                    mode: .moviePlayback,
                    options: [.mixWithOthers]
                )
                try audioSession.setActive(true)
                activated = true
            } catch {
                // 통화 등으로 오디오 장치를 점유한 상태에서는 소리만 포기한다.
                activated = false
            }
            DispatchQueue.main.async { completion(activated) }
        }
    }
}
