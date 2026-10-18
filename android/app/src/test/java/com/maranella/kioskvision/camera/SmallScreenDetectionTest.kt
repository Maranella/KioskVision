package com.maranella.kioskvision.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The small-screen case: a coffee machine, a vending keypad, a parking meter.
 *
 * `ScreenDetectorCoverageTest` covers a panel that fills the frame. This covers the opposite
 * end, which the detector's lower bound was never examined against. `MIN_COVERAGE` is 8 % of
 * frame **area**, chosen for a payment terminal; its comment says a screen smaller than that
 * "is too far away to read anyway". That reasoning assumes a kiosk-sized panel.
 *
 * Work the geometry through for a small display. A phone camera with a ~65° horizontal field
 * sees roughly 52 cm across at 40 cm away, and the upright 9:16 frame is about 52 × 92 cm. A
 * 10 × 6 cm machine display is 60 cm² of 4784 cm², which is **1.3 %** — six times under the
 * bound. Halving the distance to 20 cm still only reaches about 5 %, and at that range the
 * user's hand can no longer be in frame at the ~40 % of frame width MediaPipe needs (R-13).
 * So for a small screen the bound may be unreachable at *every* usable working distance.
 *
 * These tests do not assert that the bound is wrong — that is a product decision about which
 * machines are in scope, and it needs a real frame. They assert that when it refuses, it
 * **says why and reports the coverage it measured**, because a bare null sent one device
 * session at a coffee machine home with no information at all.
 */
class SmallScreenDetectionTest {

    private val width = 64
    private val height = 48

    /** A bright rectangle of the given size on a dark housing, as the grid would see it. */
    private fun panel(panelWidth: Int, panelHeight: Int, panelLuma: Int = 230, surround: Int = 30): IntArray {
        val pixels = IntArray(width * height) { surround }
        val left = (width - panelWidth) / 2
        val top = (height - panelHeight) / 2
        for (y in top until top + panelHeight) {
            for (x in left until left + panelWidth) pixels[y * width + x] = panelLuma
        }
        return pixels
    }

    @Test
    fun `a small display is refused, and the refusal names the size`() {
        val detector = ScreenDetector()
        // 12 x 8 of 64 x 48 = 3.1 % of the frame: a small machine display at a distance that
        // also keeps the user's hand in view.
        val candidate = detector.detect(panel(12, 8), width, height)

        assertNull("below MIN_COVERAGE the detector refuses, which is current behaviour", candidate)
        assertEquals(
            "the reason must be the size, not a generic failure",
            ScreenRejection.TooSmall,
            detector.lastRejection,
        )
        assertTrue(
            "the measured coverage must be reported, because the number is the diagnosis: " +
                "got ${detector.lastCoverage}",
            detector.lastCoverage > 0f && detector.lastCoverage < ScreenDetector.MIN_COVERAGE,
        )
    }

    /**
     * The coverages a real coffee machine produced — and why they are still refused.
     *
     * At the distance where the app read that machine well (42-46 recognised lines, a target
     * selected, guidance running) the detector reported coverage of 0.055-0.079 against the
     * 0.08 bound and refused 23 times in one session. Lowering the bound to 0.04 was tried, and
     * **reverted**: it admitted a ceiling light, a window and a laptop screen across a
     * cafeteria, where confidence measured 0.56-0.62 against the 0.89-0.97 the real machine
     * scores, and `MIN_CONFIDENCE` is only 0.45. OCR was then pointed at furniture, returning
     * 433-657 blocks per run at 12-22 seconds each, every one discarded as noise.
     *
     * Coverage alone cannot tell a small screen from a bright patch of wall; they are the same
     * size in frame. The confidence score might, but it had never been computed for a refused
     * candidate. It is now, for diagnosis only, which is what the next device session needs in
     * order to decide this on evidence rather than on another guess.
     */
    @Test
    fun `a machine-sized screen is refused, and what it would have scored is recorded`() {
        val detector = ScreenDetector()
        // ~5.5 % of frame area, the low end of what the real machine produced.
        val candidate = detector.detect(panel(16, 11), width, height)

        assertNull("the bound is unchanged until there is evidence for moving it", candidate)
        assertEquals(ScreenRejection.TooSmall, detector.lastRejection)
        assertTrue(
            "the refused candidate must still be scored, or the question stays unanswerable: " +
                "got ${detector.lastRejectedConfidence}",
            detector.lastRejectedConfidence > 0f,
        )
    }

    @Test
    fun `a candidate far below the probe floor is not scored at all`() {
        val detector = ScreenDetector()
        // Scoring every speck would cost a hull on frames that can never be a screen.
        detector.detect(panel(5, 4), width, height)
        assertEquals(-1f, detector.lastRejectedConfidence, 0.001f)
    }

    @Test
    fun `a display just over the bound is accepted`() {
        val detector = ScreenDetector()
        // 24 x 16 = 12.5 %, comfortably over. The control: the refusal above is about size and
        // nothing else about this scene.
        val candidate = detector.detect(panel(24, 16), width, height)

        assertNotNull("a large enough panel on a dark housing is the case this was built for", candidate)
        assertNull(detector.lastRejection)
    }

    @Test
    fun `a dark display in a bright room is found as the room, not the screen`() {
        val detector = ScreenDetector()
        // Inverted polarity: a dark UI behind glass, a bright kitchen behind it. Otsu splits on
        // brightness alone, so the largest bright region is everything *except* the display.
        val candidate = detector.detect(panel(24, 16, panelLuma = 35, surround = 225), width, height)

        // It does not fail — it succeeds on the wrong region, which is worse, and is why the
        // overlay now prints coverage and the confidence parts rather than a single score.
        assertNotNull("the surround is bright and large, so something is always found", candidate)
        assertTrue(
            "the found region is the surround: it covers far more than the display's 12.5 %",
            candidate!!.coverage > 0.5f,
        )
    }

    @Test
    fun `a bright reflection larger than the display wins`() {
        val detector = ScreenDetector()
        // A chrome machine body throwing a specular highlight bigger than the screen itself.
        val pixels = panel(16, 10)
        for (y in 2 until 18) {
            for (x in 2 until 26) pixels[y * width + x] = 250
        }
        val candidate = detector.detect(pixels, width, height)

        assertNotNull(candidate)
        // The highlight is at the top left; the display is centred. Whichever is larger wins,
        // and nothing in the detector prefers a display. Documented rather than fixed: the fix
        // needs a real frame to evaluate against.
        assertTrue(
            "the brightest largest region is the reflection, so the quad is drawn around it",
            candidate!!.quad.corners.minOf { it.y } < height / 3f,
        )
    }

    @Test
    fun `a flat image is refused with a reason rather than silently`() {
        val detector = ScreenDetector()
        detector.detect(IntArray(width * height) { 128 }, width, height)
        assertNotNull("even a featureless frame must record why it produced nothing", detector.lastRejection)
    }
}
