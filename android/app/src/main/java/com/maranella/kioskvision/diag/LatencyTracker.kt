package com.maranella.kioskvision.diag

/**
 * Collects durations for one stage of a pipeline and reports percentiles.
 *
 * Averages hide exactly what matters here. A stage that is usually fast and occasionally
 * terrible feels worse than one that is uniformly mediocre, and a mean reports them the same.
 * p50 says what the user normally experiences; p90 says what they notice and complain about.
 *
 * Pure and deterministic — samples are supplied, not timed — so the arithmetic is testable
 * without a device.
 */
class LatencyTracker(private val capacity: Int = CAPACITY) {

    private val samples = ArrayDeque<Long>()

    fun record(durationMs: Long) {
        if (durationMs < 0) return
        samples.addLast(durationMs)
        while (samples.size > capacity) samples.removeFirst()
    }

    val count: Int get() = samples.size

    /** The value below which [fraction] of samples fall. Null until there is anything to say. */
    fun percentile(fraction: Double): Long? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    val max: Long? get() = samples.maxOrNull()

    fun summary(): String = if (samples.isEmpty()) {
        "no samples"
    } else {
        "n=${samples.size} p50=${percentile(0.5)}ms p90=${percentile(0.9)}ms max=${max}ms"
    }

    fun reset() = samples.clear()

    private companion object {
        /** Enough for a few minutes of guidance; old samples stop describing the present. */
        const val CAPACITY = 200
    }
}
