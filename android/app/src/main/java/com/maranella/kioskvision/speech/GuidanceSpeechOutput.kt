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
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(text, mode, null, nextUtteranceId())
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
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(text, mode, null, nextUtteranceId())
    }

    /**
     * Whether this engine is producing sound right now.
     *
     * Reported from the engine's own utterance callbacks, which is the only signal in this
     * app that actually means "audio is playing" — the Live SDK offers nothing equivalent for
     * its own playback, and `turnComplete` describes the model's turn, not the speaker.
     */
    @Volatile
    var isSpeaking: Boolean = false
        private set

    fun stop() {
        tts?.stop()
        isSpeaking = false
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
            override fun onStart(utteranceId: String?) { isSpeaking = true }
            override fun onDone(utteranceId: String?) { isSpeaking = false }
            @Deprecated("Required override", ReplaceWith(""))
            override fun onError(utteranceId: String?) { isSpeaking = false }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { isSpeaking = false }
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
