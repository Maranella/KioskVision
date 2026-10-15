package com.maranella.kioskvision.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behaviour that makes the app usable in the hand rather than on a tripod.
 *
 * Motion blur does not stop Tesseract reading a Georgian label correctly — it collapses the
 * confidence it reports for that correct reading (R-12). These tests pin down that a label
 * corroborated across runs is still usable, and that nothing is accepted on one bad frame.
 */
class MotionToleranceTest {

    private val frame = FrameSize(720, 1280)
    private var now = 1_000L

    private fun block(text: String, x: Float, y: Float, confidence: Float = 0.9f) =
        DetectedTextBlock(text, TextBox(x, y, x + 200f, y + 40f), confidence)

    /** The menu as read from a sharp frame. */
    private fun menu(dx: Float = 0f, dy: Float = 0f, confidence: Float = 0.9f) = listOf(
        block("გადახდა", 50f + dx, 300f + dy, confidence),
        block("ბალანსის შემოწმება", 400f + dx, 300f + dy, confidence),
        block("სხვა მომსახურება", 50f + dx, 600f + dy, confidence),
        block("დახმარება", 400f + dx, 600f + dy, confidence),
    )

    private fun TargetTracker.run(blocks: List<DetectedTextBlock>): OcrUpdate {
        now += 300
        return onOcrResult(blocks, frame, now, now)
    }

    private fun observed(vararg labels: ObservedLabel) = labels.toList()

    // --- Georgian matching ------------------------------------------------------------------

    @Test
    fun `a request with a polite filler still names the label`() {
        assertEquals(AssistCommand.SelectTarget("გადახდა"), CommandParser.parse("გადახდა მინდა"))
        assertEquals(AssistCommand.SelectTarget("გადახდა"), CommandParser.parse("გთხოვ გადახდა"))
        assertEquals(AssistCommand.SelectTarget("ბალანსი"), CommandParser.parse("ბალანსი მაჩვენე"))
    }

    @Test
    fun `realistic OCR misreadings of the label still match`() {
        // Each of these is a reading seen from a softened frame: a trailing stop, a stray
        // separator, a doubled character. All should still lead to the payment button.
        val variations = listOf("გადახდა", "გადახდა.", "გადახდა ", "გადახდაა", "გადახდა|")
        for (text in variations) {
            val blocks = listOf(block(text, 50f, 300f), block("დახმარება", 400f, 600f))
            val match = TargetMatcher.match("გადახდა მინდა", blocks)
            assertTrue("'$text' should match: $match", match is TargetMatch.Found)
        }
    }

    @Test
    fun `the originally recognised text is preserved on the block`() {
        val blocks = listOf(block("გადახდა.", 50f, 300f))
        val match = TargetMatcher.match("გადახდა", blocks) as TargetMatch.Found
        assertEquals("გადახდა.", match.block.text)
    }

    @Test
    fun `OCR edge artefacts are trimmed before the label is spoken`() {
        // Blur routinely adds these; reading them aloud is noise, but the letters between them
        // must survive untouched.
        assertEquals("გადახდა", TextNormalizer.forSpeech("| გადახდა"))
        assertEquals("გადახდა", TextNormalizer.forSpeech("გადახდა."))
        assertEquals("გადახდა", TextNormalizer.forSpeech("  \"გადახდა\"  "))
        assertEquals("ბალანსის შემოწმება", TextNormalizer.forSpeech("ბალანსის შემოწმება"))
        // Inner punctuation is part of the label and stays.
        assertEquals("გადახდა - ბალანსი", TextNormalizer.forSpeech("გადახდა - ბალანსი."))
    }

    @Test
    fun `a poor picture is reported as unconfirmed rather than absent`() {
        // The label is there but every reading is weak: the caller must be able to tell this
        // apart from the button genuinely not being on screen.
        val weak = ObservedLabel(block("გადახდა", 50f, 300f, confidence = 0.30f), timesSeen = 1, bestConfidence = 0.30f)
        val match = TargetMatcher.matchObserved("გადახდა", observed(weak))
        assertEquals(Rejection.UnconfirmedLabel, (match as TargetMatch.NotFound).reason)

        // Whereas a screen that simply does not have it says so.
        val other = ObservedLabel(block("დახმარება", 50f, 300f, confidence = 0.95f), timesSeen = 3, bestConfidence = 0.95f)
        val absent = TargetMatcher.matchObserved("გადახდა", observed(other))
        assertEquals(Rejection.NoSimilarLabel, (absent as TargetMatch.NotFound).reason)
    }

    @Test
    fun `a different Georgian label is not matched`() {
        val blocks = listOf(block("დახმარება", 50f, 300f), block("უკან", 400f, 300f))
        val match = TargetMatcher.match("გადახდა", blocks)
        assertTrue("$match", match is TargetMatch.NotFound)
        assertEquals(Rejection.NoSimilarLabel, (match as TargetMatch.NotFound).reason)
    }

    // --- Confidence vs corroboration ---------------------------------------------------------

