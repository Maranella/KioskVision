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

    private var lastModelChunkMs = Long.MIN_VALUE / 2

    /**
     * Called whenever a chunk of the model's own transcript arrives.
     *
     * Only the arrival of a chunk is trusted, never its absence. The earlier version also
     * cleared the flag — on any user transcript chunk, which interleaves with the model's —
     * so the app believed the assistant had stopped mid-sentence and spoke over it. Playback
     * also outlasts the transcript: the SDK keeps rendering audio after the last chunk, so a
     * flag that goes false with the text is false while a voice is still talking.
     *
     * There is no signal for "audio is currently playing": the SDK owns the AudioTrack. This
     * is a hold timer over the one event that is real, which is a proxy and is treated as one.
     */
    fun onModelChunk(nowMs: Long) {
        lastModelChunkMs = nowMs
    }

    /** Clears the hold at once, for a session that has stopped. */
    fun onModelSilent() {
        lastModelChunkMs = Long.MIN_VALUE / 2
    }

    fun isModelSpeaking(nowMs: Long): Boolean = nowMs - lastModelChunkMs < SPEAKING_HOLD_MS

    private var lastSpoken: SpokenMessage? = null
    private var lastSpokenAtMs = Long.MIN_VALUE / 2

    /**
     * @param userRequested true when the user asked for this out loud, by pressing Repeat.
     *   An explicit request is always honoured: it is never deduplicated and never yields to
     *   the assistant, because the user asking twice means they did not hear it the first time.
     */
    fun decide(message: SpokenMessage, nowMs: Long, userRequested: Boolean = false): SpeechDecision {
        if (userRequested) return record(message, nowMs, SpeechDecision.PreemptModel)

        // The same sentence repeated within a few seconds tells the user nothing and crowds
        // out whatever else needs saying. Directions are exempt: repetition is how a finger
        // gets steered, and GuidanceAnnouncer already owns their cadence.
        if (message !is SpokenMessage.Direction &&
            message == lastSpoken &&
            nowMs - lastSpokenAtMs < REPEAT_SUPPRESSION_MS
        ) {
            return SpeechDecision.Drop
        }

        val decision = when {
            !isModelSpeaking(nowMs) -> SpeechDecision.SpeakNow
            isUrgent(message) -> SpeechDecision.PreemptModel
            else -> SpeechDecision.Drop
        }
        return record(message, nowMs, decision)
    }

    private fun record(message: SpokenMessage, nowMs: Long, decision: SpeechDecision): SpeechDecision {
        if (decision != SpeechDecision.Drop) {
            lastSpoken = message
            lastSpokenAtMs = nowMs
        }
        return decision
    }

    /**
     * Forgets what was last said, so the next message is never suppressed as a repeat.
     *
     * Called when the target or the screen changes: the same sentence means something
     * different once the thing it refers to has moved on.
     */
    fun onContextChanged() {
        lastSpoken = null
        lastSpokenAtMs = Long.MIN_VALUE / 2
    }

    /**
     * Whether a message is about the user's immediate physical action.
     *
     * Directions and arrival are urgent because the finger is moving now. So are the messages
     * that withdraw guidance: letting the assistant talk over "I have lost the button" leaves
     * the user reaching towards a position the app no longer stands behind, which is the one
     * outcome the safety invariants exist to prevent.
     */
    private companion object {
        /**
         * How long after the last transcript chunk the assistant is still assumed to be
         * speaking. Long enough to cover the gap between chunks and the playback that trails
         * the final one; short enough that a genuinely finished reply does not keep guidance
         * silent while the user's finger is moving.
         */
        const val SPEAKING_HOLD_MS = 2_000L

        /** How long an identical non-directional message stays suppressed. */
        const val REPEAT_SUPPRESSION_MS = 4_000L
    }

    private fun isUrgent(message: SpokenMessage): Boolean = when (message) {
        is SpokenMessage.Direction -> true
        SpokenMessage.TargetPositionLost -> true
        SpokenMessage.ScreenNotTracked -> true
        SpokenMessage.GuidanceStopped -> true
        SpokenMessage.HoldStill -> true
        is SpokenMessage.TargetSelected -> true
        // A question the user has to answer before anything moves; nothing may talk over it.
        is SpokenMessage.TargetOffered -> true
        is SpokenMessage.GoalSuggestion -> true
        SpokenMessage.GoalNoMatchHere -> false

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
