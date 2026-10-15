package com.maranella.kioskvision.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The magnification rule is production policy — it decides both accuracy and latency — so it is
 * pinned here rather than left implicit in the pipeline.
 */
class OcrUpscaleTest {

    private val frameHeight = 1280

    @Test
    fun `small text is enlarged`() {
        // ~14 px labels on a 1280 frame: below the 19 px threshold, where the measured gain is.
        assertEquals(OcrUpscale.FACTOR, OcrUpscale.forLabelHeights(listOf(12f, 14f, 15f), frameHeight), 0f)
    }

    @Test
    fun `comfortable text is left alone`() {
        // 30 px labels already read at 0% CER; enlarging them would only cost latency.
        assertEquals(1f, OcrUpscale.forLabelHeights(listOf(28f, 30f, 34f), frameHeight), 0f)
    }

    @Test
    fun `the median decides, not one outlier`() {
        // A single tiny line among readable ones must not slow down every frame.
        assertEquals(1f, OcrUpscale.forLabelHeights(listOf(6f, 30f, 32f), frameHeight), 0f)
        // And one large heading must not stop small body text being enlarged.
        assertEquals(OcrUpscale.FACTOR, OcrUpscale.forLabelHeights(listOf(10f, 12f, 60f), frameHeight), 0f)
    }

    @Test
    fun `nothing read yet means the cheaper pass`() {
        assertEquals(1f, OcrUpscale.forLabelHeights(emptyList(), frameHeight), 0f)
    }

    @Test
    fun `a degenerate frame height cannot force a decision`() {
        assertEquals(1f, OcrUpscale.forLabelHeights(listOf(10f), 0), 0f)
    }

    @Test
    fun `the threshold scales with the frame, not with fixed pixels`() {
        // The same label is small in a tall frame and comfortable in a short one.
        assertEquals(OcrUpscale.FACTOR, OcrUpscale.forLabelHeights(listOf(18f), 2560), 0f)
        assertEquals(1f, OcrUpscale.forLabelHeights(listOf(18f), 640), 0f)
    }
}
