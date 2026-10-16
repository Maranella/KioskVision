package com.maranella.kioskvision.settings

import android.content.Context

/**
 * The one durable choice the user makes about voice: whether the streaming assistant may be
 * used at all.
 *
 * It is a setting rather than a per-session button because consent to send microphone audio
 * off the device is a decision about the product, made once, not a mode to be re-selected
 * before every sentence. An earlier design exposed it as a second voice button, which left a
 * blind user with two controls that both looked like "talk" and no way to tell them apart.
 *
 * Default is off. Nothing leaves the device until the user turns it on.
 */
class VoicePreferences(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var assistantEnabled: Boolean
        get() = preferences.getBoolean(KEY_ASSISTANT_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_ASSISTANT_ENABLED, value).apply()

    /** Whether the user has been asked at all; distinct from having answered "no". */
    val hasChosen: Boolean get() = preferences.contains(KEY_ASSISTANT_ENABLED)

    private companion object {
        const val FILE = "voice_preferences"
        const val KEY_ASSISTANT_ENABLED = "assistant_enabled"
    }
}
