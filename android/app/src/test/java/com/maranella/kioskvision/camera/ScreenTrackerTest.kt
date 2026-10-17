package com.maranella.kioskvision.camera

import com.maranella.kioskvision.assist.TextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tracking is where "the screen moved" and "the detector was wrong once" have to be told
 * apart, and where geometry has to be retired before it becomes a confident lie about where
 * the screen used to be.
 */
class ScreenTrackerTest {

    private fun quad(offsetX: Float = 0f, offsetY: Float = 0f, size: Float = 400f) = Quad(
        Point(100f + offsetX, 100f + offsetY),
        Point(100f + size + offsetX, 100f + offsetY),
        Point(100f + size + offsetX, 100f + size + offsetY),
        Point(100f + offsetX, 100f + size + offsetY),
    )

    private fun candidate(q: Quad, confidence: Float = 0.8f) =
        ScreenCandidate(q, confidence, coverage = 0.4f)

    @Test
    fun `geometry is unavailable before anything is detected`() {
        val tracker = ScreenTracker()
        assertNull(tracker.geometry(1_000L))
        assertEquals(ScreenLoss.NeverDetected, tracker.loss(1_000L))
    }

    @Test
    fun `a single detection is usable but not yet stable`() {
        val tracker = ScreenTracker()
        tracker.onDetection(candidate(quad()), nowMs = 1_000L)

        val geometry = tracker.geometry(1_000L)
        assertNotNull(geometry)
        assertFalse("one detection must not count as settled", geometry!!.stable)
        assertNull(tracker.loss(1_000L))
    }

    @Test
    fun `repeated detections settle into a stable track`() {
        val tracker = ScreenTracker()
        repeat(3) { tracker.onDetection(candidate(quad()), nowMs = 1_000L + it * 100L) }

        assertTrue(tracker.geometry(1_200L)!!.stable)
    }

    @Test
    fun `low-confidence detections are ignored entirely`() {
        val tracker = ScreenTracker()
        tracker.onDetection(candidate(quad(), confidence = 0.1f), nowMs = 1_000L)

        assertNull(tracker.geometry(1_000L))
        assertEquals(ScreenLoss.NeverDetected, tracker.loss(1_000L))
    }

    @Test
    fun `small movements are smoothed rather than followed exactly`() {
        val tracker = ScreenTracker()
        tracker.onDetection(candidate(quad()), nowMs = 1_000L)
        tracker.onDetection(candidate(quad(offsetX = 20f)), nowMs = 1_100L)

        val left = tracker.geometry(1_100L)!!.quad.topLeft.x
        // Neither ignored nor followed one-for-one: a jittering detector must not make the
        // target jitter with it.
        assertTrue("expected partial follow, got $left", left > 100f && left < 120f)
    }

    @Test
    fun `a large jump restarts the track instead of averaging across it`() {
        val tracker = ScreenTracker()
        repeat(3) { tracker.onDetection(candidate(quad()), nowMs = 1_000L + it * 100L) }
        assertTrue(tracker.geometry(1_200L)!!.stable)

        // The phone swings to a different screen.
        tracker.onDetection(candidate(quad(offsetX = 600f)), nowMs = 1_300L)
        val geometry = tracker.geometry(1_300L)!!

        assertEquals("must snap, not average", 700f, geometry.quad.topLeft.x, 1f)
        assertFalse("a restarted track is not stable", geometry.stable)
    }

    @Test
    fun `geometry goes stale when detection stops confirming it`() {
        val tracker = ScreenTracker()
        tracker.onDetection(candidate(quad()), nowMs = 1_000L)

        assertNotNull(tracker.geometry(1_500L))
        assertNull("geometry older than the stale window must not be used", tracker.geometry(2_500L))
        assertEquals(ScreenLoss.Stale, tracker.loss(2_500L))
    }

    @Test
    fun `a long gap is reported as tracking lost rather than merely stale`() {
        val tracker = ScreenTracker()
        tracker.onDetection(candidate(quad()), nowMs = 1_000L)

        assertEquals(ScreenLoss.TrackingLost, tracker.loss(10_000L))
    }

    @Test
    fun `failed detections do not age out geometry on their own`() {
        val tracker = ScreenTracker()
        tracker.onDetection(candidate(quad()), nowMs = 1_000L)
        tracker.onDetection(null, nowMs = 1_100L)

        // The screen is still where it was; only the clock decides when that stops being true.
        assertNotNull(tracker.geometry(1_200L))
    }

    @Test
    fun `reset forgets everything, including that a screen was ever seen`() {
        val tracker = ScreenTracker()
        tracker.onDetection(candidate(quad()), nowMs = 1_000L)
        tracker.reset()

        assertNull(tracker.geometry(1_000L))
        assertEquals(ScreenLoss.NeverDetected, tracker.loss(1_000L))
    }

    // --- Shared coordinate pipeline ---------------------------------------------------------

