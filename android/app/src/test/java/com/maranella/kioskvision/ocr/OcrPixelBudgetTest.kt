package com.maranella.kioskvision.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The image handed to Tesseract has to be bounded before the work starts.
 *
 * `KioskOcrEngine`'s time budget asks Tesseract to stop and is not enough on its own: measured
 * on the device, runs asked to stop at 8 s still took **18-26 s**, and `ocrInFlight` blocks
 * every other scan for the whole of it. One such run swallowed a guidance window outright —
 * `anchorJobs=0` while a target was held — so the arithmetic below is what actually keeps a
 * scan affordable.
 *
 * Anchored by measurement at both ends: ~1.7 MP crops finished in 2.0-2.8 s, 3.0 MP whole
 * frames took 12-26 s.
 */
class OcrPixelBudgetTest {

    private val budget = OcrUpscale.MAX_PIXELS

    // The production function itself, not a copy of its arithmetic: OcrUpscale exists so this
    // rule has exactly one home.
    private fun capped(wanted: Float, width: Int, height: Int) =
        OcrUpscale.cappedToPixelBudget(wanted, width, height)

    @Test
    fun `the analysis frame this device delivers is capped below two`() {
        // 590x1280 at 2x is 3.0 MP, the size that took 12-26 s on the device.
        val factor = capped(2f, 590, 1280)
        assertEquals(1.544f, factor, 0.01f)
        assertTrue(
            "the capped image must fit the budget",
            590 * factor * 1280 * factor <= budget * 1.01,
        )
    }

    @Test
    fun `a region small enough to magnify is left alone`() {
        // A re-anchor crop is a fraction of the frame; 2x of it is cheap and must stay 2x,
        // because magnification is what rescues small text (R-16).
        assertEquals(2f, capped(2f, 200, 60), 0.001f)
    }

    @Test
    fun `a screen-sized crop keeps most of its magnification`() {
        // ~520x830, the crop size measured at the machine: 1.7 MP at 2x, already affordable.
        assertTrue("a crop that already fits must not be reduced", capped(2f, 520, 830) > 1.9f)
    }

    @Test
    fun `magnification is never reduced below one`() {
        // Shrinking the image would lose detail that is not recoverable; the floor is no-op.
        assertEquals(1f, capped(2f, 4000, 4000), 0.001f)
        assertEquals(1f, capped(1f, 590, 1280), 0.001f)
    }
}
