package com.maranella.kioskvision.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matching against the labels Tesseract actually produced from a TBC Pay screen on device.
 *
 * Every misreading below was captured from the live app, not invented: `ბალანსის` came back as
 * `ბაღანსის`, `დახმარება` as `დასმარება`, `შემოწმება` as `შემონშება` (R-14). A user asking for
 * one of these buttons must still reach it.
 */
class OcrConfusionTest {

    private fun block(text: String, x: Float, y: Float, confidence: Float = 0.9f) =
        DetectedTextBlock(text, TextBox(x, y, x + 240f, y + 40f), confidence)

    /** The menu exactly as OCR reported it on the device, misreadings included. */
    private val screenAsRead = listOf(
        block("ტრანსპორტი და ბილეოები", 40f, 300f, 0.85f),
        block("კომუნაღური მომსახურება", 40f, 420f, 0.92f),
        block("ბაღანსის შემონშება", 40f, 540f, 0.88f),
        block("მობიღურის შევსება", 40f, 660f, 0.91f),
        block("და სხვა გადასახადები", 40f, 780f, 0.91f),
        block("დასმარება", 40f, 900f, 0.93f),
    )

    private fun found(query: String): DetectedTextBlock {
        val match = TargetMatcher.match(query, screenAsRead)
        assertTrue("'$query' did not match: $match", match is TargetMatch.Found)
        return (match as TargetMatch.Found).block
    }

    // --- Confusable letters ------------------------------------------------------------------

    @Test
    fun `balance is reached though OCR read l as gh`() {
        assertEquals("ბაღანსის შემონშება", found("ბალანსი").text)
    }

    @Test
    fun `mobile top-up is reached though OCR read l as gh`() {
        assertEquals("მობიღურის შევსება", found("მობილურის შევსება").text)
    }

    @Test
    fun `help is reached though OCR read kh as s`() {
        assertEquals("დასმარება", found("დახმარება").text)
    }

    @Test
    fun `utilities are reached though OCR read l as gh`() {
        assertEquals("კომუნაღური მომსახურება", found("კომუნალური").text)
    }

    @Test
    fun `a confused letter costs less than a real difference`() {
        // ლ/ღ is a known confusion; ლ/ტ is not, so the latter must score lower.
        val confused = TextNormalizer.wordSimilarity("ბალანსი", "ბაღანსი")
        val genuine = TextNormalizer.wordSimilarity("ბალანსი", "ბატანსი")
        assertTrue("confused=$confused genuine=$genuine", confused > genuine)
    }

    @Test
    fun `the allowance does not make unrelated labels match`() {
        // Tolerating OCR noise must not turn one button into another.
        val match = TargetMatcher.match("ტრანსპორტი", screenAsRead)
        assertEquals("ტრანსპორტი და ბილეოები", (match as TargetMatch.Found).block.text)
        assertTrue(TargetMatcher.match("ინტერნეტი", screenAsRead) is TargetMatch.NotFound)
    }

    // --- The payments button under a different name --------------------------------------------

    @Test
    fun `asking to pay reaches the other-payments button`() {
        // This screen has no literal "გადახდა"; its payments button is "და სხვა გადასახადები".
        assertEquals("და სხვა გადასახადები", found("გადახდა").text)
        assertEquals("და სხვა გადასახადები", found("გადახდა მინდა").text)
    }

    @Test
    fun `an exact payment label still wins over the generic one`() {
        val withBoth = screenAsRead + block("გადახდა", 320f, 780f)
        assertEquals("გადახდა", (TargetMatcher.match("გადახდა", withBoth) as TargetMatch.Found).block.text)
    }

    @Test
    fun `payment wording is still flagged as critical`() {
        val match = TargetMatcher.match("გადახდა", screenAsRead) as TargetMatch.Found
        assertTrue("a payments button must prompt the user to check details", match.isCritical)
    }
}
