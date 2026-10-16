package com.maranella.kioskvision.voice

/** What to do after a Live session ends unexpectedly. */
sealed interface ReconnectDecision {
    /** Wait this long, then reconnect. */
    data class RetryAfter(val delayMs: Long, val attempt: Int) : ReconnectDecision

    /**
     * Stop trying. The user is told the assistant is unavailable, and the app falls back to
     * on-device commands rather than pretending a session exists.
     */
    data class GiveUp(val attempts: Int) : ReconnectDecision
}

/**
 * How often and how fast a dropped voice session may be re-established.
 *
 * A session ends for two quite different reasons. The server announces a planned rollover
 * roughly every ten minutes, which is routine and must be reconnected promptly or the user
 * loses the assistant mid-sentence. Or the connection failed, which may mean no network, a
 * revoked key, or a retired model — and retrying those in a tight loop burns the request quota
 * the free tier actually limits, drains the battery, and holds the microphone open while
 * achieving nothing.
 *
 * So planned rollovers are cheap and immediate; failures back off and then stop. The policy is
 * separated from the provider precisely so it can be tested without a network.
 */
class ReconnectPolicy(
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val baseDelayMs: Long = BASE_DELAY_MS,
    private val maxDelayMs: Long = MAX_DELAY_MS,
) {

    private var consecutiveFailures = 0

    /**
     * The session ended where the server had warned it would. Not a failure, and it does not
     * count against the retry budget — otherwise a long, healthy conversation would exhaust it
     * simply by lasting.
     */
    fun onPlannedRollover(): ReconnectDecision = ReconnectDecision.RetryAfter(ROLLOVER_DELAY_MS, attempt = 0)

    /** The session failed or dropped without warning. */
    fun onFailure(): ReconnectDecision {
        consecutiveFailures++
        if (consecutiveFailures > maxAttempts) {
            return ReconnectDecision.GiveUp(consecutiveFailures - 1)
        }
        // Doubling, capped. Shifting by (n-1) rather than multiplying keeps it exact.
        val delay = (baseDelayMs shl (consecutiveFailures - 1)).coerceAtMost(maxDelayMs)
        return ReconnectDecision.RetryAfter(delay, attempt = consecutiveFailures)
    }

    /** A session connected. Clears the failure budget so a later drop starts over. */
    fun onConnected() {
        consecutiveFailures = 0
    }

    /** The user stopped the session; nothing is owed. */
    fun reset() {
        consecutiveFailures = 0
    }

    val failuresSoFar: Int get() = consecutiveFailures

    private companion object {
        /**
         * Deliberately small. Beyond a handful of failures the cause is almost never
         * transient, and the honest answer to the user is that the assistant is unavailable.
         */
        const val MAX_ATTEMPTS = 4
        const val BASE_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 15_000L

        /** Brief, because the user is mid-conversation and the drop was expected. */
        const val ROLLOVER_DELAY_MS = 250L
    }
}
