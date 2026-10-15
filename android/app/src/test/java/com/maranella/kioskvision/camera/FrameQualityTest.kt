package com.maranella.kioskvision.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameQualityTest {

    private val side = 64
    private val white = 0xFFFFFFFF.toInt()
    private val dark = 0xFF202020.toInt()
    private val black = 0xFF000000.toInt()

    /** The threshold the pipeline uses to bring an OCR run forward. */
    private val changeThreshold = 0.08f

    private fun image(pixel: (x: Int, y: Int) -> Int) =
        IntArray(side * side) { pixel(it % side, it / side) }

    /** Rows of light text on a dark background, as a kiosk menu roughly looks. */
    private fun kioskScreen() = image { _, y -> if ((y / 4) % 2 == 0) white else dark }

    /** The same screen with a different set of rows lit: a real transition. */
    private fun otherScreen() = image { x, _ -> if ((x / 4) % 2 == 0) white else dark }

    /** A hand reaching in over the middle of the screen. */
    private fun IntArray.withHand(): IntArray = copyOf().also { pixels ->
        for (y in 20 until 52) {
            for (x in 12 until 44) pixels[y * side + x] = black
        }
    }

    private val handMask = FrameQuality.cellsAround(x = 28f, y = 36f, frameWidth = side, frameHeight = side, radius = 18f)

    private fun signature(pixels: IntArray) = FrameQuality.signature(pixels, side, side)

    // --- Screen-change detection ---------------------------------------------------------------

    @Test
    fun `a hand moving over the screen reads as a change until it is masked out`() {
        val before = signature(kioskScreen())
        val after = signature(kioskScreen().withHand())

        // This is the bug the mask exists for: the whole-frame comparison fires on the hand.
        assertTrue(
            "the raw difference should exceed the threshold",
            FrameQuality.maskedDistance(before, after) > changeThreshold,
        )
        assertTrue(
            "masking the hand must leave the screen looking unchanged",
            FrameQuality.maskedDistance(before, after, handMask) <= changeThreshold,
        )
    }

    @Test
    fun `a genuine screen change is still detected with the hand masked out`() {
        val before = signature(kioskScreen().withHand())
        val after = signature(otherScreen().withHand())
        assertTrue(FrameQuality.maskedDistance(before, after, handMask) > changeThreshold)
    }

    @Test
    fun `an unchanged screen has no distance`() {
        val signature = signature(kioskScreen())
        assertEquals(0f, FrameQuality.maskedDistance(signature, signature, handMask), 1e-6f)
    }

    @Test
    fun `masking everything compares nothing rather than dividing by zero`() {
        val before = signature(kioskScreen())
        val after = signature(otherScreen())
        val everything = BooleanArray(before.size) { true }
        assertEquals(0f, FrameQuality.maskedDistance(before, after, everything), 1e-6f)
    }

    @Test
    fun `the mask covers the cells under the fingertip and no more`() {
        val grid = FrameQuality.SIGNATURE_GRID
        val mask = FrameQuality.cellsAround(x = 8f, y = 8f, frameWidth = side, frameHeight = side, radius = 4f)
        // 8 px with 4 px cells is cell 2; a 4 px radius reaches cells 1..3 on each axis.
        assertTrue(mask[2 * grid + 2])
        assertTrue(mask[1 * grid + 1])
        assertTrue(mask[3 * grid + 3])
        assertTrue("the far corner is untouched", !mask[grid * grid - 1])
    }

    // --- Sharpness -------------------------------------------------------------------------------

    @Test
    fun `sharp detail scores far above a softened version of the same image`() {
        val sharp = image { x, y -> if ((x + y) % 2 == 0) white else black }
        val soft = image { x, y -> if (((x / 8) + (y / 8)) % 2 == 0) white else black }

        val sharpScore = FrameQuality.sharpness(sharp, side, side)
        val softScore = FrameQuality.sharpness(soft, side, side)
        assertTrue("sharp=$sharpScore soft=$softScore", sharpScore > 4 * softScore)
    }

    @Test
    fun `a blank frame has no edges`() {
        assertEquals(0f, FrameQuality.sharpness(image { _, _ -> white }, side, side), 1e-6f)
    }

    @Test
    fun `a frame too dark to judge scores zero rather than exploding`() {
        val nearlyBlack = image { x, y -> if ((x + y) % 2 == 0) black else 0xFF010101.toInt() }
        assertEquals(0f, FrameQuality.sharpness(nearlyBlack, side, side), 1e-6f)
    }

    @Test
    fun `sharpness does not simply track brightness`() {
        val bright = image { x, y -> if ((x + y) % 2 == 0) white else 0xFF808080.toInt() }
        val dim = image { x, y -> if ((x + y) % 2 == 0) 0xFF808080.toInt() else 0xFF404040.toInt() }
        // Both are checkerboards of the same relative contrast, at different exposures.
        assertEquals(FrameQuality.sharpness(bright, side, side), FrameQuality.sharpness(dim, side, side), 0.05f)
    }
}
