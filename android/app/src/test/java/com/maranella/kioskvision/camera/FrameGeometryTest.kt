package com.maranella.kioskvision.camera

import com.maranella.kioskvision.assist.FrameSize
import org.junit.Assert.assertEquals
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
}
