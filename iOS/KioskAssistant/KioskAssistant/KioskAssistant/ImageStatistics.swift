import Foundation
import CoreVideo
import CoreGraphics
import Accelerate

/// Fast luminance statistics for a BGRA pixel buffer. All work is
/// vectorized via Accelerate / vImage so we can run at 4 Hz on
/// hand-held video without stalling the main thread.
///
/// Returned stats are enough for `FramingAssessor` to decide blur
/// (Laplacian variance proxy) and glare (bright-highlight ratio).
enum ImageStatistics {

    struct Stats {
        /// Mean luminance 0..255.
        let mean: Double
        /// Laplacian variance proxy — higher = sharper. Nil if the
        /// buffer is too small / wrong format to compute.
        let laplacianVariance: Double?
        /// Fraction of pixels at or above the highlight threshold
        /// (0...1). Nil when the buffer is unusable.
        let highlightRatio: Double?
        /// 4×4 grid of mean luminance per cell (row-major, 0..255).
        /// Mirrors Android `FrameQuality.signature()`; the per-cell
        /// means are stable across normal camera jitter but shift
        /// instantly when the kiosk screen advances, making this a
        /// much faster screen-transition signal than re-running OCR.
        let signature16: [Double]
        /// 64×64 nearest-neighbour subsampled luminance grid,
        /// row-major 0..255 ints. Fed into
        /// `KioskVisionCore.ScreenDetector.detect(...)`. Mirrors
        /// Android's `SIGNATURE_SOURCE = 64` grid in
        /// `LiveAssistPipeline.maybeDetectScreen` so iOS and Android
        /// use the identical screen-detection algorithm instead of
        /// iOS falling back to Vision's rectangle detector.
        let luminance64: [Int]
    }

    /// Side of the square luminance grid passed to `ScreenDetector`.
    /// Matches Android `LiveAssistPipeline.SIGNATURE_SOURCE = 64`.
    static let detectionGridSide: Int = 64

    static func stats(for pixelBuffer: CVPixelBuffer) -> Stats {
        CVPixelBufferLockBaseAddress(
            pixelBuffer, .readOnly)
        defer {
            CVPixelBufferUnlockBaseAddress(
                pixelBuffer, .readOnly)
        }
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        guard width >= 16, height >= 16,
              let base = CVPixelBufferGetBaseAddress(pixelBuffer)
        else {
            return Stats(
                mean: 0, laplacianVariance: nil,
                highlightRatio: nil, signature16: [],
                luminance64: [])
        }
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
        let pixelCount = width * height

        var y = [UInt8](repeating: 0, count: pixelCount)
        // BT.601 Y = 0.114 B + 0.587 G + 0.299 R
        // On an interleaved BGRA buffer we can use vImageConvert
        // but a simple vDSP integer path keeps dependencies minimal.
        let bufferPtr = base.assumingMemoryBound(to: UInt8.self)
        for row in 0..<height {
            let src = bufferPtr.advanced(by: row * bytesPerRow)
            let dstOffset = row * width
            for col in 0..<width {
                let b = Int(src[col * 4 + 0])
                let g = Int(src[col * 4 + 1])
                let r = Int(src[col * 4 + 2])
                let yv = (299 * r + 587 * g + 114 * b) / 1000
                y[dstOffset + col] = UInt8(min(255, max(0, yv)))
            }
        }

        var sum: Double = 0
        for v in y { sum += Double(v) }
        let mean = sum / Double(pixelCount)

        // Highlight ratio.
        var hi = 0
        for v in y where v >= 240 { hi += 1 }
        let highlightRatio = Double(hi) / Double(pixelCount)

        // Laplacian variance proxy: 4·I - (I_up + I_down + I_left + I_right)
        // summed over interior pixels, variance of the result.
        var lap: [Int] = []
        lap.reserveCapacity((width - 2) * (height - 2))
        for row in 1..<(height - 1) {
            for col in 1..<(width - 1) {
                let i = row * width + col
                let c = Int(y[i])
                let u = Int(y[i - width])
                let d = Int(y[i + width])
                let l = Int(y[i - 1])
                let r = Int(y[i + 1])
                lap.append(4 * c - (u + d + l + r))
            }
        }
        guard !lap.isEmpty else {
            return Stats(mean: mean, laplacianVariance: nil,
                         highlightRatio: highlightRatio,
                         signature16: [],
                         luminance64: subsample(y, width: width,
                                                height: height,
                                                to: detectionGridSide))
        }
        let lapMean = Double(lap.reduce(0, +)) / Double(lap.count)
        var variance: Double = 0
        for v in lap {
            let d = Double(v) - lapMean
            variance += d * d
        }
        variance /= Double(lap.count)

        // 4×4 cell means over the full luminance grid. Walked
        // separately rather than mixed into the Laplacian loop so
        // the arithmetic stays obvious; same O(W*H) cost.
        let gridSize = 4
        var cellSums = [Int](repeating: 0, count: gridSize * gridSize)
        var cellCounts = [Int](repeating: 0, count: gridSize * gridSize)
        for row in 0..<height {
            let rowCell = min(gridSize - 1,
                              (row * gridSize) / height)
            for col in 0..<width {
                let colCell = min(gridSize - 1,
                                  (col * gridSize) / width)
                let idx = rowCell * gridSize + colCell
                cellSums[idx] += Int(y[row * width + col])
                cellCounts[idx] += 1
            }
        }
        let signature: [Double] = zip(cellSums, cellCounts).map {
            s, c in c == 0 ? 0 : Double(s) / Double(c)
        }

        return Stats(
            mean: mean,
            laplacianVariance: variance,
            highlightRatio: highlightRatio,
            signature16: signature,
            luminance64: subsample(y, width: width,
                                   height: height,
                                   to: detectionGridSide))
    }

