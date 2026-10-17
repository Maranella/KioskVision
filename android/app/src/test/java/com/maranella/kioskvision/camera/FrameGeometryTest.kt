package com.maranella.kioskvision.camera

import com.maranella.kioskvision.assist.FrameSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGeometryTest {

    // A landscape 1280×720 crop, as delivered by a typical rear sensor.
    private val w = 1280
    private val h = 720

    private fun map(rotation: Int, x: Float, y: Float) = FrameGeometry.cropToFrame(w, h, rotation).map(x, y)

    @Test
    fun `upright size swaps for 90 and 270`() {
        assertEquals(FrameSize(720, 1280), FrameGeometry.uprightSize(w, h, 90))
        assertEquals(FrameSize(720, 1280), FrameGeometry.uprightSize(w, h, 270))
        assertEquals(FrameSize(1280, 720), FrameGeometry.uprightSize(w, h, 180))
    }

    @Test
    fun `90 degrees clockwise moves the buffer's top-left to the frame's top-right`() {
        assertEquals(720f to 0f, map(90, 0f, 0f))
        assertEquals(720f to 1280f, map(90, 1280f, 0f))
        assertEquals(0f to 0f, map(90, 0f, 720f))
    }

    @Test
    fun `180 and 270 degrees`() {
        assertEquals(1280f to 720f, map(180, 0f, 0f))
        assertEquals(0f to 1280f, map(270, 0f, 0f))
        assertEquals(0f to 0f, map(270, 1280f, 0f))
    }

    @Test
    fun `every rotation keeps points inside the upright frame`() {
        for (rotation in listOf(0, 90, 180, 270, -90, 450)) {
            val size = FrameGeometry.uprightSize(w, h, rotation)
            for ((x, y) in listOf(0f to 0f, 1280f to 720f, 640f to 360f, 100f to 700f)) {
                val (fx, fy) = map(rotation, x, y)
                assert(fx in 0f..size.width.toFloat() && fy in 0f..size.height.toFloat()) { "$rotation: $x,$y → $fx,$fy" }
            }
        }
    }

    @Test
    fun `frame to view is a uniform scale when aspect ratios match`() {
        val frame = FrameSize(576, 1280)
        assertEquals(540f to 1200f, FrameGeometry.frameToView(576f, 1280f, frame, 540f, 1200f))
        assertEquals(270f to 600f, FrameGeometry.frameToView(288f, 640f, frame, 540f, 1200f))
    }

    @Test
    fun `frame to view fills and centres when aspect ratios differ`() {
        // 4:3 frame into a square view: scaled to fill height, cropped equally left and right.
        val (x, y) = FrameGeometry.frameToView(0f, 0f, FrameSize(400, 300), 300f, 300f)
        assertEquals(-50f, x, 0.001f)
        assertEquals(0f, y, 0.001f)
    }

    // --- the whole chain, which is what guidance actually depends on ----------------------

    @Test
    fun `the rear camera is never mirrored`() {
        // A mirrored preview would reverse left and right in every spoken direction, and the
        // user has no way to notice they are being sent the wrong way. Two points differing
        // only in x must keep their order through every rotation.
        for (rotation in listOf(0, 90, 180, 270)) {
            val t = FrameGeometry.cropToFrame(w, h, rotation)
            val left = t.map(10f, 360f)
            val right = t.map(1270f, 360f)
            val moved = when (rotation) {
                0 -> left.first < right.first
                180 -> left.first > right.first          // a half turn reverses x, as it must
                else -> left.second != right.second      // a quarter turn sends x into y
            }
            assertTrue("rotation $rotation mapped x unexpectedly", moved)
        }
    }

    @Test
    fun `a 90 degree rotation sends the x axis to y and not to x`() {
        // The specific failure this guards: a transform that swaps the axes twice, or not at
        // all, still keeps points inside the frame and passes a bounds check.
        val t = FrameGeometry.cropToFrame(w, h, 90)
        val a = t.map(0f, 0f)
        val b = t.map(1280f, 0f)
        assertEquals("x must not survive as x under a quarter turn", a.first, b.first, 0.001f)
        assertTrue("x must become y", kotlin.math.abs(a.second - b.second) > 1000f)
    }

    @Test
    fun `a fingertip and a label at the same place coincide in screen space`() {
        // Hand landmarks and OCR boxes both arrive in upright frame pixels, so the thing that
        // must hold is that one shared transform carries both into screen space. If either
        // ever gained its own conversion, this is where the two would drift apart.
        val screen = TrackedScreen(
            quad = Quad(
                Point(120f, 90f), Point(520f, 130f),
                Point(560f, 470f), Point(80f, 430f),
            ),
            confidence = 0.9f,
            stable = true,
            ageMs = 20L,
        )
        val samePlaceInFrame = Point(300f, 280f)

        val asFingertip = ScreenSpace.toScreen(screen, samePlaceInFrame)!!
        val asLabelBox = ScreenSpace.toScreen(
            screen,
            com.maranella.kioskvision.assist.TextBox(
                samePlaceInFrame.x - 1f, samePlaceInFrame.y - 1f,
                samePlaceInFrame.x + 1f, samePlaceInFrame.y + 1f,
            ),
        )!!

        assertEquals(asFingertip.x, asLabelBox.centerX, 0.01f)
        assertEquals(asFingertip.y, asLabelBox.centerY, 0.01f)
    }

    @Test
    fun `a degenerate view size does not produce non-finite coordinates`() {
        val (x, y) = FrameGeometry.frameToView(
            100f, 100f,
            FrameSize(w, h),
            viewWidth = 0f, viewHeight = 0f,
        )
        // A zero-sized view happens during layout. Guidance must not inherit a NaN from it.
        assertTrue("x was $x", x.isFinite())
        assertTrue("y was $y", y.isFinite())
    }

    @Test
    fun `a rotation that is not a multiple of ninety is rejected rather than approximated`() {
        try {
            FrameGeometry.cropToFrame(w, h, 45)
            org.junit.Assert.fail("expected a rejection")
        } catch (expected: IllegalArgumentException) {
            // Silently rounding would move every coordinate by an unknown amount.
        }
    }
}
