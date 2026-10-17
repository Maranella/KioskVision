package com.maranella.kioskvision.ui.assist

import android.app.Application
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maranella.kioskvision.assist.AssistCommand
import com.maranella.kioskvision.assist.CommandParser
import com.maranella.kioskvision.assist.RecognitionFailure
import com.maranella.kioskvision.assist.ResolutionFailure
import com.maranella.kioskvision.assist.TargetResolution
import com.maranella.kioskvision.assist.TargetResolver
import com.maranella.kioskvision.assist.UserIntent
import com.maranella.kioskvision.assist.SpokenMessage
import com.maranella.kioskvision.camera.FocusState
import com.maranella.kioskvision.camera.ScreenFocusController
import com.maranella.kioskvision.hand.HandLandmarkTracker
import com.maranella.kioskvision.ocr.KioskOcrEngine
import com.maranella.kioskvision.pipeline.AssistancePhase
import com.maranella.kioskvision.pipeline.DebugFrame
import com.maranella.kioskvision.pipeline.LiveAssistPipeline
import com.maranella.kioskvision.pipeline.SessionSnapshot
import com.maranella.kioskvision.sensors.DeviceMotionMonitor
import com.maranella.kioskvision.settings.VoicePreferences
import com.maranella.kioskvision.speech.AudioOutputCoordinator
import com.maranella.kioskvision.speech.GuidanceSpeechOutput
import com.maranella.kioskvision.speech.SpeechDecision
import com.maranella.kioskvision.speech.SpeechCommandRecognizer
import com.maranella.kioskvision.speech.SpeechOutputStatus
import com.maranella.kioskvision.ui.main.PermissionStatus
import com.maranella.kioskvision.voice.AssistantActions
import com.maranella.kioskvision.voice.AssistantReply
import com.maranella.kioskvision.voice.StreamingVoiceProvider
import com.maranella.kioskvision.voice.TextAssistant
import com.maranella.kioskvision.voice.ToolOutcome
import com.maranella.kioskvision.voice.VoiceSessionReport
import com.maranella.kioskvision.voice.VoiceSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EngineStatus { Loading, Ready, Failed }

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
     * device** while this is active, which is why it is only ever entered by an explicit user
     * action and never automatically.
     */
    LiveAssistant,
}

@Immutable
data class LiveAssistUiState(
    val micPermission: PermissionStatus = PermissionStatus.Unknown,
    /** The microphone explanation is shown only after the user asks for a voice command. */
    val showMicExplanation: Boolean = false,
    val listening: Boolean = false,
    val heardText: String? = null,
    val session: SessionSnapshot = SessionSnapshot(),
    val lastMessage: SpokenMessage? = null,
    val speechOutput: SpeechOutputStatus = SpeechOutputStatus.Initializing,
    val recognitionAvailable: Boolean = true,
    /** Null until the recognition service answers; false means it has no Georgian. */
    val georgianRecognition: Boolean? = null,
    val ocr: EngineStatus = EngineStatus.Loading,
    val hand: EngineStatus = EngineStatus.Loading,
    val debugOverlay: Boolean = false,
    val focus: FocusState = FocusState.Unknown,
    val voiceMode: VoiceMode = VoiceMode.LocalCommands,
    /** The user's durable choice about the streaming assistant. */
    val assistantEnabled: Boolean = false,
    /** Shown once, the first time the user speaks, so consent is asked rather than assumed. */
    val showAssistantConsent: Boolean = false,
    /** Live session state, as the provider actually reports it. */
    val voice: VoiceSessionReport = VoiceSessionReport(VoiceSessionState.Idle),
    /** The assistant's most recent reply, shown for diagnostics; never kiosk content. */
    val assistantText: String? = null,
)

/**
 * Single source of truth for live assist. Owns the models, the speech components and the
 * pipeline, so they survive configuration changes and are released exactly once.
 */