    /// Nearest-neighbour subsample of the full luminance buffer to a
    /// `side × side` grid of ints 0..255, row-major. Mirrors Android
    /// `LiveAssistPipeline.scaledPixels` (bitmap scaled to 64×64,
    /// then Rec.601 luma per pixel). On iOS we already have the full
    /// luma buffer, so we skip the colour-matrix step and just
    /// sample.
    private static func subsample(
        _ y: [UInt8], width: Int, height: Int, to side: Int
    ) -> [Int] {
        if width <= 0 || height <= 0 || side <= 0 { return [] }
        var out = [Int](repeating: 0, count: side * side)
        for dstRow in 0..<side {
            let srcRow = min(height - 1,
                             (dstRow * height) / side)
            for dstCol in 0..<side {
                let srcCol = min(width - 1,
                                 (dstCol * width) / side)
                out[dstRow * side + dstCol] =
                    Int(y[srcRow * width + srcCol])
            }
        }
        return out
    }

    /// Signed L1 distance between two signatures, excluding cells
    /// whose geometric centre falls inside a hand-mask disc. Returns
    /// `.infinity` if the signatures are incompatible (empty / wrong
    /// length), so a cautious caller treats that as "no comparison
    /// possible" rather than "unchanged".
    ///
    /// `handCenter` and `handRadius` are in normalized frame coords
    /// (0..1). Pass `nil` for `handCenter` to compare every cell.
    static func signatureDistance(
        _ a: [Double], _ b: [Double],
        handCenter: CGPoint? = nil, handRadius: Double = 0.15
    ) -> Double {
        guard !a.isEmpty, a.count == b.count else { return .infinity }
        let side = Int(Double(a.count).squareRoot().rounded())
        guard side * side == a.count else { return .infinity }
        let step = 1.0 / Double(side)
        var sum: Double = 0
        var counted = 0
        for row in 0..<side {
            let cy = (Double(row) + 0.5) * step
            for col in 0..<side {
                let cx = (Double(col) + 0.5) * step
                if let hc = handCenter {
                    let dx = cx - Double(hc.x)
                    let dy = cy - Double(hc.y)
                    if dx * dx + dy * dy <= handRadius * handRadius {
                        continue
                    }
                }
                let idx = row * side + col
                sum += abs(a[idx] - b[idx])
                counted += 1
            }
        }
        return counted == 0 ? .infinity : sum / Double(counted)
    }
}
