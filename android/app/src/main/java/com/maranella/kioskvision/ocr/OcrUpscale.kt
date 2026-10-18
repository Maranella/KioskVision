package com.maranella.kioskvision.ocr

import kotlin.math.sqrt

/**
 * How much to magnify an image before recognition.
 *
 * Measured on device (R-16): at 2x, exact recognition of kiosk labels rose from 52% to 67% on a
 * rendered corpus, the gain coming entirely from text below about 20 px — 34 px text already
 * reads at 0% CER, so enlarging it only costs latency (69 -> 158 ms on a full frame).
 *
 * A separate object rather than a constant inside the pipeline so the evaluation harness can
 * apply the *same* rule to still photographs instead of reimplementing it and drifting.
 */
object OcrUpscale {

    const val FACTOR = 2f

    /** Median label height below this fraction of the frame counts as small text. */
    const val SMALL_TEXT_FRACTION = 0.015f

    /**
     * The factor for a screen whose labels have these heights, in pixels.
     *
     * Returns 1 when nothing has been read yet: with no evidence that the text is small, the
     * cheaper pass is the right default.
     */
    fun forLabelHeights(heights: List<Float>, frameHeight: Int): Float {
        if (heights.isEmpty() || frameHeight <= 0) return 1f
        val sorted = heights.sorted()
        val median = sorted[sorted.size / 2]
        return if (median < SMALL_TEXT_FRACTION * frameHeight) FACTOR else 1f
    }

    /**
     * Most pixels Tesseract may be given in one run.
     *
     * Measured on the device: crops of about 1.7 MP finished in 2.0-2.8 s, while whole frames
     * at 3.0 MP took 12-26 s. The bound sits just above the fast side of that gap.
     */
    const val MAX_PIXELS = 1_800_000.0

    /**
     * Reduces [wanted] until an image of [width] x [height] magnified by it fits [MAX_PIXELS].
     *
     * `KioskOcrEngine`'s time budget asks Tesseract to stop, and that is not enough on its own:
     * runs asked to stop at 8 s were measured still taking 18-26 s, and `ocrInFlight` blocks
     * every other scan for the whole of it. One such run swallowed a guidance window outright —
     * `anchorJobs=0` while a target was held, so its position was never refreshed and guidance
     * died within six seconds of starting.
     *
     * This bound is deterministic and applied before any work begins. Magnification is cut
     * rather than the image, because cropping would lose labels outright, and a frame read at
     * 1.5x is still read.
     */
    fun cappedToPixelBudget(wanted: Float, width: Int, height: Int): Float {
        val pixels = width.toDouble() * height
        if (pixels <= 0.0) return wanted
        return wanted.coerceAtMost(sqrt(MAX_PIXELS / pixels).toFloat()).coerceAtLeast(1f)
    }
}
