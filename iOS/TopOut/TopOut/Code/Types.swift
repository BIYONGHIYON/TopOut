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

    static let background = RecordingStopNotice(
        title: "녹화가 자동 종료되었습니다",
        message: "앱이 백그라운드로 이동해 녹화를 안전하게 종료했습니다. 녹화된 영상은 결과 화면에서 확인할 수 있습니다."
    )

    static let captureInterruption = RecordingStopNotice(
        title: "녹화가 중단되었습니다",
        message: "다른 앱의 카메라·마이크 사용으로 촬영 장치가 중단되어 녹화를 안전하게 종료했습니다."
    )

    static let sessionError = RecordingStopNotice(
        title: "카메라 오류로 녹화가 종료되었습니다",
        message: "카메라 세션 오류가 발생해 현재까지 촬영된 영상을 안전하게 마무리했습니다."
    )

    static func lowStorage(availableBytes: Int64?) -> RecordingStopNotice {
        let availableText: String
        if let availableBytes {
            availableText = " 현재 사용 가능한 저장공간은 약 \(ByteCountFormatter.string(fromByteCount: availableBytes, countStyle: .file))입니다."
        } else {
            availableText = ""
        }

        return RecordingStopNotice(
            title: "저장공간이 부족합니다",
            message: "저장공간이 임박해 녹화를 자동 종료했습니다. 영상을 저장하기 전에 기기의 저장공간을 확보해주세요.\(availableText)"
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
    var endPTS: CMTime?
    var completedRanges: [ClipRange] = []

}
