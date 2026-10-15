package com.maranella.kioskvision.ocr

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
}
