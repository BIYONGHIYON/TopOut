// 색상, 영역, 선택 결과, 상태 형식
import UIKit
import CoreML
import CoreMedia
import AVFoundation

enum ScreenMode {
    case camera
    /// 준비 완료 뒤 측정한 매트 경계를 보정하는 화면
    case select
    case recording
    case reviewing
}

enum RecordingState: Equatable {
    case idle
    case buffering
    case exporting
    case reviewing
}

// MARK: - 카메라 방향

/// iOS 17부터 사용 중단된 형식을 대신하는 내부 방향 모델이다.
enum TopOutVideoOrientation: Equatable {
    case portrait
    case portraitUpsideDown
    case landscapeLeft
    case landscapeRight

    var isLandscape: Bool {
        switch self {
        case .landscapeLeft, .landscapeRight:
            return true
        case .portrait, .portraitUpsideDown:
            return false
        }
    }

    var interfaceMask: UIInterfaceOrientationMask {
        switch self {
        case .portrait:
            return .portrait
        case .portraitUpsideDown:
            return .portraitUpsideDown
        case .landscapeLeft:
            return .landscapeLeft
        case .landscapeRight:
            return .landscapeRight
        }
    }

    /// 카메라 연결에 적용할 기존 TopOut 회전 각도다.
    var captureRotationAngle: CGFloat {
        switch self {
        case .portrait:
            return 90
        case .landscapeRight:
            return 0
        case .portraitUpsideDown:
            return 270
        case .landscapeLeft:
            return 180
        }
    }



    @available(iOS, introduced: 4.0, deprecated: 17.0)
    var legacyCaptureVideoOrientation: AVCaptureVideoOrientation {
        switch self {
        case .portrait:
            return .portrait
        case .portraitUpsideDown:
            return .portraitUpsideDown
        case .landscapeLeft:
            return .landscapeLeft
        case .landscapeRight:
            return .landscapeRight
        }
    }


    static func from(deviceOrientation: UIDeviceOrientation, fallbackIsLandscape: Bool) -> TopOutVideoOrientation {
        switch deviceOrientation {
        case .portrait:
            return .portrait
        case .portraitUpsideDown:
            return .portraitUpsideDown
        case .landscapeLeft:
            return .landscapeRight
        case .landscapeRight:
            return .landscapeLeft
        default:
            return fallbackIsLandscape ? .landscapeRight : .portrait
        }
    }

    static func from(interfaceOrientation: UIInterfaceOrientation, fallbackIsLandscape: Bool) -> TopOutVideoOrientation {
        switch interfaceOrientation {
        case .landscapeLeft:
            return .landscapeLeft
        case .landscapeRight:
            return .landscapeRight
        case .portraitUpsideDown:
            return .portraitUpsideDown
        case .portrait:
            return .portrait
        case .unknown:
            return fallbackIsLandscape ? .landscapeRight : .portrait
        @unknown default:
            return fallbackIsLandscape ? .landscapeRight : .portrait
        }
    }
}


struct RecordingStopNotice {
    let title: String
    let message: String

    static var background: RecordingStopNotice {
        RecordingStopNotice(title: L("notice.background_title"), message: L("notice.background_message"))
    }

    static var captureInterruption: RecordingStopNotice {
        RecordingStopNotice(title: L("notice.interruption_title"), message: L("notice.interruption_message"))
    }

    static var sessionError: RecordingStopNotice {
        RecordingStopNotice(title: L("notice.camera_error_title"), message: L("notice.camera_error_message"))
    }

    static func lowStorage(availableBytes: Int64?) -> RecordingStopNotice {
        let availableText: String
        if let availableBytes {
            let gigabytes = Double(availableBytes) / 1_073_741_824.0
            let formattedBytes: String
            if gigabytes >= 1 {
                formattedBytes = String(
                    format: "%.1f GB",
                    locale: AppLanguageManager.locale,
                    gigabytes
                )
            } else {
                formattedBytes = String(
                    format: "%.0f MB",
                    locale: AppLanguageManager.locale,
                    Double(availableBytes) / 1_048_576.0
                )
            }
            availableText = L(
                "notice.available_storage",
                formattedBytes
            )
        } else {
            availableText = ""
        }

        return RecordingStopNotice(
            title: L("notice.low_storage_title"),
            message: L("notice.low_storage_message", availableText)
        )
    }
}

enum AppError: Error {
    case modelNotReady
    case encoderOutputMissing
    case renderFailed
}

struct ClipRange {
    var startPTS: CMTime
    var endPTS: CMTime
}

struct ReviewClipRange {
    var startSeconds: TimeInterval
    var endSeconds: TimeInterval
    var unpaddedStartSeconds: TimeInterval
    var unpaddedEndSeconds: TimeInterval

    init(
        startSeconds: TimeInterval,
        endSeconds: TimeInterval,
        unpaddedStartSeconds: TimeInterval? = nil,
        unpaddedEndSeconds: TimeInterval? = nil
    ) {
        self.startSeconds = startSeconds
        self.endSeconds = endSeconds
        self.unpaddedStartSeconds = unpaddedStartSeconds ?? startSeconds
        self.unpaddedEndSeconds = unpaddedEndSeconds ?? endSeconds
    }
}

struct ClipTimes {
    var startPTS: CMTime?
    var completedRanges: [ClipRange] = []
}
