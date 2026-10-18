import Foundation
import CoreGraphics
import CoreVideo
import KioskVisionCore

/// Everything the perception layer figured out for one frame.
struct FramingAssessmentResult: Equatable {
    let signals: FramingSignals
    let problems: [FramingProblem]
    let controls: [ActionableControl]
    let handPose: HandPose
    /// Normalized-space kiosk-screen quadrilateral smoothed across
    /// frames by `ScreenTracker`. Nil when the current detection
    /// is unreliable or stale; downstream guidance must not point
    /// to coordinates derived from an absent screen.
    let trackedScreen: TrackedScreen?
    /// Candidate from this single frame, pre-tracker, kept so the
    /// coordinator can feed it into `ScreenTracker` on the main
    /// actor without re-running the detector.
    let candidate: ScreenCandidate?
    /// 4×4 cell-mean luminance signature from `ImageStatistics`.
    /// Used by the coordinator for cheap screen-change detection
    /// (OCR-free). Empty when the frame was unusable.
    let signature16: [Double]
}

/// Runs the per-frame framing + optional OCR + optional hand-pose
/// pipeline. Called from `PerceptionCoordinator` on a background
/// task so the main actor stays free.
enum FramingAssessor {

    static func assess(
        pixelBuffer: CVPixelBuffer,
        thresholds: FramingThresholds,
        runTextRecognition: Bool,
        runHandPose: Bool
    ) -> FramingAssessmentResult {
        let stats = ImageStatistics.stats(for: pixelBuffer)
        let rect = (try? VisionRectangleDetector.detect(
            in: pixelBuffer)) ?? nil

        let rectangle: RectangleDetection = {
            guard let rect else { return .none }
            let area = Double(
                rect.normalizedBBox.width * rect.normalizedBBox.height)
            let aspect = Double(rect.aspectRatio) >= 1
                ? Double(rect.aspectRatio)
                : 1 / max(Double(rect.aspectRatio), 1e-6)
            let geometry = RectangleGeometry(
                areaRatio: area,
                aspectRatio: aspect,
                confidence: Double(rect.confidence))
            if Double(rect.confidence)
                < thresholds.minRectangleConfidence
            {
                return .lowConfidence(
                    confidence: Double(rect.confidence))
            }
            // partialMarginPx is in pixels against the raw image.
            // We're already in normalized space here, so use a
            // small normalized epsilon (0.01 ≈ 1% of each axis)
            // which is a reasonable analogue.
            if Self.touchesEdge(rect.normalizedBBox, margin: 0.01) {
                return .partial(geometry)
            }
            return .present(geometry)
        }()

        let signals = FramingSignals(
            rectangle: rectangle,
            laplacianVariance: stats.laplacianVariance,
            highlightRatio: stats.highlightRatio)

        let problems = FramingDiagnostics.diagnose(
            signals: signals, thresholds: thresholds)

        let controls: [ActionableControl] = {
            guard runTextRecognition else { return [] }
            do {
                let observations = try TextRecognizer.detect(
                    in: pixelBuffer)
                return ControlClassifier.classify(
                    textObservations: observations)
            } catch {
                return []
            }
        }()

        let handPose: HandPose = {
            guard runHandPose, problems.isEmpty else {
                return .notDetected
            }
            return (try? HandPoseDetector.detect(in: pixelBuffer))
                ?? .notDetected
        }()

        // Screen geometry comes from the ported `ScreenDetector`
        // (Otsu threshold + flood fill + convex hull + 4-point
        // area-maximizing search) running on the 64×64 luminance
        // grid — matches Android's `LiveAssistPipeline.maybeDetectScreen`
        // exactly. The quad is in 64×64 grid coords; scale to 0..1
        // frame coords so the homography stays consistent with
        // label bboxes + fingertip (all 0..1 normalized).
        //
        // Vision's rectangle detector still runs above for the
        // framing-diagnostics path ("point at the kiosk" cues),
        // because it is tuned specifically for that user coaching
        // and reports a confidence more aligned with Vision's own
        // heuristics than with ScreenDetector's score.
        let candidate: ScreenCandidate? = {
            let grid = stats.luminance64
            let side = ImageStatistics.detectionGridSide
            guard grid.count == side * side else { return nil }
            let raw = ScreenDetector().detect(
                luminance: grid, width: side, height: side)
            guard let raw else { return nil }
            let inv = 1.0 / Float(side)
            return ScreenCandidate(
                quad: raw.quad.scaled(x: inv, y: inv),
                confidence: raw.confidence,
                coverage: raw.coverage)
        }()

        return FramingAssessmentResult(
            signals: signals,
            problems: problems,
            controls: controls,
            handPose: handPose,
            trackedScreen: nil,
            candidate: candidate,
            signature16: stats.signature16)
    }

    private static func touchesEdge(
        _ bbox: CGRect, margin: Double
    ) -> Bool {
        let m = CGFloat(margin)
        return bbox.minX <= m || bbox.minY <= m
            || bbox.maxX >= 1 - m || bbox.maxY >= 1 - m
    }
}
