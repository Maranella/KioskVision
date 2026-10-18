package com.maranella.kioskvision.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Plays a [HapticCue]. The only part of the haptics that touches Android.
 *
 * Everything degrades rather than failing: a device with no vibrator, or one that refuses
 * amplitude control, still runs the app — it simply does not buzz. Guidance is spoken as well
 * as felt, so losing the rhythm costs expressiveness, never correctness.
 */
class HapticPlayer(context: Context) {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()?.takeIf { it.hasVibrator() }

    val isAvailable: Boolean get() = vibrator != null

    private val hasAmplitudeControl: Boolean =
        vibrator?.hasAmplitudeControl() == true

    fun play(cue: HapticCue) {
        val device = vibrator ?: return
        when (cue) {
            is HapticCue.Pulse -> {
                val effect = if (hasAmplitudeControl) {
                    VibrationEffect.createOneShot(cue.durationMs, cue.amplitude)
                } else {
                    // Without amplitude control only the rhythm carries the distance, which
                    // is the larger half of the signal anyway.
                    VibrationEffect.createOneShot(cue.durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                runCatching { device.vibrate(effect) }
            }

            // Two firm taps: deliberately unlike the single pulses of the approach, so
            // arrival is felt as an event rather than as the rhythm getting faster.
            HapticCue.Arrived -> runCatching {
                device.vibrate(VibrationEffect.createWaveform(ARRIVAL_PATTERN, -1))
            }

            HapticCue.Silent -> Unit
        }
    }

    fun stop() {
        runCatching { vibrator?.cancel() }
    }

    private companion object {
        /** wait, buzz, gap, buzz. */
        val ARRIVAL_PATTERN = longArrayOf(0, 60, 70, 60)
    }
}
