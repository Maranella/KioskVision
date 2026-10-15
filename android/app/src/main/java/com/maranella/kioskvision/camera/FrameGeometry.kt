package com.maranella.kioskvision.camera

import com.maranella.kioskvision.assist.FrameSize
import kotlin.math.max

/**
 * Coordinate systems of the live-assist pipeline.
 *
 * 1. **Buffer**: pixels of the `ImageProxy` as delivered by the sensor, not rotated.
 * 2. **Frame**: the `ImageProxy.cropRect` rotated upright by `imageInfo.rotationDegrees`
 *    (clockwise). Origin top-left, x right, y down, in pixels. OCR boxes, the fingertip, the
 *    target and all guidance geometry live here.
 * 3. **View**: `PreviewView` pixels. Preview and ImageAnalysis are bound in one `UseCaseGroup`
 *    with the PreviewView's `ViewPort`, so the analysis crop shows the same field of view as
 *    the preview and frame → view is a uniform scale ([frameToView]). Only the developer
 *    overlay uses view coordinates; guidance never does.
 *
 * The kiosk screen is approximated by the frame. That is only valid when the screen faces the
 * camera roughly head-on; no perspective (homography) correction is done yet.
 */
object FrameGeometry {

    /** `x' = a·x + b·y + c`, `y' = d·x + e·y + f`. */
    data class AffineTransform(
        val a: Float, val b: Float, val c: Float,
        val d: Float, val e: Float, val f: Float,
    ) {
        fun map(x: Float, y: Float): Pair<Float, Float> = (a * x + b * y + c) to (d * x + e * y + f)

        /** Values in `android.graphics.Matrix.setValues` order. */
        fun toMatrixValues(): FloatArray = floatArrayOf(a, b, c, d, e, f, 0f, 0f, 1f)
    }

    fun uprightSize(cropWidth: Int, cropHeight: Int, rotationDegrees: Int): FrameSize =
        when (normalizeRotation(rotationDegrees)) {
            90, 270 -> FrameSize(cropHeight, cropWidth)
            else -> FrameSize(cropWidth, cropHeight)
        }

    /**
     * Maps crop-relative buffer pixels (origin at the crop's top-left) to frame pixels by
     * rotating clockwise by [rotationDegrees].
     */
    fun cropToFrame(cropWidth: Int, cropHeight: Int, rotationDegrees: Int): AffineTransform {
        val w = cropWidth.toFloat()
        val h = cropHeight.toFloat()
        return when (normalizeRotation(rotationDegrees)) {
            0 -> AffineTransform(1f, 0f, 0f, 0f, 1f, 0f)
            90 -> AffineTransform(0f, -1f, h, 1f, 0f, 0f)
            180 -> AffineTransform(-1f, 0f, w, 0f, -1f, h)
            270 -> AffineTransform(0f, 1f, 0f, -1f, 0f, w)
            else -> error("unreachable")
        }
    }

    /**
     * Frame pixels to view pixels for a view that shows the frame scaled to fill and centred
     * (PreviewView FILL_CENTER). With a shared ViewPort the aspect ratios match and this is a
     * plain scale; the centring only matters if they differ slightly due to rounding.
     */
    fun frameToView(x: Float, y: Float, frame: FrameSize, viewWidth: Float, viewHeight: Float): Pair<Float, Float> {
        val scale = max(viewWidth / frame.width, viewHeight / frame.height)
        val offsetX = (viewWidth - frame.width * scale) / 2f
        val offsetY = (viewHeight - frame.height * scale) / 2f
        return (x * scale + offsetX) to (y * scale + offsetY)
    }

    private fun normalizeRotation(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        require(normalized % 90 == 0) { "Rotation must be a multiple of 90, was $degrees" }
        return normalized
    }
}
