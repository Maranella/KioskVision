import Foundation
import CoreGraphics
import KioskVisionCore

/// Pluggable OCR backend.
protocol OcrEngine: AnyObject {
    func recognize(
        image: CGImage,
        region: CGRect?,
        upscale: Float
    ) async -> [DetectedTextBlock]
}

extension OcrEngine {
    func recognize(image: CGImage) async -> [DetectedTextBlock] {
        await recognize(image: image, region: nil, upscale: 1)
    }
}
