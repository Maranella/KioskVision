import Foundation
import CoreGraphics
import CoreVideo
import Vision
import KioskVisionCore

/// One kiosk-screen rectangle candidate from Vision, including the
/// four corner points so downstream code can build a perspective
/// homography. Normalized coords (0..1), origin top-left.
struct DetectedRectangle: Equatable {
    let normalizedBBox: CGRect
    let confidence: Float
    let aspectRatio: Float
    let topLeft: CGPoint
    let topRight: CGPoint
    let bottomRight: CGPoint
    let bottomLeft: CGPoint

    /// Builds a `ScreenCandidate` in normalized screen space (unit
    /// square) suitable for feeding `ScreenTracker`.
    ///
    /// Coverage is the axis-aligned area of the bounding box, which
    /// is cheap and good enough to reject frames where the detected
    /// rectangle fills the viewfinder (window light, white wall) or
    /// is too small to be a kiosk panel.
    func toScreenCandidate() -> ScreenCandidate {
        let coverage = Float(normalizedBBox.width * normalizedBBox.height)
        let quad = Quad(
            topLeft: ScreenPoint(x: Float(topLeft.x), y: Float(topLeft.y)),
            topRight: ScreenPoint(x: Float(topRight.x), y: Float(topRight.y)),
            bottomRight: ScreenPoint(
                x: Float(bottomRight.x), y: Float(bottomRight.y)),
            bottomLeft: ScreenPoint(
                x: Float(bottomLeft.x), y: Float(bottomLeft.y)))
        return ScreenCandidate(
            quad: quad, confidence: confidence, coverage: coverage)
    }
}

/// Thin wrapper around `VNDetectRectanglesRequest` tuned for a
/// kiosk-shaped bright display against a darker housing.
enum VisionRectangleDetector {

    static func detect(
        in pixelBuffer: CVPixelBuffer
    ) throws -> DetectedRectangle? {
        let request = VNDetectRectanglesRequest()
        request.minimumConfidence = 0.6
        request.maximumObservations = 1
        request.minimumAspectRatio = 0.4
        request.maximumAspectRatio = 2.5
        request.minimumSize = 0.1
        request.quadratureTolerance = 25

        let handler = VNImageRequestHandler(
            cvPixelBuffer: pixelBuffer,
            orientation: .up,
            options: [:])
        try handler.perform([request])
        guard let obs = request.results?.first else { return nil }

        // Vision returns bottom-left origin; flip Y so top-left.
        let bb = obs.boundingBox
        let flipped = CGRect(
            x: bb.minX,
            y: 1.0 - bb.maxY,
            width: bb.width,
            height: bb.height)
        let aspect = Float(flipped.width / max(flipped.height, 1e-6))
        return DetectedRectangle(
            normalizedBBox: flipped,
            confidence: obs.confidence,
            aspectRatio: aspect,
            topLeft: flipY(obs.topLeft),
            topRight: flipY(obs.topRight),
            bottomRight: flipY(obs.bottomRight),
            bottomLeft: flipY(obs.bottomLeft))
    }

    private static func flipY(_ p: CGPoint) -> CGPoint {
        CGPoint(x: p.x, y: 1.0 - p.y)
    }
}
