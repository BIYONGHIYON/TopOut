import UIKit

// MARK: - 녹화 형식

enum RecordingResolution: Int, CaseIterable, Hashable {
    case fhd = 0
    case uhd = 1

    var title: String {
        switch self {
        case .fhd: return "FHD"
        case .uhd: return "4K"
        }
    }

    var portraitSize: CGSize {
        switch self {
        case .fhd: return CGSize(width: 1080, height: 1920)
        case .uhd: return CGSize(width: 2160, height: 3840)
        }
    }

    var shortSide: Int32 { Int32(min(portraitSize.width, portraitSize.height)) }
    var longSide: Int32 { Int32(max(portraitSize.width, portraitSize.height)) }

    func bitRate(for fps: RecordingFrameRate, aspectRatio: RecordingAspectRatio = .story916) -> Int {
        let base: Int
        switch (self, fps) {
        case (.fhd, .fps30): base = 8_000_000
        case (.fhd, .fps60): base = 14_000_000
        case (.uhd, .fps30): base = 45_000_000
        case (.uhd, .fps60): base = 70_000_000
        }
        return Int(Double(base) * aspectRatio.pixelScaleComparedToStory)
    }
}

enum RecordingFrameRate: Int, CaseIterable, Hashable {
    case fps30 = 30
    case fps60 = 60

    var title: String { "\(rawValue)fps" }
    var fps: Double { Double(rawValue) }
}

enum RecordingAspectRatio: Int, CaseIterable {
    case story916 = 0
    case feed45 = 1
    case portrait34 = 2
    case square11 = 3

    var title: String {
        title(isLandscape: false)
    }

    func title(isLandscape: Bool) -> String {
        switch (self, isLandscape) {
        case (.story916, false): return "9:16"
        case (.story916, true): return "16:9"
        case (.feed45, false): return "4:5"
        case (.feed45, true): return "5:4"
        case (.portrait34, false): return "3:4"
        case (.portrait34, true): return "4:3"
        case (.square11, _): return "1:1"
        }
    }

    var subtitle: String {
        switch self {
        case .story916: return L("settings.story_reels")
        case .feed45: return L("settings.instagram_feed")
        case .portrait34: return L("settings.portrait_post")
        case .square11: return L("settings.square_post")
        }
    }

    /// 세로 영상의 가로 대 세로 비율이다.
    var widthOverHeight: CGFloat {
        switch self {
        case .story916: return 9.0 / 16.0
        case .feed45: return 4.0 / 5.0
        case .portrait34: return 3.0 / 4.0
        case .square11: return 1.0
        }
    }

    /// 실제 촬영/프리뷰 화면의 width / height.
    /// 기기를 가로로 돌리면 9:16 → 16:9, 4:5 → 5:4처럼 동일한 포맷을 가로 기준으로 뒤집어 사용합니다.
    func widthOverHeight(isLandscape: Bool) -> CGFloat {
        guard isLandscape, widthOverHeight > 0 else { return widthOverHeight }
        return 1.0 / widthOverHeight
    }

    var pixelScaleComparedToStory: Double {
        let heightComparedToStory: Double
        switch self {
        case .story916: heightComparedToStory = 1.0
        case .feed45: heightComparedToStory = (5.0 / 4.0) / (16.0 / 9.0)
        case .portrait34: heightComparedToStory = (4.0 / 3.0) / (16.0 / 9.0)
        case .square11: heightComparedToStory = 1.0 / (16.0 / 9.0)
        }
        return max(0.55, min(1.0, heightComparedToStory))
    }

    func outputSize(for resolution: RecordingResolution) -> CGSize {
        let base = resolution.portraitSize
        let width = base.width
        let height = width / widthOverHeight
        return CGSize(width: even(width), height: even(min(height, base.height)))
    }

    func displayOutputSize(for resolution: RecordingResolution, isLandscape: Bool) -> CGSize {
        let portrait = outputSize(for: resolution)
        guard isLandscape, portrait.height > portrait.width else { return portrait }
        return CGSize(width: portrait.height, height: portrait.width)
    }

    /// 미리보기, 촬영, 녹화, 움직임 분석이 함께 쓰는 정규화 중앙 영역이다.
    func normalizedSourceCropRect(isLandscape: Bool) -> CGRect {
        let sourceAspect = RecordingAspectRatio.story916.widthOverHeight(isLandscape: isLandscape)
        let targetAspect = widthOverHeight(isLandscape: isLandscape)

        if targetAspect > sourceAspect {
            let activeHeight = sourceAspect / targetAspect
            let y = (1 - activeHeight) * 0.5
            return CGRect(x: 0, y: y, width: 1, height: activeHeight)
        } else if targetAspect < sourceAspect {
            let activeWidth = targetAspect / sourceAspect
            let x = (1 - activeWidth) * 0.5
            return CGRect(x: x, y: 0, width: activeWidth, height: 1)
        } else {
            return CGRect(x: 0, y: 0, width: 1, height: 1)
        }
    }

    func displayRect(in bounds: CGRect, isLandscape: Bool) -> CGRect {
        let aspect = widthOverHeight(isLandscape: isLandscape)
        guard bounds.width > 0, bounds.height > 0, aspect > 0 else { return bounds }

        let availableAspect = bounds.width / bounds.height
        if availableAspect > aspect {
            let height = bounds.height
            let width = height * aspect
            return CGRect(x: bounds.midX - width * 0.5, y: bounds.minY, width: width, height: height).integral
        } else {
            let width = bounds.width
            let height = width / aspect
            return CGRect(x: bounds.minX, y: bounds.midY - height * 0.5, width: width, height: height).integral
        }
    }

    private func even(_ value: CGFloat) -> CGFloat {
        let rounded = Int(value.rounded())
        return CGFloat(rounded - rounded % 2)
    }
}
