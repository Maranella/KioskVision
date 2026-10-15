package com.maranella.kioskvision.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandAndMatchingTest {

    private fun block(text: String, x: Float, y: Float, confidence: Float = 0.9f) =
        DetectedTextBlock(text, TextBox(x, y, x + 200f, y + 40f), confidence)

    /** The demo kiosk menu, laid out as a 2×2 grid. */
    private val menu = listOf(
        block("გადახდა", 50f, 300f),
        block("ბალანსის შემოწმება", 400f, 300f),
        block("სხვა მომსახურება", 50f, 600f),
        block("დახმარება", 400f, 600f),
    )

    // --- CommandParser ---------------------------------------------------------------------

    @Test
    fun `parses the required demo commands`() {
        assertEquals(AssistCommand.SelectTarget("გადახდა"), CommandParser.parse("გადახდა მინდა."))
        assertEquals(AssistCommand.SelectTarget("ბალანსის შემოწმება"), CommandParser.parse("ბალანსის შემოწმება მინდა."))
        assertEquals(AssistCommand.SelectTarget("დახმარება"), CommandParser.parse("დახმარება მჭირდება."))
        assertEquals(AssistCommand.ReadScreen, CommandParser.parse("ეკრანზე რა წერია?"))
        assertEquals(AssistCommand.Repeat, CommandParser.parse("გაიმეორე."))
        assertEquals(AssistCommand.Stop, CommandParser.parse("გაჩერდი."))
    }

    @Test
    fun `stop wins over a target name and filler-only speech is unknown`() {
        assertEquals(AssistCommand.Stop, CommandParser.parse("გაჩერდი, გადახდა არ მინდა"))
        assertEquals(AssistCommand.Unknown, CommandParser.parse("მინდა გთხოვთ"))
        assertEquals(AssistCommand.Unknown, CommandParser.parse("   "))
    }

    // --- TargetMatcher ---------------------------------------------------------------------

    @Test
    fun `matches each menu option from natural phrasing`() {
        assertFound("გადახდა", "გადახდა")
        assertFound("ბალანსის შემოწმება", "ბალანსის შემოწმება")
        assertFound("ბალანსი", "ბალანსის შემოწმება")
        assertFound("დახმარება", "დახმარება")
        assertFound("სხვა მომსახურება", "სხვა მომსახურება")
        // Different inflection of the same verb, via the alias dictionary.
        assertFound("გადავიხადო", "გადახდა")
    }

    @Test
    fun `tolerates a single OCR character error`() {
        val noisy = listOf(block("გადახდა", 0f, 0f), block("დახმარებ", 0f, 100f))
        val match = TargetMatcher.match("დახმარება", noisy)
        assertEquals("დახმარებ", (match as TargetMatch.Found).block.text)
    }

    @Test
    fun `payment is flagged as critical, help is not`() {
        assertTrue((TargetMatcher.match("გადახდა", menu) as TargetMatch.Found).isCritical)
        assertEquals(false, (TargetMatcher.match("დახმარება", menu) as TargetMatch.Found).isCritical)
    }

    @Test
    fun `option that is not on screen is not found`() {
        val match = TargetMatcher.match("ტრანსპორტი", menu)
        assertTrue("$match", match is TargetMatch.NotFound)
        assertEquals(Rejection.NoSimilarLabel, (match as TargetMatch.NotFound).reason)
    }

    @Test
    fun `two equally good options are ambiguous instead of guessed`() {
        val twoPayments = listOf(block("გადახდა ბარათით", 0f, 0f), block("გადახდა ნაღდით", 0f, 100f))
        assertTrue(TargetMatcher.match("გადახდა", twoPayments) is TargetMatch.Ambiguous)
    }

    @Test
    fun `exact label beats a longer label that shares the concept`() {
        val blocks = listOf(block("გადახდის ისტორია", 0f, 0f), block("გადახდა", 0f, 100f))
        assertEquals("გადახდა", (TargetMatcher.match("გადახდა", blocks) as TargetMatch.Found).block.text)
    }

    @Test
    fun `a low-confidence line read only once is not a target`() {
        val blurry = listOf(block("გადახდა", 0f, 0f, confidence = 0.4f))
        val match = TargetMatcher.match("გადახდა", blurry)
        assertTrue("$match", match is TargetMatch.NotFound)
        // Reported as unconfirmed rather than absent: the label may well be there, and the
        // caller asks for a steadier frame instead of denying the button exists.
        assertEquals(Rejection.UnconfirmedLabel, (match as TargetMatch.NotFound).reason)
    }

    private fun assertFound(query: String, expectedLabel: String) {
        val match = TargetMatcher.match(query, menu)
        assertTrue("$query → $match", match is TargetMatch.Found)
        assertEquals(expectedLabel, (match as TargetMatch.Found).block.text)
    }

    // --- ScreenReader ----------------------------------------------------------------------

    @Test
    fun `reads top-to-bottom then left-to-right`() {
        val shuffled = listOf(menu[3], menu[1], menu[2], menu[0])
        assertEquals(
            listOf("გადახდა", "ბალანსის შემოწმება", "სხვა მომსახურება", "დახმარება"),
            ScreenReader.read(shuffled).lines,
        )
    }

    @Test
    fun `never reads digits on a PIN screen and omits uncertain amounts`() {
        val pinScreen = listOf(block("შეიყვანეთ PIN კოდი", 0f, 0f), block("1234", 0f, 100f))
        assertEquals(listOf("შეიყვანეთ PIN კოდი"), ScreenReader.read(pinScreen).lines)

        val amount = listOf(block("თანხა: 25 ლარი", 0f, 0f, confidence = 0.7f))
        val reading = ScreenReader.read(amount)
        assertTrue(reading.lines.isEmpty())
        assertTrue(reading.omittedUncertain)
    }
}
