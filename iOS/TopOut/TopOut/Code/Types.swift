//RGB, HSV, Blob, Pick, 상태 enum
import UIKit
import CoreML
import CoreMedia
import AVFoundation

typealias RGB = (r: Int, g: Int, b: Int)
typealias HSV = (h: Float, s: Float, v: Float)

enum ScreenMode {
    case camera
    case select
    case processing
    case recording
    case reviewing
}

enum RecordingState: Equatable {
    case idle
    case buffering
    case exporting
    case reviewing
}

// MARK: - Camera Orientation

/// Internal orientation model.
/// Avoids AVCaptureVideoOrientation, which is deprecated from iOS 17.
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

    /// Rotation angle used by AVCaptureConnection.videoRotationAngle.
    /// These values match the previous TopOut camera mapping.
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

enum HitStatus: CustomStringConvertible {
    case good
    case wall
    case tiny
    case empty

    var description: String {
        switch self {
        case .good: return "good"
        case .wall: return "wall"
        case .tiny: return "tiny"
        case .empty: return "empty"
        }
    }
}

enum FixReason {
    case wall
    case tiny
}

struct Blob {
    let indices: [Int]
    let box: CGRect

    static let empty = Blob(indices: [], box: .zero)

    var area: Int { indices.count }
    var isEmpty: Bool { indices.isEmpty }
}

struct Pick {
    let point: CGPoint
    let tol: Float
    let blob: Blob
    let status: HitStatus
    let dist: CGFloat
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
