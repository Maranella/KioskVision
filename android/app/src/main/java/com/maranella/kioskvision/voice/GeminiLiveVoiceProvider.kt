package com.maranella.kioskvision.voice

import android.media.AudioAttributes
import android.media.MediaRecorder
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.LiveAudioConversationConfig
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import com.maranella.kioskvision.ai.AiConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.DurationUnit

/**
 * Gemini Live via Firebase AI Logic — the M1 feasibility spike.
 *
 * Deliberately narrow: it opens a Georgian audio session, streams the microphone and reports
 * what the SDK actually tells us. It sends no camera frames and makes no kiosk decisions, both
 * because the spec forbids it here and because Live video is 1 FPS discrete frames, which is
 * useless for fingertip tracking.
 *
 * The SDK handles recording and playback through `startAudioConversation`, including
 * interruption when enabled. What it exposes is measured rather than assumed — transcripts and
 * the "going away" warning arrive through the callbacks below.
 */
// The Live API is an explicit public preview; the opt-in is acknowledged in one place so the
// preview surface stays visible to anyone reading this class.
@OptIn(PublicPreviewAPI::class)
class GeminiLiveVoiceProvider : VoiceProvider {

    private val _report = MutableStateFlow(VoiceSessionReport(VoiceSessionState.Idle))
    override val report: StateFlow<VoiceSessionReport> = _report.asStateFlow()

    private var session: LiveSession? = null

    private var transcript = TranscriptAssembler(HISTORY_LIMIT)

