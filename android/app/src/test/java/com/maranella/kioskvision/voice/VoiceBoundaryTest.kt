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

    @Test
    fun `the actions the model may ask for carry no screen data in either direction`() {
        // The widest surface between the app and the model, and the one that grew most
        // recently: `compare_prices` answers a question *about amounts on the screen*, which
        // is exactly the kind of feature that would otherwise be implemented by handing the
        // model the amounts. It is not. The app compares locally and speaks the answer itself.
        val offenders = AssistantActions::class.java.declaredMethods.flatMap { method ->
            val signature = method.parameterTypes.joinToString { it.name } + " -> " + method.returnType.name
            offendersIn(signature, "fun ${method.name}")
        }

        assertTrue(
            "AssistantActions must not be able to carry kiosk screen data (ADR-009): $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `every action answers with an outcome and nothing else`() {
        // A method that returned anything but a ToolOutcome would be a channel back to the
        // model: a label, a position, a price. The arguments are the user's own words, which
        // is the one thing the model is allowed to put in.
        val returns = AssistantActions::class.java.declaredMethods.map { it.returnType }
        assertTrue(
            "an action returns something other than ToolOutcome: " + returns.map { it.name },
            returns.all { it == ToolOutcome::class.java },
        )
        val arguments = AssistantActions::class.java.declaredMethods
            .flatMap { it.parameterTypes.toList() }
            .toSet()
        assertTrue(
            "an action takes something other than text: " + arguments.map { it.name },
            arguments.all { it == String::class.java },
        )
    }

    @Test
    fun `an outcome is a bare word, with no room for detail`() {
        // ToolOutcome is what goes on the wire. An instance field on it — a label, an amount,
        // a count — would travel with it, so there are none, and `wireValue` is derived from
        // the constant's own name.
        val instanceFields = ToolOutcome::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertTrue(
            "ToolOutcome must stay a closed set of bare words: " + instanceFields.map { it.name },
            instanceFields.isEmpty(),
        )
        for (outcome in ToolOutcome.entries) {
            assertTrue("'${outcome.wireValue}' is not a bare word", outcome.wireValue.all { it.isLetter() })
        }
    }
}
