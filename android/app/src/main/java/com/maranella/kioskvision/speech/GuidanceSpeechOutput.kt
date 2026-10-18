package com.maranella.kioskvision.speech

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import com.maranella.kioskvision.assist.SpokenMessage
import java.util.Locale

sealed interface SpeechOutputStatus {
    data object Initializing : SpeechOutputStatus
    /** A Georgian voice is in use. */
    data object Georgian : SpeechOutputStatus
    /** No installed engine has a Georgian voice; English is spoken instead. */
    data object EnglishFallback : SpeechOutputStatus
    /** No text-to-speech engine could be used at all. */
    data object Unavailable : SpeechOutputStatus
}

/**
 * App speech for guidance and replies, via Android TextToSpeech.
 *
 * Georgian voices are not installed on most devices, so on start-up this tries the default
 * engine and then every other installed engine for `ka-GE`. If none has it, it falls back to
 * English (with English phrases, not Georgian text read by an English voice) and reports
 * [SpeechOutputStatus.EnglishFallback] so the UI can say what to install.
 *
 * Must be used from the main thread.
 */
class GuidanceSpeechOutput(
    context: Context,
    private val onStatus: (SpeechOutputStatus) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var phrases: Resources? = null
    private var shutDown = false

    /** The default engine, kept as the English fallback while other engines are probed. */
    private var fallback: TextToSpeech? = null
    private var candidates: ArrayDeque<String>? = null

    init {
        onStatus(SpeechOutputStatus.Initializing)
        connect(engine = null)
    }

    fun speak(message: SpokenMessage, interrupt: Boolean = true) {
        val engine = tts ?: return
        val text = SpokenMessageText.render(phrases ?: return, message)
        enqueue(engine, text, interrupt)
    }

    /**
     * Speaks a sentence the assistant produced, rather than one of the app's own
     * [SpokenMessage] values.
     *
     * Deliberately separate. Everything the app says about the kiosk goes through
     * `SpokenMessage`, a closed set grounded in what the camera saw (ADR-009); this carries
     * the assistant's conversation, which is about the user's intent and never about the
     * screen. Keeping them apart is what stops free-form model text from becoming a claim
     * about a button.
     */
    fun speakText(text: String, interrupt: Boolean = true) {
        val engine = tts ?: return
        if (text.isBlank()) return
        enqueue(engine, text, interrupt)
    }

    /**
     * The utterance this engine is currently committed to, or null when it is idle.
     *
     * Tracked by id rather than as a boolean, so that **a turn made of several queued
     * utterances reads as one**. With a boolean, the first chunk's `onDone` cleared it while
     * the second was still queued, and anything gating on "is the app speaking" — the
     * microphone, above all — reopened in the gap and captured the app's own next sentence.
     * A terminal callback for an id that is no longer the active one is ignored, which also
     * makes `QUEUE_FLUSH` safe: the flushed utterance's `onStop` cannot clear its successor.
     */
    @Volatile
    private var activeUtteranceId: String? = null

    /**
     * Whether this engine is producing sound, or is about to.
     *
     * Comes from the engine's own utterance callbacks, which is the one signal in this app
     * that means "audio is coming out" rather than "audio was requested".
     */
    val isSpeaking: Boolean get() = activeUtteranceId != null

    /**
     * `elapsedRealtime` when the engine last reported that an utterance began playing.
     *
     * For measurement only. Compared against the moment text was handed to the engine, it gives
     * the synthesis latency, which is the last unmeasured leg between the model answering and
     * the user hearing it.
     */
    @Volatile
    var lastAudibleAtMs: Long = 0L
        private set

    private fun enqueue(engine: TextToSpeech, text: String, interrupt: Boolean) {
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val id = nextUtteranceId()
        // Set before speaking: the engine may report onStart synchronously, and a caller that
        // polls immediately must not see an idle engine it has just given work to.
        activeUtteranceId = id
        if (engine.speak(text, mode, null, id) != TextToSpeech.SUCCESS) {
            // No callback will arrive for an utterance the engine refused, and an id left
            // active here would hold the microphone closed for the rest of the session.
            Log.w(TAG, "text-to-speech refused an utterance")
            finish(id)
        }
    }

    /** Clears [activeUtteranceId], but only if [id] is still the one in flight. */
    private fun finish(id: String?) {
        if (id == null || id == activeUtteranceId) activeUtteranceId = null
    }

    fun stop() {
        tts?.stop()
        activeUtteranceId = null
    }

    fun shutdown() {
        shutDown = true
        mainHandler.removeCallbacksAndMessages(null)
        tts?.shutdown()
        if (fallback !== tts) fallback?.shutdown()
        tts = null
        fallback = null
    }

    private fun connect(engine: String?) {
        var created: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { status ->
            // Posted so `created` is assigned even if the engine reports synchronously.
            mainHandler.post { created?.let { onInit(it, status, isDefault = engine == null) } }
        }
        created = if (engine == null) TextToSpeech(appContext, listener) else TextToSpeech(appContext, listener, engine)
    }

    private fun onInit(engine: TextToSpeech, status: Int, isDefault: Boolean) {
        if (shutDown) {
            engine.shutdown()
            return
        }
        val ready = status == TextToSpeech.SUCCESS
        if (ready && supportsGeorgian(engine)) {
            if (fallback !== engine) fallback?.shutdown()
            fallback = null
            use(engine, GEORGIAN, SpeechOutputStatus.Georgian)
            return
        }
        if (isDefault) {
            if (ready) fallback = engine
            candidates = ArrayDeque(if (ready) engine.engines.map { it.name } - engine.defaultEngine else emptyList())
        } else {
            engine.shutdown()
        }
        val next = candidates?.removeFirstOrNull()
        when {
            next != null -> connect(next)
            fallback != null -> use(fallback!!, Locale.ENGLISH, SpeechOutputStatus.EnglishFallback)
            else -> {
                Log.w(TAG, "No usable text-to-speech engine")
                onStatus(SpeechOutputStatus.Unavailable)
            }
        }
    }

    private var utteranceCount = 0L

    private fun nextUtteranceId(): String = "kv-${utteranceCount++}"

    private fun use(engine: TextToSpeech, locale: Locale, status: SpeechOutputStatus) {
        engine.language = locale
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // The one moment in this app that means "sound is coming out", as opposed to
                // "sound was requested". Recorded so time-to-first-audible-word can be
                // measured end to end rather than estimated from the request.
                lastAudibleAtMs = android.os.SystemClock.elapsedRealtime()
            }
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            @Deprecated("Required override", ReplaceWith(""))
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
        })
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts = engine
        val configuration = Configuration(appContext.resources.configuration).apply { setLocale(locale) }
        phrases = appContext.createConfigurationContext(configuration).resources
        onStatus(status)
    }

    private fun supportsGeorgian(engine: TextToSpeech): Boolean =
        engine.isLanguageAvailable(GEORGIAN) >= TextToSpeech.LANG_AVAILABLE

    private companion object {
        const val TAG = "GuidanceSpeechOutput"
        val GEORGIAN: Locale = Locale.forLanguageTag("ka-GE")
    }
}
