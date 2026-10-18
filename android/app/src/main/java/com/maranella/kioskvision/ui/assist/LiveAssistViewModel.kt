package com.maranella.kioskvision.ui.assist

import android.app.Application
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maranella.kioskvision.assist.AssistCommand
import com.maranella.kioskvision.assist.CommandParser
import com.maranella.kioskvision.assist.ConfirmDecision
import com.maranella.kioskvision.assist.PriceAnswer
import com.maranella.kioskvision.assist.PriceQuestion
import com.maranella.kioskvision.assist.ConfirmationGate
import com.maranella.kioskvision.assist.RecognitionFailure
import com.maranella.kioskvision.assist.ResolutionFailure
import com.maranella.kioskvision.assist.ResolvedTarget
import com.maranella.kioskvision.assist.TaskGoal
import com.maranella.kioskvision.assist.TargetResolution
import com.maranella.kioskvision.assist.TargetResolver
import com.maranella.kioskvision.assist.UserIntent
import com.maranella.kioskvision.assist.SpokenMessage
import com.maranella.kioskvision.camera.FocusState
import com.maranella.kioskvision.camera.ScreenFocusController
import com.maranella.kioskvision.diag.LatencyTracker
import com.maranella.kioskvision.hand.HandLandmarkTracker
import com.maranella.kioskvision.haptics.HapticCue
import com.maranella.kioskvision.haptics.HapticPlayer
import com.maranella.kioskvision.ocr.KioskOcrEngine
import com.maranella.kioskvision.pipeline.AssistancePhase
import com.maranella.kioskvision.pipeline.DebugFrame
import com.maranella.kioskvision.pipeline.LiveAssistPipeline
import com.maranella.kioskvision.pipeline.SessionSnapshot
import com.maranella.kioskvision.sensors.DeviceMotionMonitor
import com.maranella.kioskvision.settings.VoicePreferences
import com.maranella.kioskvision.speech.AudioOutputCoordinator
import com.maranella.kioskvision.speech.ReplyDecision
import com.maranella.kioskvision.speech.GuidanceSpeechOutput
import com.maranella.kioskvision.speech.LocalReply
import com.maranella.kioskvision.speech.SpeechDecision
import com.maranella.kioskvision.speech.SpeechCommandRecognizer
import com.maranella.kioskvision.speech.SpeechOutputStatus
import com.maranella.kioskvision.speech.VoiceAction
import com.maranella.kioskvision.speech.VoiceMode
import com.maranella.kioskvision.speech.VoiceRouting
import com.maranella.kioskvision.ui.main.PermissionStatus
import com.maranella.kioskvision.voice.AssistantActions
import com.maranella.kioskvision.voice.AssistantReply
import com.maranella.kioskvision.voice.StreamingVoiceProvider
import com.maranella.kioskvision.voice.TextAssistant
import com.maranella.kioskvision.voice.ToolOutcome
import com.maranella.kioskvision.voice.asOutcome
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
    /** False when the device cannot vibrate, so the UI does not offer what it cannot do. */
    val hapticsEnabled: Boolean = true,
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
    /**
     * On-device Georgian command recognition: the path for a user who declined the streaming
     * assistant, and the fallback when it has given up.
     *
     * One turn per press, not a loop. R-25 removed the *looped* push-to-talk because Android's
     * `SpeechRecognizer` ends after about 800 ms of silence and plays the system tone on every
     * restart, so hands-free use produced a beep a second and `error 7 -> NoMatch` on repeat.
     * A single turn opened by a button press has neither problem.
     *
     * Microphone ownership is what kept this dormant, and [VoiceRouting] settles it: this and
     * [StreamingVoiceProvider] are alternatives, never concurrent, because Android grants
     * capture to one consumer.
     */
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

    /**
     * The approach rhythm. Speech says which way; this says how close, continuously, which is
     * the part speech is worst at.
     */
    private val haptics = HapticPlayer(application)
    private val audioOutput = AudioOutputCoordinator()

    /** One assistant request at a time; a newer question cancels an unanswered one. */
    private var assistantJob: Job? = null

    /** A reply waiting for the voice channel. A newer reply replaces an older one. */
    private var replyJob: Job? = null

    /** The continuous recognition loop. */
    private var listenJob: Job? = null

    /** Bounds one on-device recognition turn, so a silent recogniser cannot mute guidance. */
    private var localListenJob: Job? = null

    /** Holds the microphone closed while the app is speaking. */
    private var speakJob: Job? = null
    private var guidanceSpeakJob: Job? = null

    /** Identifies one reply across its stages in the log. */
    private var speechRequestId = 0L

    /** Direction handed to the engine -> first audible word. Diagnostics builds only. */
    private val speechStartLatency = LatencyTracker()
    private var lastSpeechLatencyLogMs = Long.MIN_VALUE / 2

    /** When a directional cue was last spoken; conversation yields for a moment after one. */
    private var lastDirectionSpokenMs = Long.MIN_VALUE / 2

    /** Until when the assistant stays quiet because the app is speaking the substance. */
    private var suppressAssistantUntilMs = Long.MIN_VALUE / 2

    /** A target that has been offered and is waiting for the user to agree (M6). */
    private var pendingTarget: ResolvedTarget? = null
    private var pendingQuery: String? = null

    /** What the user is trying to do, carried across screens (M5). */
    private var goal: TaskGoal? = null

    /**
     * The user has stopped, and nothing may start guiding again until they ask.
     *
     * Needed because [AssistantActions] methods are called synchronously on the voice SDK's
     * thread. A `select_target` already in flight when Stop is pressed would otherwise land
     * afterwards, set a pending target and offer it aloud - the app resuming a task the user
     * had just withdrawn from. Volatile: written on the main thread, read on the SDK's.
     */
    @Volatile
    private var userStopped = false

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
                hapticsEnabled = haptics.isAvailable,
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
        val action = VoiceRouting.onVoicePressed(
            listening = state.listening,
            micGranted = state.micPermission == PermissionStatus.Granted,
            hasChosen = voicePreferences.hasChosen,
            assistantEnabled = state.assistantEnabled,
            liveAvailable = liveAvailable,
        )
        when (action) {
            VoiceAction.CancelListening -> cancelListening()
            VoiceAction.AskMicPermission -> _state.update { it.copy(showMicExplanation = true) }
            VoiceAction.AskConsent -> _state.update { it.copy(showAssistantConsent = true) }
            VoiceAction.StartStreaming -> startListening()
            // The user said no, or the streaming session has given up. Either way nothing
            // leaves the device: one short command, recognised here.
            VoiceAction.StartLocalCommands -> startLocalCommand()
        }
    }

    /**
     * One push-to-talk turn, recognised on the device.
     *
     * This is what "ყველაფერი ამ ტელეფონში დარჩეს" buys, and until now it bought nothing:
     * declining consent started the streaming session anyway. [SpeechCommandRecognizer] and
     * [CommandParser] were written and tested for exactly this path and were never called
     * from production.
     *
     * Microphone ownership — the reason they stayed dormant — is not in question here. This
     * branch is only ever taken when the streaming provider is not running, so the two never
     * compete for a microphone Android grants to one consumer.
     *
     * The app's own directions are muted for the duration, because the recogniser would
     * otherwise transcribe them as the user. That mute is bounded by the recognition turn: it
     * is lifted in every terminal callback, and the recogniser ends itself after about 800 ms
     * of silence.
     */
    private fun startLocalCommand() {
        if (!recognizer.isAvailable) {
            say(SpokenMessage.RecognitionFailed(RecognitionFailure.ServiceUnavailable))
            return
        }
        userStopped = false
        speech.stop()
        pipeline.speechMuted = true
        _state.update {
            it.copy(listening = true, heardText = null, voiceMode = VoiceMode.LocalCommands)
        }
        // The mute above is lifted by whichever callback arrives — and by this if none does.
        // A recogniser that never reports back would otherwise leave the app guiding a finger
        // in silence, which is a worse failure than the one the mute prevents: a misheard
        // direction costs the user a "that is not on this screen", a stuck mute costs them
        // every direction for the rest of the session.
        localListenJob?.cancel()
        localListenJob = viewModelScope.launch {
            delay(LOCAL_LISTEN_CEILING_MS)
            Log.w(TAG, "local recognition did not report back within ${LOCAL_LISTEN_CEILING_MS}ms")
            recognizer.cancel()
            endListening()
        }

        recognizer.start(object : SpeechCommandRecognizer.Listener {
            override fun onListening() = Unit

            override fun onResults(hypotheses: List<String>) {
                endLocalListen()
                handleHypotheses(hypotheses)
            }

            override fun onFailure(reason: RecognitionFailure) {
                endLocalListen()
                // Never silent: a blind user cannot see that recognition failed, and
                // "heard nothing" and "this phone has no Georgian" need different answers.
                say(SpokenMessage.RecognitionFailed(reason))
            }
        })
    }

    private fun endLocalListen() {
        localListenJob?.cancel()
        localListenJob = null
        endListening()
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
        // Defence in depth, at the one place a socket and a microphone are opened together.
        //
        // [VoiceRouting] already decides this, and it is tested. This refuses anyway, because
        // the defect it guards against (R-31) was not a wrong decision — it was a caller that
        // never asked. A future caller that forgets gets the local path, not the user's voice
        // on the network. The saved answer is read here rather than from UI state, which is a
        // copy of it.
        if (!voicePreferences.assistantEnabled) {
            Log.w(TAG, "streaming refused: the user has not agreed to it")
            startLocalCommand()
            return
        }
        if (listenJob?.isActive == true) return
        // The user is asking for something again, which is the only thing that lifts a stop.
        userStopped = false
        speech.stop()
        // Deliberately NOT muting pipeline speech. That flag exists so the local recogniser
        // does not hear the app's own directions and treat them as commands, and it was set
        // for as long as listening lasted. With listening now continuous it stayed on for
        // ever, and `if (toSpeak != null && !speechMuted)` silenced every direction — the
        // app guided correctly and said nothing. The microphone is paused around each
        // utterance instead, which solves the same problem without muting the user.
        pipeline.speechMuted = false
        _state.update {
            it.copy(listening = true, heardText = null, voiceMode = VoiceMode.LiveAssistant)
        }

        listenJob = viewModelScope.launch {
            launch {
                streaming.report.collect { report ->
                    _state.update {
                        it.copy(
                            voice = report,
                            // Only honestly "listening" while a connection exists. Leaving this
                            // true after the session died is what made a dead assistant look
                            // like an attentive one: the label said "listening", the microphone
                            // was closed, and nothing told the user either way.
                            listening = report.state != VoiceSessionState.Error,
                            heardText = report.lastUserText ?: it.heardText,
                        )
                    }
                    if (report.state == VoiceSessionState.Error) onVoiceSessionLost()
                }
            }
            streaming.start()
        }
    }

    /**
     * The streaming assistant gave up reconnecting.
     *
     * Said out loud, because this is otherwise a silent failure: the provider stops streaming
     * the microphone and the app simply goes quiet, which for a blind user is indistinguishable
     * from an assistant with nothing to say. Everything except the conversation is local and
     * keeps working, and the message says so.
     *
     * Idempotent: the report is a StateFlow and a late collector would otherwise re-announce a
     * failure the user has already been told about.
     */
    private fun onVoiceSessionLost() {
        val job = listenJob ?: return
        listenJob = null
        // The socket is not coming back on its own, so the button must stop reopening it.
        // On-device recognition of the short command vocabulary needs no network, which is
        // why the user is left with a voice path rather than a control that does nothing.
        liveAvailable = false
        _state.update { it.copy(listening = false, voiceMode = VoiceMode.LocalCommands) }
        pipeline.speechMuted = false
        say(SpokenMessage.VoiceAssistantUnavailable)
        // A sibling of the collector, so cancelling the collector below cannot abandon the
        // socket and the microphone half-released.
        viewModelScope.launch { runCatching { streaming.stop() } }
        job.cancel()
    }

    private fun cancelListening() {
        listenJob?.cancel()
        listenJob = null
        localListenJob?.cancel()
        localListenJob = null
        recognizer.cancel()
        viewModelScope.launch { streaming.stop() }
        endListening()
    }

    private fun endListening() {
        pipeline.speechMuted = false
        _state.update { it.copy(listening = false) }
    }

    /**
     * Whether the streaming assistant is still worth opening.
     *
     * Set false when it has given up reconnecting; the user's saved consent is untouched, so
     * the next session starts streaming again if they still want it.
     */
    @Volatile
    private var liveAvailable = true

    /**
     * Turns recognised Georgian into an app command, with no network involved.
     *
     * Reached from [startLocalCommand]. Every decision below is made from the recognised text
     * alone: stop, repeat, read the screen, or name a control to look for. The assistant is
     * only consulted for an utterance none of those match, and only if the user enabled it —
     * which in this path they have not, so an unmatched utterance is answered locally.
     */
    private fun handleHypotheses(hypotheses: List<String>) {
        // Developer builds only: shows whether a failed request was misheard rather than
        // unmatched, which the pipeline alone cannot distinguish.
        if (pipeline.diagnosticsEnabled) Log.i(TAG, "HEARD ${hypotheses.joinToString(" | ")}")
        val parsed = hypotheses.map { it to CommandParser.parse(it) }
        val (heard, command) = parsed.firstOrNull { it.second != AssistCommand.Unknown } ?: parsed.first()
        _state.update { it.copy(heardText = heard) }
        when (command) {
            // Through the same gate the assistant uses, not straight to the pipeline. The
            // pipeline's own `selectTarget` begins guidance immediately; M6 says a control is
            // offered and nothing moves until the user agrees, and a user who declined the
            // streaming assistant is owed that gate too — most of all on a payment terminal,
            // where the difference between the button they meant and one that reads like it
            // is money.
            is AssistCommand.SelectTarget -> {
                val outcome = selectTarget(command.query)
                LocalReply.forOutcome(outcome, command.query)?.let(::say)
            }

            AssistCommand.Confirm -> LocalReply.forOutcome(confirmTarget(), heard)?.let(::say)
            AssistCommand.Cancel -> cancelTarget()
            AssistCommand.Repeat -> repeatLast()
            // Everything the app stops when the user withdraws, not only guidance: queued
            // speech, the microphone gates and the goal. The same thing the Stop button does,
            // except that it leaves listening open — they asked the app to stop guiding.
            AssistCommand.Stop -> stopGuidance()
            AssistCommand.ReadScreen -> readScreen()
            // Not one of the app's own commands, so it is a question for the assistant
            // rather than a misheard instruction — unless the user declined, in which case
            // nothing of theirs is sent anywhere.
            AssistCommand.Unknown ->
                if (_state.value.assistantEnabled) askAssistant(heard) else say(SpokenMessage.NotUnderstood)
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
        // The other place the user's words would leave the device, and guarded the same way
        // and for the same reason as `startListening`. The caller checks; so does this.
        if (!voicePreferences.assistantEnabled) {
            Log.w(TAG, "assistant request refused: the user has not agreed to it")
            say(SpokenMessage.NotUnderstood)
            return
        }
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
     * Waits for the current speech turn to finish, with a ceiling.
     *
     * [GuidanceSpeechOutput.isSpeaking] goes true the moment an utterance is handed to the
     * engine and stays true across every utterance queued behind it, which is what makes this
     * gate cover a whole turn rather than reopening between chunks. The ceiling is the other
     * half of that: an engine that never delivers a terminal callback would otherwise hold the
     * microphone closed for the rest of the session, and a user who cannot be heard at all is
     * worse off than one whose last sentence overlapped.
     */
    private suspend fun awaitSpeechFinished() {
        withTimeoutOrNull(SPEECH_TURN_CEILING_MS) {
            while (speech.isSpeaking) delay(SPEECH_POLL_MS)
        } ?: Log.w(TAG, "speech did not report finishing within ${SPEECH_TURN_CEILING_MS}ms")
    }

    /**
     * Speaks one guidance message with the microphone closed for its duration.
     *
     * The same gate the assistant's replies use. Without it the model hears "move a little
     * left" through the open microphone, transcribes it as the user and answers it.
     */
    private fun speakGuidance(message: SpokenMessage) {
        val handedToEngineAt = elapsedNow()
        speech.speak(message)
        guidanceSpeakJob?.cancel()
        guidanceSpeakJob = viewModelScope.launch {
            streaming.microphonePaused = true
            try {
                measureTimeToAudible(handedToEngineAt)
                awaitSpeechFinished()
                delay(MIC_RESUME_DELAY_MS)
            } finally {
                streaming.microphonePaused = false
            }
        }
    }

    /**
     * The last unmeasured leg of the guidance path: a direction handed to the engine, and the
     * first sound of it.
     *
     * Every other stage is already tracked inside the pipeline — frame, hand, decide,
     * announce-wait — and this one sits outside it, in the ViewModel, because that is where
     * the engine lives. Without it the chain from camera to spoken cue has a hole at the end,
     * which is the part the user actually experiences as the app being slow.
     *
     * Reported through [LatencyTracker] in the same `LATENCY <stage> n=.. p50=..` shape the
     * pipeline uses, so one capture summarises them together. Measurement only: nothing here
     * changes what is spoken or when.
     */
    private suspend fun measureTimeToAudible(handedToEngineAt: Long) {
        if (!pipeline.diagnosticsEnabled) return
        var waited = 0L
        while (waited < AUDIBLE_WAIT_CEILING_MS) {
            val audible = speech.lastAudibleAtMs
            if (audible >= handedToEngineAt) {
                speechStartLatency.record(audible - handedToEngineAt)
                val now = elapsedNow()
                if (now - lastSpeechLatencyLogMs >= SPEECH_LATENCY_LOG_INTERVAL_MS) {
                    lastSpeechLatencyLogMs = now
                    Log.i(TAG, "LATENCY speak-start ${speechStartLatency.summary()}")
                }
                return
            }
            delay(SPEECH_POLL_MS)
            waited += SPEECH_POLL_MS
        }
        // Not recorded as a sample: a cue that never became audible is a different failure
        // from a slow one, and averaging the two would hide both.
        Log.w(TAG, "a direction never became audible within ${AUDIBLE_WAIT_CEILING_MS}ms")
    }

    /**
     * Speaks an assistant reply once the channel is free, or gives up out loud.
     *
     * Replies used to be dropped on the spot whenever a direction had been spoken in the last
     * 3 s. `GuidanceAnnouncer` repeats the same direction every 2.5 s, so while the app was
     * guiding that window never closed and **every** reply was discarded — the user asked a
     * question mid-reach and the app simply never answered. The policy now waits for a real
     * gap, bounded by a deadline, and says when it has given up instead of going quiet. The
     * rules themselves live in [AudioOutputCoordinator.decideAssistantReply] so they can be
     * tested without a device.
     */
    private fun speakAssistant(text: String) {
        val offeredAt = elapsedNow()
        replyJob?.cancel()
        replyJob = viewModelScope.launch {
            while (true) {
                val decision = audioOutput.decideAssistantReply(
                    offeredAtMs = offeredAt,
                    nowMs = elapsedNow(),
                    appSpeaking = speech.isSpeaking,
                    lastGuidanceSpokenAtMs = lastDirectionSpokenMs,
                    appOwnsVoiceUntilMs = suppressAssistantUntilMs,
                )
                when (decision) {
                    ReplyDecision.SpeakNow -> {
                        speakAssistantNow(text, offeredAt)
                        return@launch
                    }

                    ReplyDecision.Wait -> delay(SPEECH_POLL_MS)

                    ReplyDecision.TooLate -> {
                        // Logged rather than silent: "the assistant said nothing" and "the
                        // assistant was never given the channel" look identical from outside,
                        // and they need completely different fixes.
                        Log.i(TAG, "reply abandoned after ${elapsedNow() - offeredAt}ms of waiting")
                        return@launch
                    }
                }
            }
        }
    }

    /** The assistant's own words, through the single speech engine. */
    private fun speakAssistantNow(text: String, offeredAt: Long = elapsedNow()) {
        // Deliberately **not** gated on `listening`. It was, and with continuous listening
        // that flag is always true, so the assistant never said a word: the reply came back
        // and went nowhere. The guard made sense only while listening meant holding a button.
        //
        // Directions win over conversation, but only once directions are actually being
        // given. Suppressing on the phase alone silenced the one sentence that tells the user
        // guidance has begun — "move your finger and follow the directions" — so the assistant
        // went quiet at the exact moment it had succeeded.
        _state.update { it.copy(assistantText = text) }

        val requestId = ++speechRequestId
        if (pipeline.diagnosticsEnabled) {
            Log.i(TAG, "speak#$requestId: ${text.length} chars, tts=${_state.value.speechOutput}")
        }
        speakJob?.cancel()
        speakJob = viewModelScope.launch {
            // The microphone is live while we talk, so it is held closed for the duration —
            // otherwise the assistant hears itself and answers its own reply.
            streaming.microphonePaused = true
            try {
                val handedToEngineAt = elapsedNow()
                speech.speakText(text)
                awaitSpeechFinished()
                delay(MIC_RESUME_DELAY_MS)
                if (pipeline.diagnosticsEnabled) {
                    // The last unmeasured leg: text handed to the engine, to the first sound.
                    // Reported as unknown rather than zero when the engine never started, so a
                    // silent reply is not recorded as an instantaneous one.
                    val audible = speech.lastAudibleAtMs
                        .takeIf { it >= handedToEngineAt }
                        ?.let { "${it - handedToEngineAt}ms" }
                        ?: "never"
                    Log.i(TAG, "speak#$requestId: done, waited=${handedToEngineAt - offeredAt}ms audible=$audible")
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
        // The user stopped between this call leaving the model and arriving here.
        if (userStopped) return ToolOutcome.Stopped
        val scene = pipeline.latestScene
        val outcome = when (val resolution = targetResolver.resolve(UserIntent(query), scene, elapsedNow())) {
            is TargetResolution.Resolved -> offerTarget(query, resolution.target)
            is TargetResolution.Ambiguous -> ToolOutcome.Ambiguous
            is TargetResolution.Unresolved -> resolution.reason.asOutcome()
        }

        // The resolver's verdict is what the assistant is told. The actual selection still
        // goes through the pipeline, which stays the single authority on the live target and
        // owns anchoring, confirmation and screen transitions. Both reach their answer through
        // TargetMatcher, so they agree about what exists; the pipeline simply knows more.
        // Unconfirmed is included deliberately: the pipeline holds such a request open for
        // several seconds while it looks for a better frame (R-12), which is a far more
        // useful answer than dropping it. Only a label that is genuinely absent is refused
        // outright. A confirmed target starts guidance in confirmTarget, not here.
        if (outcome == ToolOutcome.Ambiguous || outcome == ToolOutcome.Unconfirmed) {
            pipeline.selectTarget(listOf(query))
        }
        return outcome
    }

    /**
     * Offers a resolved target and waits to be told to go ahead.
     *
     * Nothing is guided to until the user agrees. They cannot see what they are about to be
     * sent towards, and on a payment terminal the difference between the button they meant
     * and one that reads like it is money — so the app says what it found and stops.
     */
    private fun offerTarget(query: String, target: ResolvedTarget): ToolOutcome {
        pendingTarget = target
        pendingQuery = query
        goal = (goal?.takeUnless { it.isExpired(elapsedNow()) } ?: TaskGoal(query, target.role, elapsedNow()))
            .copy(query = query, role = target.role)
        say(SpokenMessage.TargetOffered(target.label, target.isCritical))
        return ToolOutcome.NeedsConfirmation
    }

    /**
     * The user agreed. Checked against the screen as it is **now**, not as it was when offered.
     *
     * Saying „დიახ" takes a second or two, and a kiosk can advance in that time — on a timeout,
     * on a card being inserted, or because the previous press has gone through. Guiding a
     * finger to where a button *was* is the failure the whole screen-tracking apparatus exists
     * to prevent, and the check that prevents it, [TargetResolver.revalidate], was written and
     * unit-tested and reached by no production code at all.
     */
    override fun confirmTarget(): ToolOutcome {
        val decision = ConfirmationGate.decide(
            pending = pendingTarget,
            query = pendingQuery,
            scene = pipeline.latestScene,
            nowMs = elapsedNow(),
            userStopped = userStopped,
            resolver = targetResolver,
        )
        // Every branch clears the offer: it has been answered, whatever the answer led to.
        if (decision !is ConfirmDecision.UserStopped && decision !is ConfirmDecision.NothingPending) {
            clearPendingOffer()
        }
        return when (decision) {
            ConfirmDecision.UserStopped -> ToolOutcome.Stopped
            ConfirmDecision.NothingPending -> ToolOutcome.NothingPending

            is ConfirmDecision.Guide -> {
                goal = goal?.withVisited(decision.visited)
                pipeline.selectTarget(decision.queries)
                ToolOutcome.Confirmed
            }

            is ConfirmDecision.AskWhich -> {
                pipeline.selectTarget(decision.queries)
                ToolOutcome.Ambiguous
            }

            is ConfirmDecision.HoldOpen -> {
                pipeline.selectTarget(decision.queries)
                ToolOutcome.Unconfirmed
            }

            // Said in the app's own voice, because the model is told an outcome word and the
            // user is the one who needs to know the button is no longer there.
            is ConfirmDecision.Refuse -> {
                say(decision.message)
                decision.reason.asOutcome()
            }
        }
    }

    private fun clearPendingOffer() {
        pendingTarget = null
        pendingQuery = null
    }

    override fun cancelTarget(): ToolOutcome {
        if (pendingTarget == null) return ToolOutcome.NothingPending
        pendingTarget = null
        pendingQuery = null
        // The offer was wrong, so the goal that produced it is wrong too. Keeping it would
        // make the app volunteer the same suggestion on the next screen.
        goal = null
        return ToolOutcome.Stopped
    }

    /**
     * Answers a question about amounts, out loud, from what the camera read.
     *
     * The whole comparison is local. [OptionPricing] has already decided which amounts may be
     * attached to which options and refused the rest, the matching from the user's words to an
     * option is the same [TargetMatcher] that resolves targets, and the sentence is a
     * [SpokenMessage] rendered by the device. The model receives one outcome word and learns
     * no amount, no label and no position — ADR-009 holds exactly as it does for guidance.
     */
    override fun comparePrices(first: String?, second: String?): ToolOutcome {
        if (userStopped) return ToolOutcome.Stopped
        val now = elapsedNow()
        val answer = PriceQuestion.answer(pipeline.latestScene, first, second, now)
        // The app is about to speak the substance; the assistant's acknowledgement would
        // otherwise cut it off with QUEUE_FLUSH, as it did for screen reading.
        if (answer is PriceAnswer.Cheaper || answer is PriceAnswer.Same || answer is PriceAnswer.Single) {
            suppressAssistantUntilMs = now + APP_SPEAKS_WINDOW_MS
        }
        return when (answer) {
            PriceAnswer.NoScreen -> ToolOutcome.NoScreen

            // The kiosk has moved on, or the scene is too old to trust. Quoting an amount now
            // would state the previous screen's price as a fact about the one in front of the
            // user, and they cannot check it. The model is told the screen cannot be read,
            // which is what is true until the next scan lands.
            PriceAnswer.NotCurrent -> ToolOutcome.ScreenNotReadable

            PriceAnswer.NoPrices -> {
                // Said aloud as well as reported: the model is told "priceunknown" but the
                // user is the one who needs to know that holding the phone steadier may fix it.
                say(SpokenMessage.PriceNotReadable)
                ToolOutcome.PriceUnknown
            }

            is PriceAnswer.NotOnScreen -> ToolOutcome.NotOnScreen

            is PriceAnswer.Cheaper -> {
                say(
                    SpokenMessage.CheaperOption(
                        cheaper = answer.cheaper.label,
                        cheaperPrice = answer.cheaper.price,
                        other = answer.dearer.label,
                        otherPrice = answer.dearer.price,
                    ),
                )
                ToolOutcome.Comparing
            }

            is PriceAnswer.Same -> {
                say(SpokenMessage.OptionPrice(answer.first.label, answer.first.price))
                say(SpokenMessage.OptionPrice(answer.second.label, answer.second.price))
                ToolOutcome.Comparing
            }

            is PriceAnswer.Single -> {
                say(SpokenMessage.OptionPrice(answer.option.label, answer.option.price))
                ToolOutcome.Comparing
            }
        }
    }

    override fun stopGuidance(): ToolOutcome {
        // Stopping is the user withdrawing from the task, not pausing it. A goal that
        // survived would start making suggestions again on the next screen. The conversation
        // stays open: they asked the app to stop guiding, not to stop listening.
        viewModelScope.launch(Dispatchers.Main.immediate) { haltSpeechAndGuidance(endListening = false) }
        return ToolOutcome.Stopped
    }

    override fun readScreen(): ToolOutcome {
        // The app itself is about to read the screen aloud, which takes seconds. The
        // assistant's acknowledgement arrives in the middle of it and speaks with
        // QUEUE_FLUSH, cutting off the very reading it is announcing — the user heard
        // "the app will read the screen" and then nothing.
        suppressAssistantUntilMs = elapsedNow() + APP_SPEAKS_WINDOW_MS
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
     * Everything that has to stop when the user withdraws from the task.
     *
     * Cancelling guidance is not enough on its own. Speech already handed to the engine keeps
     * playing - a screen reading runs for seconds - and the jobs that hold the microphone shut
     * while the app talks keep running after it. Pressing Stop and then listening to the app
     * finish its sentence is not stopping.
     *
     * @param endListening true for a user-initiated stop, which also closes the voice session.
     *   The assistant's own `stop_guidance` leaves the conversation open: the user asked the
     *   app to stop guiding, not to stop listening to them.
     */
    private fun haltSpeechAndGuidance(endListening: Boolean) {
        userStopped = true
        goal = null
        pendingTarget = null
        pendingQuery = null

        // Queued and in-flight speech, and the two jobs that gate the microphone around it.
        assistantJob?.cancel()
        assistantJob = null
        replyJob?.cancel()
        replyJob = null
        speakJob?.cancel()
        speakJob = null
        guidanceSpeakJob?.cancel()
        guidanceSpeakJob = null
        speech.stop()
        streaming.microphonePaused = false

        haptics.stop()
        suppressAssistantUntilMs = Long.MIN_VALUE / 2
        lastDirectionSpokenMs = Long.MIN_VALUE / 2
        // The stop confirmation must not be swallowed as a repeat of an earlier one.
        audioOutput.onContextChanged()

        if (endListening) cancelListening()
        // Last, so the pipeline's spoken confirmation is not cancelled by the speech.stop above.
        pipeline.submit(AssistCommand.Stop)
    }

    /**
     * The on-screen stop button.
     *
     * **This is the only stop that does not depend on the network.** Saying "stop" travels to
     * Gemini Live and back, and while the app is speaking a direction the microphone is paused
     * and the word is discarded unheard - so a spoken stop is not reliable and must not be
     * presented as if it were. The button is deliberately large and always present.
     */
    fun onStopPressed() = haltSpeechAndGuidance(endListening = true)

    /** Explicit user request to read the kiosk screen again from scratch. */
    fun rescan() {
        userStopped = false
        cancelListening()
        pipeline.rescan()
    }

    fun setDebugOverlay(enabled: Boolean) {
        // Turning the overlay on is also the moment a developer wants the image OCR is reading.
        // One shot, app-private, debuggable builds only — see KioskOcrEngine.dumpNextInput.
        if (enabled && pipeline.diagnosticsEnabled) pipeline.dumpNextOcrInput()
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
        val wasPhase = _state.value.session.phase
        _state.update { it.copy(session = snapshot) }

        // A screen the kiosk has just advanced to. This is the moment the goal earns its
        // keep: without it the user would have to state the whole request again on every
        // screen of a payment.
        if (wasPhase != AssistancePhase.ScreenReady && snapshot.phase == AssistancePhase.ScreenReady) {
            offerGoalOnNewScreen()
        }
    }

    /**
     * Re-runs the active goal against the screen that has just appeared.
     *
     * It matches the user's own words again rather than following a script: the app does not
     * know this kiosk's flow and must not invent one. Finding nothing is said plainly instead
     * of guessing at a next step.
     */
    private fun offerGoalOnNewScreen() {
        if (userStopped) return
        val now = elapsedNow()
        val active = goal?.takeUnless { it.isExpired(now) } ?: run { goal = null; return }
        if (pendingTarget != null) return

        val scene = pipeline.latestScene
        when (val resolution = targetResolver.resolve(UserIntent(active.query, active.role), scene, now)) {
            is TargetResolution.Resolved -> {
                // The button just pressed is often still on screen as the kiosk redraws;
                // offering it again would send the user back where they came from.
                if (active.hasVisited(resolution.target.label)) return
                pendingTarget = resolution.target
                pendingQuery = active.query
                say(SpokenMessage.GoalSuggestion(resolution.target.label))
            }

            is TargetResolution.Ambiguous -> Unit
            is TargetResolution.Unresolved -> when (resolution.reason) {
                // Nothing here matches, and the user is mid-task: say so rather than leave
                // them waiting for an app that has quietly given up.
                ResolutionFailure.NoSimilarLabel -> say(SpokenMessage.GoalNoMatchHere)
                else -> Unit
            }
        }
    }

    override fun onDebugFrame(frame: DebugFrame) {
        _debugFrame.value = frame
    }

    /** Plays the approach rhythm. Called on the pipeline thread; the player is thread-safe. */
    override fun onHaptic(cue: HapticCue) {
        if (_state.value.hapticsEnabled) haptics.play(cue)
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
            SpeechDecision.SpeakNow -> speakGuidance(message)
            SpeechDecision.Drop -> Unit
        }
    }

    override fun onCleared() {
        haptics.stop()
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

        /**
         * Longest a single speech turn may hold the microphone closed.
         *
         * Generous on purpose - reading a whole kiosk screen aloud is the longest thing the
         * app says - but finite, so a missing utterance callback degrades into a slightly late
         * microphone rather than a permanently deaf app.
         */
        const val SPEECH_TURN_CEILING_MS = 30_000L

        /** Tail of silence after speaking, so the room's echo is not captured either. */
        const val MIC_RESUME_DELAY_MS = 250L

        /**
         * How long the app owns the voice after a command whose answer it speaks itself.
         * Long enough to cover reading a screen of labels aloud.
         */
        const val APP_SPEAKS_WINDOW_MS = 20_000L

        /**
         * Longest one on-device recognition turn may hold the app's directions muted.
         *
         * The system recogniser ends itself after about 800 ms of silence and is asked to
         * allow 700 ms of speech, so a turn that has not reported back in fifteen seconds is
         * not slow, it is gone.
         */
        const val LOCAL_LISTEN_CEILING_MS = 15_000L

        /** Longest a direction may take to become audible before it is counted as lost. */
        const val AUDIBLE_WAIT_CEILING_MS = 3_000L

        /** How often the speech-start percentiles are printed, matching the pipeline's cadence. */
        const val SPEECH_LATENCY_LOG_INTERVAL_MS = 5_000L
    }
}
