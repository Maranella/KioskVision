package com.maranella.kioskvision.assist

import com.maranella.kioskvision.speech.LocalReply
import com.maranella.kioskvision.voice.ToolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The path a user gets when they decline the streaming assistant.
 *
 * It had none: declining saved a preference nothing read, and the next press opened the
 * session it had declined (R-31). These tests cover the words that path now has to understand
 * without a network, and the sentences the app has to say for itself when no assistant is
 * there to say them.
 */
class LocalCommandPathTest {

    @Test
    fun `yes and no are answers to the app, not things to find on the screen`() {
        // Nothing is guided to until the user agrees (M6). Without a local word for "yes",
        // „დიახ" would have been matched against the kiosk screen as if it were a button.
        for (yes in listOf("დიახ", "კი", "ხო", "კარგი", "yes", "ok")) {
            assertEquals(yes, AssistCommand.Confirm, CommandParser.parse(yes))
        }
        for (no in listOf("არა", "არ მინდა", "no")) {
            assertEquals(no, AssistCommand.Cancel, CommandParser.parse(no))
        }
    }

    @Test
    fun `a refusal is not a request for something called other`() {
        // „სხვა მინდა" — I want a different one — must cancel the offer rather than search
        // the screen for a control named „სხვა", which on a payment terminal exists.
        assertEquals(AssistCommand.Cancel, CommandParser.parse("სხვა მინდა"))
    }

    @Test
    fun `stop still outranks everything`() {
        assertEquals(AssistCommand.Stop, CommandParser.parse("გაჩერდი"))
        // Including when it arrives with filler that would otherwise look like a target.
        assertEquals(AssistCommand.Stop, CommandParser.parse("გაჩერდი გთხოვ"))
    }

    @Test
    fun `naming a drink is still a target request`() {
        val command = CommandParser.parse("ამერიკანო მინდა")
        assertTrue(command is AssistCommand.SelectTarget)
        assertEquals("ამერიკანო", (command as AssistCommand.SelectTarget).query)
    }

    @Test
    fun `a refusal the app cannot act on is still answered out loud`() {
        // Silence after a spoken request is indistinguishable from a crash to someone who
        // cannot see the screen.
        assertEquals(
            SpokenMessage.TargetNotFound("ამერიკანო"),
            LocalReply.forOutcome(ToolOutcome.NotOnScreen, "ამერიკანო"),
        )
        assertEquals(SpokenMessage.CannotReadText, LocalReply.forOutcome(ToolOutcome.ScreenNotReadable, "x"))
        assertEquals(SpokenMessage.ScreenNotTracked, LocalReply.forOutcome(ToolOutcome.NoScreen, "x"))
        assertNotNull(LocalReply.forOutcome(ToolOutcome.NothingPending, "x"))
    }

    @Test
    fun `outcomes whose sentence was already spoken are not repeated`() {
        // The offer, the ambiguity and the held-open request each carry detail the action
        // itself speaks. Saying them again here would say everything twice.
        for (outcome in listOf(
            ToolOutcome.NeedsConfirmation,
            ToolOutcome.Ambiguous,
            ToolOutcome.Unconfirmed,
            ToolOutcome.Confirmed,
            ToolOutcome.Guiding,
            ToolOutcome.Reading,
            ToolOutcome.Comparing,
            ToolOutcome.Stopped,
        )) {
            assertNull(outcome.name, LocalReply.forOutcome(outcome, "x"))
        }
    }
}
