plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.maranella.kioskvision"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.maranella.kioskvision"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    bundle {
        // Spoken messages are rendered in the voice's language (Georgian or English), which
        // may differ from the device language, so every language must be installed.
        language {
            enableSplit = false
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.tesseract4android)

    // Firebase AI Logic, for the Gemini Live voice spike. Versions come from the BoM, as the
    // official get-started guide specifies. Analytics is deliberately not included: it is
    // optional here and pulls play-services-measurement, which ships Kotlin 2.4.0 metadata
    // that this project's Kotlin 2.2.10 compiler rejects.
    implementation(platform("com.google.firebase:firebase-bom:35.0.1"))
    implementation("com.google.firebase:firebase-ai")
    // Debug-only App Check provider; a release build must use Play Integrity instead.
    debugImplementation("com.google.firebase:firebase-appcheck-debug")

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}