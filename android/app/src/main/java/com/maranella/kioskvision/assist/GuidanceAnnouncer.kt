package com.maranella.kioskvision.assist

/**
 * Decides *when* a guidance direction is worth speaking, so speech never follows the raw
 * frame rate. Pure and deterministic; not thread-safe.
 *
 * - A direction must persist for its stability time before it is spoken (short for
 *   directions, longer for "hand not detected" and "target uncertain", which are often brief
 *   glitches).
 * - The same direction is not repeated until its cooldown has passed.
 * - Different directions are at least [MIN_GAP_MS] apart, so speech does not chatter.
 * - ON_TARGET is spoken as soon as it appears (the engine already required a dwell).
 */
class GuidanceAnnouncer {

    private var pending: GuidanceDirection? = null
    private var pendingSinceMs = 0L
    private var lastSpoken: GuidanceDirection? = null
    private var lastSpokenAtMs = Long.MIN_VALUE / 2

    fun reset() {
        pending = null
        lastSpoken = null
        lastSpokenAtMs = Long.MIN_VALUE / 2
    }

    /** Returns the direction to speak now, or null to stay silent. */
    fun onDirection(direction: GuidanceDirection, nowMs: Long): GuidanceDirection? {
        if (direction != pending) {
            pending = direction
            pendingSinceMs = nowMs
        }
        if (nowMs - pendingSinceMs < stabilityMs(direction)) return null
        val sinceLast = nowMs - lastSpokenAtMs
        if (direction == lastSpoken && sinceLast < cooldownMs(direction)) return null
        if (direction != lastSpoken && sinceLast < MIN_GAP_MS && direction != GuidanceDirection.ON_TARGET) return null
        lastSpoken = direction
        lastSpokenAtMs = nowMs
        return direction
    }

    private fun stabilityMs(direction: GuidanceDirection): Long = when (direction) {
        GuidanceDirection.ON_TARGET -> 0
        GuidanceDirection.HAND_NOT_DETECTED -> 1500
        GuidanceDirection.TARGET_UNCERTAIN -> 1200
        else -> 300
    }

    private fun cooldownMs(direction: GuidanceDirection): Long = when (direction) {
        GuidanceDirection.HAND_NOT_DETECTED, GuidanceDirection.TARGET_UNCERTAIN -> 6000
        GuidanceDirection.NEAR_TARGET -> 3000
        // ON_TARGET ends the guidance session, so it is never repeated automatically.
        GuidanceDirection.ON_TARGET -> Long.MAX_VALUE / 2
        else -> 2500
    }

    companion object {
        const val MIN_GAP_MS = 700L
    }
}
