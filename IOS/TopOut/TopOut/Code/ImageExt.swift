//UIImage 방향 보정 등
import UIKit

extension UIImage {
    var bounds: CGRect {
        CGRect(origin: .zero, size: size)
    }

    func normalizedUpImage() -> UIImage {
        if imageOrientation == .up { return self }

        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        format.opaque = false

        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        return renderer.image { _ in
            draw(in: CGRect(origin: .zero, size: size))
        }
    }
}
