package com.maranella.kioskvision.diag

/**
 * One open-ended interval, for measuring how long a condition held.
 *
 * Bookkeeping only: it owns no decision. It exists so that a flag which is *assigned* many
 * times can be *logged* only when it actually moves, and so the duration between the two can
 * be reported without the caller keeping a timestamp beside every write.
 *
 * Both methods are no-ops when the interval is already in the state asked for, which is what
 * makes repeated assignments of the same value silent.
 *
 * Times are whatever monotonic clock the caller passes — in this app
 * `SystemClock.elapsedRealtime()`. Never wall-clock: these durations are measured across
 * seconds in which the system clock may step.
 */
class UnresolvedInterval {

    private var startedAtMs: Long? = null

    val isActive: Boolean get() = startedAtMs != null

    /**
     * Opens the interval. Returns true **only** when this call is what opened it, so a caller
     * can log a transition rather than every assignment.
     */
    fun begin(nowMs: Long): Boolean {
        if (startedAtMs != null) return false
        startedAtMs = nowMs
        return true
    }

    /**
     * Closes the interval and returns how long it was open, or null when none was open.
     *
     * Clamped at zero: a caller that passes a slightly earlier timestamp than the one it
     * opened with should get "no time" rather than a negative duration that would read as a
     * clock fault in a log.
     */
    fun end(nowMs: Long): Long? {
        val started = startedAtMs ?: return null
        startedAtMs = null
        return (nowMs - started).coerceAtLeast(0L)
    }
}
