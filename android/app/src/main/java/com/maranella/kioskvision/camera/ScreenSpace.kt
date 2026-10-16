package com.maranella.kioskvision.camera

import com.maranella.kioskvision.assist.TextBox

/**
 * The one place that converts between camera frame pixels and canonical screen space.
 *
 * **Canonical screen space** is the unit square over the detected kiosk screen: (0,0) at its
 * top-left corner, (1,1) at its bottom-right, independent of how far away the phone is or what
 * angle it is held at. Two things make it worth having. A target expressed here survives the
 * user shifting the phone, and an OCR box and a fingertip expressed here can be compared at
 * all — in frame pixels they are only comparable when the screen happens to face the camera
 * squarely.
 *
 * Everything that needs both OCR geometry and hand geometry must come through here, so the two
 * can never drift apart by using different conversions.
 */
object ScreenSpace {

    /** Maps a frame-pixel point into screen space. Null when the screen is unusable. */
    fun toScreen(screen: TrackedScreen, point: Point): Point? =
        screen.toScreenSpace?.map(point)

    /** Maps a screen-space point back into frame pixels, for drawing and for guidance. */
    fun toFrame(screen: TrackedScreen, point: Point): Point? =
        screen.toScreenSpace?.inverse()?.map(point)

    /**
     * Maps an axis-aligned frame-pixel box into screen space.
     *
     * Under perspective a rectangle becomes a quadrilateral, so this returns the bounding box
     * of the four mapped corners. That is a slight over-estimate, growing with the viewing
     * angle. It is the right trade for label boxes — a box that is marginally too large keeps
     * the whole label inside it, whereas an exact quadrilateral would complicate every
     * comparison downstream for accuracy nobody needs when deciding which word was read.
     *
     * It is deliberately **not** used for the fingertip, which is a point and is mapped as one.
     */
    fun toScreen(screen: TrackedScreen, box: TextBox): TextBox? {
        val h = screen.toScreenSpace ?: return null
        val corners = listOf(
            Point(box.left, box.top),
            Point(box.right, box.top),
            Point(box.right, box.bottom),
            Point(box.left, box.bottom),
        ).map { h.map(it) ?: return null }

        return TextBox(
            left = corners.minOf { it.x },
            top = corners.minOf { it.y },
            right = corners.maxOf { it.x },
            bottom = corners.maxOf { it.y },
        )
    }

    /** Maps a screen-space box back to frame pixels, as a bounding box. */
    fun toFrame(screen: TrackedScreen, box: TextBox): TextBox? {
        val inverse = screen.toScreenSpace?.inverse() ?: return null
        val corners = listOf(
            Point(box.left, box.top),
            Point(box.right, box.top),
            Point(box.right, box.bottom),
            Point(box.left, box.bottom),
        ).map { inverse.map(it) ?: return null }

        return TextBox(
            left = corners.minOf { it.x },
            top = corners.minOf { it.y },
            right = corners.maxOf { it.x },
            bottom = corners.maxOf { it.y },
        )
    }

    /**
     * Whether a screen-space point is on the screen at all.
     *
     * A small margin is allowed because a fingertip resting on the bezel is still meaningfully
     * "at the edge of the screen", and telling the user their finger has left the screen when
     * it is a millimetre outside would be both true and useless.
     */
    fun isOnScreen(point: Point, margin: Float = EDGE_MARGIN): Boolean =
        point.x >= -margin && point.x <= 1f + margin &&
            point.y >= -margin && point.y <= 1f + margin

    private const val EDGE_MARGIN = 0.04f
}
