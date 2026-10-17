package com.maranella.kioskvision.assist

/**
 * What the user is trying to do, kept across screen changes.
 *
 * A kiosk payment is several screens long, and until now each one started from nothing: the
 * user said what they wanted, was guided to a button, pressed it, and then had to say it all
 * again on the screen that followed. Holding the goal is what makes the app an assistant
 * rather than a one-shot finder.
 *
 * **It does not know the kiosk's flow and never pretends to.** There is no script of steps.
 * All it does is remember the words the user used, re-run them against each new screen, and
 * offer what it finds. When a new screen has nothing matching, it says so and waits — it does
 * not guess what the next step ought to be.
 */
data class TaskGoal(
    /** The user's own words, which is what gets re-matched. */
    val query: String,
    /** The role the request was understood as, used only to break ties. */
    val role: ElementRole?,
    val startedAtMs: Long,
    /**
     * Labels already guided to under this goal.
     *
     * Without this the goal re-offers the button the user has just pressed, because it is
     * often still on screen for a moment after the kiosk advances.
     */
    val visited: List<String> = emptyList(),
) {
    fun withVisited(label: String): TaskGoal =
        if (label in visited) this else copy(visited = visited + label)

    fun hasVisited(label: String): Boolean = visited.any { it.equals(label, ignoreCase = true) }

    fun age(nowMs: Long): Long = nowMs - startedAtMs

    /**
     * Whether the goal is still worth carrying.
     *
     * A goal left over from a transaction the user walked away from is worse than none: it
     * would volunteer a suggestion to whoever picks the phone up next.
     */
    fun isExpired(nowMs: Long, limitMs: Long = EXPIRY_MS): Boolean = age(nowMs) > limitMs

    companion object {
        /** Long enough for an unhurried payment, short enough not to outlive one. */
        const val EXPIRY_MS = 5 * 60 * 1_000L
    }
}
