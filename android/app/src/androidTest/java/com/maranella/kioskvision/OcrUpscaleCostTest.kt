package com.maranella.kioskvision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maranella.kioskvision.ocr.KioskOcrEngine
import com.maranella.kioskvision.ocr.OcrUpscale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What magnification actually buys, and what it costs, on the path the app uses most.
 *
 * ### Why ask
 *
 * The first full validation capture on the SM-S731B logged 88 OCR runs. The dominant kind —
 * a full scan cropped to the detected screen, magnified 2x — cost **p50 1202 ms, p90 2362 ms**,
 * and OCR is the app's dominant latency by an order of magnitude over every other stage. The
 * magnification is applied because the image is a crop (`regionUpscale`), **not** because the
 * text on it was measured to be small, so a screen of large labels pays for 2x it may not
 * need.
 *
 * R-16 measured the other side of that trade: 2x lifts exact recognition of small labels from
 * 52% to 67%, with the whole gain coming from text below about 20 px, while 34 px text already
 * reads at 0% CER and magnifying it only costs time.
 *
 * So the question is not "is 2x good" but "at what text size does it stop paying", and that is
 * a measurement, not an opinion. This reports the table. It asserts only that the measurement
 * happened and that magnification never *loses* text — the decision belongs to whoever reads
 * the numbers.
 */
@RunWith(AndroidJUnit4::class)
class OcrUpscaleCostTest {

    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    /** Ground truth from the asset's README. */
    private val names = listOf("ესპრესო", "ამერიკანო", "კაპუჩინო", "ლატე")
    private val amounts = listOf("3.50", "4.00", "5.00", "8.50", "0.20")

    @Test
    fun whatMagnificationBuysAndCosts() {
        val source = appContext.assets.open("ocr_test/coffee_machine_screen.png").use {
            BitmapFactory.decodeStream(it)
        }
        val engine = KioskOcrEngine.create(appContext)
        var anyComplete = false
        try {
            // Three framings, as fractions of the analysis frame's width. This is the variable
            // the user controls by how close they stand, and the one the README's arithmetic
            // turns into text pixels.
            for (fill in listOf(1.0f, 0.75f, 0.5f)) {
                val (frame, region) = frameWithScreen(source, fill)
                val textPx = (52f * region.width() / source.width)
                val results = mutableMapOf<Float, Triple<Long, Int, Int>>()

                for (upscale in listOf(1.0f, 1.5f, 2.0f)) {
                    val capped = OcrUpscale.cappedToPixelBudget(upscale, region.width(), region.height())
                    val started = SystemClock.elapsedRealtime()
                    val blocks = engine.recognize(frame, region, capped)
                    val elapsed = SystemClock.elapsedRealtime() - started
                    val text = blocks.joinToString("\n") { it.text }
                    val foundNames = names.count { it in text }
                    val foundAmounts = amounts.count { it in text }
                    results[upscale] = Triple(elapsed, foundNames, foundAmounts)
                    Log.i(
                        TAG,
                        "fill=%.0f%% nameHeight~%.0fpx @%.1fx(capped %.2f) %dms names=%d/4 amounts=%d/5"
                            .format(fill * 100, textPx, upscale, capped, elapsed, foundNames, foundAmounts),
                    )
                }
                frame.recycle()

                val one = results.getValue(1.0f)
                val two = results.getValue(2.0f)
                if (two.second == names.size) anyComplete = true
                Log.i(
                    TAG,
                    "fill=%.0f%% VERDICT 1x:%dms %d names / 2x:%dms %d names -> 2x costs %+dms for %+d names"
                        .format(fill * 100, one.first, one.second, two.first, two.second,
                            two.first - one.first, two.second - one.second),
                )
            }
        } finally {
            engine.close()
            source.recycle()
        }

        assertTrue(
            "no framing recovered all four drink names at 2x; the fixture or the engine is " +
                "not working and this table means nothing",
            anyComplete,
        )
    }

    /**
     * The kiosk screen drawn into an analysis-sized frame, occupying [fill] of its width, with
     * the crop the pipeline would hand to OCR.
     *
     * 720x1280 is what this device's analyser delivers. Drawing the screen smaller than the
     * frame is what standing further back does, and it is the only honest way to vary text
     * size without re-rendering the target at a different resolution.
     */
    private fun frameWithScreen(source: Bitmap, fill: Float): Pair<Bitmap, Rect> {
        val frame = createBitmap(FRAME_WIDTH, FRAME_HEIGHT)
        val canvas = Canvas(frame)
        canvas.drawColor(Color.rgb(18, 18, 20))
        val width = (FRAME_WIDTH * fill).toInt()
        val height = width * source.height / source.width
        val left = (FRAME_WIDTH - width) / 2
        val top = (FRAME_HEIGHT - height) / 2
        val scaled = source.scale(width, height)
        canvas.drawBitmap(scaled, left.toFloat(), top.toFloat(), null)
        scaled.recycle()
        return frame to Rect(left, top, left + width, top + height)
    }

    private companion object {
        const val TAG = "OcrUpscaleCost"
        const val FRAME_WIDTH = 720
        const val FRAME_HEIGHT = 1280
    }
}
