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

    /**
     * Maps a box into screen space by its centre and extents, not by its corners.
     *
     * [toScreen] takes the bounding box of the four mapped corners, which is right for
     * matching — a slightly generous box still contains the whole label. It is wrong for
     * guidance. Under a tilted view the two top corners of a wide, short label map to
     * different screen heights, and the enclosing box inherits that spread: measured at 3%
     * extra width but **38% extra height** on a moderately skewed screen.
     *
     * That inflation lands directly on the on-target zone, whose half-height is a fraction of
     * the target's height, so the app would announce that the finger had arrived while it was
     * still most of a label-height above the button. For a blind user that is the app being
     * confidently wrong at the exact moment it matters.
     *
     * Mapping the centre is exact, and taking each extent from the distance between opposite
     * edge midpoints measures the label along the screen's own axes instead of around it.
     */
    fun toScreenCentred(screen: TrackedScreen, box: TextBox): TextBox? {
        val h = screen.toScreenSpace ?: return null
        val centre = h.map(Point(box.centerX, box.centerY)) ?: return null
        val left = h.map(Point(box.left, box.centerY)) ?: return null
        val right = h.map(Point(box.right, box.centerY)) ?: return null
        val top = h.map(Point(box.centerX, box.top)) ?: return null
        val bottom = h.map(Point(box.centerX, box.bottom)) ?: return null

        val halfWidth = kotlin.math.hypot(right.x - left.x, right.y - left.y) / 2f
        val halfHeight = kotlin.math.hypot(bottom.x - top.x, bottom.y - top.y) / 2f
        return TextBox(
            left = centre.x - halfWidth,
            top = centre.y - halfHeight,
            right = centre.x + halfWidth,
            bottom = centre.y + halfHeight,
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
