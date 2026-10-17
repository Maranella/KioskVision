package com.maranella.kioskvision.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The goal is what turns a one-shot finder into an assistant that can see a payment through.
 * It is also the piece most able to misbehave quietly: a goal that outlives its task starts
 * volunteering suggestions to whoever is holding the phone next.
 */
class TaskGoalTest {

    private fun goal(startedAtMs: Long = 0L) =
        TaskGoal(query = "გადახდა", role = ElementRole.Payment, startedAtMs = startedAtMs)

    @Test
    fun `a goal remembers what was already guided to`() {
        val after = goal().withVisited("კომუნალური გადასახადი")

        assertTrue(after.hasVisited("კომუნალური გადასახადი"))
        assertFalse(after.hasVisited("დენი"))
    }

    @Test
    fun `visiting the same label twice does not duplicate it`() {
        val after = goal().withVisited("დენი").withVisited("დენი")
        assertEquals(listOf("დენი"), after.visited)
    }

    @Test
    fun `a visited label is matched regardless of case`() {
        // OCR returns a label's case as it reads it, which varies between runs.
        assertTrue(goal().withVisited("Back").hasVisited("back"))
    }

    @Test
    fun `a goal expires so it cannot outlive the task`() {
        val active = goal(startedAtMs = 0L)

        assertFalse("a payment in progress must keep its goal", active.isExpired(60_000L))
        assertTrue(
            "a goal left from an abandoned transaction must not suggest anything",
            active.isExpired(TaskGoal.EXPIRY_MS + 1),
        )
    }

    @Test
    fun `the query is what carries across screens, not a script of steps`() {
        // The app does not know this kiosk's flow. All it keeps is the user's own words, to
        // be matched again on whatever screen appears next.
        val carried = goal().withVisited("კომუნალური გადასახადი")
        assertEquals("გადახდა", carried.query)
        assertEquals(ElementRole.Payment, carried.role)
    }
}
