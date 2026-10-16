package com.maranella.kioskvision.camera

import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The close-up case: a kiosk screen that fills the camera frame.
 *
 * This is not an edge case. The user is told to come close enough for the text to be legible,
 * and at that distance the panel covers most or all of the view — its edges are outside the
 * frame, so there is nothing to find a quadrilateral *from*.
 *
 * The detector's upper coverage bound was meant to reject a camera pressed against a lit
 * surface. It rejected this too, and refusing to guide in the most ordinary situation is worse
 * than the failure it was guarding against.
 */
class ScreenDetectorCoverageTest {

    private val width = 64
    private val height = 48

    /** A bright panel covering the whole frame, with dark text drawn on it. */
    private fun filledScreenWithText(): IntArray {
        val pixels = IntArray(width * height) { 235 }
        // Three rows of "text": dark runs, as a real heading and two labels would read once
        // the frame is downscaled to the detection grid.
        for (row in listOf(10, 24, 36)) {
            for (y in row until row + 3) {
                for (x in 12 until 52) {
                    if ((x / 2) % 2 == 0) pixels[y * width + x] = 25
                }
            }
        }
        return pixels
    }

    @Test
    fun `a screen filling the frame is still a screen`() {
        val candidate = ScreenDetector().detect(filledScreenWithText(), width, height)

        assertNotNull(
            "a kiosk screen seen close up fills the frame; refusing it refuses the normal case",
            candidate,
        )
    }

    @Test
    fun `a screen filling the frame yields a usable transform`() {
        val candidate = ScreenDetector().detect(filledScreenWithText(), width, height)!!
        // Its edges were never seen, so the quadrilateral is the frame and the transform is an
        // identity-like normalisation. That is still the right basis for guidance: the OCR box
        // and the fingertip are in the same frame, so they remain comparable.
        assertNotNull(Homography.quadToUnitSquare(candidate.quad))
    }
}
