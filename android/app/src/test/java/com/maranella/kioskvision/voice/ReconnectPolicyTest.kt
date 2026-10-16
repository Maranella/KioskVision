package com.maranella.kioskvision.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun `failures back off by doubling`() {
        val policy = ReconnectPolicy()
        val delays = (1..4).map {
            (policy.onFailure() as ReconnectDecision.RetryAfter).delayMs
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), delays)
    }

    @Test
    fun `backoff is capped so a retry is never absurdly far off`() {
        val policy = ReconnectPolicy(maxAttempts = 10, baseDelayMs = 1_000L, maxDelayMs = 5_000L)
        val delays = (1..6).map { (policy.onFailure() as ReconnectDecision.RetryAfter).delayMs }
        assertTrue("expected a cap, got $delays", delays.all { it <= 5_000L })
        assertEquals(5_000L, delays.last())
    }

    @Test
    fun `retrying stops rather than looping forever`() {
        val policy = ReconnectPolicy(maxAttempts = 2)
        policy.onFailure()
        policy.onFailure()

        val decision = policy.onFailure()
        assertEquals(ReconnectDecision.GiveUp(2), decision)
    }

    @Test
    fun `a planned rollover reconnects promptly and costs nothing`() {
        val policy = ReconnectPolicy(maxAttempts = 1)
        // A healthy conversation crosses several rollovers; none may consume the failure
        // budget, or lasting a long time would itself end the session.
        repeat(20) {
            val decision = policy.onPlannedRollover()
            assertTrue(decision is ReconnectDecision.RetryAfter)
            assertTrue((decision as ReconnectDecision.RetryAfter).delayMs < 1_000L)
        }
        assertEquals(0, policy.failuresSoFar)
    }

    @Test
    fun `connecting clears the failure budget`() {
        val policy = ReconnectPolicy(maxAttempts = 2)
        policy.onFailure()
        policy.onFailure()
        policy.onConnected()

        // A drop after a good session starts the backoff over rather than giving up at once.
        assertEquals(
            ReconnectDecision.RetryAfter(1_000L, attempt = 1),
            policy.onFailure(),
        )
    }

    @Test
    fun `the user stopping the session clears the budget too`() {
        val policy = ReconnectPolicy()
        policy.onFailure()
        policy.reset()
        assertEquals(0, policy.failuresSoFar)
    }
}
