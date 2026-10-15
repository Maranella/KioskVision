package com.maranella.kioskvision.ui.assist

import android.app.Application
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maranella.kioskvision.assist.AssistCommand
import com.maranella.kioskvision.assist.CommandParser
import com.maranella.kioskvision.assist.RecognitionFailure
import com.maranella.kioskvision.assist.SpokenMessage
import com.maranella.kioskvision.camera.FocusState
import com.maranella.kioskvision.camera.ScreenFocusController
import com.maranella.kioskvision.hand.HandLandmarkTracker
import com.maranella.kioskvision.ocr.KioskOcrEngine
import com.maranella.kioskvision.pipeline.DebugFrame
import com.maranella.kioskvision.pipeline.LiveAssistPipeline
import com.maranella.kioskvision.pipeline.SessionSnapshot
import com.maranella.kioskvision.sensors.DeviceMotionMonitor
import com.maranella.kioskvision.speech.GuidanceSpeechOutput
import com.maranella.kioskvision.speech.SpeechCommandRecognizer
import com.maranella.kioskvision.speech.SpeechOutputStatus
import com.maranella.kioskvision.ui.main.PermissionStatus
import kotlinx.coroutines.Dispatchers
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
)

/**
 * Single source of truth for live assist. Owns the models, the speech components and the
 * pipeline, so they survive configuration changes and are released exactly once.
 */
class LiveAssistViewModel(application: Application) : AndroidViewModel(application), LiveAssistPipeline.Listener {

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
    private val speech = GuidanceSpeechOutput(application) { status ->
        _state.update { it.copy(speechOutput = status) }
    }

    val analyzer get() = pipeline.analyzer
    val analysisExecutor get() = pipeline.executor

    init {
        // Diagnostic logging names recognised screen content, so it is for debug builds only.
        pipeline.diagnosticsEnabled =
            application.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        _state.update { it.copy(recognitionAvailable = recognizer.isAvailable) }
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

    fun onVoiceCommandPressed() {
        val state = _state.value
        when {
            state.listening -> cancelListening()
            state.micPermission != PermissionStatus.Granted -> _state.update { it.copy(showMicExplanation = true) }
            else -> startListening()
        }
    }

    private fun startListening() {
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

    // --- Panel actions ----------------------------------------------------------------------

    fun repeatLast() {
        _state.value.lastMessage?.let { speech.speak(it) }
    }

    fun stopGuidance() {
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

    private fun say(message: SpokenMessage) {
        _state.update { it.copy(lastMessage = message) }
        if (!_state.value.listening) speech.speak(message)
    }

    override fun onCleared() {
        motion.stop()
        focusController.detach()
        recognizer.destroy()
        speech.shutdown()
        pipeline.release()
    }

    private companion object {
        const val TAG = "LiveAssistViewModel"
    }
}
