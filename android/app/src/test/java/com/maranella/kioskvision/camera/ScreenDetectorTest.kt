package com.maranella.kioskvision.camera

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detector's job is as much to abstain as to find. A confidently wrong rectangle silently
 * relocates every target on the screen, and the user has no way to notice.
 */
class ScreenDetectorTest {

    private val width = 64
    private val height = 48

    /** Builds a luminance grid with a bright quadrilateral painted on a dark background. */
    private fun grid(
        quad: Quad,
        bright: Int = 230,
        dark: Int = 30,
    ): IntArray {
        val pixels = IntArray(width * height) { dark }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (inside(quad, Point(x + 0.5f, y + 0.5f))) pixels[y * width + x] = bright
            }
        }
        return pixels
    }

    private fun inside(quad: Quad, p: Point): Boolean {
        val c = quad.corners
        var sign = 0
        for (i in c.indices) {
            val a = c[i]
            val b = c[(i + 1) % c.size]
            val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
            val current = when {
                cross > 0f -> 1
                cross < 0f -> -1
                else -> 0
            }
            if (current != 0) {
                if (sign == 0) sign = current else if (sign != current) return false
            }
        }
        return true
    }

    private fun maxCornerError(expected: Quad, actual: Quad): Float =
        expected.corners.zip(actual.corners)
            .maxOf { (e, a) -> kotlin.math.hypot(e.x - a.x, e.y - a.y) }

    @Test
    fun `finds an axis-aligned bright panel and places its corners`() {
        val screen = Quad(
            Point(12f, 8f), Point(50f, 8f),
            Point(50f, 38f), Point(12f, 38f),
        )
        val candidate = ScreenDetector().detect(grid(screen), width, height)

        assertNotNull("expected the panel to be found", candidate)
        // Two grid cells of slack: the detector works on quantised pixels, and the corner is
        // the centre of a cell rather than a mathematical point.
        assertTrue(
            "corners off by ${maxCornerError(screen, candidate!!.quad)}",
            maxCornerError(screen, candidate.quad) <= 2.5f,
        )
        assertTrue("expected decent confidence, got ${candidate.confidence}", candidate.confidence > 0.5f)
    }

    @Test
    fun `finds a screen viewed at an angle`() {
        val skewed = Quad(
            Point(14f, 10f), Point(48f, 6f),
            Point(52f, 40f), Point(10f, 36f),
        )
        val candidate = ScreenDetector().detect(grid(skewed), width, height)

        assertNotNull("expected the angled panel to be found", candidate)
        assertTrue(
            "corners off by ${maxCornerError(skewed, candidate!!.quad)}",
            maxCornerError(skewed, candidate.quad) <= 3f,
        )
        // The rectified transform must be obtainable, or the detection is useless downstream.
        assertNotNull(Homography.quadToUnitSquare(candidate.quad))
    }

    @Test
    fun `abstains on a uniformly lit frame with no panel`() {
        val flat = IntArray(width * height) { 128 }
        assertNull(ScreenDetector().detect(flat, width, height))
    }

    @Test
    fun `abstains when the bright region is too small to be a usable screen`() {
        val speck = Quad(
            Point(30f, 22f), Point(34f, 22f),
            Point(34f, 26f), Point(30f, 26f),
        )
        assertNull(ScreenDetector().detect(grid(speck), width, height))
    }

    @Test
    fun `abstains when brightness fills almost the whole frame`() {
        val everything = Quad(
            Point(0f, 0f), Point(63f, 0f),
            Point(63f, 47f), Point(0f, 47f),
        )
        assertNull(ScreenDetector().detect(grid(everything), width, height))
    }

    @Test
    fun `scores a ragged non-rectangular bright blob below a clean panel`() {
        val panel = Quad(Point(12f, 8f), Point(50f, 8f), Point(50f, 38f), Point(12f, 38f))
        val clean = ScreenDetector().detect(grid(panel), width, height)!!

        // An L-shape: two overlapping bright rectangles, which no quadrilateral fits well.
        val ragged = IntArray(width * height) { 30 }
        for (y in 8 until 38) for (x in 12 until 30) ragged[y * width + x] = 230
        for (y in 8 until 20) for (x in 30 until 52) ragged[y * width + x] = 230
        val blob = ScreenDetector().detect(ragged, width, height)

        if (blob != null) {
            assertTrue(
                "ragged blob scored ${blob.confidence} against clean panel ${clean.confidence}",
                blob.confidence < clean.confidence,
            )
        }
    }

    @Test
    fun `rejects a grid whose dimensions do not match its contents`() {
        assertNull(ScreenDetector().detect(IntArray(10), width, height))
    }

    @Test
    fun `rejects a grid too small to hold a screen`() {
        assertNull(ScreenDetector().detect(IntArray(4 * 4) { 200 }, 4, 4))
    }
}
