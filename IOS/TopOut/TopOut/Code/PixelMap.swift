//이미지 RGB/평균색 추출
import UIKit

struct PixelMap {
    let width: Int
    let height: Int
    private let data: [UInt8] // RGBA

    init?(image: UIImage) {
        guard let cgImage = image.cgImage else { return nil }

        width = cgImage.width
        height = cgImage.height

        var raw = [UInt8](repeating: 0, count: width * height * 4)
        guard let ctx = CGContext(
            data: &raw,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }

        ctx.draw(cgImage, in: CGRect(x: 0, y: 0, width: CGFloat(width), height: CGFloat(height)))
        data = raw
    }

    func rgb(at point: CGPoint, in image: UIImage) -> RGB {
        let px = Int((point.x / image.size.width) * CGFloat(width))
        let py = Int((point.y / image.size.height) * CGFloat(height))
        return rgb(x: px, y: py)
    }

    func avgRGB(at point: CGPoint, in image: UIImage, radius: Int) -> RGB {
        let cx = Int((point.x / image.size.width) * CGFloat(width))
        let cy = Int((point.y / image.size.height) * CGFloat(height))

        var r = 0
        var g = 0
        var b = 0
        var count = 0

        for dy in -radius...radius {
            for dx in -radius...radius {
                let c = rgb(x: cx + dx, y: cy + dy)
                r += c.r
                g += c.g
                b += c.b
                count += 1
            }
        }

        return (r / count, g / count, b / count)
    }

    private func rgb(x: Int, y: Int) -> RGB {
        let x = max(0, min(x, width - 1))
        let y = max(0, min(y, height - 1))
        let i = (y * width + x) * 4
        return (Int(data[i]), Int(data[i + 1]), Int(data[i + 2]))
    }
}