class LiveAssistViewModel(application: Application) :
    AndroidViewModel(application),
    LiveAssistPipeline.Listener,
    AssistantActions {

    private val _state = MutableStateFlow(LiveAssistUiState())
    val state: StateFlow<LiveAssistUiState> = _state.asStateFlow()

    /** Kept apart from [state] so frame-rate updates only recompose the overlay. */
    private val _debugFrame = MutableStateFlow<DebugFrame?>(null)
    val debugFrame: StateFlow<DebugFrame?> = _debugFrame.asStateFlow()

    private val motion = DeviceMotionMonitor(application)
    private val pipeline = LiveAssistPipeline(motion, this)

    /**
     * Owned here, not by the camera composable, so recomposition can never restart the
     * camera session or re-trigger a focus run (see [ScreenFocusController]).
     */
    val focusController = ScreenFocusController(
        executor = { runnable -> viewModelScope.launch(Dispatchers.Main.immediate) { runnable.run() } },
        onState = { state ->
            pipeline.onFocusState(state)
            _state.update { it.copy(focus = state) }
        },
    )
    private val recognizer = SpeechCommandRecognizer(application)

    /**
     * The Live assistant, behind [VoiceProvider] so the Azure ka-GE fallback can replace it.
     *
     * It is given no camera frame, no OCR text and no screen geometry, and the interface has
     * no method that could carry them (ADR-009). What is on the kiosk screen is answered from
     * [SpokenMessage] values the pipeline produces, never in the model's own words.
     */
    /**
     * The assistant, over text generation rather than the Live transport.
     *
     * Speech in comes from [recognizer], the reply comes back as text, and [speech] says it.
     * One voice, because `generateContent` has no audio to emit.
     */
    private val assistant = TextAssistant(actions = this)

    /**
     * The microphone transport. Owns capture itself so listening is continuous and silent;
     * replies arrive as text and are spoken by [speech], never by the model.
     */
    private val streaming = StreamingVoiceProvider(
        context = application,
        actions = this,
        onSpeak = { text -> viewModelScope.launch { speakAssistant(text) } },
        onCancelSpeech = { speech.stop() },
    )
    private val targetResolver = TargetResolver()
    private val voicePreferences = VoicePreferences(application)
    private val audioOutput = AudioOutputCoordinator()

    /** One assistant request at a time; a newer question cancels an unanswered one. */
    private var assistantJob: Job? = null

    /** The continuous recognition loop. */
    private var listenJob: Job? = null

    /** Holds the microphone closed while the app is speaking. */
    private var speakJob: Job? = null
    private var guidanceSpeakJob: Job? = null

    /** Identifies one reply across its stages in the log. */
    private var speechRequestId = 0L

    /** When a directional cue was last spoken; conversation yields for a moment after one. */
    private var lastDirectionSpokenMs = Long.MIN_VALUE / 2

    /** Outlives [viewModelScope] only long enough to release the microphone and the socket. */
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val speech = GuidanceSpeechOutput(application) { status ->
        _state.update { it.copy(speechOutput = status) }
    }

    val analyzer get() = pipeline.analyzer
    val analysisExecutor get() = pipeline.executor

    init {
        // Diagnostic logging names recognised screen content, so it is for debug builds only.
        streaming.diagnosticsEnabled =
            application.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        pipeline.diagnosticsEnabled =
            application.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        _state.update {
            it.copy(
                recognitionAvailable = recognizer.isAvailable,
                assistantEnabled = voicePreferences.assistantEnabled,
            )
        }
        recognizer.queryLanguages()
        viewModelScope.launch {
            val hand = withContext(Dispatchers.IO) { runCatching { HandLandmarkTracker.create(application) } }
            hand.onSuccess(pipeline::setHandTracker)
                .onFailure { Log.e(TAG, "Hand model unavailable", it); pipeline.onEngineUnavailable() }
            _state.update { it.copy(hand = if (hand.isSuccess) EngineStatus.Ready else EngineStatus.Failed) }
        }
        viewModelScope.launch {
            val ocr = withContext(Dispatchers.IO) { runCatching { KioskOcrEngine.create(application) } }
            ocr.onSuccess(pipeline::setOcrEngine)
                .onFailure { Log.e(TAG, "OCR unavailable", it); pipeline.onEngineUnavailable() }
            _state.update { it.copy(ocr = if (ocr.isSuccess) EngineStatus.Ready else EngineStatus.Failed) }
        }
    }

    // --- Lifecycle --------------------------------------------------------------------------

    /** Called when the camera screen starts or stops being visible. */
    fun onForegroundChanged(visible: Boolean) {
        if (visible) {
            motion.start()
            // Hands-free: the user is holding the phone at a kiosk and reaching for a button
            // with the other hand. Only after they have explicitly enabled the assistant, and
            // only while this screen is in front of them — leaving it stops the microphone.
            if (_state.value.assistantEnabled &&
                _state.value.micPermission == PermissionStatus.Granted
            ) {
                startListening()
            }
            // Begins screen acquisition, which is also the only thing that asks for focus.
            pipeline.start()
        } else {
            motion.stop()
            cancelListening()
            speech.stop()
            pipeline.stopSilently()
        }
    }

    // --- Microphone permission --------------------------------------------------------------

    fun onMicPermissionChecked(granted: Boolean) = _state.update { state ->
        when {
            granted -> state.copy(micPermission = PermissionStatus.Granted, showMicExplanation = false)
            state.micPermission == PermissionStatus.Denied || state.micPermission == PermissionStatus.PermanentlyDenied -> state
            else -> state.copy(micPermission = PermissionStatus.NotGranted)
        }
    }

    fun onMicPermissionResult(granted: Boolean, canAskAgain: Boolean) {
        _state.update {
            it.copy(
                micPermission = when {
                    granted -> PermissionStatus.Granted
                    canAskAgain -> PermissionStatus.Denied
                    else -> PermissionStatus.PermanentlyDenied
                },
                showMicExplanation = !granted,
            )
        }
        // The user asked for a voice command before the dialog, so continue with it.
        if (granted) startListening()
    }

    fun dismissMicExplanation() = _state.update { it.copy(showMicExplanation = false) }

    // --- Voice commands ---------------------------------------------------------------------

    /**
     * The one voice control.
     *
     * The user says "talk" and the app decides how: a streaming conversation if they have
     * enabled the assistant, otherwise push-to-talk recognised on the device. Which engine is
     * used is an implementation detail, and making the user choose it was a mistake — it put
     * two controls that both mean "talk" in front of someone who cannot see either.
     */
    fun onVoiceCommandPressed() {
        val state = _state.value
        when {
            state.listening -> cancelListening()

            state.micPermission != PermissionStatus.Granted ->
                _state.update { it.copy(showMicExplanation = true) }

            // Asked once, at the moment it first matters, rather than buried in a settings
            // screen the user has no reason to visit.
            !voicePreferences.hasChosen -> _state.update { it.copy(showAssistantConsent = true) }

            // Always the same path now: the device recognises the speech, the assistant
            // answers in text, and one engine speaks. There is no longer a branch that gives
            // the model a voice of its own.
            else -> startListening()
        }
    }

    /**
     * Listens continuously until stopped, rather than once per button press.
     *
     * A blind user at a kiosk has one hand on the phone and the other reaching for a button;
     * requiring a press before every sentence makes the assistant useless at exactly the
     * moment it is needed. Android's recogniser is one-shot, so this restarts it in a loop.
     *
     * **The loop pauses while the app itself is speaking.** That is the half-duplex gate the
     * Live transport could never have: both sides are now the app's, so "are we making sound
     * right now" is answered by the engine's own utterance callbacks instead of a timer
     * (R-24). Without it the assistant hears its own replies and answers them.
     */
    /**
     * Opens continuous listening.
     *
     * Android's `SpeechRecognizer` is one-shot: it ends after about 800 ms of silence, and
     * every restart plays the system's recognition tone. Looping it for hands-free use turned
     * the app into a beep every second that never heard a sentence — measured as
     * `error 7 -> NoMatch` on repeat.
     *
     * So the microphone is the app's own `AudioRecord` instead, streamed straight to the
     * model. No restarts, no tone, and silence between sentences costs nothing. The reply
     * comes back as **text** and is spoken by the one local engine, so there is still exactly
     * one voice (R-26).
     */
    private fun startListening() {
        if (listenJob?.isActive == true) return
        speech.stop()
        // Deliberately NOT muting pipeline speech. That flag exists so the local recogniser
        // does not hear the app's own directions and treat them as commands, and it was set
        // for as long as listening lasted. With listening now continuous it stayed on for
        // ever, and `if (toSpeak != null && !speechMuted)` silenced every direction — the
        // app guided correctly and said nothing. The microphone is paused around each
        // utterance instead, which solves the same problem without muting the user.
        pipeline.speechMuted = false
        _state.update { it.copy(listening = true, heardText = null) }

        listenJob = viewModelScope.launch {
            launch {
                streaming.report.collect { report ->
                    _state.update {
                        it.copy(
                            voice = report,
                            heardText = report.lastUserText ?: it.heardText,
                        )
                    }
                }
            }
            streaming.start()
        }
    }

    private fun cancelListening() {
        listenJob?.cancel()
        listenJob = null
        recognizer.cancel()
        viewModelScope.launch { streaming.stop() }
        endListening()
    }

    private fun endListening() {
        pipeline.speechMuted = false
        _state.update { it.copy(listening = false) }
    }

    private fun handleHypotheses(hypotheses: List<String>) {
        // Developer builds only: shows whether a failed request was misheard rather than
        // unmatched, which the pipeline alone cannot distinguish.
        if (pipeline.diagnosticsEnabled) Log.i(TAG, "HEARD ${hypotheses.joinToString(" | ")}")
        val parsed = hypotheses.map { it to CommandParser.parse(it) }
        val (heard, command) = parsed.firstOrNull { it.second != AssistCommand.Unknown } ?: parsed.first()
        _state.update { it.copy(heardText = heard) }
        when (command) {
            is AssistCommand.SelectTarget -> pipeline.selectTarget(
                parsed.mapNotNull { (it.second as? AssistCommand.SelectTarget)?.query }.distinct(),
            )
            AssistCommand.Repeat -> repeatLast()
            // Not one of the app's own commands, so it is a question for the assistant
            // rather than a misheard instruction — unless the user declined, in which case
            // nothing of theirs is sent anywhere.
            AssistCommand.Unknown ->
                if (_state.value.assistantEnabled) askAssistant(heard) else say(SpokenMessage.NotUnderstood)
            AssistCommand.ReadScreen, AssistCommand.Stop -> pipeline.submit(command)
        }
    }

    // --- Live assistant ----------------------------------------------------------------------
    //
    // Deliberately absent. The streaming transport gives the model an AudioTrack the app can
    // neither mute nor observe, which alongside the device's Georgian text-to-speech is two
    // voices at once — unusable when audio is the whole interface. Configuration could not fix
    // it: ResponseModality.TEXT stops the microphone after about 491 ms (R-24).
    //
    // Conversation now goes through TextAssistant, whose endpoint has no audio to emit. The
    // Live provider remains only in the debug diagnostics screen, for comparison.

    /**
     * The user answered the one-time question about the assistant.
     *
     * Still asked, and still about the same boundary: what the user says is recognised and
     * then sent to Google as text. The camera, the OCR and the fingertip never leave the
     * device, whatever the answer.
     */
    fun onAssistantConsent(enabled: Boolean) {
        voicePreferences.assistantEnabled = enabled
        _state.update { it.copy(assistantEnabled = enabled, showAssistantConsent = false) }
        onVoiceCommandPressed()
    }

    fun dismissAssistantConsent() = _state.update { it.copy(showAssistantConsent = false) }

    /**
     * Sends an utterance the local parser did not recognise to the assistant.
     *
     * The reply is spoken through the one coordinator the rest of the app uses, so it obeys
     * the same cancellation, de-duplication and priority rules as everything else. Nothing is
     * sent about the kiosk: only the user's own words leave the device.
     */
    private fun askAssistant(heard: String) {
        if (heard.isBlank()) {
            say(SpokenMessage.NotUnderstood)
            return
        }
        assistantJob?.cancel()
        assistantJob = viewModelScope.launch {
            when (val reply = assistant.respond(heard)) {
                is AssistantReply.Speak -> speakAssistant(reply.text)
                // The app announces what it did in its own voice; adding the assistant's
                // commentary on top is how two voices start overlapping.
                AssistantReply.Acted -> Unit
                is AssistantReply.Failed -> {
                    Log.w(TAG, "assistant unavailable: ${reply.reason}")
                    say(SpokenMessage.NotUnderstood)
                }
            }
        }
    }

    /**
     * Speaks one guidance message with the microphone closed for its duration.
     *
     * The same gate the assistant's replies use. Without it the model hears "move a little
     * left" through the open microphone, transcribes it as the user and answers it.
     */
    private fun speakGuidance(message: SpokenMessage) {
        speech.speak(message)
        guidanceSpeakJob?.cancel()
        guidanceSpeakJob = viewModelScope.launch {
            streaming.microphonePaused = true
            try {
                withTimeoutOrNull(SPEECH_START_TIMEOUT_MS) {
                    while (!speech.isSpeaking) delay(SPEECH_POLL_MS)
                }
                while (speech.isSpeaking) delay(SPEECH_POLL_MS)
                delay(MIC_RESUME_DELAY_MS)
            } finally {
                streaming.microphonePaused = false
            }
        }
    }

    /** The assistant's own words, through the single speech engine. */
    private fun speakAssistant(text: String) {
        // Deliberately **not** gated on `listening`. It was, and with continuous listening
        // that flag is always true, so the assistant never said a word: the reply came back
        // and went nowhere. The guard made sense only while listening meant holding a button.
        //
        // Directions win over conversation, but only once directions are actually being
        // given. Suppressing on the phase alone silenced the one sentence that tells the user
        // guidance has begun — "move your finger and follow the directions" — so the assistant
        // went quiet at the exact moment it had succeeded.
        val sinceDirection = elapsedNow() - lastDirectionSpokenMs
        if (sinceDirection < DIRECTION_HOLDS_VOICE_MS) {
            if (pipeline.diagnosticsEnabled) {
                Log.i(TAG, "speak: suppressed, a direction was spoken ${sinceDirection}ms ago")
            }
            return
        }
        _state.update { it.copy(assistantText = text) }

        val requestId = ++speechRequestId
        if (pipeline.diagnosticsEnabled) {
            Log.i(TAG, "speak#$requestId: ${text.length} chars, tts=${_state.value.speechOutput}")
        }
        speakJob?.cancel()
        speakJob = viewModelScope.launch {
            // The microphone is live while we talk, so it is muted for the duration —
            // otherwise the assistant hears itself and answers its own reply.
            streaming.microphonePaused = true
            try {
                speech.speakText(text)
                // Wait for the engine to actually start, then for it to finish. Both edges
                // come from its own utterance callbacks, which is the one signal in this app
                // that means "sound is coming out" rather than "sound was requested".
                withTimeoutOrNull(SPEECH_START_TIMEOUT_MS) {
                    while (!speech.isSpeaking) delay(SPEECH_POLL_MS)
                }
                val started = speech.isSpeaking
                while (speech.isSpeaking) delay(SPEECH_POLL_MS)
                delay(MIC_RESUME_DELAY_MS)
                if (pipeline.diagnosticsEnabled) {
                    // "started=false" means the engine never produced sound: the reply reached
                    // it and died there, which looks exactly like the model saying nothing.
                    Log.i(TAG, "speak#$requestId: done, engineStarted=$started")
                }
            } finally {
                streaming.microphonePaused = false
            }
        }
    }

    // --- What the assistant may ask for (AssistantActions) -----------------------------------
    //
    // Called synchronously on the Live SDK's thread. Each one resolves against what the camera
    // observed and returns only an outcome: the assistant learns whether its request worked,
    // never what is on the screen (ADR-009).

    override fun selectTarget(query: String): ToolOutcome {
        val scene = pipeline.latestScene
        val outcome = when (val resolution = targetResolver.resolve(UserIntent(query), scene, elapsedNow())) {
            is TargetResolution.Resolved -> ToolOutcome.Guiding
            is TargetResolution.Ambiguous -> ToolOutcome.Ambiguous
            is TargetResolution.Unresolved -> when (resolution.reason) {
                ResolutionFailure.NoScreen -> ToolOutcome.NoScreen
                ResolutionFailure.GeometryUnusable -> ToolOutcome.NoScreen
                ResolutionFailure.SceneUnreadable -> ToolOutcome.ScreenNotReadable
                ResolutionFailure.SceneStale -> ToolOutcome.ScreenNotReadable
                ResolutionFailure.NoSimilarLabel -> ToolOutcome.NotOnScreen
                ResolutionFailure.UnconfirmedLabel -> ToolOutcome.Unconfirmed
            }
        }

        // The resolver's verdict is what the assistant is told. The actual selection still
        // goes through the pipeline, which stays the single authority on the live target and
        // owns anchoring, confirmation and screen transitions. Both reach their answer through
        // TargetMatcher, so they agree about what exists; the pipeline simply knows more.
        // Unconfirmed is included deliberately: the pipeline holds such a request open for
        // several seconds while it looks for a better frame (R-12), which is a far more
        // useful answer than dropping it. Only a label that is genuinely absent is refused
        // outright.
        if (outcome == ToolOutcome.Guiding ||
            outcome == ToolOutcome.Ambiguous ||
            outcome == ToolOutcome.Unconfirmed
        ) {
            pipeline.selectTarget(listOf(query))
        }
        return outcome
    }

    override fun stopGuidance(): ToolOutcome {
        pipeline.submit(AssistCommand.Stop)
        return ToolOutcome.Stopped
    }

    override fun readScreen(): ToolOutcome {
        pipeline.submit(AssistCommand.ReadScreen)
        return ToolOutcome.Reading
    }

    private fun elapsedNow() = android.os.SystemClock.elapsedRealtime()

    /** True while the app is actively guiding a finger, which is when it owns the voice. */
    private fun isGuiding(): Boolean = _state.value.session.phase.let {
        it == AssistancePhase.GuidingFinger || it == AssistancePhase.WaitingForScreenChange
    }

    // --- Panel actions ----------------------------------------------------------------------

    /**
     * Says the last message again, at the user's request.
     *
     * Routed through [say] rather than straight to the engine. Calling the engine directly
     * bypassed both the audio coordinator and the rule about which voice owns the channel, so
     * pressing Repeat during an assistant reply produced two voices at once — one of the two
     * ways that could happen.
     */
    fun repeatLast() {
        _state.value.lastMessage?.let { say(it, userRequested = true) }
    }

    /**
     * The on-screen stop button. Distinct from [stopGuidance], which is the assistant asking:
     * a button press may also have to abandon a push-to-talk capture that is still running,
     * and it is dispatched on the main thread rather than the SDK's.
     */
    fun onStopPressed() {
        cancelListening()
        pipeline.submit(AssistCommand.Stop)
    }

    /** Explicit user request to read the kiosk screen again from scratch. */
    fun rescan() {
        cancelListening()
        pipeline.rescan()
    }

    fun setDebugOverlay(enabled: Boolean) {
        pipeline.debugEnabled = enabled
        if (!enabled) _debugFrame.value = null
        _state.update { it.copy(debugOverlay = enabled) }
    }

    // --- Pipeline callbacks (pipeline thread) -------------------------------------------------

    override fun onSpeak(message: SpokenMessage) {
        viewModelScope.launch(Dispatchers.Main.immediate) { say(message) }
    }

    override fun onSession(snapshot: SessionSnapshot) {
        // A new target, or a new screen, makes the previous sentence stale rather than
        // repetitive: it must not be suppressed as a duplicate.
        if (snapshot.targetLabel != _state.value.session.targetLabel) audioOutput.onContextChanged()
        _state.update { it.copy(session = snapshot) }
    }

    override fun onDebugFrame(frame: DebugFrame) {
        _debugFrame.value = frame
    }

    override fun onFocusRequested() {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            focusController.focusOnScreen(android.os.SystemClock.elapsedRealtime())
        }
    }

    /**
     * Speaks a guidance message, unless the assistant is already talking and the message can
     * wait. See [AudioOutputCoordinator] for the ranking.
     */
    private fun say(message: SpokenMessage, userRequested: Boolean = false) {
        if (message is SpokenMessage.Direction) lastDirectionSpokenMs = elapsedNow()
        _state.update { it.copy(lastMessage = message) }
        // No `listening` guard: with continuous listening it is always true, and it silenced
        // every direction the guidance engine produced.
        // An explicit Repeat is honoured whatever else is happening; the user asked.
        if (userRequested) {
            speech.speak(message)
            audioOutput.decide(message, elapsedNow(), userRequested = true)
            return
        }

        when (audioOutput.decide(message, elapsedNow())) {
            SpeechDecision.SpeakNow, SpeechDecision.PreemptModel -> speakGuidance(message)
            SpeechDecision.Drop -> Unit
        }
    }

    override fun onCleared() {
        motion.stop()
        assistantJob?.cancel()
        assistant.reset()
        teardownScope.launch { runCatching { streaming.stop() } }
        // viewModelScope is already cancelled by the time this runs, so closing the socket and
        // releasing the microphone happens on a scope that outlives it. One short job, on a
        // scope this ViewModel owns, rather than GlobalScope: the work must finish, but it
        // must not outlive the process arbitrarily either.
        focusController.detach()
        recognizer.destroy()
        speech.shutdown()
        pipeline.release()
    }

    /** The outcome of one recognition turn, classified by what the loop should do next. */
    private sealed interface Recognition {
        data class Heard(val hypotheses: List<String>) : Recognition
        data object Silent : Recognition
        data object Retryable : Recognition
        data class Fatal(val reason: RecognitionFailure) : Recognition
    }

    private companion object {
        const val TAG = "LiveAssistViewModel"

        /** How often the loop checks whether the app has stopped speaking. */
        const val SPEECH_POLL_MS = 120L

        /** Breath before retrying a recogniser that was busy. */
        const val RETRY_DELAY_MS = 400L

        /** How long to wait for the engine to start before assuming it will not. */
        const val SPEECH_START_TIMEOUT_MS = 1_500L

        /** Tail of silence after speaking, so the room's echo is not captured either. */
        const val MIC_RESUME_DELAY_MS = 250L

        /**
         * How long a spoken direction keeps the channel. Long enough that conversation does
         * not interleave with a run of cues, short enough that a question asked mid-reach is
         * still answered.
         */
        const val DIRECTION_HOLDS_VOICE_MS = 3_000L
    }
}
