package com.maranella.kioskvision.haptics

import com.maranella.kioskvision.assist.GuidanceDirection
import kotlin.math.hypot

/** What the phone should do right now. */
sealed interface HapticCue {
    /** One pulse of the approach rhythm. Closer to the button means more often and harder. */
    data class Pulse(val durationMs: Long, val amplitude: Int) : HapticCue

    /** The finger is on the button. A distinct shape, so it cannot be mistaken for a pulse. */
    data object Arrived : HapticCue

    /** Nothing to feel: too soon since the last pulse, or nothing to guide to. */
    data object Silent : HapticCue
}

/**
 * Turns the distance between fingertip and target into a rhythm the user can feel.
 *
 * Speech is a slow channel for a closed loop. "A little to the left" takes two seconds to say,
 * by which time the hand has moved, and while the app is talking the microphone is muted so
 * the user cannot interrupt. A pulse that quickens as the finger closes carries the same
 * information continuously and costs no words — the way a metal detector does, which is a
 * device people already understand without being taught.
 *
 * It does not replace speech. Direction still comes from the spoken cue; this says only
 * *warmer* or *colder*, which is exactly what speech is worst at.
 *
 * Pure and deterministic: time is passed in rather than read, so the rhythm can be tested
 * without a device. The Android `Vibrator` lives in [HapticPlayer].
 */
class HapticGuidance(
    private val nearZoneMultiplier: Float = NEAR_ZONE,
) {

    private var lastPulseMs = Long.MIN_VALUE / 2
    private var announcedArrival = false

    /**
     * @param offsetX fingertip minus target centre, in units of the on-target zone's
     *   half-width, as [com.maranella.kioskvision.assist.GuidanceOutput] reports it.
     */
    fun cue(
        direction: GuidanceDirection?,
        offsetX: Float?,
        offsetY: Float?,
        nowMs: Long,
    ): HapticCue {
        if (direction == GuidanceDirection.ON_TARGET) {
            if (announcedArrival) return HapticCue.Silent
            announcedArrival = true
            return HapticCue.Arrived
        }
        announcedArrival = false

        // No target, no hand, or a position the app will not stand behind. Buzzing through
        // any of those would tell the user the app is tracking them when it is not.
        if (direction == null ||
            direction == GuidanceDirection.HAND_NOT_DETECTED ||
            direction == GuidanceDirection.TARGET_UNCERTAIN ||
            offsetX == null || offsetY == null
        ) {
            return HapticCue.Silent
        }

        val distance = hypot(offsetX, offsetY)
        // Beyond the near zone the finger is nowhere near; a slow tick says "still searching"
        // without implying progress that is not happening.
        val closeness = (1f - (distance / (nearZoneMultiplier * FAR_MULTIPLIER))).coerceIn(0f, 1f)
        val interval = (FARTHEST_INTERVAL_MS - (FARTHEST_INTERVAL_MS - NEAREST_INTERVAL_MS) * closeness)
            .toLong()
        if (nowMs - lastPulseMs < interval) return HapticCue.Silent

        lastPulseMs = nowMs
        val amplitude = (MIN_AMPLITUDE + (MAX_AMPLITUDE - MIN_AMPLITUDE) * closeness).toInt()
        return HapticCue.Pulse(durationMs = PULSE_MS, amplitude = amplitude)
    }

    /** Forgets the rhythm, so a new target does not inherit the previous one's timing. */
    fun reset() {
        lastPulseMs = Long.MIN_VALUE / 2
        announcedArrival = false
    }

    private companion object {
        /** Matches the guidance engine's own near zone, so the two agree on "close". */
        const val NEAR_ZONE = 3f

        /** How far beyond the near zone still earns the slowest tick. */
        const val FAR_MULTIPLIER = 2f

        /** Slow enough to be a heartbeat, not an alarm. */
        const val FARTHEST_INTERVAL_MS = 700f

        /** Fast enough to read as "almost there" without merging into a buzz. */
        const val NEAREST_INTERVAL_MS = 90f

        const val PULSE_MS = 22L
        const val MIN_AMPLITUDE = 70f
        const val MAX_AMPLITUDE = 255f
    }
}
