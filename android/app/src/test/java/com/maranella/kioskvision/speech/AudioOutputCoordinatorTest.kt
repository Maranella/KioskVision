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
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(direction))
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(reading))
    }

    @Test
    fun `a direction cuts the assistant off`() {
        val coordinator = AudioOutputCoordinator()
        coordinator.onModelSpeaking(true)
        assertEquals(SpeechDecision.PreemptModel, coordinator.decide(direction))
    }

    @Test
    fun `withdrawing guidance also cuts the assistant off`() {
        val coordinator = AudioOutputCoordinator()
        coordinator.onModelSpeaking(true)
        // Letting conversation cover these leaves the user reaching for a position the app no
        // longer stands behind.
        assertEquals(SpeechDecision.PreemptModel, coordinator.decide(SpokenMessage.TargetPositionLost))
        assertEquals(SpeechDecision.PreemptModel, coordinator.decide(SpokenMessage.ScreenNotTracked))
        assertEquals(SpeechDecision.PreemptModel, coordinator.decide(SpokenMessage.GuidanceStopped))
    }

    @Test
    fun `descriptions are dropped rather than queued behind the assistant`() {
        val coordinator = AudioOutputCoordinator()
        coordinator.onModelSpeaking(true)
        // Queuing would deliver the screen reading after the assistant finishes, by which time
        // the user has asked something else. They can ask again; a late answer confuses.
        assertEquals(SpeechDecision.Drop, coordinator.decide(reading))
        assertEquals(SpeechDecision.Drop, coordinator.decide(SpokenMessage.NotUnderstood))
    }

    @Test
    fun `the assistant falling silent restores normal speech`() {
        val coordinator = AudioOutputCoordinator()
        coordinator.onModelSpeaking(true)
        assertEquals(SpeechDecision.Drop, coordinator.decide(reading))

        coordinator.onModelSpeaking(false)
        assertEquals(SpeechDecision.SpeakNow, coordinator.decide(reading))
    }

    @Test
    fun `every spoken message is classified, so a new one cannot default to urgent`() {
        val coordinator = AudioOutputCoordinator()
        coordinator.onModelSpeaking(true)
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
            val decision = coordinator.decide(message)
            assertEquals(
                "every message must resolve to preempt or drop while the model speaks: $message",
                true,
                decision == SpeechDecision.PreemptModel || decision == SpeechDecision.Drop,
            )
        }
    }
}