    @Test
    fun `an OCR box and a fingertip convert through the same transform`() {
        val tracker = ScreenTracker()
        val skewed = Quad(
            Point(120f, 90f), Point(520f, 130f),
            Point(560f, 470f), Point(80f, 430f),
        )
        tracker.onDetection(candidate(skewed), nowMs = 1_000L)
        val screen = tracker.geometry(1_000L)!!

        // A label at the centre of the screen and a fingertip at the same physical place must
        // land on each other in screen space, whatever the viewing angle.
        val centreInFrame = ScreenSpace.toFrame(screen, Point(0.5f, 0.5f))!!
        val fingertip = ScreenSpace.toScreen(screen, centreInFrame)!!

        assertEquals(0.5f, fingertip.x, 1e-2f)
        assertEquals(0.5f, fingertip.y, 1e-2f)
    }

    @Test
    fun `a box round-trips through screen space within tolerance`() {
        val tracker = ScreenTracker()
        tracker.onDetection(candidate(quad()), nowMs = 1_000L)
        val screen = tracker.geometry(1_000L)!!

        val original = TextBox(200f, 180f, 320f, 240f)
        val roundTripped = ScreenSpace.toFrame(screen, ScreenSpace.toScreen(screen, original)!!)!!

        assertEquals(original.left, roundTripped.left, 0.5f)
        assertEquals(original.top, roundTripped.top, 0.5f)
        assertEquals(original.right, roundTripped.right, 0.5f)
        assertEquals(original.bottom, roundTripped.bottom, 0.5f)
    }

    @Test
    fun `a fingertip just off the panel still counts as on screen`() {
        assertTrue(ScreenSpace.isOnScreen(Point(1.01f, 0.5f)))
        assertFalse(ScreenSpace.isOnScreen(Point(1.5f, 0.5f)))
    }

    // --- geometry versioning -----------------------------------------------------------------

    @Test
    fun `continuing to track the same screen keeps the geometry version`() {
        val tracker = ScreenTracker()
        tracker.onDetection(candidate(quad()), nowMs = 1_000L)
        val first = tracker.geometry(1_000L)!!.version

        tracker.onDetection(candidate(quad(offsetX = 15f)), nowMs = 1_100L)
        assertEquals(
            "a smoothed continuation is the same screen; results stay comparable",
            first,
            tracker.geometry(1_100L)!!.version,
        )
    }

    @Test
    fun `a restarted track gets a new geometry version`() {
        val tracker = ScreenTracker()
        tracker.onDetection(candidate(quad()), nowMs = 1_000L)
        val first = tracker.geometry(1_000L)!!.version

        tracker.onDetection(candidate(quad(offsetX = 600f)), nowMs = 1_100L)
        val second = tracker.geometry(1_100L)!!.version

        // Anything anchored under the old version describes a screen that is no longer there.
        assertNotEquals(first, second)
    }

    @Test
    fun `reset invalidates the version so nothing from before can be reused`() {
        val tracker = ScreenTracker()
        tracker.onDetection(candidate(quad()), nowMs = 1_000L)
        val before = tracker.geometry(1_000L)!!.version

        tracker.reset()
        tracker.onDetection(candidate(quad()), nowMs = 2_000L)

        assertNotEquals(before, tracker.geometry(2_000L)!!.version)
    }

    @Test
    fun `the centred mapping does not inflate a label's height on a tilted screen`() {
        val tracker = ScreenTracker()
        val skewed = Quad(
            Point(120f, 90f), Point(520f, 130f),
            Point(560f, 470f), Point(80f, 430f),
        )
        tracker.onDetection(candidate(skewed), nowMs = 1_000L)
        val screen = tracker.geometry(1_000L)!!

        // A wide, short label — the shape of a real kiosk button's text, and the shape the
        // corner-bounding form handles worst.
        val raw = TextBox(200f, 180f, 320f, 240f)
        val cornerBounded = ScreenSpace.toScreen(screen, raw)!!
        val centred = ScreenSpace.toScreenCentred(screen, raw)!!

        // Both agree on where the label is...
        assertEquals(cornerBounded.centerX, centred.centerX, 0.02f)
        assertEquals(cornerBounded.centerY, centred.centerY, 0.02f)

        // ...and disagree on how tall it is, which is what sizes the on-target zone. The
        // corner form was measured 38% taller; the centred form must be close to the true
        // extent, so the zone no longer announces arrival early.
        assertTrue(
            "centred height ${centred.height} should be well under corner-bounded ${cornerBounded.height}",
            centred.height < cornerBounded.height * 0.85f,
        )
        assertTrue("width should barely differ", centred.width > cornerBounded.width * 0.9f)
    }

    @Test
    fun `on a square-on screen the two mappings agree`() {
        // No perspective, no spread: the distinction must cost nothing in the ordinary case.
        val tracker = ScreenTracker()
        tracker.onDetection(candidate(quad()), nowMs = 1_000L)
        val screen = tracker.geometry(1_000L)!!

        val raw = TextBox(200f, 180f, 320f, 240f)
        val cornerBounded = ScreenSpace.toScreen(screen, raw)!!
        val centred = ScreenSpace.toScreenCentred(screen, raw)!!

        assertEquals(cornerBounded.width, centred.width, 1e-3f)
        assertEquals(cornerBounded.height, centred.height, 1e-3f)
    }

}
