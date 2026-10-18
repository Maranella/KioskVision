package com.maranella.kioskvision.voice

import com.maranella.kioskvision.assist.ResolutionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What the model is told when a request cannot be met.
 *
 * The distinctions here are the ones the user hears. "It is not on this screen" sends them
 * looking elsewhere; "I cannot read the screen" tells them to move the phone. Collapsing the
 * two into one generic refusal is the failure `SPIKES_AND_DECISIONS` calls a false
 * "not on screen", and this is where it would reappear.
 */
class ResolutionOutcomeTest {

    @Test
    fun `an absent control and an unreadable screen are different answers`() {
        assertEquals(ToolOutcome.NotOnScreen, ResolutionFailure.NoSimilarLabel.asOutcome())
        assertEquals(ToolOutcome.ScreenNotReadable, ResolutionFailure.SceneUnreadable.asOutcome())
        assertNotEquals(
            ResolutionFailure.NoSimilarLabel.asOutcome(),
            ResolutionFailure.SceneUnreadable.asOutcome(),
        )
    }

    @Test
    fun `a label read too poorly to act on is held open, not denied`() {
        // The pipeline keeps looking for a better frame for several seconds (R-12). Telling
        // the user it is not there would end a request that is about to succeed.
        assertEquals(ToolOutcome.Unconfirmed, ResolutionFailure.UnconfirmedLabel.asOutcome())
    }

    @Test
    fun `unusable geometry is reported as no screen, because the remedy is the same`() {
        assertEquals(ToolOutcome.NoScreen, ResolutionFailure.GeometryUnusable.asOutcome())
        assertEquals(ToolOutcome.NoScreen, ResolutionFailure.NoScreen.asOutcome())
    }

    @Test
    fun `every failure has an answer`() {
        // A `when` without an else: if a new reason is added, this fails to compile rather
        // than silently mapping to whatever came first.
        for (reason in ResolutionFailure.entries) {
            assertNotEquals(ToolOutcome.Guiding, reason.asOutcome())
        }
    }
}
