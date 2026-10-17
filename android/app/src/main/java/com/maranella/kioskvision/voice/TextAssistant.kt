package com.maranella.kioskvision.voice

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.Chat
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.content
import com.maranella.kioskvision.ai.AiConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** What came back from one exchange with the assistant. */
sealed interface AssistantReply {
    /** Something to say to the user, already in Georgian. */
    data class Speak(val text: String) : AssistantReply

    /** The assistant acted and the app will say the specifics itself; stay quiet. */
    data object Acted : AssistantReply

    /** Nothing usable came back. [reason] is for diagnostics and is never spoken. */
    data class Failed(val reason: String) : AssistantReply
}

/**
 * The assistant, over ordinary text generation.
 *
 * ### Why not the Live API
 *
 * The Live transport gives the model its own `AudioTrack`, which the app cannot mute and whose
 * playback it cannot observe. Alongside the device's Georgian text-to-speech that is two
 * voices talking at once, and for a blind user the audio channel is the entire interface
 * (R-22, R-24). Configuration could not fix it: `ResponseModality.TEXT` with
 * `startAudioConversation` stops streaming the microphone after about 491 ms.
 *
 * `generateContent` cannot emit audio **at all**. It is a text endpoint: there is no audio
 * part in its response and no track for it to play through. One voice is therefore a property
 * of the API chosen, not of a setting that could be flipped back by accident.
 *
 * Speech reaches it as text from the device's own recogniser, so the microphone stays under
 * the app's control and audio is never streamed anywhere.
 *
 * ### What it is still not allowed to do
 *
 * Nothing changes about ADR-009. The model is given the user's words and nothing else — no
 * camera frame, no OCR text, no geometry. It may ask the app to act through
 * [AssistantActions] and is told only an outcome. Where things are on the kiosk screen is
 * decided on the device, from what the camera saw.
 */
@OptIn(PublicPreviewAPI::class)
class TextAssistant(private val actions: AssistantActions? = null) {

    private var chat: Chat? = null

    /** Clears the conversation. A new session must not inherit the previous user's context. */
    fun reset() {
        chat = null
    }

    private fun chat(): Chat = chat ?: Firebase.ai(backend = GenerativeBackend.googleAI())
        .generativeModel(
            modelName = AiConfig.TEXT_MODEL,
            tools = if (actions == null) emptyList() else listOf(AssistantToolSchema.tool()),
            systemInstruction = content { text(AssistantPrompt.text()) },
        )
        .startChat()
        .also { chat = it }

    /**
     * Sends one recognised utterance and returns what to say back.
     *
     * Blank input is refused rather than sent: the recogniser returns an empty string on a
     * failed capture, and forwarding it would spend a request to be told nothing was said.
     */
    suspend fun respond(userText: String): AssistantReply {
        val text = userText.trim()
        if (text.isEmpty()) return AssistantReply.Failed("empty recognition")

        return try {
            val session = chat()
            var response = session.sendMessage(text)

            // One round of tool calls. The model asks, the app decides from what the camera
            // saw, and the outcome goes back — never a position, never screen text.
            val calls = response.functionCalls
            if (calls.isNotEmpty() && actions != null) {
                val replies = calls.map { call ->
                    val arguments = call.args.mapValues { (_, value) ->
                        (value as? JsonPrimitive)?.contentOrNull()
                    }
                    val outcome = AssistantTools.dispatch(call.name, arguments, actions)
                    Log.i(TAG, "tool ${call.name} -> ${outcome.wireValue}")
                    FunctionResponsePart(
                        name = call.name,
                        response = buildJsonObject { put("outcome", JsonPrimitive(outcome.wireValue)) },
                    )
                }
                response = session.sendMessage(Content("function", replies))
            }

            val reply = response.text?.trim().orEmpty()
            when {
                reply.isNotEmpty() -> AssistantReply.Speak(reply)
                calls.isNotEmpty() -> AssistantReply.Acted
                else -> AssistantReply.Failed("empty reply")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The message may name the model or the project but never the user's words.
            Log.e(TAG, "assistant request failed", e)
            AssistantReply.Failed(e::class.simpleName ?: "error")
        }
    }

    private companion object {
        const val TAG = "TextAssistant"
    }
}

private fun JsonPrimitive.contentOrNull(): String? = if (isString) content else content.ifEmpty { null }
