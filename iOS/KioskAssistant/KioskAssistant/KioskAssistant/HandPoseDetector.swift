import CoreGraphics
import CoreVideo
import Vision
import KioskVisionCore

/// Wraps `VNDetectHumanHandPoseRequest` and returns the index tip
/// as a normalized point (origin top-left, 0...1).
///
/// Mirrors Android `hand/HandLandmarkTracker.kt`: when the fingertip
/// lands within `edgeMargin` of the frame edge the sample is dropped
/// as `.notDetected`. The finger is almost certainly leaving the
/// frame (or the detector locked onto a thumbnail of a hand in the
/// corner of a cluttered scene); either way the direction cue would
/// be unreliable.
enum HandPoseDetector {

    /// Fraction of a frame edge within which the fingertip is
    /// considered off-frame. Android uses the same ratio.
    static let edgeMargin: CGFloat = 0.02

    static func detect(
        in pixelBuffer: CVPixelBuffer
    ) throws -> HandPose {
        let request = VNDetectHumanHandPoseRequest()
        request.maximumHandCount = 1
        let handler = VNImageRequestHandler(
            cvPixelBuffer: pixelBuffer,
            orientation: .up,
            options: [:])
        try handler.perform([request])
        guard let obs = request.results?.first else {
            return .notDetected
        }
        let point: VNRecognizedPoint
        do {
            point = try obs.recognizedPoint(.indexTip)
        } catch {
            return .notDetected
        }
        let normalized = CGPoint(
            x: CGFloat(point.location.x),
            y: 1.0 - CGFloat(point.location.y))
        if normalized.x <= edgeMargin
            || normalized.y <= edgeMargin
            || normalized.x >= 1 - edgeMargin
            || normalized.y >= 1 - edgeMargin
        {
            return .notDetected
        }
        return HandPose(
            indexTipNormalized: normalized,
            confidence: Double(point.confidence))
    }
}
