import Foundation
import CoreGraphics
import ImageIO
import UIKit
import KioskVisionCore

#if canImport(SwiftyTesseract)
import SwiftyTesseract
#endif

/// Tesseract-backed OCR engine. Mirrors Android `KioskOcrEngine.kt`
/// (`kat+eng`, `OEM_LSTM_ONLY`, `PSM_SPARSE_TEXT`, conditional 2×
/// upscale, per-word confidence /100).
///
/// Activation path:
/// 1. Add `SwiftyTesseract` SPM package to the KioskAssistant target
///    (https://github.com/SwiftyTesseract/SwiftyTesseract, 4.0.1).
/// 2. Make sure `tessdata/` is bundled as a **folder reference**
///    (blue folder in Xcode), not a group. SwiftyTesseract 4 resolves
///    traineddata via `bundle.url(forResource:withExtension:
///    subdirectory:)` and needs the subdirectory to survive bundling.
///
/// Returns a single-string `DetectedTextBlock` per OCR call for now;
/// full word-level bounding boxes will come once the first live-
/// device run confirms the SwiftyTesseract build is wired correctly.
@MainActor
final class TesseractOcrEngine: OcrEngine {

    enum Status: Equatable {
        /// SwiftyTesseract SPM package has not been added yet.
        case notLinked
        /// Package present and traineddata on disk; ready to use.
        case ready
        /// Package present but traineddata missing; log path.
        case missingData(path: String)
    }

    static let status: Status = {
        #if canImport(SwiftyTesseract)
        let kat = Bundle.main.url(
            forResource: "kat", withExtension: "traineddata",
            subdirectory: "tessdata")
        let eng = Bundle.main.url(
            forResource: "eng", withExtension: "traineddata",
            subdirectory: "tessdata")
        if kat != nil && eng != nil { return .ready }
        return .missingData(
            path: Bundle.main.resourcePath ?? "<no resource path>")
        #else
        return .notLinked
        #endif
    }()

    private let upscaleFactor: Float
    private let minConfidence: Float
    #if canImport(SwiftyTesseract)
    private let tesseract: Tesseract
    #endif

    /// - Parameters:
    ///   - upscale: 1 to disable; 2 for Android's measured 2× sweep
    ///     that lifted exact recognition from 52% to 67% on
    ///     rendered kiosk corpora.
    ///   - minConfidence: per-word confidence cut-off (0..1); below
    ///     this the word is dropped before phrase grouping. Kept for
    ///     when the per-block API comes online.
    init(upscale: Float = 2.0, minConfidence: Float = 0.3) {
        self.upscaleFactor = upscale
        self.minConfidence = minConfidence
        #if canImport(SwiftyTesseract)
        // SwiftyTesseract 4 takes a `LanguageModelDataSource` (a
        // protocol `Bundle` conforms to); the label is `dataSource:`,
        // not `bundle:`. Pass `Bundle.main` so the engine reads
        // traineddata from the app bundle's `tessdata/` subdirectory.
        //
        // Page-segmentation mode: SwiftyTesseract 4 removed the
        // `pageSegmentationMode` property in favour of an internal
        // default (PSM_SINGLE_BLOCK). Android uses PSM_SPARSE_TEXT
        // for scattered kiosk labels. Default is good enough for a
        // first on-device check; revisit once per-word blocks +
        // PhraseGrouper come online — if quality is poor, we switch
        // to the underlying libtesseract C API via a thin bridge.
        self.tesseract = Tesseract(
            languages: [.custom("kat"), .custom("eng")],
            dataSource: Bundle.main,
            engineMode: .lstmOnly)
        #endif
    }

    func recognize(
        image: CGImage, region: CGRect?, upscale: Float
    ) async -> [DetectedTextBlock] {
        #if canImport(SwiftyTesseract)
        guard case .ready = Self.status else { return [] }
        let factor = max(upscale, 1)
        let cropped = Self.crop(image, to: region) ?? image
        guard let scaled = Self.upscale(cropped, factor: factor) else {
            return []
        }
        let uiImage = UIImage(cgImage: scaled)
        let result = tesseract.performOCR(on: uiImage)
        switch result {
        case let .success(text):
            let trimmed = text.trimmingCharacters(
                in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return [] }
            // Minimal single-block return. Replace with per-word
            // boxes + PhraseGrouper once the SwiftyTesseract build
            // is confirmed on-device — SwiftyTesseract 4 exposes
            // per-word data via helpers whose exact signature we
            // should confirm against the actual installed version.
            let box = TextBox(
                left: 0, top: 0,
                right: Float(scaled.width) / factor,
                bottom: Float(scaled.height) / factor)
            return [DetectedTextBlock(
                text: trimmed,
                boundingBox: box,
                confidence: 0.8)]
        case .failure:
            return []
        }
        #else
        _ = (image, region, upscale)
        return []
        #endif
    }

    private static func crop(
        _ image: CGImage, to normalized: CGRect?
    ) -> CGImage? {
        guard let normalized else { return image }
        let w = CGFloat(image.width)
        let h = CGFloat(image.height)
        let rect = CGRect(
            x: normalized.minX * w,
            y: normalized.minY * h,
            width: normalized.width * w,
            height: normalized.height * h).integral
        return image.cropping(to: rect)
    }

    private static func upscale(
        _ image: CGImage, factor: Float
    ) -> CGImage? {
        if factor <= 1 { return image }
        let w = Int(CGFloat(image.width) * CGFloat(factor))
        let h = Int(CGFloat(image.height) * CGFloat(factor))
        guard let colorSpace = image.colorSpace,
              let ctx = CGContext(
                data: nil, width: w, height: h,
                bitsPerComponent: image.bitsPerComponent,
                bytesPerRow: 0,
                space: colorSpace,
                bitmapInfo: image.bitmapInfo.rawValue)
        else { return nil }
        ctx.interpolationQuality = .high
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        return ctx.makeImage()
    }
}
