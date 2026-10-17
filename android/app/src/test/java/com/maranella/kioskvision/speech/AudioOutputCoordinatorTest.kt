package com.maranella.kioskvision.speech

import com.maranella.kioskvision.assist.GuidanceDirection
import com.maranella.kioskvision.assist.ScreenReading
import com.maranella.kioskvision.assist.SpokenMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * For a blind user the audio channel is the whole interface, so which voice wins is a product
 * decision and not a detail.
 */
class AudioOutputCoordinatorTest {

    private val direction = SpokenMessage.Direction(GuidanceDirection.LEFT)
    private val reading = SpokenMessage.ScreenContents(ScreenReading(emptyList(), omittedUncertain = false))

    @Test
    fun `with the assistant silent, anything may be spoken`() {
        val coordinator = AudioOutputCoordinator()
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(direction, 1_000L))
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(reading, 1_000L))
    }

    @Test
    fun `a direction cuts the assistant off`() {
        val coordinator = AudioOutputCoordinator()
        coordinator.onModelChunk(1_000L)
        assertEquals(SpeechDecision.PreemptModel, coordinator.decide(direction, 1_000L))
    }

    @Test
    fun `withdrawing guidance also cuts the assistant off`() {
        val coordinator = AudioOutputCoordinator()
        coordinator.onModelChunk(1_000L)
        // Letting conversation cover these leaves the user reaching for a position the app no
        // longer stands behind.
        assertEquals(SpeechDecision.PreemptModel, coordinator.decide(SpokenMessage.TargetPositionLost, 1_000L))
        assertEquals(SpeechDecision.PreemptModel, coordinator.decide(SpokenMessage.ScreenNotTracked, 1_000L))
        assertEquals(SpeechDecision.PreemptModel, coordinator.decide(SpokenMessage.GuidanceStopped, 1_000L))
    }

    @Test
    fun `descriptions are dropped rather than queued behind the assistant`() {
        val coordinator = AudioOutputCoordinator()
        coordinator.onModelChunk(1_000L)
        // Queuing would deliver the screen reading after the assistant finishes, by which time
        // the user has asked something else. They can ask again; a late answer confuses.
        assertEquals(SpeechDecision.Drop, coordinator.decide(reading, 1_000L))
        assertEquals(SpeechDecision.Drop, coordinator.decide(SpokenMessage.NotUnderstood, 1_000L))
    }

    @Test
    fun `the assistant is still speaking between transcript chunks`() {
        val coordinator = AudioOutputCoordinator()
        coordinator.onModelChunk(1_000L)

        // Chunks arrive with gaps, and the user's own transcript interleaves with them. The
        // first version of this cleared the flag on anything that was not a model chunk, so
        // the app concluded the assistant had finished mid-sentence and talked over it —
        // which is what a listener hears as two voices at once.
        assertEquals(SpeechDecision.Drop, coordinator.decide(reading, 1_400L))
        coordinator.onModelChunk(1_500L)
        assertEquals(SpeechDecision.Drop, coordinator.decide(reading, 2_900L))
    }

    @Test
    fun `playback is assumed to outlast the last chunk`() {
        val coordinator = AudioOutputCoordinator()
        coordinator.onModelChunk(1_000L)

        // The SDK keeps rendering audio after the final transcript chunk, so the hold has to
        // extend past it.
        assertEquals(SpeechDecision.Drop, coordinator.decide(reading, 2_500L))
        // ...but not forever, or guidance would stay silent while the finger is moving.
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(reading, 5_000L))
    }

    @Test
    fun `the assistant falling silent restores normal speech`() {
        val coordinator = AudioOutputCoordinator()
        coordinator.onModelChunk(1_000L)
        assertEquals(SpeechDecision.Drop, coordinator.decide(reading, 1_000L))

        coordinator.onModelSilent()
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(reading, 1_000L))
    }

    // --- one voice at a time -----------------------------------------------------------------

    @Test
    fun `an identical message is not repeated back to back`() {
        val coordinator = AudioOutputCoordinator()
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(reading, 1_000L))
        assertEquals(SpeechDecision.Drop, coordinator.decide(reading, 2_000L))
        // Once the suppression window passes it may be said again.
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(reading, 9_000L))
    }

    @Test
    fun `directions are exempt from repeat suppression`() {
        val coordinator = AudioOutputCoordinator()
        // Repetition is how a finger gets steered; suppressing it would strand the user
        // mid-reach with no feedback.
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(direction, 1_000L))
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(direction, 1_200L))
    }

    @Test
    fun `a change of target makes the same sentence sayable again`() {
        val coordinator = AudioOutputCoordinator()
        coordinator.decide(reading, 1_000L)
        assertEquals(SpeechDecision.Drop, coordinator.decide(reading, 1_500L))

        coordinator.onContextChanged()
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(reading, 1_600L))
    }

    @Test
    fun `an explicit repeat is honoured even while the assistant is speaking`() {
        val coordinator = AudioOutputCoordinator()
        coordinator.onModelChunk(1_000L)

        // Pressing Repeat means the user did not hear it. Dropping it, or waiting for the
        // assistant, answers a direct request with silence.
        assertEquals(
            SpeechDecision.PreemptModel,
            coordinator.decide(reading, 1_200L, userRequested = true),
        )
    }

    @Test
    fun `an explicit repeat is never suppressed as a duplicate`() {
        val coordinator = AudioOutputCoordinator()
        coordinator.decide(reading, 1_000L)
        assertEquals(
            SpeechDecision.PreemptModel,
            coordinator.decide(reading, 1_100L, userRequested = true),
        )
    }

    @Test
    fun `a dropped message does not count as having been said`() {
        val coordinator = AudioOutputCoordinator()
        coordinator.onModelChunk(1_000L)
        // Dropped because the assistant is talking...
        assertEquals(SpeechDecision.Drop, coordinator.decide(reading, 1_100L))
        // ...so once it stops, the message is still new and must be spoken rather than
        // suppressed as a repeat of something the user never heard.
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(reading, 4_000L))
    }

    @Test
    fun `every spoken message is classified, so a new one cannot default to urgent`() {
        val coordinator = AudioOutputCoordinator()
        coordinator.onModelChunk(1_000L)
        // The when over SpokenMessage is exhaustive by compilation; this exercises a
        // representative message of each kind so the classification is covered rather than
        // merely compiled.
        val messages = listOf(
            direction,
            SpokenMessage.TargetSelected("გადახდა", isCritical = true),
            SpokenMessage.TargetNotFound("ბილეთი"),
            SpokenMessage.TargetAmbiguous("გადახდა", "გადახდის ისტორია"),
            reading,
            SpokenMessage.NoTextVisible,
            SpokenMessage.TextRecognitionNotReady,
            SpokenMessage.ScreenChanged,
            SpokenMessage.ScreenOutOfFocus,
            SpokenMessage.CannotReadText,
            SpokenMessage.TargetPositionLost,
            SpokenMessage.ScreenNotTracked,
            SpokenMessage.ReadingScreen,
            SpokenMessage.HoldStill,
            SpokenMessage.GuidanceStopped,
            SpokenMessage.NotUnderstood,
        )
        messages.forEach { message ->
            val decision = coordinator.decide(message, 1_000L)
            assertEquals(
                "every message must resolve to preempt or drop while the model speaks: $message",
                true,
                decision == SpeechDecision.PreemptModel || decision == SpeechDecision.Drop,
            )
        }
    }
}
