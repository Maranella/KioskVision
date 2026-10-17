package com.maranella.kioskvision.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.google.firebase.Firebase
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerToolCall
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import com.maranella.kioskvision.ai.AiConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A Live session the app drives itself, so that **only one voice is ever audible**.
 *
 * ### Why this exists
 *
 * [GeminiLiveVoiceProvider] uses `startAudioConversation`, which owns both the microphone and
 * an `AudioTrack` of its own. That gives the model a speaker the app cannot mute, alongside
 * the app's own Georgian text-to-speech — two voices, which for a blind user is not a rough
 * edge but an unusable interface. The SDK exposes no signal for when its playback stops, so
 * the two can only be arbitrated by guesswork (R-22, R-24).
 *
 * The obvious configuration fix was measured and does not work: `ResponseModality.TEXT` with
 * `startAudioConversation` stops streaming the microphone after about 491 ms.
 *
 * So this takes the transport apart. The app owns the microphone, sends audio frames itself,
 * and reads the one `receive()` flow. **No model audio is ever played**: the response arrives
 * as text and is spoken by the single local engine the rest of the app already uses. One
 * voice, by construction rather than by timing.
 *
 * ### What is prototype about it
 *
 * It is not wired into the product path. Both providers implement [VoiceProvider] so they can
 * be compared on a real device, which is the only place the question — do two voices overlap? —
 * can actually be answered.
 *
 * ### Verified SDK surface (firebase-ai 18.0.0, checked against the AAR)
 *
 * - `LiveSession.sendAudioRealtime(InlineData)` — one chunk of PCM.
 * - `LiveSession.receive(): Flow<LiveServerMessage>` — collected exactly once, here.
 * - `LiveServerContent.content.parts`, `.turnComplete`, `.interrupted`, `.generationComplete`.
 * - `InlineData(ByteArray, mimeType)`.
 */
