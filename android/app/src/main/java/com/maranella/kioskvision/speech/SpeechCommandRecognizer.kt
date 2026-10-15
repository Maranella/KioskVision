package com.maranella.kioskvision.speech

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.maranella.kioskvision.assist.RecognitionFailure

/**
 * One-shot Georgian speech recognition through the system `SpeechRecognizer`, started only by
 * an explicit user action. The app never stores audio; the recognition service may process it
 * according to its own policy (often online for Georgian).
 *
 * Must be used from the main thread.
 */
class SpeechCommandRecognizer(private val context: Context) {

    interface Listener {
        fun onListening()
        fun onResults(hypotheses: List<String>)
        fun onFailure(reason: RecognitionFailure)
    }

    private var recognizer: SpeechRecognizer? = null
    private var active = false
    /** When listening started, so the round trip can be reported. */
    private var startedAtMs = 0L

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Whether the recognition service lists Georgian at all. Null until [queryLanguages] has
     * answered; some services never reply, which is itself not an error.
     */
    @Volatile
    var georgianSupported: Boolean? = null
        private set

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    /**
     * Asks the recognition service which languages it has.
     *
     * Without this the service silently falls back to its default language when Georgian is
     * missing, so Georgian speech comes back transcribed as nonsense and the app looks like it
     * is mishearing the user rather than lacking the language.
     */
    fun queryLanguages() {
        Log.i(TAG, "recognition service: ${recognitionServicePackage()}")
        checkRecognitionSupport()
        val intent = Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS).apply {
            // Addressed to whichever service handles recognition, so it is not a global query.
            `package` = recognitionServicePackage()
        }
        try {
            context.sendOrderedBroadcast(
                intent,
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(receiver: Context?, received: Intent?) {
                        val extras = getResultExtras(true)
                        val languages = extras.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES)
                        val preferred = extras.getString(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)
                        if (languages == null) {
                            // No handler for the query. Common when the only recognisers are
                            // on-device ones, which is itself worth knowing.
                            Log.i(TAG, "recognition service did not report its languages")
                        } else {
                            georgianSupported = languages.any { it.startsWith("ka", ignoreCase = true) }
                            Log.i(
                                TAG,
                                "recognition languages: georgian=$georgianSupported preferred=$preferred " +
                                    "count=${languages.size} sample=${languages.take(12)}",
                            )
                        }
                    }
                },
                null,
                Activity.RESULT_OK,
                null,
                null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not query recognition languages", e)
        }
    }

    /**
     * Android 13+ reports exactly which languages the recogniser has, on device and online.
     * This is the only authoritative answer: a service may accept a language tag it cannot
     * actually handle and quietly transcribe with a different model, which looks to the user
     * like the app mishearing them.
     */
    private fun checkRecognitionSupport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val probe = SpeechRecognizer.createSpeechRecognizer(context)
        probe.checkRecognitionSupport(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG),
            context.mainExecutor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    fun hasGeorgian(languages: List<String>) =
                        languages.any { it.startsWith("ka", ignoreCase = true) }

                    val installed = support.installedOnDeviceLanguages
                    val supported = support.supportedOnDeviceLanguages
                    val online = support.onlineLanguages
                    georgianSupported = hasGeorgian(installed) || hasGeorgian(online)
                    Log.i(
                        TAG,
                        "recognition support: georgian=$georgianSupported " +
                            "installedOnDevice=${installed.size}/ka=${hasGeorgian(installed)} " +
                            "supportedOnDevice=${supported.size}/ka=${hasGeorgian(supported)} " +
                            "online=${online.size}/ka=${hasGeorgian(online)}",
                    )
                    probe.destroy()
                }

                override fun onError(error: Int) {
                    Log.i(TAG, "recognition support unavailable (error $error)")
                    probe.destroy()
                }
            },
        )
    }

    private fun recognitionServicePackage(): String? =
        context.packageManager
            .queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
            .firstOrNull()
            ?.serviceInfo
            ?.packageName

    fun start(listener: Listener) {
        if (!isAvailable) {
            listener.onFailure(RecognitionFailure.ServiceUnavailable)
            return
        }
        val recognizer = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        recognizer.cancel()
        recognizer.setRecognitionListener(Adapter(listener))
        active = true
        startedAtMs = SystemClock.elapsedRealtime()
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE_TAG)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Commands here are one or two words. The default end-of-speech silence is
                // tuned for dictation and leaves the user waiting seconds after they have
                // finished speaking. Services may ignore these, so they are a request, not a
                // guarantee.
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS)
                .putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    POSSIBLY_COMPLETE_SILENCE_MS,
                )
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MINIMUM_INPUT_MS),
        )
    }

    /** Stops listening without delivering a result or an error. */
    fun cancel() {
        active = false
        recognizer?.cancel()
    }

    fun destroy() {
        active = false
        recognizer?.destroy()
        recognizer = null
    }

    private inner class Adapter(private val listener: Listener) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!active) return
            // A blind user cannot see "Listening…", and speaking before the microphone opens
            // clips the first syllable — which is most of a one-word Georgian command. A short
            // buzz marks the moment it is safe to speak, and unlike a beep the microphone does
            // not transcribe it.
            cueReadyToSpeak()
            listener.onListening()
        }

        override fun onResults(results: Bundle?) {
            if (!active) return
            active = false
            Log.i(TAG, "results in ${SystemClock.elapsedRealtime() - startedAtMs}ms")
            val hypotheses = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                .filter { it.isNotBlank() }
            if (hypotheses.isEmpty()) listener.onFailure(RecognitionFailure.NoMatch) else listener.onResults(hypotheses)
        }

        override fun onError(error: Int) {
            if (!active) return
            active = false
            // The raw code distinguishes "no Georgian on this device" from "heard nothing",
            // which the mapped failure alone cannot.
            Log.i(TAG, "recognition error $error -> ${mapError(error)} (requested $LANGUAGE_TAG)")
            listener.onFailure(mapError(error))
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun cueReadyToSpeak() {
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(CUE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(CUE_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not signal that listening started", e)
        }
    }

    private companion object {
        const val TAG = "SpeechCommandRecognizer"
        const val LANGUAGE_TAG = "ka-GE"

        /** Silence after which a command counts as finished. The default is dictation-length. */
        const val COMPLETE_SILENCE_MS = 800L
        const val POSSIBLY_COMPLETE_SILENCE_MS = 600L
        const val MINIMUM_INPUT_MS = 700L

        /** Length of the "you may speak now" buzz. */
        const val CUE_MS = 40L
        // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE (API 31);
        // declared locally because minSdk is 24 and older services simply never report them.
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13

        fun mapError(error: Int): RecognitionFailure = when (error) {
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RecognitionFailure.NoSpeech
            SpeechRecognizer.ERROR_NO_MATCH -> RecognitionFailure.NoMatch
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> RecognitionFailure.NetworkRequired
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognitionFailure.PermissionDenied
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> RecognitionFailure.Busy
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> RecognitionFailure.LanguageUnsupported
            else -> RecognitionFailure.Other
        }
    }
}
