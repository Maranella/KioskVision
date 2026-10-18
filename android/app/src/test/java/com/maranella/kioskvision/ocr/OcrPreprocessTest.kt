package com.maranella.kioskvision.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transformation every OCR input now passes through, and the image it must not destroy.
 *
 * It was added because amber prices on a cream card were unreadable without it (R-32). The
 * case it could break is the opposite one: a kiosk screen with a few words on a plain
 * background, where the clipped percentiles both land inside the background and stretching
 * them would amplify its noise into something that looks like text and leave the words with
 * nothing to stand out from.
 */
class OcrPreprocessTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun luminance(pixel: Int) = pixel and 0xFF

    /** [coverage] of the pixels are ink; the rest is paper with a little grain. */
    private fun page(ink: Int, paper: Int, coverage: Double, size: Int = 10_000): IntArray {
        val inkPixels = (size * coverage).toInt()
        return IntArray(size) { i ->
            // Grey pixels, not bare luminance values: the production function reads ARGB.
            val value = if (i < inkPixels) ink else paper + (i % 3) // grain: three levels
            rgb(value, value, value)
        }
    }

    @Test
    fun `faint text against its background is pulled apart`() {
        // Amber (#C77B30) on cream (#F5F1E8): the measured case. 60 levels apart before.
        val pixels = IntArray(10_000) { if (it < 2_000) rgb(199, 123, 48) else rgb(245, 241, 232) }
        OcrPreprocess.normalise(pixels)

        val ink = luminance(pixels[0])
        val paper = luminance(pixels[9_999])
        assertTrue("faint text must end up further from its background, was $ink vs $paper", paper - ink > 200)
    }

    @Test
    fun `sparse text on a plain background survives`() {
        // One percent ink — under the 2% clipped tail, so both percentiles land in the paper.
        // Stretching that span would turn the grain into ink and the ink into nothing.
        val pixels = page(ink = 20, paper = 240, coverage = 0.01)
        OcrPreprocess.normalise(pixels)

        val ink = luminance(pixels[0])
        val paper = luminance(pixels[9_999])
        assertTrue("the text must still be darker than the page, was $ink vs $paper", paper - ink > 100)
    }

    @Test
    fun `a page of grain alone is not turned into text`() {
        val pixels = page(ink = 240, paper = 240, coverage = 0.0)
        OcrPreprocess.normalise(pixels)
        val levels = pixels.map(::luminance).distinct()
        assertTrue("background noise must not be stretched into contrast: $levels", levels.max() - levels.min() < 20)
    }

    @Test
    fun `ordinary dark text keeps its contrast`() {
        val pixels = page(ink = 20, paper = 240, coverage = 0.15)
        OcrPreprocess.normalise(pixels)
        assertEquals(0, luminance(pixels[0]))
        assertTrue(luminance(pixels[9_999]) > 240)
    }

    @Test
    fun `an empty image is not a crash`() {
        OcrPreprocess.normalise(IntArray(0))
    }

    @Test
    fun `the result is grey, whichever branch ran`() {
        for (coverage in listOf(0.0, 0.01, 0.15)) {
            val pixels = page(ink = 20, paper = 240, coverage = coverage)
            OcrPreprocess.normalise(pixels)
            for (pixel in pixels) {
                val r = pixel shr 16 and 0xFF
                val g = pixel shr 8 and 0xFF
                val b = pixel and 0xFF
                assertTrue("channels must match: $r $g $b", r == g && g == b)
                assertEquals(0xFF, pixel ushr 24)
            }
        }
    }
}
