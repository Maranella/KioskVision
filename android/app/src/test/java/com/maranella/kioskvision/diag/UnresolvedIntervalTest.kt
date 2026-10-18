package com.maranella.kioskvision.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bookkeeping behind the `CHANGE-UNRESOLVED` log lines.
 *
 * It carries no decision — the pipeline's behaviour is identical with the logging removed —
 * but the two things a reader of those lines relies on are here: that a line appears once per
 * transition rather than once per assignment, and that the duration on the closing line is
 * the real one.
 */
class UnresolvedIntervalTest {

    @Test
    fun `only the first of repeated opens is a transition`() {
        // The pipeline assigns this flag on every frame where the signature differs. One
        // suspicion event must produce one line, or a validation session counts frames
        // instead of events.
        val interval = UnresolvedInterval()
        assertTrue(interval.begin(1_000))
        assertFalse(interval.begin(1_100))
        assertFalse(interval.begin(1_200))
        assertTrue(interval.isActive)
    }

    @Test
    fun `the duration is measured from the first open, not the last`() {
        val interval = UnresolvedInterval()
        interval.begin(1_000)
        interval.begin(1_400)
        assertEquals(900L, interval.end(1_900))
    }

    @Test
    fun `closing an interval that was never open says nothing`() {
        // The clear sites run unconditionally — an `Unchanged` result arrives whether or not
        // anything was suspected — so this is the common case, and it must not log.
        val interval = UnresolvedInterval()
        assertNull(interval.end(1_000))
        assertFalse(interval.isActive)
    }

    @Test
    fun `closing twice reports once`() {
        val interval = UnresolvedInterval()
        interval.begin(1_000)
        assertEquals(500L, interval.end(1_500))
        assertNull(interval.end(1_600))
    }

    @Test
    fun `a second suspicion after a resolved one is its own interval`() {
        val interval = UnresolvedInterval()
        interval.begin(1_000)
        assertEquals(200L, interval.end(1_200))
        assertTrue(interval.begin(5_000))
        assertEquals(300L, interval.end(5_300))
    }

    @Test
    fun `a duration is never negative`() {
        // Defensive: a caller that passes an earlier timestamp should produce "no time"
        // rather than a negative number, which in a log reads as a clock fault.
        val interval = UnresolvedInterval()
        interval.begin(2_000)
        assertEquals(0L, interval.end(1_900))
    }

    @Test
    fun `an interval starts closed`() {
        assertFalse(UnresolvedInterval().isActive)
    }
}
