package com.maranella.kioskvision.haptics

import com.maranella.kioskvision.assist.GuidanceDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rhythm is the whole feature: it has to mean *warmer* without anyone explaining it. These
 * pin the two properties a user would notice immediately — that it quickens as the finger
 * closes, and that it stops entirely when the app does not actually know where the finger is.
 */
class HapticGuidanceTest {

    /** Pulses produced over a window, by driving time forward in small steps. */
    private fun pulses(
        guidance: HapticGuidance,
        offsetX: Float,
        offsetY: Float,
        windowMs: Long = 2_000L,
    ): List<HapticCue.Pulse> {
        val collected = mutableListOf<HapticCue.Pulse>()
        var now = 0L
        while (now <= windowMs) {
            val cue = guidance.cue(GuidanceDirection.LEFT, offsetX, offsetY, now)
            if (cue is HapticCue.Pulse) collected += cue
            now += 10L
        }
        return collected
    }

    @Test
    fun `the rhythm quickens as the finger closes`() {
        val far = pulses(HapticGuidance(), offsetX = 5f, offsetY = 0f)
        val near = pulses(HapticGuidance(), offsetX = 1.2f, offsetY = 0f)

        assertTrue(
            "near should pulse more often: far=${far.size} near=${near.size}",
            near.size > far.size * 2,
        )
    }

    @Test
    fun `pulses grow stronger as the finger closes`() {
        val far = pulses(HapticGuidance(), offsetX = 5f, offsetY = 0f).first().amplitude
        val near = pulses(HapticGuidance(), offsetX = 0.5f, offsetY = 0f).first().amplitude

        assertTrue("far=$far near=$near", near > far)
    }

    @Test
    fun `diagonal distance is measured, not just one axis`() {
        // A finger off by 3 on each axis is further away than one off by 3 on a single axis,
        // and must feel like it.
        val oneAxis = pulses(HapticGuidance(), offsetX = 3f, offsetY = 0f).first().amplitude
        val diagonal = pulses(HapticGuidance(), offsetX = 3f, offsetY = 3f).first().amplitude

        assertTrue("oneAxis=$oneAxis diagonal=$diagonal", diagonal < oneAxis)
    }

    @Test
    fun `arrival is a single distinct cue, not another pulse`() {
        val guidance = HapticGuidance()
        assertEquals(
            HapticCue.Arrived,
            guidance.cue(GuidanceDirection.ON_TARGET, 0f, 0f, 1_000L),
        )
        // Repeating it would turn arrival into a buzz and lose the moment it marks.
        assertEquals(
            HapticCue.Silent,
            guidance.cue(GuidanceDirection.ON_TARGET, 0f, 0f, 1_200L),
        )
    }

    @Test
    fun `leaving the button and returning announces arrival again`() {
        val guidance = HapticGuidance()
        guidance.cue(GuidanceDirection.ON_TARGET, 0f, 0f, 1_000L)
        guidance.cue(GuidanceDirection.LEFT, 2f, 0f, 1_500L)

        assertEquals(
            HapticCue.Arrived,
            guidance.cue(GuidanceDirection.ON_TARGET, 0f, 0f, 2_000L),
        )
    }

    @Test
    fun `nothing is felt when the app does not know where the finger is`() {
        val guidance = HapticGuidance()
        // Buzzing through any of these would tell the user they are being tracked when they
        // are not, which is the haptic version of inventing a position.
        assertEquals(
            HapticCue.Silent,
            guidance.cue(GuidanceDirection.HAND_NOT_DETECTED, null, null, 1_000L),
        )
        assertEquals(
            HapticCue.Silent,
            guidance.cue(GuidanceDirection.TARGET_UNCERTAIN, 1f, 1f, 2_000L),
        )
        assertEquals(HapticCue.Silent, guidance.cue(null, 1f, 1f, 3_000L))
    }

    @Test
    fun `a missing offset silences the rhythm even with a direction`() {
        val guidance = HapticGuidance()
        assertEquals(
            HapticCue.Silent,
            guidance.cue(GuidanceDirection.LEFT, null, null, 1_000L),
        )
    }

    @Test
    fun `reset clears the rhythm so a new target does not inherit its timing`() {
        val guidance = HapticGuidance()
        guidance.cue(GuidanceDirection.LEFT, 1f, 0f, 1_000L)
        guidance.reset()

        // Without the reset this would be suppressed as too soon after the previous pulse.
        assertTrue(guidance.cue(GuidanceDirection.LEFT, 1f, 0f, 1_010L) is HapticCue.Pulse)
    }
}
