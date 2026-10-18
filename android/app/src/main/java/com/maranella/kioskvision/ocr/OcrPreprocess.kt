package com.maranella.kioskvision.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.graphics.createBitmap

/**
 * The one transformation applied to an image before Tesseract reads it.
 *
 * ### Why there is one at all
 *
 * Measured on the physical SM-S731B against `assets/ocr_test/coffee_machine_screen.png`, whose
 * ground truth is published beside it (`PriceLegibilityTest`):
 *
 * | image | cost | amounts read |
 * |---|---|---|
 * | untouched | 393 ms | **2 of 5** |
 * | grayscale + percentile-clipped contrast | 407 ms | **5 of 5** |
 * | 2x magnification | 710 ms | 5 of 5 |
 * | both | 690 ms | 5 of 5 |
 *
 * Every drink name, in both languages, read correctly in all four. What the untouched image
 * lost was the *prices* — the one kind of text on that screen printed in an accent colour.
 * Amber on cream is a large colour difference and a small luminance difference, and luminance
 * is all a binarisation sees. The drink names are near-black on the same cream and were never
 * in doubt.
 *
 * That is why this is worth 14 ms and magnification is not worth 317: they recover the same
 * text, and one of them costs twenty times more. Magnification still earns its place for text
 * that is too *small* rather than too faint (R-16); the two are not substitutes, which is why
 * both exist and are applied for different reasons.
 *
 * ### Percentile clipping, and when it must not be applied
 *
 * The white point is the 98th percentile rather than the maximum, so a specular highlight —
 * the normal case on a glossy kiosk screen, not an edge case — cannot define it and flatten
 * everything else.
 *
 * That same clipping is a trap on an image whose text covers less than the clipped tail. A
 * kiosk screen with a few words on a plain background is exactly such an image: the 2nd
 * percentile lands *inside the background*, the span collapses to the background's own noise,
 * and stretching it turns paper grain into black and the text into nothing. [MIN_SPAN] is
 * where that is refused: below it the image is converted to grayscale and left alone, which is
 * what Tesseract would have done to it anyway.
 */
object OcrPreprocess {

    /** Fractions of the histogram ignored at each end when choosing the black and white points. */
    const val LOW_PERCENTILE = 0.02
    const val HIGH_PERCENTILE = 0.98

    /**
     * Luminance range the clipped percentiles must still span before the image is stretched.
     *
     * Below this the two percentiles are describing one surface rather than ink and paper, and
     * stretching amplifies its noise into something that looks like text. 40 of 255 is about
     * 16%: far under the ~100 that separates amber from cream, which is the case this exists
     * for, and far above the handful of levels that separate one patch of background from
     * another.
     */
    const val MIN_SPAN = 40

    /**
     * A grayscale, contrast-stretched copy of [region] of [source].
     *
     * The result is exactly [region]'s size, so a caller maps a box back by adding the
     * region's origin — the same arithmetic magnification already uses.
     *
     * Statistics are taken over the region alone. A crop of the kiosk screen surrounded by a
     * dark room should be stretched by what is *on the screen*, not by the room.
     */
    fun normalisedCrop(source: Bitmap, region: Rect): Bitmap {
        val width = region.width()
        val height = region.height()
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, region.left, region.top, width, height)
        normalise(pixels)
        return createBitmap(width, height).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    /**
     * Grayscales [pixels] in place and stretches the contrast when there is a contrast to
     * stretch. Pure, so the decision not to stretch is testable without a device.
     */
    fun normalise(pixels: IntArray) {
        if (pixels.isEmpty()) return
        val histogram = IntArray(256)
        for (i in pixels.indices) {
            val value = luma(pixels[i])
            pixels[i] = value
            histogram[value]++
        }

        val low = percentile(histogram, pixels.size, LOW_PERCENTILE)
        val high = percentile(histogram, pixels.size, HIGH_PERCENTILE)
        val span = high - low

        if (span < MIN_SPAN) {
            // Grayscale only. Nothing is gained by stretching one surface against itself, and
            // what would be lost is the text sitting on it.
            for (i in pixels.indices) pixels[i] = gray(pixels[i])
            return
        }
        for (i in pixels.indices) {
            pixels[i] = gray(((pixels[i] - low) * 255 / span).coerceIn(0, 255))
        }
    }

    /** Rec. 601 luminance, integer arithmetic: this runs over every pixel of every OCR frame. */
    private fun luma(pixel: Int): Int =
        (299 * (pixel shr 16 and 0xFF) + 587 * (pixel shr 8 and 0xFF) + 114 * (pixel and 0xFF)) / 1000

    private fun gray(value: Int): Int = (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    /** The luminance below which [fraction] of the pixels fall. A histogram, so one pass. */
    private fun percentile(histogram: IntArray, total: Int, fraction: Double): Int {
        val target = (total * fraction).toInt().coerceIn(0, total - 1)
        var seen = 0
        for (value in histogram.indices) {
            seen += histogram[value]
            if (seen > target) return value
        }
        return 255
    }
}
