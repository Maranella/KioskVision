package com.maranella.kioskvision.voice

import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle of a voice session, as the spec requires it to be represented explicitly.
 *
 * Only the states the underlying SDK can actually signal are used; a state the provider cannot
 * distinguish is never reported, because a lifecycle that lies is worse than a coarse one.
 */
enum class VoiceSessionState {
    Idle,
    Connecting,

    /** Connected, microphone streaming. */
    Listening,

    /** The model is speaking. Reported only when the provider can tell. */
    Speaking,

    /** The connection ended and is being re-established, carrying the task goal forward. */
    Reconnecting,

    Stopped,
    Error,
}

/** What the session observed, for diagnostics and for the spike's own measurements. */
data class VoiceSessionReport(
    val state: VoiceSessionState,
    /** Most recent transcription of the user, when the provider exposes one. */
    val lastUserText: String? = null,
    /** Most recent transcription of the model's own speech, when exposed. */
    val lastModelText: String? = null,
    val connects: Int = 0,
    val reconnects: Int = 0,
    /**
     * Recent exchanges, newest first, for reviewing an evaluation run.
     *
     * In memory only and never logged: these are the user's words. The product build has no
     * reason to keep them at all — this exists so a spike session can be scored.
     */
    val history: List<String> = emptyList(),
    /** Seconds of warning the server gave before closing the connection, if any. */
    val goAwaySecondsLeft: Long? = null,
    val error: String? = null,
)

/**
 * A streaming Georgian voice session, behind an interface so the provider can be replaced.
 *
 * Gemini Live is the first candidate; an Azure Speech `ka-GE` implementation is the documented
 * fallback. Exactly one provider is active at a time — two streaming providers would double
 * audio cost and complexity for no benefit.
 *
 * Implementations must not touch the camera, OCR, fingertip geometry or kiosk control
 * decisions. Those stay local and deterministic.
 */
interface VoiceProvider {

    val report: StateFlow<VoiceSessionReport>

    /** Opens a session and begins streaming the microphone. Safe to call when already started. */
    suspend fun start()

    /** Stops streaming and closes the session. Safe to call when not started. */
    suspend fun stop()
}
