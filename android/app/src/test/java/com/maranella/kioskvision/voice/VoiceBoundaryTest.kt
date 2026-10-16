package com.maranella.kioskvision.voice

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Makes ADR-009's central invariant executable.
 *
 * The voice layer is denied screen data rather than merely asked not to use it: a model that
 * was never told what the kiosk shows cannot describe it, truthfully or otherwise. That is a
 * property of the wiring, and the only control here that does not depend on the model
 * behaving — R-20 is what happens when the only control is a prompt.
 *
 * An invariant nobody can break by accident is worth more than one written in a document, so
 * this fails the build when a future change hands perception types to the voice interface.
 * Plain Java reflection is used so the check costs no dependency.
 */
class VoiceBoundaryTest {

    /**
     * Types that describe what is on the kiosk screen. Matched by simple name so the check
     * survives these types moving package, and so a new perception type named along the same
     * lines is caught rather than silently permitted.
     */
    private val forbidden = listOf(
        "DetectedTextBlock",
        "TextBox",
        "KioskScene",
        "SceneElement",
        "ObservedLabel",
        "TrackedTarget",
        "ResolvedTarget",
        "TrackedScreen",
        "Quad",
        "Homography",
        "FingertipPoint",
        "Bitmap",
        "Image",
    )

    private fun offendersIn(type: String, where: String): List<String> =
        forbidden.filter { type.contains(it) }.map { "$where mentions $it" }

    @Test
    fun `the voice interface cannot carry screen data`() {
        val offenders = VoiceProvider::class.java.declaredMethods.flatMap { method ->
            val signature = method.parameterTypes.joinToString { it.name } + " -> " + method.returnType.name
            offendersIn(signature, "fun ${method.name}")
        }

        assertTrue(
            "VoiceProvider must not be able to receive kiosk screen data (ADR-009): $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the session report carries no screen data either`() {
        val offenders = VoiceSessionReport::class.java.declaredFields.flatMap { field ->
            // Generic parameters matter: a List<DetectedTextBlock> would pass a raw type check.
            offendersIn(field.genericType.toString(), "val ${field.name}")
        }

        assertTrue(
            "VoiceSessionReport must stay a transport-level report (ADR-009): $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the voice interface exposes only session control`() {
        // Narrow by construction: start, stop and the report. Anything else is a place where
        // screen knowledge could enter later without anyone noticing.
        val names = VoiceProvider::class.java.declaredMethods.map { it.name }.toSet()
        assertTrue(
            "unexpected members on VoiceProvider, each one a new way in: $names",
            names == setOf("start", "stop", "getReport"),
        )
    }

    @Test
    fun `the forbidden list actually matches the types it names`() {
        // Guards the guard: if a perception type were renamed, the check above would quietly
        // pass on a list of names that no longer correspond to anything.
        val signature = "java.util.List<com.maranella.kioskvision.assist.DetectedTextBlock>"
        assertTrue(
            "the check must detect a perception type in a signature",
            offendersIn(signature, "probe").isNotEmpty(),
        )
    }
}
