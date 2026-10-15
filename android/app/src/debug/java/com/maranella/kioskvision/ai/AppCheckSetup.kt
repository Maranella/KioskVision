package com.maranella.kioskvision.ai

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.initialize

/**
 * Debug build: installs the App Check **debug** provider.
 *
 * The debug token this prints must be registered under Security > App Check > Apps in the
 * Firebase console before the Live API will accept requests. It is a local development
 * affordance only — `firebase-appcheck-debug` is a `debugImplementation` dependency, so this
 * provider cannot reach a release build.
 */
object AppCheckSetup {
    fun install(context: Context) {
        Firebase.initialize(context)
        Firebase.appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
        Log.i("AppCheckSetup", "debug App Check provider installed; register the token from logcat")
    }
}
