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
import com.maranella.kioskvision.voice.GeminiLiveVoiceProvider
import com.maranella.kioskvision.voice.ToolOutcome
import com.maranella.kioskvision.voice.ReconnectDecision
import com.maranella.kioskvision.voice.ReconnectPolicy
import com.maranella.kioskvision.voice.VoiceProvider
import com.maranella.kioskvision.voice.VoiceSessionReport
import com.maranella.kioskvision.voice.VoiceSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private val voiceProvider: VoiceProvider = GeminiLiveVoiceProvider(application, actions = this)
    private val targetResolver = TargetResolver()
    private val voicePreferences = VoicePreferences(application)
    private val reconnect = ReconnectPolicy()
    private val audioOutput = AudioOutputCoordinator()

    /** The single job that owns the Live session, so a second one cannot be started. */
    private var voiceJob: Job? = null

    /** Outlives [viewModelScope] only long enough to release the microphone and the socket. */
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val speech = GuidanceSpeechOutput(application) { status ->
        _state.update { it.copy(speechOutput = status) }
    }

    val analyzer get() = pipeline.analyzer
    val analysisExecutor get() = pipeline.executor

    init {
        // Diagnostic logging names recognised screen content, so it is for debug builds only.
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
            // Begins screen acquisition, which is also the only thing that asks for focus.
            pipeline.start()
        } else {
            motion.stop()
            cancelListening()
            // The microphone must not stay open behind a backgrounded screen, and the Live
            // session must not keep streaming audio to Google when nobody is using it.
            stopLiveAssistant()
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
            // Already talking, either way: this press means stop.
            state.voiceMode == VoiceMode.LiveAssistant -> stopLiveAssistant()
            state.listening -> cancelListening()

            state.micPermission != PermissionStatus.Granted ->
                _state.update { it.copy(showMicExplanation = true) }

            // Asked once, at the moment it first matters, rather than buried in a settings
            // screen the user has no reason to visit.
            !voicePreferences.hasChosen -> _state.update { it.copy(showAssistantConsent = true) }

            state.assistantEnabled -> startLiveAssistant()
            else -> startListening()
        }
    }

    private fun startListening() {
        // Push-to-talk and the Live session cannot both hold the microphone. The Live session
        // is stopped rather than refused, because the user pressing the button is a clear
        // statement about which one they want.
        if (_state.value.voiceMode == VoiceMode.LiveAssistant) stopLiveAssistant()
        // Silence our own speech first so the recognizer does not hear it.
        speech.stop()
        pipeline.speechMuted = true
        _state.update { it.copy(listening = true, heardText = null) }
        recognizer.start(
            object : SpeechCommandRecognizer.Listener {
                override fun onListening() = Unit

                override fun onResults(hypotheses: List<String>) {
                    endListening()
                    handleHypotheses(hypotheses)
                }

                override fun onFailure(reason: RecognitionFailure) {
                    endListening()
                    if (reason == RecognitionFailure.ServiceUnavailable) {
                        _state.update { it.copy(recognitionAvailable = false) }
                    }
                    if (reason == RecognitionFailure.LanguageUnsupported) {
                        _state.update { it.copy(georgianRecognition = false) }
                    }
                    say(SpokenMessage.RecognitionFailed(reason))
                }
            },
        )
    }

    private fun cancelListening() {
        if (_state.value.listening) recognizer.cancel()
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
            AssistCommand.Unknown -> say(SpokenMessage.NotUnderstood)
            AssistCommand.ReadScreen, AssistCommand.Stop -> pipeline.submit(command)
        }
    }

    // --- Live assistant ---------------------------------------------------------------------

    /**
     * Starts or stops the streaming Georgian assistant.
     *
     * Always user-initiated. Audio leaves the device only between this being switched on and
     * off, which is the boundary `SAFETY_AND_PRIVACY.md` draws.
     */
    /** The user answered the one-time question about the streaming assistant. */
    fun onAssistantConsent(enabled: Boolean) {
        voicePreferences.assistantEnabled = enabled
        _state.update { it.copy(assistantEnabled = enabled, showAssistantConsent = false) }
        // Honour the choice immediately: they asked to talk, and now we know how.
        onVoiceCommandPressed()
    }

    fun dismissAssistantConsent() = _state.update { it.copy(showAssistantConsent = false) }

    private fun startLiveAssistant() {
        // One session at a time. A second job would mean a second microphone consumer and a
        // second billable audio stream, both invisible from the UI.
        if (voiceJob?.isActive == true) return
        cancelListening()
        speech.stop()
        reconnect.reset()
        _state.update { it.copy(voiceMode = VoiceMode.LiveAssistant) }

        voiceJob = viewModelScope.launch {
            // Collecting inside the session job means the collector dies with the session;
            // a collector outliving it is how a leak starts.
            launch {
                voiceProvider.report.collect { report ->
                    audioOutput.onModelSpeaking(report.state == VoiceSessionState.Speaking)
                    if (report.state == VoiceSessionState.Listening) reconnect.onConnected()
                    _state.update { it.copy(voice = report) }
                    handleSessionEnd(report)
                }
            }
            voiceProvider.start()
        }
    }

    private fun stopLiveAssistant() {
        val job = voiceJob
        voiceJob = null
        reconnect.reset()
        audioOutput.onModelSpeaking(false)
        _state.update { it.copy(voiceMode = VoiceMode.LocalCommands) }
        // Cancelling the job stops the collector; the provider still has to be told, because
        // closing the socket and releasing the microphone is its own work.
        job?.cancel()
        viewModelScope.launch { voiceProvider.stop() }
    }

    /**
     * Reacts to a session that has ended, within the limits of [ReconnectPolicy].
     *
     * A planned rollover is reconnected promptly, because the user is mid-conversation. A
     * failure backs off and eventually stops: retrying a revoked key or a retired model in a
     * loop burns the request quota, holds the microphone, and never succeeds.
     */
    private suspend fun handleSessionEnd(report: VoiceSessionReport) {
        if (_state.value.voiceMode != VoiceMode.LiveAssistant) return
        val decision = when (report.state) {
            VoiceSessionState.Reconnecting -> reconnect.onPlannedRollover()
            VoiceSessionState.Error -> reconnect.onFailure()
            else -> return
        }
        when (decision) {
            is ReconnectDecision.RetryAfter -> {
                delay(decision.delayMs)
                if (_state.value.voiceMode == VoiceMode.LiveAssistant) voiceProvider.start()
            }

            is ReconnectDecision.GiveUp -> {
                Log.w(TAG, "live voice unavailable after ${decision.attempts} attempts")
                // Falls back to on-device commands rather than leaving the user with a dead
                // assistant: camera, OCR and guidance all still work without the network.
                stopLiveAssistant()
                say(SpokenMessage.RecognitionFailed(RecognitionFailure.NetworkRequired))
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
        if (outcome == ToolOutcome.Guiding || outcome == ToolOutcome.Ambiguous) {
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

    // --- Panel actions ----------------------------------------------------------------------

    fun repeatLast() {
        _state.value.lastMessage?.let { speech.speak(it) }
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

    override fun onSession(snapshot: SessionSnapshot) = _state.update { it.copy(session = snapshot) }

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
    private fun say(message: SpokenMessage) {
        _state.update { it.copy(lastMessage = message) }
        if (_state.value.listening) return
        when (audioOutput.decide(message)) {
            SpeechDecision.SpeakNow -> speech.speak(message)
            SpeechDecision.PreemptModel -> {
                // Nothing here can silence the model's own playback — the SDK owns it — so the
                // overlap is reduced rather than removed. Recorded as a known gap.
                speech.speak(message)
            }
            SpeechDecision.Drop -> Unit
        }
    }

    override fun onCleared() {
        motion.stop()
        voiceJob?.cancel()
        voiceJob = null
        // viewModelScope is already cancelled by the time this runs, so closing the socket and
        // releasing the microphone happens on a scope that outlives it. One short job, on a
        // scope this ViewModel owns, rather than GlobalScope: the work must finish, but it
        // must not outlive the process arbitrarily either.
        teardownScope.launch { runCatching { voiceProvider.stop() } }
        focusController.detach()
        recognizer.destroy()
        speech.shutdown()
        pipeline.release()
    }

    private companion object {
        const val TAG = "LiveAssistViewModel"
    }
}
