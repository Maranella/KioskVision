package com.maranella.kioskvision.speech

import com.maranella.kioskvision.assist.GuidanceAnnouncer
import com.maranella.kioskvision.assist.GuidanceDirection
import com.maranella.kioskvision.assist.ScreenReading
import com.maranella.kioskvision.assist.SpokenMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * For a blind user the audio channel is the whole interface, so which voice wins is a product
 * decision and not a detail.
 *
 * These tests were rewritten when the model-speaking arbitration was removed. The previous
 * suite drove `onModelChunk`, which **no production code ever called**: `isModelSpeaking` was
 * permanently false, so twelve passing tests described a policy that never ran. They are
 * replaced by tests of the policy that does.
 */
class AudioOutputCoordinatorTest {

    private val direction = SpokenMessage.Direction(GuidanceDirection.LEFT)
    private val reading = SpokenMessage.ScreenContents(ScreenReading(emptyList(), omittedUncertain = false))

    // --- Guidance ---------------------------------------------------------------------------

    @Test
    fun `guidance is spoken, never held back`() {
        val coordinator = AudioOutputCoordinator()
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(direction, 1_000L))
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(reading, 1_000L))
    }

    @Test
    fun `the same explanation is not repeated within a few seconds`() {
        val coordinator = AudioOutputCoordinator()
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(SpokenMessage.NotUnderstood, 1_000L))
        assertEquals(SpeechDecision.Drop, coordinator.decide(SpokenMessage.NotUnderstood, 2_000L))
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(SpokenMessage.NotUnderstood, 6_000L))
    }

    @Test
    fun `directions are exempt from repeat suppression`() {
        val coordinator = AudioOutputCoordinator()
        // Repetition is how a finger gets steered; GuidanceAnnouncer owns their cadence.
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(direction, 1_000L))
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(direction, 1_100L))
    }

    @Test
    fun `an explicit repeat is always honoured`() {
        val coordinator = AudioOutputCoordinator()
        coordinator.decide(reading, 1_000L)
        // The user asking again means they did not hear it the first time.
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(reading, 1_100L, userRequested = true))
    }

    @Test
    fun `a new target clears the repeat suppression`() {
        val coordinator = AudioOutputCoordinator()
        coordinator.decide(SpokenMessage.CannotReadText, 1_000L)
        coordinator.onContextChanged()
        // The same sentence means something different once the thing it refers to has moved on.
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(SpokenMessage.CannotReadText, 1_100L))
    }

    @Test
    fun `losing the assistant is announced rather than left silent`() {
        val coordinator = AudioOutputCoordinator()
        // Otherwise undetectable: the microphone simply stops being streamed.
        assertEquals(
            SpeechDecision.SpeakNow,
            coordinator.decide(SpokenMessage.VoiceAssistantUnavailable, 1_000L),
        )
    }

    // --- Assistant replies ------------------------------------------------------------------

    private fun reply(
        nowMs: Long,
        offeredAtMs: Long = 0L,
        appSpeaking: Boolean = false,
        lastGuidanceSpokenAtMs: Long = Long.MIN_VALUE / 2,
        appOwnsVoiceUntilMs: Long = Long.MIN_VALUE / 2,
    ) = AudioOutputCoordinator().decideAssistantReply(
        offeredAtMs = offeredAtMs,
        nowMs = nowMs,
        appSpeaking = appSpeaking,
        lastGuidanceSpokenAtMs = lastGuidanceSpokenAtMs,
        appOwnsVoiceUntilMs = appOwnsVoiceUntilMs,
    )

    @Test
    fun `a reply is spoken when nothing else is using the channel`() {
        assertEquals(ReplyDecision.SpeakNow, reply(nowMs = 0L))
    }

    @Test
    fun `a reply waits while the app is making sound`() {
        assertEquals(ReplyDecision.Wait, reply(nowMs = 100L, appSpeaking = true))
    }

    @Test
    fun `a reply waits while the app is speaking the substance itself`() {
        // read_screen takes seconds; an acknowledgement on top cuts off the very reading it
        // announces, which is what the user actually asked for.
        assertEquals(ReplyDecision.Wait, reply(nowMs = 100L, appOwnsVoiceUntilMs = 20_000L))
    }

    @Test
    fun `a reply waits briefly after a direction, then goes`() {
        assertEquals(ReplyDecision.Wait, reply(nowMs = 1_000L, lastGuidanceSpokenAtMs = 500L))
        assertEquals(ReplyDecision.SpeakNow, reply(nowMs = 1_400L, lastGuidanceSpokenAtMs = 500L))
    }

    @Test
    fun `a reply that waited past its deadline is reported, not dropped in silence`() {
        assertEquals(ReplyDecision.TooLate, reply(offeredAtMs = 0L, nowMs = 10_000L, appSpeaking = true))
    }

    /**
     * The defect this policy exists to fix, as arithmetic.
     *
     * The old rule suppressed a reply for a flat 3 s after any spoken direction, while
     * `GuidanceAnnouncer` repeats the same direction every 2.5 s. 3000 > 2500, so while the app
     * was guiding the window never opened and **every** reply was discarded without a word.
     */
    @Test
    fun `a reply gets the channel during a run of directions, which the old rule never allowed`() {
        val cadenceMs = 2_500L // GuidanceAnnouncer.cooldownMs for a directional cue
        val oldHoldMs = 3_000L // the DIRECTION_HOLDS_VOICE_MS that was removed
        assertTrue(
            "the old hold must outlast the cadence, or there was no bug to fix",
            oldHoldMs > cadenceMs,
        )

        val spokenAt = generateSequence(0L) { it + cadenceMs }.take(4).toList()
        val windowUnderOldRule = (0L..7_500L step 100L).firstOrNull { now ->
            now - spokenAt.last { it <= now } >= oldHoldMs
        }
        assertEquals("the old rule left no window at all", null, windowUnderOldRule)

        val windowNow = (0L..7_500L step 100L).firstOrNull { now ->
            reply(nowMs = now, offeredAtMs = now, lastGuidanceSpokenAtMs = spokenAt.last { it <= now }) ==
                ReplyDecision.SpeakNow
        }
        assertNotNull("a reply must get the channel at some point during continuous guidance", windowNow)
    }

    @Test
    fun `the settle leaves a window wider than the announcer's own minimum gap`() {
        // Guards the anchor: the settle is justified as being at least MIN_GAP_MS, the spacing
        // the app already requires between two of its own cues. If that constant moved above
        // the settle, this should fail rather than the behaviour quietly degrading on a device.
        assertEquals(
            ReplyDecision.SpeakNow,
            reply(nowMs = GuidanceAnnouncer.MIN_GAP_MS + 200L, lastGuidanceSpokenAtMs = 0L),
        )
    }
}
