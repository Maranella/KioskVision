package com.maranella.kioskvision.camera

import org.junit.Test
import kotlin.random.Random

/**
 * How long one detection takes. Not a benchmark for its own sake: detection runs on the same
 * thread as fingertip tracking, so if it is slow it does not merely cost time, it starves the
 * pipeline that has to keep up with the camera.
 */
class ScreenDetectorCostTest {

    private val width = 64
    private val height = 48

    /** A textured panel: the worst case, because texture makes the hull complex. */
    private fun texturedPanel(): IntArray {
        val random = Random(7)
        val pixels = IntArray(width * height) { 30 }
        for (y in 6 until height - 6) {
            for (x in 8 until width - 8) {
                pixels[y * width + x] = if (random.nextInt(10) == 0) 40 else 230
            }
        }
        return pixels
    }

    @Test
    fun `one detection is cheap enough to share a thread with fingertip tracking`() {
        val detector = ScreenDetector()
        val grid = texturedPanel()
        repeat(20) { detector.detect(grid, width, height) }

        val runs = 50
        val start = System.nanoTime()
        repeat(runs) { detector.detect(grid, width, height) }
        val perCallMs = (System.nanoTime() - start) / 1_000_000.0 / runs

        // Measured at 0.35 ms on a development machine. The budget is deliberately loose,
        // because this guards against an algorithmic change — an unbounded hull search, say —
        // rather than against machine-to-machine variation. Detection shares a thread with the
        // hand tracking that has to keep up with the camera, so slowness here is not merely
        // slow: it starves the pipeline.
        org.junit.Assert.assertTrue(
            "one detection took %.2f ms, which is too slow to share the camera thread"
                .format(perCallMs),
            perCallMs < 5.0,
        )
    }

    @Test
    fun `the finer grid the pipeline now uses is still cheap enough`() {
        // `LiveAssistPipeline.DETECTION_GRID` is 128, where this file's other case measures
        // the 64-wide signature grid. Four times the pixels; the budget is unchanged, because
        // what it guards against is an algorithmic change rather than this factor.
        val detector = ScreenDetector()
        val side = 128
        val random = Random(7)
        val grid = IntArray(side * side) { 30 }
        for (y in 12 until side - 12) {
            for (x in 16 until side - 16) {
                grid[y * side + x] = if (random.nextInt(10) == 0) 40 else 230
            }
        }
        repeat(20) { detector.detect(grid, side, side) }

        val runs = 50
        val start = System.nanoTime()
        repeat(runs) { detector.detect(grid, side, side) }
        val perCallMs = (System.nanoTime() - start) / 1_000_000.0 / runs

        org.junit.Assert.assertTrue(
            "one 128x128 detection took %.2f ms, too slow to share the camera thread"
                .format(perCallMs),
            perCallMs < 5.0,
        )
        println("128x128 detection: %.2f ms".format(perCallMs))
    }
}
