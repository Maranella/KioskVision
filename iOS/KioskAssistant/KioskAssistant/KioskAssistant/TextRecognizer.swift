import Foundation
import CoreGraphics
import CoreVideo
import Vision
import KioskVisionCore

/// Vision-framework text recognition. Produces `TextObservation`s
/// in normalized frame coords (origin top-left, 0..1).
enum TextRecognizer {

    static func detect(
        in pixelBuffer: CVPixelBuffer
    ) throws -> [KioskVisionCore.TextObservation] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false
        request.minimumTextHeight = 0.012

        let handler = VNImageRequestHandler(
            cvPixelBuffer: pixelBuffer,
            orientation: .up,
            options: [:])
        try handler.perform([request])
        guard let observations = request.results else { return [] }
        return observations.compactMap { obs -> KioskVisionCore.TextObservation? in
            guard let top = obs.topCandidates(1).first else { return nil }
            let bb = obs.boundingBox   // 0..1, bottom-left origin
            let flipped = CGRect(
                x: bb.minX,
                y: 1.0 - bb.maxY,
                width: bb.width,
                height: bb.height)
            return TextObservation(
                text: top.string,
                normalizedBBox: flipped,
                confidence: Double(top.confidence))
        }
    }
}
