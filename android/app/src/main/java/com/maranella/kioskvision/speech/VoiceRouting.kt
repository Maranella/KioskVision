package com.maranella.kioskvision.speech

/**
 * What pressing the one voice control should do.
 *
 * Exactly one of these, decided from state the user can change and nothing else.
 */
enum class VoiceAction {
    /** Already listening; the press means stop. */
    CancelListening,

    /** The microphone has not been granted yet. */
    AskMicPermission,

    /** The user has never been asked whether the streaming assistant may be used. */
    AskConsent,

    /** Open the streaming assistant. **Microphone audio leaves the device.** */
    StartStreaming,

    /** Recognise one short command on the device. Nothing leaves the phone. */
    StartLocalCommands,
}

/**
 * Which voice path a press of the voice button opens.
 *
 * Split out of the ViewModel and made pure for one reason: it decides whether the user's
 * microphone audio is sent to Google, and that decision has to be testable without a device,
 * a socket or a permission dialog.
 *
 * ### The defect this exists to prevent
 *
 * The ViewModel used to branch on `listening`, the microphone permission and whether consent
 * had been *asked* — and then start the streaming session unconditionally. The user's answer
 * was never read. `assistantEnabled` was consulted in exactly one place, inside
 * `handleHypotheses`, which no production code reached (R-27's pattern again). So a user who
 * chose "ყველაფერი ამ ტელეფონში დარჩეს" — keep everything on this phone — had their
 * microphone streamed to Google the moment they pressed the button, and the press that
 * declined consent started the very thing it declined, because `onAssistantConsent` ends by
 * re-entering the same routine.
 *
 * `VoiceMode` already named this invariant ("exactly one may hold it") and
 * `LiveAssistUiState.voiceMode` already carried it, and nothing ever assigned either.
 *
 * ### [liveAvailable]
 *
 * False once the streaming session has given up reconnecting. The user keeps a voice path
 * instead of a button that reopens a socket known to be dead: on-device recognition of the
 * short command vocabulary needs no network at all.
 */
object VoiceRouting {

    fun onVoicePressed(
        listening: Boolean,
        micGranted: Boolean,
        hasChosen: Boolean,
        assistantEnabled: Boolean,
        liveAvailable: Boolean = true,
    ): VoiceAction = when {
        listening -> VoiceAction.CancelListening
        !micGranted -> VoiceAction.AskMicPermission
        // Asked once, at the moment it first matters, rather than buried in a settings screen
        // the user has no reason to visit.
        !hasChosen -> VoiceAction.AskConsent
        assistantEnabled && liveAvailable -> VoiceAction.StartStreaming
        else -> VoiceAction.StartLocalCommands
    }

    /** The mode [onVoicePressed] would open, for the label that tells the user what is happening. */
    fun modeFor(assistantEnabled: Boolean, liveAvailable: Boolean): VoiceMode =
        if (assistantEnabled && liveAvailable) VoiceMode.LiveAssistant else VoiceMode.LocalCommands
}

/**
 * Which voice interface currently owns the microphone.
 *
 * Exactly one may hold it. Android gives the microphone to one consumer, and two recognizers
 * fighting over it is not a degraded experience but a broken one — so this is an invariant of
 * the screen, not a preference.
 */
enum class VoiceMode {
    /**
     * Push-to-talk, recognised on the device. The default, because it needs no network and
     * sends nothing anywhere.
     */
    LocalCommands,

    /**
     * A streaming conversation with the Gemini Live assistant. **Microphone audio leaves the
     * device** while this is active, which is why it is only ever entered after the user has
     * said yes.
     */
    LiveAssistant,
}
