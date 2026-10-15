package com.maranella.kioskvision.ai

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.initialize

/**
 * Release build: Firebase is initialized, but **no App Check provider is installed yet**.
 *
 * This is a deliberate, visible gap rather than a silent one. Shipping requires the Play
 * Integrity provider (`firebase-appcheck-playintegrity`) plus enforcement enabled in the
 * Firebase console; Google documents enforcement as required for Firebase AI Logic from
 * 2 November 2026. Until that is done this build must not be released with AI features on.
 */
object AppCheckSetup {
    fun install(context: Context) {
        Firebase.initialize(context)
    }
}
