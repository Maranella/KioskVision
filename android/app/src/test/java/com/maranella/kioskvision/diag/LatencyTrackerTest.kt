package com.maranella.kioskvision.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatencyTrackerTest {

    @Test
    fun `percentiles describe the distribution, not the average`() {
        val tracker = LatencyTracker()
        // Nine fast samples and one terrible one: a mean would report 108 ms and describe
        // neither the normal case nor the one the user notices.
        repeat(9) { tracker.record(20) }
        tracker.record(900)

        assertEquals(20L, tracker.percentile(0.5))
        assertEquals(900L, tracker.max)
    }

    @Test
    fun `nothing is claimed before there is anything to claim`() {
        val tracker = LatencyTracker()
        assertNull(tracker.percentile(0.5))
        assertNull(tracker.max)
        assertEquals("no samples", tracker.summary())
    }

    @Test
    fun `old samples fall out so the summary describes the present`() {
        val tracker = LatencyTracker(capacity = 3)
        tracker.record(100)
        tracker.record(100)
        tracker.record(100)
        tracker.record(10)

        assertEquals(3, tracker.count)
        assertEquals(10L, tracker.percentile(0.0))
    }

    @Test
    fun `a negative duration is a clock error and is ignored`() {
        val tracker = LatencyTracker()
        tracker.record(-5)
        assertEquals(0, tracker.count)
    }
}
