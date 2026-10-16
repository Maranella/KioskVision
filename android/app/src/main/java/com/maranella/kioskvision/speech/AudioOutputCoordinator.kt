package com.maranella.kioskvision.speech

import com.maranella.kioskvision.assist.SpokenMessage

/** What the coordinator decided should happen to a local guidance message. */
sealed interface SpeechDecision {
    /** Say it now. */
    data object SpeakNow : SpeechDecision

    /** Say it now and cut the assistant off; the user needs this more than the sentence. */
    data object PreemptModel : SpeechDecision

    /**
     * Do not say it. Guidance is not queued: by the time the assistant finishes a sentence the
     * finger has moved, and a direction computed a second ago points somewhere the finger no
     * longer is. A stale cue is worse than a missing one.
     */
    data object Drop : SpeechDecision
}

/**
 * Decides which of the two voices the user hears when both have something to say.
 *
 * Two sources can speak: the Gemini Live assistant, whose audio the SDK plays itself, and the
 * app's own guidance, which is a closed set of [SpokenMessage] values rendered locally. They
 * must never overlap — two Georgian voices at once is unusable, and for a blind user the audio
 * channel is the entire interface.
 *
 * The ranking is by consequence, not by recency. A direction or a safety message concerns where
 * the user's finger is about to go and preempts conversation. An explanation of the screen can
 * wait, and is dropped rather than queued.
 *
 * **What this does not solve.** It arbitrates *output*. It does not stop the app's own speech
 * from entering the microphone the Live session is holding, which is the same unsolved problem
 * as barge-in (R-18) and needs either a routed output path or a capture gate the SDK does not
 * currently expose.
 */
class AudioOutputCoordinator {

    private var modelSpeaking = false

    fun onModelSpeaking(speaking: Boolean) {
        modelSpeaking = speaking
    }

    val isModelSpeaking: Boolean get() = modelSpeaking

    fun decide(message: SpokenMessage): SpeechDecision = when {
        !modelSpeaking -> SpeechDecision.SpeakNow
        isUrgent(message) -> SpeechDecision.PreemptModel
        else -> SpeechDecision.Drop
    }

    /**
     * Whether a message is about the user's immediate physical action.
     *
     * Directions and arrival are urgent because the finger is moving now. So are the messages
     * that withdraw guidance: letting the assistant talk over "I have lost the button" leaves
     * the user reaching towards a position the app no longer stands behind, which is the one
     * outcome the safety invariants exist to prevent.
     */
    private fun isUrgent(message: SpokenMessage): Boolean = when (message) {
        is SpokenMessage.Direction -> true
        SpokenMessage.TargetPositionLost -> true
        SpokenMessage.ScreenNotTracked -> true
        SpokenMessage.GuidanceStopped -> true
        SpokenMessage.HoldStill -> true
        is SpokenMessage.TargetSelected -> true

        // Everything else is description or explanation. The user can ask again.
        is SpokenMessage.ScreenContents -> false
        is SpokenMessage.TargetNotFound -> false
        is SpokenMessage.TargetAmbiguous -> false
        is SpokenMessage.RecognitionFailed -> false
        SpokenMessage.NoTextVisible -> false
        SpokenMessage.TextRecognitionNotReady -> false
        SpokenMessage.ScreenChanged -> false
        SpokenMessage.ScreenOutOfFocus -> false
        SpokenMessage.CannotReadText -> false
        SpokenMessage.ReadingScreen -> false
        SpokenMessage.NotUnderstood -> false
    }
}
