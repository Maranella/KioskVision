package com.maranella.kioskvision.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.LiveAudioConversationConfig
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import com.maranella.kioskvision.ai.AiConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
class GeminiLiveVoiceProvider(
    context: Context,
    /**
     * What the assistant may ask the app to do. Null leaves the session conversational only,
     * which is what the diagnostics screen uses.
     */
    private val actions: AssistantActions? = null,
) : VoiceProvider {

    /**
     * Resolved lazily, not in the constructor.
     *
     * An Activity may construct this as a field initialiser, which runs before its base
     * context is attached — `getApplicationContext()` then returns null and the app crashes on
     * launch. Deferring the lookup to first use makes the provider safe to construct wherever
     * it is convenient, which is how a caller will reasonably expect it to behave.
     */
    private val audioManager: AudioManager? by lazy {
        runCatching { context.applicationContext.getSystemService(AudioManager::class.java) }.getOrNull()
    }

    /** Restored on stop, so leaving the app does not leave the phone in call audio mode. */
    private var previousAudioMode: Int? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdog: Job? = null

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
                tools = if (actions == null) emptyList() else listOf(AssistantToolSchema.tool()),
                generationConfig = liveGenerationConfig {
                    // TEXT silences the model's own speaker so one engine speaks (AiConfig).
                    responseModality =
                        if (AiConfig.SINGLE_VOICE) ResponseModality.TEXT else ResponseModality.AUDIO
                    // Georgian for the spoken reply. The voice is left at the service default;
                    // naturalness in Georgian is one of the things this spike has to judge.
                    speechConfig = SpeechConfig(Voice(AiConfig.VOICE_NAME), AiConfig.SPOKEN_LANGUAGE)
                    // Ask for both transcripts so the spike can separate "did not hear" from
                    // "heard and misunderstood" — the distinction the whole evaluation rests on.
                    inputAudioTranscription = AudioTranscriptionConfig()
                    outputAudioTranscription = AudioTranscriptionConfig()
                },
                systemInstruction = content { text(AssistantPrompt.text()) },
            )

            val opened = model.connect()
            session = opened
            _report.update {
                it.copy(state = VoiceSessionState.Listening, connects = it.connects + 1)
            }

            routeCommunicationAudio()
            watchForClose(opened)

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
                    .setFunctionCallHandler { call -> handleFunctionCall(call) }
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
        } catch (e: CancellationException) {
            // The session was stopped, not broken. Catching this would both mislabel a
            // deliberate stop as an error — which the reconnect policy then counts against its
            // retry budget — and break structured concurrency by swallowing a cancellation the
            // parent scope is entitled to see.
            runCatching { session?.close() }
            restoreAudioRoute()
            session = null
            _report.update { it.copy(state = VoiceSessionState.Stopped) }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "live session failed", e)
            // The message may name the model or project but never user speech.
            _report.update {
                it.copy(state = VoiceSessionState.Error, error = e::class.simpleName + ": " + e.message)
            }
            runCatching { session?.close() }
            restoreAudioRoute()
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
        val plain = "text OK: " + (reply.text?.take(30) ?: "(empty)")

        // The Live socket closes without a reason the SDK exposes, so the same tool
        // declaration is sent over an ordinary request, which answers with a readable HTTP
        // error naming the offending field if the schema is the problem.
        val withTool = runCatching {
            Firebase.ai(backend = GenerativeBackend.googleAI())
                .generativeModel(AiConfig.TEXT_MODEL, tools = listOf(AssistantToolSchema.tool()))
                .generateContent("Say OK")
        }
        val toolResult = withTool.fold(
            onSuccess = { "tools OK" },
            onFailure = { (it::class.simpleName + ": " + it.message).take(220) },
        )
        "$plain | $toolResult"
    } catch (e: Exception) {
        (e::class.simpleName + ": " + e.message).take(400)
    }

    override suspend fun stop() {
        val open = session ?: run {
            _report.update { it.copy(state = VoiceSessionState.Stopped) }
            return
        }
        watchdog?.cancel()
        watchdog = null
        runCatching { open.stopAudioConversation() }
        runCatching { open.close() }
        restoreAudioRoute()
        session = null
        _report.update { it.copy(state = VoiceSessionState.Stopped) }
    }

    /**
     * Notices when the session ends, which nothing otherwise does.
     *
     * `startAudioConversation` returns without saying whether the conversation is still alive,
     * and the socket can be closed by the server at any time — the SDK surfaces that only
     * through `isClosed()`. Without this the session died silently after a few seconds: the
     * microphone was released, the UI still said the assistant was listening, and the
     * reconnect policy never fired because nothing told it the session had ended.
     *
     * Reported as [VoiceSessionState.Error] rather than Stopped, because an end nobody asked
     * for is a failure, and the retry budget is what should decide whether to try again.
     */
    private fun watchForClose(session: LiveSession) {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive) {
                delay(CLOSE_POLL_MS)
                if (session.isClosed()) {
                    Log.i(TAG, "live session closed by the server")
                    restoreAudioRoute()
                    _report.update {
                        it.copy(state = VoiceSessionState.Error, error = "session closed")
                    }
                    return@launch
                }
            }
        }
    }

    /**
     * Puts the phone into communication audio and sends playback somewhere audible.
     *
     * The model's voice was coming out very quietly next to the app's own guidance, which is a
     * direct consequence of the echo fix in R-18: `USAGE_VOICE_COMMUNICATION` is the telephony
     * path, carried on the call stream and governed by call volume, while the app's TTS speaks
     * on the navigation-guidance path at media volume. Two voices at two loudnesses.
     *
     * The usage cannot simply be changed back — it is what puts capture and playback on the
     * platform's echo-cancelled path, and without it the assistant interviews itself. So the
     * stream is made audible instead: `MODE_IN_COMMUNICATION` activates it, and an output
     * device is chosen explicitly.
     *
     * A wired or Bluetooth headset is preferred over the loudspeaker where one is connected.
     * That is not a convenience: playback into an earpiece or a headset is playback the
     * microphone can barely hear, which is the real fix for both the echo loop and the
     * barge-in that R-18 had to disable.
     */
    private fun routeCommunicationAudio() {
        val manager = audioManager ?: return
        if (previousAudioMode == null) previousAudioMode = manager.mode
        manager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val preferred = PREFERRED_OUTPUTS.firstNotNullOfOrNull { type ->
                manager.availableCommunicationDevices.firstOrNull { it.type == type }
            }
            if (preferred != null) {
                val selected = manager.setCommunicationDevice(preferred)
                Log.i(TAG, "communication output ${preferred.type}, selected=$selected")
            }
        } else {
            // Pre-31 has no device selection; the loudspeaker is the only lever, and only
            // when nothing better is plugged in.
            @Suppress("DEPRECATION")
            if (!manager.isWiredHeadsetOn && !manager.isBluetoothScoOn) manager.isSpeakerphoneOn = true
        }
    }

    /** Returns the phone to whatever audio mode it was in before the session. */
    private fun restoreAudioRoute() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching { manager.isSpeakerphoneOn = false }
        }
        previousAudioMode?.let { runCatching { manager.mode = it } }
        previousAudioMode = null
    }

    /**
     * The functions the model may call.
     *
     * This is how an intent reaches the app at all. The input transcript cannot be used for it:
     * R-19 found it returns Latin transliteration and, at times, an entirely different
     * language, while the model's *comprehension* of the Georgian stays correct. A function
     * call comes from that comprehension, so it carries the meaning the transcript loses.
     */
    /**
     * Answers one tool call, synchronously, on the SDK's thread.
     *
     * The reply carries a single outcome and nothing else — no label, no position, no screen
     * text (ADR-009). Everything the user actually needs to hear about the screen is spoken
     * locally from `SpokenMessage`, where it is grounded in what the camera saw.
     */
    private fun handleFunctionCall(call: FunctionCallPart): FunctionResponsePart {
        val arguments = call.args.mapValues { (_, value) ->
            (value as? JsonPrimitive)?.contentOrNull
        }
        val outcome = actions
            ?.let { AssistantTools.dispatch(call.name, arguments, it) }
            ?: ToolOutcome.NoScreen
        // Never logged with its arguments: the query is the user's own words.
        Log.i(TAG, "tool ${call.name} -> ${outcome.wireValue}")
        return FunctionResponsePart(
            name = call.name,
            response = buildJsonObject { put("outcome", JsonPrimitive(outcome.wireValue)) },
        )
    }

    /**
     * The prompt, built in one place because it carries the only two levers the SDK leaves over
     * input interpretation: the language assertion and the domain vocabulary.
     */
    private companion object {
        const val TAG = "GeminiLiveVoice"

        /** How often the socket is checked for having been closed from the other end. */
        const val CLOSE_POLL_MS = 1_000L

        /**
         * Output devices in order of preference. A headset keeps the assistant's voice out of
         * the microphone entirely; the loudspeaker is the audible fallback.
         */
        val PREFERRED_OUTPUTS = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
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
