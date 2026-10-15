package com.maranella.kioskvision.assist

import org.junit.Assert.assertEquals
import org.junit.Test

class PhraseGrouperTest {

    private fun word(text: String, left: Float, right: Float, startsLine: Boolean = false, top: Float = 400f, confidence: Float = 0.9f) =
        OcrWord(text, TextBox(left, top, right, top + 40f), confidence, startsLine)

    @Test
    fun `side-by-side buttons on one OCR line become separate phrases`() {
        val line = listOf(
            word("გადახდა", 100f, 260f, startsLine = true),
            // 100 px gap = 2.5× the 40 px text height → a different label.
            word("ბალანსის", 360f, 520f),
            // 15 px gap → same label.
            word("შემოწმება", 535f, 700f),
        )
        val phrases = PhraseGrouper.group(line)
        assertEquals(listOf("გადახდა", "ბალანსის შემოწმება"), phrases.map { it.text })
        assertEquals(TextBox(360f, 400f, 700f, 440f), phrases[1].boundingBox)
    }

    @Test
    fun `a new OCR line always starts a new phrase`() {
        val words = listOf(
            word("სხვა", 100f, 180f, startsLine = true),
            word("მომსახურება", 190f, 400f, startsLine = true, top = 460f),
        )
        assertEquals(2, PhraseGrouper.group(words).size)
    }

    @Test
    fun `confidence is weighted by word length`() {
        val words = listOf(
            word("ab", 0f, 40f, startsLine = true, confidence = 0.0f),
            word("abcdefgh", 50f, 200f, confidence = 1.0f),
        )
        assertEquals(0.8f, PhraseGrouper.group(words).single().confidence, 0.001f)
    }
}
