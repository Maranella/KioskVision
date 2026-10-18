package com.maranella.kioskvision.speech

import com.maranella.kioskvision.assist.SpokenMessage

/** What the coordinator decided should happen to a local guidance message. */
sealed interface SpeechDecision {
    /** Say it now. */
    data object SpeakNow : SpeechDecision

    /**
     * Do not say it. Guidance is not queued: by the time the assistant finishes a sentence the
     * finger has moved, and a direction computed a second ago points somewhere the finger no
     * longer is. A stale cue is worse than a missing one.
     */
    data object Drop : SpeechDecision
}

/** What should happen to an assistant reply that is ready to be spoken. */
sealed interface ReplyDecision {
    data object SpeakNow : ReplyDecision

    /** The channel is busy. Hold the reply and ask again shortly. */
    data object Wait : ReplyDecision

    /**
     * It waited past its deadline. An answer to a question asked ten seconds ago, delivered
     * now, is no longer an answer; the user has moved on and the app has been talking since.
     * Reported rather than dropped in silence, which is what used to happen.
     */
    data object TooLate : ReplyDecision
}

/**
 * Decides which voice the user hears when guidance and conversation both have something to say.
 *
 * Both are now the app's own text-to-speech: the streaming provider never plays model audio
 * (R-25). The ranking is by consequence, not recency — a direction concerns where the user's
 * finger is about to go, and conversation yields to it.
 *
 * **What changed, and why.** This used to arbitrate against the Live SDK's own `AudioTrack`
 * through a hold timer over transcript chunks, because there was no signal for "audio is
 * playing". That whole path — `onModelChunk`, `onModelSilent`, `isModelSpeaking` and the
 * `PreemptModel` decision — was **never called from production code**: it was reachable only
 * from its own unit tests, so `isModelSpeaking` was permanently false and the arbitration it
 * described never ran. It is removed rather than wired, because the thing it was a proxy for
 * no longer exists: the model has no speaker, and whether the app is making sound is now
 * answered directly by `GuidanceSpeechOutput.isSpeaking`, from the engine's own utterance
 * callbacks.
 *
 * **What this still does not solve.** It arbitrates *output*. It does not stop the app's own
 * speech from entering the microphone the Live session holds; that is handled separately, by
 * pausing capture around each utterance, and remains the same unsolved problem as barge-in
 * (R-18).
 */
class AudioOutputCoordinator {

    /**
     * Whether an assistant reply may be spoken now.
     *
     * Replies are **deferred, not discarded**. The previous rule suppressed a reply for
     * [GUIDANCE_SETTLE_MS]'s predecessor — a flat 3 s after any spoken direction — while
     * `GuidanceAnnouncer` repeats the same direction every 2.5 s. 3000 > 2500, so during a run
     * of directions the window never opened and *every* reply was dropped, silently. The user
     * asked a question and the app said nothing, for as long as it was guiding.
     *
     * @param offeredAtMs when the reply became available, for the deadline.
     * @param appSpeaking the engine's own "sound is coming out" signal, not a timer.
     * @param lastGuidanceSpokenAtMs when a directional cue was last spoken.
     * @param appOwnsVoiceUntilMs set when the app is speaking the substance itself, such as
     *   reading the whole screen aloud; an acknowledgement on top would cut it off.
     */
    fun decideAssistantReply(
        offeredAtMs: Long,
        nowMs: Long,
        appSpeaking: Boolean,
        lastGuidanceSpokenAtMs: Long,
        appOwnsVoiceUntilMs: Long,
    ): ReplyDecision = when {
        nowMs - offeredAtMs >= MAX_REPLY_WAIT_MS -> ReplyDecision.TooLate
        appSpeaking -> ReplyDecision.Wait
        nowMs < appOwnsVoiceUntilMs -> ReplyDecision.Wait
        nowMs - lastGuidanceSpokenAtMs < GUIDANCE_SETTLE_MS -> ReplyDecision.Wait
        else -> ReplyDecision.SpeakNow
    }

    private var lastSpoken: SpokenMessage? = null
    private var lastSpokenAtMs = Long.MIN_VALUE / 2

    /**
     * @param userRequested true when the user asked for this out loud, by pressing Repeat.
     *   An explicit request is always honoured: it is never deduplicated and never yields to
     *   the assistant, because the user asking twice means they did not hear it the first time.
     */
    fun decide(message: SpokenMessage, nowMs: Long, userRequested: Boolean = false): SpeechDecision {
        if (userRequested) return record(message, nowMs, SpeechDecision.SpeakNow)

        // The same sentence repeated within a few seconds tells the user nothing and crowds
        // out whatever else needs saying. Directions are exempt: repetition is how a finger
        // gets steered, and GuidanceAnnouncer already owns their cadence.
        if (message !is SpokenMessage.Direction &&
            message == lastSpoken &&
            nowMs - lastSpokenAtMs < REPEAT_SUPPRESSION_MS
        ) {
            return SpeechDecision.Drop
        }

        // Guidance itself is never held back. It is the app's one safety-bearing output, it
        // already has a cadence of its own in GuidanceAnnouncer, and a cue that waits for a
        // sentence to finish points where the finger no longer is. Conversation yields to it,
        // not the other way round — see decideAssistantReply.
        return record(message, nowMs, SpeechDecision.SpeakNow)
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

    private companion object {
        /** How long an identical non-directional message stays suppressed. */
        const val REPEAT_SUPPRESSION_MS = 4_000L

        /**
         * Quiet required after a directional cue before conversation may speak.
         *
         * Anchored to `GuidanceAnnouncer.MIN_GAP_MS` (700 ms), which is the spacing the app
         * already insists on between two of its *own* spoken cues. Giving conversation at
         * least that much clearance is a justified floor rather than a guess, and it is
         * deliberately shorter than the 2.5 s cadence at which the same direction repeats, so
         * a window always exists. **Not yet tuned against a device**: the right value depends
         * on how long a Georgian directional phrase actually takes to speak, which has not
         * been measured.
         */
        const val GUIDANCE_SETTLE_MS = 800L

        /**
         * How long a reply may wait for the channel before it is no longer worth saying.
         *
         * Longer than one full repeat cycle of a direction (2.5 s), so a reply is abandoned
         * only when the channel was genuinely never free — not merely because a cue happened
         * to land first. Also unmeasured, and the first thing to revisit once there are device
         * numbers for utterance length.
         */
        const val MAX_REPLY_WAIT_MS = 4_000L
    }

    // `isUrgent` lived here: it ranked messages so an urgent one could cut the model off.
    // It is gone with the rest of the model-speaking path. Urgency is no longer a decision the
    // coordinator has to make, because both voices are now the one local engine and guidance
    // speaks with QUEUE_FLUSH — it preempts a reply in progress by construction rather than by
    // classification.
}
