package com.maranella.kioskvision.voice

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.maranella.kioskvision.ai.AiConfig
import kotlinx.coroutines.launch

/**
 * M1 voice-provider spike. **Debug builds only** — declared in `src/debug/AndroidManifest.xml`
 * and not reachable from the product UI.
 *
 * Its only job is to answer one question on the real phone: can a Gemini Live session
 * understand Georgian speech and answer in Georgian, and what does the SDK actually expose
 * for transcripts, interruption and session limits? It touches no camera, OCR or guidance.
 *
 * Launch:
 * `adb shell am start -n com.maranella.kioskvision/.voice.VoiceSpikeActivity`
 */
class VoiceSpikeActivity : ComponentActivity() {

    /** Spoken output for the single-voice transport; the managed one speaks for itself. */
    private val speech by lazy {
        com.maranella.kioskvision.speech.GuidanceSpeechOutput(this) { }
    }

    private val managed by lazy { GeminiLiveVoiceProvider(this) }

    /**
     * The prototype transport: the app owns the microphone and speaks the model's text itself,
     * so only one voice can ever be audible. Compared against [managed] on a real device,
     * because that is the only place the question can be answered.
     */
    private val streaming by lazy {
        StreamingVoiceProvider(
            context = this,
            onSpeak = { text -> speech.speakText(text) },
            onCancelSpeech = { speech.stop() },
        )
    }

    private val useStreamingState = androidx.compose.runtime.mutableStateOf(false)
    private var useStreaming: Boolean
        get() = useStreamingState.value
        set(value) { useStreamingState.value = value }
    private val provider: VoiceProvider get() = if (useStreaming) streaming else managed

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val report by provider.report.collectAsStateWithLifecycle()
            val micGranted = remember { mutableStateOfMicPermission() }
            val diagnosis = remember { androidx.compose.runtime.mutableStateOf("") }
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted -> micGranted.value = granted }

            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Gemini Live — Georgian spike", fontSize = 22.sp)
                Text("model: ${AiConfig.LIVE_MODEL}", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Text("language: ${AiConfig.SPOKEN_LANGUAGE}   voice: ${AiConfig.VOICE_NAME}", fontSize = 13.sp, fontFamily = FontFamily.Monospace)

                Button(
                    onClick = {
                        lifecycleScope.launch { provider.stop() }
                        useStreaming = !useStreaming
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (useStreaming) {
                            "transport: STREAMING (one voice, app speaks)"
                        } else {
                            "transport: managed (model has its own voice)"
                        },
                    )
                }

                Text("state: ${report.state}", fontSize = 18.sp)
                Text("connects ${report.connects}   reconnects ${report.reconnects}", fontSize = 14.sp)
                report.goAwaySecondsLeft?.let { Text("server go-away in ${it}s", fontSize = 14.sp) }
                report.error?.let { Text("error: $it", fontSize = 14.sp) }

                // Transcripts are shown on screen for the spike only, and are never logged.
                Text("heard: ${report.lastUserText ?: "-"}", fontSize = 16.sp)
                Text("said:  ${report.lastModelText ?: "-"}", fontSize = 16.sp)

                // Newest first, so a run of test utterances can be scored afterwards.
                report.history.forEach { line -> Text(line, fontSize = 13.sp) }

                Button(
                    onClick = {
                        if (!micGranted.value) {
                            launcher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            lifecycleScope.launch { provider.start() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (micGranted.value) "Start session" else "Grant microphone") }

                Button(
                    onClick = {
                        lifecycleScope.launch { diagnosis.value = "checking..."; diagnosis.value = managed.diagnose() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Diagnose (text request)") }
                Text(diagnosis.value, fontSize = 13.sp)

                Button(
                    onClick = { lifecycleScope.launch { provider.stop() } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop session") }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        speech.stop()
        // Never keep the microphone open once the spike leaves the foreground.
        lifecycleScope.launch { managed.stop(); streaming.stop() }
    }

    private fun mutableStateOfMicPermission() = androidx.compose.runtime.mutableStateOf(
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED,
    )
}
