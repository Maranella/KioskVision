package com.maranella.kioskvision.camera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Cheap, pure measurements over frame pixels: is this frame sharp enough to read, and has the
 * kiosk screen content changed?
 *
 * Kept free of Android types so the thresholds can be unit-tested on synthetic pixel data.
 * All functions take packed ARGB ints as produced by `Bitmap.getPixels`.
 */
object FrameQuality {

    /** Cells per side of the screen-change signature. */
    const val SIGNATURE_GRID = 16

    /** Luminance 0..1 of a packed ARGB pixel (Rec. 601, matching the old implementation). */
    fun luminance(pixel: Int): Float =
        (0.299f * (pixel shr 16 and 0xFF) + 0.587f * (pixel shr 8 and 0xFF) + 0.114f * (pixel and 0xFF)) / 255f

    /**
     * Mean luminance per cell of a [grid]×[grid] division of the given pixels.
     *
     * The caller passes a downscaled copy of the frame; the grid is coarse enough that small
     * OCR-level detail is irrelevant and only real content changes move a cell.
     */
    fun signature(pixels: IntArray, width: Int, height: Int, grid: Int = SIGNATURE_GRID): FloatArray {
        val sums = FloatArray(grid * grid)
        val counts = IntArray(grid * grid)
        for (y in 0 until height) {
            val row = (y * grid / height).coerceIn(0, grid - 1) * grid
            for (x in 0 until width) {
                val cell = row + (x * grid / width).coerceIn(0, grid - 1)
                sums[cell] += luminance(pixels[y * width + x])
                counts[cell]++
            }
        }
        return FloatArray(sums.size) { if (counts[it] == 0) 0f else sums[it] / counts[it] }
    }

    /**
     * Mean absolute luminance difference between two signatures, ignoring cells marked in
     * [ignored].
     *
     * The hand is masked out because it is the one thing in the frame that is *expected* to
     * move: a finger sweeping across the view changes many cells by far more than a kiosk
     * screen transition does, and without the mask every reach for a button reads as a new
     * screen. Returns 0 when every cell is masked (nothing left to compare).
     */
    fun maskedDistance(a: FloatArray, b: FloatArray, ignored: BooleanArray? = null): Float {
        require(a.size == b.size) { "signature sizes differ: ${a.size} vs ${b.size}" }
        var sum = 0f
        var counted = 0
        for (i in a.indices) {
            if (ignored != null && ignored[i]) continue
            sum += abs(a[i] - b[i])
            counted++
        }
        return if (counted == 0) 0f else sum / counted
    }

    /**
     * Marks the signature cells covered by a disc of [radius] frame-pixels around
     * ([x], [y]) — the user's fingertip and the hand behind it.
     *
     * Returns null when there is no fingertip, so the caller compares the whole frame.
     */
    fun cellsAround(
        x: Float,
        y: Float,
        frameWidth: Int,
        frameHeight: Int,
        radius: Float,
        grid: Int = SIGNATURE_GRID,
    ): BooleanArray {
        val mask = BooleanArray(grid * grid)
        val cellWidth = frameWidth.toFloat() / grid
        val cellHeight = frameHeight.toFloat() / grid
        val firstCol = max(0, ((x - radius) / cellWidth).toInt())
        val lastCol = min(grid - 1, ((x + radius) / cellWidth).toInt())
        val firstRow = max(0, ((y - radius) / cellHeight).toInt())
        val lastRow = min(grid - 1, ((y + radius) / cellHeight).toInt())
        for (row in firstRow..lastRow) {
            for (col in firstCol..lastCol) mask[row * grid + col] = true
        }
        return mask
    }

    /**
     * Relative edge energy of the given pixels: the mean absolute luminance gradient divided by
     * the mean luminance.
     *
     * Dividing by brightness makes the measure comparable between a bright and a dim kiosk, so
     * a drop really does mean the image went soft rather than dark. It is a *relative* measure
     * only — the absolute value depends on the content, which is why the pipeline compares each
     * frame against the sharpness recorded when focus last converged rather than a fixed number.
     */
    fun sharpness(pixels: IntArray, width: Int, height: Int): Float {
        if (width < 2 || height < 2) return 0f
        var gradient = 0.0
        var brightness = 0.0
        for (y in 0 until height - 1) {
            val row = y * width
            for (x in 0 until width - 1) {
                val here = luminance(pixels[row + x])
                gradient += abs(luminance(pixels[row + x + 1]) - here)
                gradient += abs(luminance(pixels[row + width + x]) - here)
                brightness += here
            }
        }
        val samples = (width - 1) * (height - 1)
        val meanBrightness = brightness / samples
        if (meanBrightness < MIN_USABLE_BRIGHTNESS) return 0f
        return (gradient / (2 * samples) / meanBrightness).toFloat()
    }

    /** Below this mean luminance the frame is too dark for sharpness to mean anything. */
    private const val MIN_USABLE_BRIGHTNESS = 0.04
}
