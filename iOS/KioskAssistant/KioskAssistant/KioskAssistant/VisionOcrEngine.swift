import Foundation
import CoreGraphics
import Vision
import KioskVisionCore

/// Apple Vision-framework OCR used as a fallback until Tesseract
/// lands. Vision does NOT support Georgian — Latin scripts only.
final class VisionOcrEngine: OcrEngine {

    func recognize(
        image: CGImage, region: CGRect?, upscale: Float
    ) async -> [DetectedTextBlock] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false
        if let region {
            request.regionOfInterest = Self.normalized(
                region, inWidth: image.width, inHeight: image.height)
        }

        return await withCheckedContinuation { continuation in
            let handler = VNImageRequestHandler(
                cgImage: image, options: [:])
            do {
                try handler.perform([request])
            } catch {
                continuation.resume(returning: [])
                return
            }
            guard let observations = request.results else {
                continuation.resume(returning: [])
                return
            }
            let imageWidth = Float(image.width)
            let imageHeight = Float(image.height)
            var blocks: [DetectedTextBlock] = []
            for obs in observations {
                guard let top = obs.topCandidates(1).first else { continue }
                let normalized = obs.boundingBox
                let left = Float(normalized.minX) * imageWidth
                let right = Float(normalized.maxX) * imageWidth
                let topY = (1 - Float(normalized.maxY)) * imageHeight
                let bottomY = (1 - Float(normalized.minY)) * imageHeight
                blocks.append(DetectedTextBlock(
                    text: top.string,
                    boundingBox: TextBox(
                        left: left, top: topY,
                        right: right, bottom: bottomY),
                    confidence: Float(top.confidence)))
            }
            continuation.resume(returning: blocks)
        }
    }

    private static func normalized(
        _ region: CGRect, inWidth width: Int, inHeight height: Int
    ) -> CGRect {
        let w = CGFloat(width)
        let h = CGFloat(height)
        return CGRect(
            x: region.origin.x / w,
            y: 1 - (region.origin.y + region.size.height) / h,
            width: region.size.width / w,
            height: region.size.height / h)
    }
}
