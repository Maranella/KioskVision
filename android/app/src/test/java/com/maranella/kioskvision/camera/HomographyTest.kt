package com.maranella.kioskvision.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The homography is what makes an angled kiosk screen usable, so its failure modes matter as
 * much as its successes: a silently wrong transform would move every target by a few
 * centimetres and the app would still sound confident.
 */
class HomographyTest {

    private fun assertPointEquals(expected: Point, actual: Point?, tolerance: Float = 1e-3f) {
        assertNotNull("expected a mapped point", actual)
        assertEquals(expected.x, actual!!.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
    }

    @Test
    fun `maps an axis-aligned rectangle onto the unit square`() {
        val quad = Quad(
            Point(100f, 200f), Point(500f, 200f),
            Point(500f, 600f), Point(100f, 600f),
        )
        val h = Homography.quadToUnitSquare(quad)!!

        assertPointEquals(Point(0f, 0f), h.map(Point(100f, 200f)))
        assertPointEquals(Point(1f, 0f), h.map(Point(500f, 200f)))
        assertPointEquals(Point(1f, 1f), h.map(Point(500f, 600f)))
        assertPointEquals(Point(0f, 1f), h.map(Point(100f, 600f)))
        assertPointEquals(Point(0.5f, 0.5f), h.map(Point(300f, 400f)))
    }

    @Test
    fun `a perspective-distorted screen still maps corners exactly`() {
        // A trapezoid: the far edge of the screen is narrower, as when looking from below.
        val quad = Quad(
            Point(200f, 100f), Point(600f, 140f),
            Point(700f, 500f), Point(120f, 460f),
        )
        val h = Homography.quadToUnitSquare(quad)!!

        assertPointEquals(Point(0f, 0f), h.map(quad.topLeft))
        assertPointEquals(Point(1f, 0f), h.map(quad.topRight))
        assertPointEquals(Point(1f, 1f), h.map(quad.bottomRight))
        assertPointEquals(Point(0f, 1f), h.map(quad.bottomLeft))
    }

    @Test
    fun `perspective is not affine, so the centre of the quad is not the centre of the screen`() {
        val quad = Quad(
            Point(200f, 100f), Point(600f, 140f),
            Point(700f, 500f), Point(120f, 460f),
        )
        val h = Homography.quadToUnitSquare(quad)!!
        val centroid = Point(
            quad.corners.map { it.x }.average().toFloat(),
            quad.corners.map { it.y }.average().toFloat(),
        )
        val mapped = h.map(centroid)!!

        // If an affine transform were used instead, this would land exactly on (0.5, 0.5) and
        // every off-centre target would inherit the error.
        val offset = kotlin.math.hypot(mapped.x - 0.5f, mapped.y - 0.5f)
        assertTrue("expected a projective shift, got $mapped", offset > 1e-3f)
    }

    @Test
    fun `inverse round-trips a point back to where it started`() {
        val quad = Quad(
            Point(200f, 100f), Point(600f, 140f),
            Point(700f, 500f), Point(120f, 460f),
        )
        val forward = Homography.quadToUnitSquare(quad)!!
        val back = forward.inverse()!!

        val original = Point(430f, 275f)
        val roundTripped = back.map(forward.map(original)!!)
        assertPointEquals(original, roundTripped, tolerance = 1e-2f)
    }

    @Test
    fun `three collinear corners cannot define a screen`() {
        val degenerate = listOf(
            Point(0f, 0f), Point(100f, 0f), Point(200f, 0f), Point(50f, 90f),
        )
        assertNull(
            Homography.fromCorrespondences(
                degenerate,
                listOf(Point(0f, 0f), Point(1f, 0f), Point(1f, 1f), Point(0f, 1f)),
            ),
        )
    }

    @Test
    fun `a collapsed quadrilateral is rejected rather than silently inverted`() {
        val collapsed = Quad(
            Point(10f, 10f), Point(10f, 10f),
            Point(10f, 10f), Point(10f, 10f),
        )
        assertNull(Homography.quadToUnitSquare(collapsed))
    }

    @Test
    fun `corner ordering survives rotation past forty-five degrees`() {
        // A square rotated by about 50 degrees. The common "sum and difference of coordinates"
        // ordering trick mislabels corners here; angular ordering does not.
        val centre = Point(300f, 300f)
        val radius = 100f
        val points = (0 until 4).map { i ->
            val angle = Math.toRadians(50.0 + i * 90.0)
            Point(
                centre.x + radius * kotlin.math.cos(angle).toFloat(),
                centre.y + radius * kotlin.math.sin(angle).toFloat(),
            )
        }
        val quad = Quad.ordered(points.shuffled())!!

        // Whatever the input order, the result must be a non-degenerate clockwise quad that
        // the homography accepts.
        assertTrue("expected clockwise winding, area was ${quad.area}", quad.area > 0f)
        assertNotNull(Homography.quadToUnitSquare(quad))
    }

    @Test
    fun `ordering rejects anything that is not four points`() {
        assertNull(Quad.ordered(listOf(Point(0f, 0f), Point(1f, 0f), Point(1f, 1f))))
    }
}
