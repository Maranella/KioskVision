package com.maranella.kioskvision.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the intake filter keeps, and what it silently removes.
 *
 * `TargetTracker` discards any OCR block below `MIN_RETAINED_CONFIDENCE` **or** with fewer than
 * two letters, before anything else sees it. `DebugFrame.blocks` is the post-filter snapshot,
 * so until the raw output was exposed alongside it, "Tesseract read nothing" and "Tesseract
 * read the screen and the app threw it away" were the same observation.
 *
 * These tests characterise the rule rather than propose a new one. `letterCount` counts
 * letters, not digits — which is right for a payment terminal, whose controls are words, and
 * is an open question for a machine whose controls are numerals. Changing it needs a real
 * frame and a measured recognition rate, not an argument.
 */
class OcrIntakeFilterTest {

    private fun block(text: String, confidence: Float = 0.9f) = DetectedTextBlock(
        text = text,
        boundingBox = TextBox(0f, 0f, 100f, 30f),
        confidence = confidence,
    )

    @Test
    fun `a worded label is kept`() {
        assertTrue(TargetTracker.isRetainable(block("გადახდა")))
        assertTrue(TargetTracker.isRetainable(block("ESPRESSO")))
        // Two letters is the floor, and "ml" reaches it.
        assertTrue(TargetTracker.isRetainable(block("250 ml")))
    }

    @Test
    fun `a price or quantity is kept`() {
        // Measured at a coffee machine: raw=48 kept=12 droppedShort=32. Two thirds of that
        // screen was its prices. Reading an amount is also a safety requirement, not a nicety.
        assertTrue(TargetTracker.isRetainable(block("250")))
        assertTrue(TargetTracker.isRetainable(block("1.50")))
        assertTrue(TargetTracker.isRetainable(block("2,50 ₾")))
        assertTrue(TargetTracker.isRetainable(block("80°")))
        assertTrue(TargetTracker.isRetainable(block("82%")))
        assertTrue(TargetTracker.isRetainable(block("12.10.2026")))
    }

    @Test
    fun `a single digit stays out, because OCR noise looks like digits`() {
        // The stepper values are worth less than the false targets admitting them would cost.
        assertFalse(TargetTracker.isRetainable(block("3")))
        assertFalse(TargetTracker.isRetainable(block("1")))
    }

    @Test
    fun `punctuation and stray marks are still refused`() {
        // The narrowness is the point: digits-plus-marks only, never marks alone.
        assertFalse(TargetTracker.isRetainable(block("|")))
        assertFalse(TargetTracker.isRetainable(block("...")))
        assertFalse(TargetTracker.isRetainable(block("---")))
        assertFalse(TargetTracker.isRetainable(block("%")))
        assertFalse(TargetTracker.isRetainable(block("")))
    }

    @Test
    fun `a digit glued to a stray letter is not a number`() {
        // "1O" and "5l" are classic OCR confusions, and neither one is a price.
        assertFalse(TargetTracker.isNumericLabel("1O"))
        assertFalse(TargetTracker.isNumericLabel("5l"))
    }

    @Test
    fun `a single letter is discarded`() {
        assertFalse(TargetTracker.isRetainable(block("A")))
    }

    @Test
    fun `a low-confidence read is discarded however long`() {
        assertFalse(TargetTracker.isRetainable(block("გადახდა", confidence = 0.10f)))
        // Deliberately low floor: R-12 showed Tesseract returns the right Georgian string at
        // 0.20 under motion blur, and corroboration exists to rescue exactly those.
        assertTrue(TargetTracker.isRetainable(block("გადახდა", confidence = 0.20f)))
    }

    @Test
    fun `every rejection names its reason`() {
        // The diagnostic half: a dropped block must be able to say why, in the same words the
        // rule used, or a device session reports "no text" and stops there.
        assertNotNull(TargetTracker.rejectionFor(block("A")))
        assertNotNull(TargetTracker.rejectionFor(block("გადახდა", confidence = 0.01f)))
        assertEquals(null, TargetTracker.rejectionFor(block("გადახდა")))
        assertEquals("a kept price must report no rejection", null, TargetTracker.rejectionFor(block("1.50")))
    }

    @Test
    fun `the reason distinguishes the two causes`() {
        assertTrue(TargetTracker.rejectionFor(block("A"))!!.contains("letter"))
        assertTrue(TargetTracker.rejectionFor(block("გადახდა", confidence = 0.01f))!!.contains("confidence"))
    }
}
