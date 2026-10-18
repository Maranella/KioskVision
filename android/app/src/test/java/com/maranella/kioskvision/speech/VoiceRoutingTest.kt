package com.maranella.kioskvision.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The microphone boundary, pinned.
 *
 * These are not style tests. Each one is a statement about whether the user's voice is sent to
 * Google, and the first two cover a defect that shipped: the app read whether consent had been
 * *asked*, never what the answer was, and started streaming either way.
 */
class VoiceRoutingTest {

    private fun press(
        listening: Boolean = false,
        micGranted: Boolean = true,
        hasChosen: Boolean = true,
        assistantEnabled: Boolean = true,
        liveAvailable: Boolean = true,
    ) = VoiceRouting.onVoicePressed(listening, micGranted, hasChosen, assistantEnabled, liveAvailable)

    @Test
    fun `declining consent never opens the streaming session`() {
        assertEquals(VoiceAction.StartLocalCommands, press(assistantEnabled = false))
    }

    @Test
    fun `declining consent still leaves a working voice path`() {
        // "Keep everything on this phone" must buy on-device recognition, not silence.
        assertNotEquals(VoiceAction.AskConsent, press(assistantEnabled = false))
        assertEquals(VoiceAction.StartLocalCommands, press(assistantEnabled = false))
    }

    @Test
    fun `consent is asked once, before anything is sent`() {
        assertEquals(VoiceAction.AskConsent, press(hasChosen = false))
        // Including when the saved default says enabled: not having chosen outranks it.
        assertEquals(VoiceAction.AskConsent, press(hasChosen = false, assistantEnabled = true))
    }

    @Test
    fun `agreeing opens the streaming session`() {
        assertEquals(VoiceAction.StartStreaming, press(assistantEnabled = true))
    }

    @Test
    fun `a pressed button while listening stops rather than opening a second microphone`() {
        assertEquals(VoiceAction.CancelListening, press(listening = true))
        assertEquals(VoiceAction.CancelListening, press(listening = true, assistantEnabled = false))
    }

    @Test
    fun `the microphone permission is asked for before any mode is entered`() {
        assertEquals(VoiceAction.AskMicPermission, press(micGranted = false))
        assertEquals(VoiceAction.AskMicPermission, press(micGranted = false, hasChosen = false))
    }

    @Test
    fun `a streaming session that gave up falls back to on-device commands`() {
        assertEquals(VoiceAction.StartLocalCommands, press(liveAvailable = false))
    }

    @Test
    fun `the label mode follows the path that would actually be opened`() {
        assertEquals(VoiceMode.LiveAssistant, VoiceRouting.modeFor(assistantEnabled = true, liveAvailable = true))
        assertEquals(VoiceMode.LocalCommands, VoiceRouting.modeFor(assistantEnabled = false, liveAvailable = true))
        assertEquals(VoiceMode.LocalCommands, VoiceRouting.modeFor(assistantEnabled = true, liveAvailable = false))
    }
}