    override suspend fun start() {
        if (session?.isClosed() == false) return
        transcript = TranscriptAssembler(HISTORY_LIMIT)
        _report.update {
            it.copy(
                state = VoiceSessionState.Connecting,
                error = null,
                history = emptyList(),
                lastUserText = null,
                lastModelText = null,
            )
        }
        try {
            val model = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
                modelName = AiConfig.LIVE_MODEL,
                generationConfig = liveGenerationConfig {
                    responseModality = ResponseModality.AUDIO
                    // Georgian for the spoken reply. The voice is left at the service default;
                    // naturalness in Georgian is one of the things this spike has to judge.
                    speechConfig = SpeechConfig(Voice(AiConfig.VOICE_NAME), AiConfig.SPOKEN_LANGUAGE)
                    // Ask for both transcripts so the spike can separate "did not hear" from
                    // "heard and misunderstood" — the distinction the whole evaluation rests on.
                    inputAudioTranscription = AudioTranscriptionConfig()
                    outputAudioTranscription = AudioTranscriptionConfig()
                },
                systemInstruction = content { text(systemInstruction()) },
            )

            val opened = model.connect()
            session = opened
            _report.update {
                it.copy(state = VoiceSessionState.Listening, connects = it.connects + 1)
            }

            opened.startAudioConversation(
                LiveAudioConversationConfig.builder()
                    // Barge-in is what the product wants, but with it on the model also
                    // interrupts *itself*: whatever of its own voice survives echo cancellation
                    // restarts the turn, so one question draws several spoken answers. Off while
                    // the audio path is being characterised; see HALF_DUPLEX below.
                    .setEnableInterruptions(ENABLE_INTERRUPTIONS)
                    // Without this the assistant hears its own voice, transcribes it as the
                    // user, and greets itself in a loop that never lets a real turn through.
                    // VOICE_COMMUNICATION puts capture on the platform's echo-cancelled path,
                    // and matching playback usage is what the canceller references.
                    .setInitializationHandler { record, track ->
                        record.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                        track.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        )
                    }
                    .setTranscriptHandler { userSpeech, modelSpeech ->
                        // Transcripts arrive a few words at a time, so one utterance spans
                        // several callbacks; the assembler joins them back together.
                        userSpeech?.text?.let { transcript.add(TranscriptSpeaker.User, it) }
                        modelSpeech?.text?.let { transcript.add(TranscriptSpeaker.Model, it) }
                        _report.update {
                            it.copy(
                                history = transcript.history,
                                lastUserText = transcript.lastUserText,
                                lastModelText = transcript.lastModelText,
                                state = if (modelSpeech?.text != null) {
                                    VoiceSessionState.Speaking
                                } else {
                                    VoiceSessionState.Listening
                                },
                            )
                        }
                    }
                    .setGoAwayHandler { goAway ->
                        // The connection is about to close. Recorded here; rollover that
                        // carries the task goal across is M3 work, not this spike's.
                        val seconds = goAway.timeLeft?.toLong(DurationUnit.SECONDS)
                        Log.i(TAG, "server going away in ${seconds ?: "?"}s")
                        _report.update {
                            it.copy(state = VoiceSessionState.Reconnecting, goAwaySecondsLeft = seconds)
                        }
                    }
                    .build(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "live session failed", e)
            // The message may name the model or project but never user speech.
            _report.update {
                it.copy(state = VoiceSessionState.Error, error = e::class.simpleName + ": " + e.message)
            }
            runCatching { session?.close() }
            session = null
        }
    }

    /**
     * Sends one ordinary (non-Live) text request purely to surface a readable error.
     *
     * The Live websocket is closed by the server without a code or reason the SDK exposes, so a
     * failed handshake says nothing about *why*. A normal request to the same project and
     * backend returns a proper HTTP error instead — "API not enabled", "model not found" and
     * "permission denied" all look identical over the socket but are distinct here.
     */
    suspend fun diagnose(): String = try {
        val model = Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel(AiConfig.TEXT_MODEL)
        val reply = model.generateContent("Say OK")
        "text model OK: " + (reply.text?.take(40) ?: "(empty reply)")
    } catch (e: Exception) {
        (e::class.simpleName + ": " + e.message).take(400)
    }

    override suspend fun stop() {
        val open = session ?: run {
            _report.update { it.copy(state = VoiceSessionState.Stopped) }
            return
        }
        runCatching { open.stopAudioConversation() }
        runCatching { open.close() }
        session = null
        _report.update { it.copy(state = VoiceSessionState.Stopped) }
    }

    /**
     * The prompt, built in one place because it carries the only two levers the SDK leaves over
     * input interpretation: the language assertion and the domain vocabulary.
     */
    private fun systemInstruction(): String = buildString {
        // speechConfig.languageCode sets the *output* voice only: input audio was being
        // auto-detected and transcribed as Russian. The system instruction is the only lever
        // the SDK exposes over interpretation.
        append("The user speaks Georgian (ka) and only Georgian. ")
        append("Always interpret incoming audio as Georgian, never as Russian or any other ")
        append("language, even when the audio is unclear. ")
        append("Transcribe what you hear in Georgian (Mkhedruli) script, never transliterated ")
        append("into Latin letters. ")
        // Short utterances are where recognition fails: "დენი" came back as "ტენისი". Naming
        // the domain gives the decoder something to prefer when the audio is ambiguous.
        append("The user is standing at a self-service payment kiosk in Georgia and you help ")
        append("them, who is blind, say what they want to do. ")
        append("Utterances are often a single short word. ")
        append("Expect these Georgian words and prefer them over similar-sounding ones: ")
        append(AiConfig.DOMAIN_VOCABULARY.joinToString(", "))
        append(". ")
        // The hard safety boundary. An earlier version of this prompt framed the assistant AS
        // the kiosk; it then invented a whole transaction, announcing "forty lari received",
        // "the amount is confirmed" and "payment mode is active" when none of that had
        // happened. A blind user cannot check such a claim, which is precisely why
        // SAFETY_AND_PRIVACY.md forbids fabricating a screen state. See R-20.
        append("YOU ARE NOT THE KIOSK AND YOU CANNOT SEE ITS SCREEN. ")
        append("You have no way to know what the kiosk shows or what it has accepted. ")
        append("Never state that money was received, that an amount was confirmed, that a ")
        append("payment went through, that a service or button was selected, or that any ")
        append("step of a transaction has completed. Never describe the kiosk's current ")
        append("state at all. If the user asks what the screen shows or whether something ")
        append("was accepted, say in Georgian that you cannot see the screen. ")
        append("Only the application itself, from the camera, determines what is on screen. ")
        // Second pass: the ban above stopped "forty lari received", but the model then said
        // "you entered 40 lari" and "you selected payment". The user had done neither — they
        // had spoken to the assistant. Verbs of acting on the machine imply a kiosk state just
        // as much as a confirmation does, so they are named and banned too.
        append("The user has not touched the kiosk. They are talking to you. ")
        append("Never say the user entered, typed, pressed, selected or chose anything on the ")
        append("kiosk. Say only what they told you, in the form \"you said ...\" or ")
        append("\"you asked for ...\". ")
        append("Confirm back what the user asked for, and nothing more. ")
        append("Always reply in Georgian, in one or two short sentences. ")
        append("Never ask for or repeat a PIN or card number.")
    }

    private companion object {
        const val TAG = "GeminiLiveVoice"
        const val HISTORY_LIMIT = 12

        /**
         * Whether the user may talk over the assistant.
         *
         * The product needs this true. It is false for now because speaker playback leaks past
         * the platform echo canceller on this device, and every leak counts as an interruption,
         * producing several replies to one question. Re-enable once playback is routed somewhere
         * the microphone cannot hear it, or once a half-duplex gate mutes capture while speaking.
         */
        const val ENABLE_INTERRUPTIONS = false
    }
}