    @Test
    fun `a blurred single frame is not enough on its own`() {
        val blurred = ObservedLabel(block("გადახდა", 50f, 300f, confidence = 0.20f), timesSeen = 1, bestConfidence = 0.20f)
        val match = TargetMatcher.matchObserved("გადახდა", observed(blurred))
        assertTrue("$match", match is TargetMatch.NotFound)
        // Crucially: not "the button is absent", but "I could not confirm it".
        assertEquals(Rejection.UnconfirmedLabel, (match as TargetMatch.NotFound).reason)
    }

    @Test
    fun `the same low-confidence reading in two runs is enough`() {
        val corroborated = ObservedLabel(block("გადახდა", 50f, 300f, confidence = 0.20f), timesSeen = 2, bestConfidence = 0.20f)
        val match = TargetMatcher.matchObserved("გადახდა", observed(corroborated))
        assertTrue("a label read the same way twice should be usable: $match", match is TargetMatch.Found)
    }

    @Test
    fun `corroboration does not rescue a reading with no confidence at all`() {
        val noise = ObservedLabel(block("გადახდა", 50f, 300f, confidence = 0.05f), timesSeen = 3, bestConfidence = 0.05f)
        assertTrue(TargetMatcher.matchObserved("გადახდა", observed(noise)) is TargetMatch.NotFound)
    }

    @Test
    fun `one sharp frame is still enough on its own`() {
        val sharp = ObservedLabel(block("გადახდა", 50f, 300f, confidence = 0.97f), timesSeen = 1, bestConfidence = 0.97f)
        assertTrue(TargetMatcher.matchObserved("გადახდა", observed(sharp)) is TargetMatch.Found)
    }

    @Test
    fun `two plausible labels are still reported as ambiguous, not guessed`() {
        val blocks = listOf(block("გადახდა", 50f, 300f), block("გადახდა", 400f, 300f))
        assertTrue(TargetMatcher.match("გადახდა", blocks) is TargetMatch.Ambiguous)
    }

    // --- Corroboration through the tracker ----------------------------------------------------

    @Test
    fun `a label read twice under blur becomes usable through the tracker`() {
        val tracker = TargetTracker()
        tracker.run(menu(confidence = 0.22f))
        tracker.run(menu(dx = 6f, confidence = 0.22f))

        val payment = tracker.corroborated.single { it.block.normalizedText.contains("გადახდ") }
        assertEquals(2, payment.timesSeen)
        assertTrue(TargetMatcher.isTrustworthy(payment))
        assertTrue(TargetMatcher.matchObserved("გადახდა მინდა", tracker.corroborated) is TargetMatch.Found)
    }

    @Test
    fun `small translation between runs still corroborates`() {
        val tracker = TargetTracker()
        tracker.run(menu(confidence = 0.3f))
        // The phone drifted; the whole screen moved together.
        tracker.run(menu(dx = 25f, dy = 15f, confidence = 0.3f))
        val payment = tracker.corroborated.single { it.block.normalizedText.contains("გადახდ") }
        assertEquals(2, payment.timesSeen)
    }

    @Test
    fun `the same text far across the screen does not corroborate`() {
        val tracker = TargetTracker()
        tracker.run(menu())
        // A label with the same text, but on the opposite side: a different button.
        tracker.run(listOf(block("გადახდა", 500f, 1200f)) + menu().drop(1))
        val payment = tracker.corroborated.single { it.block.normalizedText == "გადახდა" }
        assertEquals("position must agree before runs corroborate", 1, payment.timesSeen)
    }

    @Test
    fun `an empty OCR run leaves the corroborated labels intact`() {
        val tracker = TargetTracker()
        tracker.run(menu(confidence = 0.25f))
        tracker.run(menu(confidence = 0.25f))
        val before = tracker.corroborated.size

        assertEquals(OcrUpdate.Unreadable, tracker.run(emptyList()))

        assertEquals(before, tracker.corroborated.size)
        assertTrue(TargetMatcher.matchObserved("გადახდა", tracker.corroborated) is TargetMatch.Found)
    }

    @Test
    fun `a confirmed screen change discards the old screen's corroboration`() {
        val tracker = TargetTracker()
        tracker.run(menu())
        tracker.run(menu())
        val newScreen = listOf(
            block("შეიყვანეთ თანხა", 50f, 200f),
            block("დადასტურება", 400f, 900f),
            block("უკან", 50f, 900f),
        )
        tracker.run(newScreen)
        tracker.run(newScreen)

        assertNull(tracker.target)
        assertTrue(
            "the old screen's payment button must not still be offered",
            TargetMatcher.matchObserved("გადახდა", tracker.corroborated) is TargetMatch.NotFound,
        )
    }

    @Test
    fun `a selected target survives small movement and a blurred run`() {
        val tracker = TargetTracker()
        tracker.run(menu())
        tracker.setTarget(tracker.snapshot.first(), isCritical = true, frame = frame, nowMs = now)
        val chosen = tracker.target!!

        tracker.run(menu(dx = 12f, dy = 8f))          // drifted
        tracker.run(emptyList())                       // a frame too blurred to read
        tracker.run(menu(dx = 20f, dy = 12f, confidence = 0.25f)) // blurred but readable

        val still = tracker.target
        assertNotNull(still)
        assertEquals("the same selection throughout", chosen.id, still!!.id)
        assertEquals("გადახდა", still.label)
        assertTrue("its position stays normalized to the screen", still.normalizedBox.centerX in 0f..1f)
    }
}