@OptIn(PublicPreviewAPI::class)
class StreamingVoiceProvider(
    context: Context,
    private val actions: AssistantActions? = null,
    /** Called with a whole Georgian utterance, on a background thread, to be spoken locally. */
    private val onSpeak: (String) -> Unit = {},
    /** Called when the model is cut off, so anything queued for speaking is abandoned. */
    private val onCancelSpeech: () -> Unit = {},
) : VoiceProvider {

    private val appContext = context.applicationContext

    /**
     * Mirrors what was heard and what was said to logcat.
     *
     * **Debug builds only.** These are the user's own words, which the rest of the app keeps
     * in memory and never writes anywhere; the same rule applies here, and the flag is set
     * from the application's debuggable flag rather than from a constant someone could leave
     * on. It exists because a Georgian recognition problem cannot be diagnosed without seeing
     * the two sides of the exchange.
     */
    @Volatile
    var diagnosticsEnabled: Boolean = false

    private val _report = MutableStateFlow(VoiceSessionReport(VoiceSessionState.Idle))
    override val report: StateFlow<VoiceSessionReport> = _report.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: LiveSession? = null
    private var microphone: AudioRecord? = null
    private var captureJob: Job? = null
    private var receiveJob: Job? = null

    /**
     * While true, captured audio is read and discarded instead of sent.
     *
     * This is the half-duplex gate. The app's own text-to-speech and this microphone are both
     * live at once, so without it the assistant hears its own reply, transcribes it as the
     * user and answers itself — the loop R-18 first ran into. The buffer is still drained so
     * that resuming does not replay stale audio from the moment the app was talking.
     */
    @Volatile
    var microphonePaused: Boolean = false

    /** Text accumulated for the current turn; spoken only once the turn is complete. */
    private val pending = StringBuilder()

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun start() {
        if (session?.isClosed() == false) return
        _report.update { it.copy(state = VoiceSessionState.Connecting, error = null) }
        try {
            val model = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
                modelName = AiConfig.LIVE_MODEL,
                tools = if (actions == null) emptyList() else listOf(AssistantToolSchema.tool()),
                generationConfig = liveGenerationConfig {
                    // AUDIO, even though no audio is ever played.
                    //
                    // TEXT looks like the obvious choice and does not work: the Live models
                    // are audio-native and close the channel when asked for text. Measured
                    // twice, by two different transports — the microphone stopped after
                    // 491 ms through `startAudioConversation` and 433 ms through this one.
                    // The common factor was the modality, not the transport.
                    //
                    // So the session is kept valid and the audio is simply **never played**:
                    // this provider has no AudioTrack, ignores audio parts, and speaks the
                    // output transcription through the app's one engine instead. Silence is
                    // achieved by not having a speaker, which no setting can undo.
                    responseModality = ResponseModality.AUDIO
                    speechConfig = SpeechConfig(Voice(AiConfig.VOICE_NAME), AiConfig.SPOKEN_LANGUAGE)
                    inputAudioTranscription = AudioTranscriptionConfig()
                    outputAudioTranscription = AudioTranscriptionConfig()
                },
                systemInstruction = content { text(AssistantPrompt.text()) },
            )

            val opened = model.connect()
            session = opened
            _report.update { it.copy(state = VoiceSessionState.Listening, connects = it.connects + 1) }

            startReceiving(opened)
            startCapturing(opened)
        } catch (e: CancellationException) {
            stopInternals()
            _report.update { it.copy(state = VoiceSessionState.Stopped) }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "streaming session failed", e)
            stopInternals()
            _report.update {
                it.copy(state = VoiceSessionState.Error, error = e::class.simpleName + ": " + e.message)
            }
        }
    }

    /**
     * Reads the model's side of the conversation. Exactly one collector, owned by the session.
     *
     * Text is accumulated rather than spoken as it arrives: the server sends a turn in
     * fragments, and speaking each one would produce stuttered, overlapping utterances from
     * the local engine. A turn is spoken when the server says it is complete.
     */
    private fun startReceiving(session: LiveSession) {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            try {
                session.receive().collect { message ->
                    // The model asking the app to do something. Owning the transport means
                    // owning this too: `startAudioConversation` had a handler for it, and
                    // writing our own receive loop without one meant every tool call was
                    // dropped. The model then waited for a response that never came and
                    // produced no reply at all — the silence that looked like "it says
                    // nothing", observed as `SRV other: LiveServerToolCall` beside an empty
                    // turn.
                    if (message is LiveServerToolCall) {
                        handleToolCalls(session, message.functionCalls)
                        return@collect
                    }

                    val content = message as? LiveServerContent ?: run {
                        if (diagnosticsEnabled) Log.i(TAG, "SRV other: ${message::class.simpleName}")
                        return@collect
                    }
                    if (diagnosticsEnabled) {
                        // The shape of what actually arrives. Without it, "the model said
                        // nothing" and "the model spoke and we never saw the text" are the
                        // same empty turn.
                        Log.i(
                            TAG,
                            "SRV parts=${content.content?.parts?.size ?: 0}" +
                                " out=${content.outputTranscription?.text?.length ?: -1}" +
                                " in=${content.inputTranscription?.text?.length ?: -1}" +
                                " turnComplete=${content.turnComplete}" +
                                " genComplete=${content.generationComplete}" +
                                " interrupted=${content.interrupted}",
                        )
                    }

                    // The model's words arrive as the transcription of speech that is never
                    // played. Text parts are appended too, for a model that sends them.
                    content.outputTranscription?.text?.let { pending.append(it) }
                    content.content?.parts.orEmpty()
                        .filterIsInstance<TextPart>()
                        .forEach { pending.append(it.text) }

                    content.inputTranscription?.text?.let { heard ->
                        if (diagnosticsEnabled) Log.i(TAG, "HEARD: $heard")
                        _report.update { it.copy(lastUserText = heard) }
                    }

                    if (content.interrupted && diagnosticsEnabled) Log.i(TAG, "INTERRUPTED")
                    if (content.interrupted) {
                        // Whatever was being prepared is now answering a question the user has
                        // already moved on from. Dropping it is the point of knowing.
                        pending.setLength(0)
                        onCancelSpeech()
                        _report.update { it.copy(state = VoiceSessionState.Listening) }
                        return@collect
                    }

                    if (content.turnComplete) {
                        val utterance = pending.toString().speakableOrEmpty()
                        pending.setLength(0)
                        // A turn can complete with nothing in it — the model stays silent on
                        // an utterance it could not make sense of, which is most of them when
                        // recognition drifts out of Georgian. Logging those as empty replies
                        // buried the turns that did produce one.
                        if (utterance.isEmpty() && diagnosticsEnabled) Log.i(TAG, "SAID: (nothing to speak)")
                        if (utterance.isNotEmpty()) {
                            if (diagnosticsEnabled) Log.i(TAG, "SAID: $utterance")
                            _report.update {
                                it.copy(state = VoiceSessionState.Speaking, lastModelText = utterance)
                            }
                            onSpeak(utterance)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "receive ended", e)
                _report.update { it.copy(state = VoiceSessionState.Error, error = "receive: ${e.message}") }
            }
        }
    }

    /**
     * Answers the model's tool calls and sends the outcomes back.
     *
     * Each call is resolved on the device against what the camera observed, and the reply
     * carries one outcome and nothing else — no label, no coordinate, no screen text
     * (ADR-009). Without the response the model's turn never completes.
     */
    private suspend fun handleToolCalls(
        session: LiveSession,
        calls: List<com.google.firebase.ai.type.FunctionCallPart>,
    ) {
        val resolved = actions ?: run {
            if (diagnosticsEnabled) Log.i(TAG, "TOOL ignored: no actions bound")
            return
        }
        val responses = calls.map { call ->
            val arguments = call.args.mapValues { (_, value) ->
                (value as? kotlinx.serialization.json.JsonPrimitive)?.let {
                    if (it.isString) it.content else it.content.ifEmpty { null }
                }
            }
            val outcome = AssistantTools.dispatch(call.name, arguments, resolved)
            if (diagnosticsEnabled) Log.i(TAG, "TOOL ${call.name} -> ${outcome.wireValue}")
            FunctionResponsePart(
                name = call.name,
                response = kotlinx.serialization.json.buildJsonObject {
                    put("outcome", kotlinx.serialization.json.JsonPrimitive(outcome.wireValue))
                },
            )
        }
        runCatching { session.sendFunctionResponse(responses) }
            .onFailure { Log.e(TAG, "tool response failed", it) }
    }

    /**
     * Streams the microphone.
     *
     * `VOICE_COMMUNICATION` is kept from the previous provider: it puts capture on the
     * platform's echo-cancelled path. That mattered when the model had a speaker; it still
     * helps here, because the app's own text-to-speech is now the thing the microphone must
     * not hear itself saying.
     */
    @SuppressLint("MissingPermission")
    private fun startCapturing(session: LiveSession) {
        captureJob?.cancel()
        captureJob = scope.launch {
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            if (minimum <= 0) {
                _report.update { it.copy(state = VoiceSessionState.Error, error = "no microphone buffer") }
                return@launch
            }
            val bufferSize = maxOf(minimum, CHUNK_BYTES * 2)
            val record = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
            } catch (e: Exception) {
                _report.update { it.copy(state = VoiceSessionState.Error, error = "microphone: ${e.message}") }
                return@launch
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                _report.update { it.copy(state = VoiceSessionState.Error, error = "microphone unavailable") }
                return@launch
            }

            microphone = record
            record.startRecording()
            Log.i(TAG, "microphone open, streaming")
            var sent = 0L
            var dropped = 0L
            val buffer = ByteArray(CHUNK_BYTES)
            try {
                while (isActive && !session.isClosed()) {
                    val read = withContext(Dispatchers.IO) { record.read(buffer, 0, buffer.size) }
                    if (read <= 0) continue
                    // Read, then drop: draining keeps the buffer from holding the app's own
                    // voice to be sent the instant speaking stops.
                    if (microphonePaused) {
                        dropped++
                        continue
                    }
                    sent++
                    // Throttled: proves whether audio is leaving the device at all, which is
                    // the difference between "the microphone is muted" and "the server is
                    // silent" — indistinguishable from the outside.
                    if (diagnosticsEnabled && sent % CHUNKS_PER_LOG == 0L) {
                        Log.i(TAG, "sent $sent chunks, dropped $dropped while speaking")
                    }
                    session.sendAudioRealtime(
                        InlineData(buffer.copyOf(read), "audio/pcm;rate=$SAMPLE_RATE"),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "capture ended", e)
                _report.update { it.copy(state = VoiceSessionState.Error, error = "capture: ${e.message}") }
            } finally {
                Log.i(TAG, "capture stopped; sessionClosed=${runCatching { session.isClosed() }.getOrNull()}")
                runCatching { record.stop() }
                runCatching { record.release() }
                if (microphone === record) microphone = null
            }
        }
    }

    override suspend fun stop() {
        stopInternals()
        _report.update { it.copy(state = VoiceSessionState.Stopped) }
    }

    private fun stopInternals() {
        onCancelSpeech()
        pending.setLength(0)
        captureJob?.cancel()
        receiveJob?.cancel()
        captureJob = null
        receiveJob = null
        microphone?.let { runCatching { it.stop() }; runCatching { it.release() } }
        microphone = null
        session?.let { open ->
            runCatching { open.stopReceiving() }
            // close() suspends, and this runs from a plain stop path; the socket is closed on
            // the provider's own scope rather than blocking the caller.
            scope.launch { runCatching { open.close() } }
        }
        session = null
    }

    private companion object {
        const val TAG = "StreamingVoice"

        /** The Live API's documented input format: 16-bit PCM, 16 kHz, mono. */
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** Roughly 100 ms per chunk: small enough to stay responsive, large enough to be cheap. */
        const val CHUNK_BYTES = SAMPLE_RATE / 10 * 2

        /** Roughly five seconds of audio between progress lines. */
        const val CHUNKS_PER_LOG = 50L
    }
}

/**
 * The text with nothing to say reduced to the empty string.
 *
 * A completed turn is not the same as a reply. The model answers an utterance it could not
 * make sense of with a zero-width space — observed as `SAID: \u200b` — which `trim()` leaves
 * alone and `isNotEmpty()` accepts, so the engine was handed a string containing no speech
 * and the assistant appeared mute. Requiring at least one letter or digit is the test that
 * actually distinguishes a reply from a placeholder.
 */
private fun String.speakableOrEmpty(): String {
    val cleaned = filterNot { it.isWhitespace() && it != ' ' }
        .replace('\u200B', ' ')
        .replace('\uFEFF', ' ')
        .trim()
    return if (cleaned.any { it.isLetterOrDigit() }) cleaned else ""
}
