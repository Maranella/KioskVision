package com.maranella.kioskvision.assist

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro filter (Casiez, Roussel & Vogel, CHI 2012) for a 2D point: strong smoothing when
 * the finger is nearly still (removes landmark jitter), little smoothing when it moves fast
 * (keeps guidance from lagging behind the hand).
 *
 * Works in any unit; the defaults are tuned for coordinates normalized to 0..1.
 */
class OneEuroFilter2D(
    private val minCutoffHz: Float = 1.0f,
    private val beta: Float = 1.5f,
    private val derivativeCutoffHz: Float = 1.0f,
) {
    private val x = Axis()
    private val y = Axis()
    private var lastTimestampMs: Long? = null

    fun reset() {
        x.reset()
        y.reset()
        lastTimestampMs = null
    }

    fun filter(px: Float, py: Float, timestampMs: Long): Pair<Float, Float> {
        val previous = lastTimestampMs
        lastTimestampMs = timestampMs
        val dt = if (previous == null || timestampMs <= previous) null else (timestampMs - previous) / 1000f
        return x.filter(px, dt) to y.filter(py, dt)
    }

    private inner class Axis {
        private var value: Float? = null
        private var derivative = 0f

        fun reset() {
            value = null
            derivative = 0f
        }

        fun filter(raw: Float, dt: Float?): Float {
            val previous = value
            if (previous == null || dt == null) {
                value = raw
                return raw
            }
            val rawDerivative = (raw - previous) / dt
            derivative += alpha(derivativeCutoffHz, dt) * (rawDerivative - derivative)
            val cutoff = minCutoffHz + beta * abs(derivative)
            val filtered = previous + alpha(cutoff, dt) * (raw - previous)
            value = filtered
            return filtered
        }
    }

    private fun alpha(cutoffHz: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / dt)
    }
}
