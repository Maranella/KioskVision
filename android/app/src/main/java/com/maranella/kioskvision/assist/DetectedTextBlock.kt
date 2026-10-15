package com.maranella.kioskvision.assist

import kotlin.math.hypot

/**
 * Axis-aligned box in **frame pixels** (see `camera/FrameGeometry.kt`): the upright analysis
 * frame, origin top-left, x to the right, y down.
 *
 * A plain Kotlin type rather than `android.graphics.RectF` so the matching and guidance logic
 * can be unit-tested on the JVM.
 */
data class TextBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun offset(dx: Float, dy: Float) = TextBox(left + dx, top + dy, right + dx, bottom + dy)

    fun centerDistanceTo(other: TextBox): Float =
        hypot(centerX - other.centerX, centerY - other.centerY)

    fun intersects(other: TextBox): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/**
 * One line of text found by OCR.
 *
 * [boundingBox] is the box around the recognized *text*, which is usually smaller than the
 * touchscreen button that contains it.
 */
data class DetectedTextBlock(
    /** Text as recognized, used for speech. */
    val text: String,
    val boundingBox: TextBox,
    /** OCR confidence in 0..1. */
    val confidence: Float,
) {
    /** Text prepared for matching; see [TextNormalizer]. */
    val normalizedText: String = TextNormalizer.normalize(text)
}

data class FrameSize(val width: Int, val height: Int) {
    val diagonal: Float get() = hypot(width.toFloat(), height.toFloat())
}
