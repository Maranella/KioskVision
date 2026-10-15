package com.maranella.kioskvision

import android.app.Application
import com.maranella.kioskvision.ai.AppCheckSetup

/**
 * Initializes Firebase and the build-appropriate App Check provider before anything uses the
 * AI SDK. Perception, OCR and guidance do not depend on this and keep working without network.
 */
class KioskVisionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCheckSetup.install(this)
    }
}
