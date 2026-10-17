package com.maranella.kioskvision.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tool call is how an intent reaches the app, because the input transcript cannot carry
 * one: R-19 found it returns Latin transliteration and sometimes an entirely different
 * language while comprehension stays correct.
 *
 * These also pin the thing that keeps ADR-009 intact now that the model can trigger actions:
 * it learns an outcome, never what the screen says.
 */
class AssistantToolsTest {

    private class Recording : AssistantActions {
        val queries = mutableListOf<String>()
        var stopped = false
        var read = false
        var outcome = ToolOutcome.Guiding

        override fun selectTarget(query: String): ToolOutcome {
            queries += query
            return outcome
        }

        override fun stopGuidance(): ToolOutcome {
            stopped = true
            return ToolOutcome.Stopped
        }

        override fun readScreen(): ToolOutcome {
            read = true
            return ToolOutcome.Reading
        }

        var confirmed = false
        var cancelled = false

        override fun confirmTarget(): ToolOutcome {
            confirmed = true
            return ToolOutcome.Confirmed
        }

        override fun cancelTarget(): ToolOutcome {
            cancelled = true
            return ToolOutcome.Stopped
        }
    }

    @Test
    fun `a select call reaches the app with the user's own wording`() {
        val actions = Recording()
        val outcome = AssistantTools.dispatch(
            AssistantTools.SELECT_TARGET,
            mapOf(AssistantTools.QUERY_ARGUMENT to "გადახდა"),
            actions,
        )

        assertEquals(listOf("გადახდა"), actions.queries)
        assertEquals(ToolOutcome.Guiding, outcome)
    }

    @Test
    fun `surrounding whitespace in an argument is trimmed`() {
        val actions = Recording()
        AssistantTools.dispatch(
            AssistantTools.SELECT_TARGET,
            mapOf(AssistantTools.QUERY_ARGUMENT to "  ბალანსი \n"),
            actions,
        )
        assertEquals(listOf("ბალანსი"), actions.queries)
    }

    @Test
    fun `a select call with no query is answered, not dispatched`() {
        val actions = Recording()
        val outcome = AssistantTools.dispatch(
            AssistantTools.SELECT_TARGET,
            mapOf(AssistantTools.QUERY_ARGUMENT to null),
            actions,
        )

        assertTrue("nothing should have been selected", actions.queries.isEmpty())
        assertEquals(ToolOutcome.NotOnScreen, outcome)
    }

    @Test
    fun `an empty query is treated the same as a missing one`() {
        val actions = Recording()
        val outcome = AssistantTools.dispatch(
            AssistantTools.SELECT_TARGET,
            mapOf(AssistantTools.QUERY_ARGUMENT to "   "),
            actions,
        )
        assertTrue(actions.queries.isEmpty())
        assertEquals(ToolOutcome.NotOnScreen, outcome)
    }

    @Test
    fun `stop and read reach the app`() {
        val actions = Recording()
        assertEquals(
            ToolOutcome.Stopped,
            AssistantTools.dispatch(AssistantTools.STOP_GUIDANCE, emptyMap(), actions),
        )
        assertEquals(
            ToolOutcome.Reading,
            AssistantTools.dispatch(AssistantTools.READ_SCREEN, emptyMap(), actions),
        )
        assertTrue(actions.stopped)
        assertTrue(actions.read)
    }

    @Test
    fun `an invented function name is answered rather than thrown`() {
        val actions = Recording()
        // The handler is synchronous inside the audio session: an exception here would take
        // the conversation down. A preview model inventing a call must not be able to do that.
        val outcome = AssistantTools.dispatch("press_the_button", mapOf("x" to "1"), actions)

        assertEquals(ToolOutcome.NotOnScreen, outcome)
        assertTrue(actions.queries.isEmpty())
        assertTrue("nothing may be actioned by an unknown call", !actions.stopped && !actions.read)
    }

    @Test
    fun `confirmation and cancellation reach the app`() {
        val actions = Recording()
        assertEquals(
            ToolOutcome.Confirmed,
            AssistantTools.dispatch(AssistantTools.CONFIRM_TARGET, emptyMap(), actions),
        )
        assertEquals(
            ToolOutcome.Stopped,
            AssistantTools.dispatch(AssistantTools.CANCEL_TARGET, emptyMap(), actions),
        )
        assertTrue(actions.confirmed)
        assertTrue(actions.cancelled)
    }

    @Test
    fun `the outcome sent back names no screen content`() {
        // The full vocabulary the model can ever learn from a tool call. If a future outcome
        // carried a label or a position, it would show up here.
        val wire = ToolOutcome.entries.map { it.wireValue }
        assertEquals(
            listOf(
                "guiding", "notonscreen", "unconfirmed", "ambiguous",
                "screennotreadable", "noscreen", "needsconfirmation", "confirmed",
                "nothingpending", "stopped", "reading",
            ),
            wire,
        )
    }

    // --- one audible source ------------------------------------------------------------------

    @Test
    fun `the text endpoint is the one the app uses for conversation`() {
        // Not a behavioural test but a statement of the guarantee, kept next to the tool code
        // it constrains: TextAssistant talks to generateContent, which has no audio part in
        // its response and no track to play through. One voice is a property of that choice.
        //
        // If someone reintroduces a Live session on the product path, the two voices come
        // back, and no unit test can catch that — hence this note and R-26.
        assertEquals("gemini-3.8-flash", com.maranella.kioskvision.ai.AiConfig.TEXT_MODEL)
    }

    @Test
    fun `an outcome is all the assistant learns, whichever transport asked`() {
        val actions = Recording()
        actions.outcome = ToolOutcome.Ambiguous
        val outcome = AssistantTools.dispatch(
            AssistantTools.SELECT_TARGET,
            mapOf(AssistantTools.QUERY_ARGUMENT to "გადახდა"),
            actions,
        )
        // Ambiguity is reported as a bare outcome; which two buttons are similar is said by
        // the app, in its own voice, from what the camera saw.
        assertEquals(ToolOutcome.Ambiguous, outcome)
        assertEquals("ambiguous", outcome.wireValue)
    }
}
