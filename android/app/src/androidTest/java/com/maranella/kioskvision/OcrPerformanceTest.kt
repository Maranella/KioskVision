package com.maranella.kioskvision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maranella.kioskvision.assist.DetectedTextBlock
import com.maranella.kioskvision.assist.TargetMatch
import com.maranella.kioskvision.assist.TargetMatcher
import com.maranella.kioskvision.assist.TextBox
import com.maranella.kioskvision.hand.HandLandmarkTracker
import com.maranella.kioskvision.ocr.KioskOcrEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Measures what the live-assist pipeline actually costs on this device, so the OCR scheduling
 * constants are chosen from numbers rather than guesses.
 *
 * Nothing here is a correctness test of the pipeline; it answers four questions:
 *  1. How does Tesseract latency scale with input resolution, and what does accuracy cost?
 *  2. Is OCR over a crop around the target cheaper than over the whole frame?
 *  3. What does hand tracking cost per frame on its own?
 *  4. How far does hand tracking degrade while OCR runs beside it, with and without the
 *     background thread priority the pipeline now sets?
 *
 * Run with:
 * `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.maranella.kioskvision.OcrPerformanceTest`
 */
@RunWith(AndroidJUnit4::class)
class OcrPerformanceTest {

    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    /** The analysis frame size the pipeline actually receives on this device. */
    private val frameWidth = 576
    private val frameHeight = 1280

    /** Button labels, with the centre each one is drawn at in the full-size frame. */
    private val buttons = listOf(
        "გადახდა" to (150f to 700f),
        "ბალანსი" to (430f to 700f),
        "მობილური" to (150f to 880f),
        "დახმარება" to (430f to 880f),
    )

    /**
     * A kiosk screen close to what the camera sees: a heading, four button labels, and small
     * print that OCR should read with low confidence. Text sizes are scaled from the real
     * 576×1280 frames captured on this device.
     */
    private fun renderKioskScreen(): Bitmap {
        val bitmap = createBitmap(frameWidth, frameHeight)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 30); textSize = 44f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("რას გინდა გააკეთო?", frameWidth / 2f, 480f, heading)

        val button = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(10, 10, 20); textSize = 30f; textAlign = Paint.Align.CENTER
        }
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 130, 150); style = Paint.Style.STROKE; strokeWidth = 2f
        }
        for ((text, centre) in buttons) {
            canvas.drawRect(centre.first - 120f, centre.second - 60f, centre.first + 120f, centre.second + 60f, box)
            canvas.drawText(text, centre.first, centre.second - (button.descent() + button.ascent()) / 2, button)
        }

        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(90, 90, 100); textSize = 16f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("აირჩიე სასურველი მომსახურება", frameWidth / 2f, 1040f, small)
        canvas.drawText("ტერმინალი 0042 • თბილისი", frameWidth / 2f, 1080f, small)
        return bitmap
    }

    /** The same screen surrounded by the clutter a real camera frame contains. */
    private fun renderClutteredScreen(): Bitmap {
        val bitmap = renderKioskScreen()
        val canvas = Canvas(bitmap)
        val chrome = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(70, 70, 80); textSize = 17f; textAlign = Paint.Align.LEFT
        }
        val noise = listOf(
            "Preview", "File", "Edit", "View", "Go", "Tools", "Window", "Help",
            "Georgian Self-service", "English", "ქართული", "12:45", "15 ოქტომბერი",
            "www.tbcpay.ge", "დახურვა", "უკან დაბრუნება",
        )
        noise.forEachIndexed { i, text ->
            canvas.drawText(text, 12f + (i % 4) * 140f, 40f + (i / 4) * 26f, chrome)
        }
        return bitmap
    }

    private fun Bitmap.scaledBy(factor: Float): Bitmap =
        if (factor == 1f) this else scale((width * factor).roundToInt(), (height * factor).roundToInt())

    /** Median of several runs, which is far steadier than a mean under thermal noise. */
    private fun medianMs(repeats: Int, block: () -> Unit): Long {
        val samples = LongArray(repeats) {
            val started = SystemClock.elapsedRealtime()
            block()
            SystemClock.elapsedRealtime() - started
        }
        samples.sort()
        return samples[samples.size / 2]
    }

    private fun matched(blocks: List<DetectedTextBlock>, label: String): DetectedTextBlock? =
        (TargetMatcher.match(label, blocks) as? TargetMatch.Found)?.block

    // --- 1 & 2: OCR latency and accuracy ---------------------------------------------------------

    @Test
    fun ocrLatencyAndAccuracyAcrossResolutionsAndCrops() {
        val full = renderKioskScreen()
        val ocr = KioskOcrEngine.create(appContext)
        try {
            Log.i(TAG, "=== OCR: full frame at decreasing resolution (${frameWidth}x$frameHeight base) ===")
            val results = linkedMapOf<String, Pair<Long, Int>>()
            for (factor in listOf(1.0f, 0.75f, 0.5f, 0.35f)) {
                val input = full.scaledBy(factor)
                var blocks: List<DetectedTextBlock> = emptyList()
                val ms = medianMs(REPEATS) { blocks = ocr.recognize(input) }

                val found = buttons.count { matched(blocks, it.first) != null }
                val confidences = buttons.mapNotNull { matched(blocks, it.first)?.confidence }
                Log.i(
                    TAG,
                    String.format(
                        Locale.ROOT,
                        "scale %.2f  %dx%d  %4d ms  lines %2d  buttons matched %d/4  mean conf %.2f",
                        factor, input.width, input.height, ms, blocks.size, found,
                        confidences.average().takeIf { !it.isNaN() } ?: 0.0,
                    ),
                )
                Log.i(TAG, "  read: " + blocks.joinToString { "'${it.text}'@${"%.2f".format(it.confidence)}" })
                results["scale_$factor"] = ms to found
                if (input !== full) input.recycle()
            }

            Log.i(TAG, "=== OCR: region-restricted re-anchoring vs the whole frame ===")
            val cluttered = renderClutteredScreen()
            val clutteredMs = medianMs(REPEATS) { ocr.recognize(cluttered) }
            val clutteredBlocks = ocr.recognize(cluttered)
            Log.i(TAG, "cluttered full frame  ${clutteredMs} ms  lines ${clutteredBlocks.size}")

            // Where "გადახდა" was drawn: a 240x120 button centred at (150, 700).
            val drawn = TextBox(30f, 640f, 270f, 760f)
            val fullMs = medianMs(REPEATS) { ocr.recognize(cluttered) }
            for (pad in listOf(2.0f, 1.0f, 0.5f, 0.25f)) {
                val region = Rect(
                    (drawn.left - drawn.width * pad).toInt().coerceAtLeast(0),
                    (drawn.top - drawn.height * pad).toInt().coerceAtLeast(0),
                    (drawn.right + drawn.width * pad).toInt().coerceAtMost(frameWidth),
                    (drawn.bottom + drawn.height * pad).toInt().coerceAtMost(frameHeight),
                )
                var blocks: List<DetectedTextBlock> = emptyList()
                val ms = medianMs(REPEATS) { blocks = ocr.recognize(cluttered, region) }
                val hit = matched(blocks, "გადახდა")
                Log.i(
                    TAG,
                    String.format(
                        Locale.ROOT,
                        "pad %.2f  region %dx%d (%2.0f%% of frame)  %4d ms  speedup %.1fx  target %s  anchors %d",
                        pad, region.width(), region.height(),
                        100f * region.width() * region.height() / (frameWidth * frameHeight),
                        ms, fullMs.toFloat() / ms.coerceAtLeast(1),
                        hit?.let { String.format(Locale.ROOT, "FOUND @%.2f box %s", it.confidence, it.boundingBox) } ?: "MISSED",
                        blocks.size,
                    ),
                )
                // The coordinates a region result reports must be usable without translation.
                if (hit != null && pad == 1.0f) {
                    val dx = kotlin.math.abs(hit.boundingBox.centerX - drawn.centerX)
                    val dy = kotlin.math.abs(hit.boundingBox.centerY - drawn.centerY)
                    Log.i(TAG, String.format(Locale.ROOT, "region box offset from drawn centre: %.0f, %.0f px", dx, dy))
                    assertTrue(
                        "setRectangle must report boxes in full-bitmap coordinates (off by $dx,$dy)",
                        dx < 60f && dy < 60f,
                    )
                }
            }
            cluttered.recycle()
        } finally {
            ocr.close()
            full.recycle()
        }
    }

    // --- 3 & 4: hand tracking, alone and while OCR runs -------------------------------------------

    @Test
    fun handTrackingFpsAloneAndDuringOcr() {
        val small = testContext.assets.open("pointing_up.jpg").use { BitmapFactory.decodeStream(it) }
            .copy(Bitmap.Config.ARGB_8888, false)
        // MediaPipe cost scales with input size, so it has to be measured on a frame the size
        // CameraX actually delivers here, not on the small reference photo.
        val photo = createBitmap(frameWidth, frameHeight).also { frame ->
            val canvas = Canvas(frame)
            canvas.drawColor(Color.DKGRAY)
            val scale = minOf(frameWidth.toFloat() / small.width, frameHeight.toFloat() / small.height)
            val w = small.width * scale
            val h = small.height * scale
            canvas.drawBitmap(
                small,
                null,
                android.graphics.RectF((frameWidth - w) / 2f, (frameHeight - h) / 2f, (frameWidth + w) / 2f, (frameHeight + h) / 2f),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
        small.recycle()
        Log.i(TAG, "hand input ${photo.width}x${photo.height} (analysis frame size)")
        val screen = renderKioskScreen()
        val tracker = HandLandmarkTracker.create(appContext)
        val ocr = KioskOcrEngine.create(appContext)
        try {
            val alone = measureHandMs(tracker, photo, startAt = 0)
            Log.i(TAG, String.format(Locale.ROOT, "hand alone          %5.1f ms/frame  %4.1f fps", alone, 1000 / alone))

            val contendedBackground = measureHandDuringOcr(tracker, photo, ocr, screen, background = true, startAt = 10_000)
            Log.i(
                TAG,
                String.format(
                    Locale.ROOT,
                    "hand + OCR (bg)     %5.1f ms/frame  %4.1f fps   (%.0f%% of the un-contended rate)",
                    contendedBackground, 1000 / contendedBackground, 100 * alone / contendedBackground,
                ),
            )

            val contendedDefault = measureHandDuringOcr(tracker, photo, ocr, screen, background = false, startAt = 20_000)
            Log.i(
                TAG,
                String.format(
                    Locale.ROOT,
                    "hand + OCR (normal) %5.1f ms/frame  %4.1f fps   (%.0f%% of the un-contended rate)",
                    contendedDefault, 1000 / contendedDefault, 100 * alone / contendedDefault,
                ),
            )
        } finally {
            tracker.close()
            ocr.close()
            photo.recycle()
            screen.recycle()
        }
    }

    private fun measureHandMs(tracker: HandLandmarkTracker, photo: Bitmap, startAt: Long): Double {
        repeat(WARMUP) { tracker.detect(photo, startAt + it.toLong()) }
        val started = SystemClock.elapsedRealtime()
        repeat(HAND_FRAMES) { tracker.detect(photo, startAt + WARMUP + it.toLong()) }
        return (SystemClock.elapsedRealtime() - started).toDouble() / HAND_FRAMES
    }

    /** Same measurement, but with Tesseract looping on another thread throughout. */
    private fun measureHandDuringOcr(
        tracker: HandLandmarkTracker,
        photo: Bitmap,
        ocr: KioskOcrEngine,
        screen: Bitmap,
        background: Boolean,
        startAt: Long,
    ): Double {
        val keepGoing = AtomicBoolean(true)
        val running = CountDownLatch(1)
        val worker = Thread {
            if (background) Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            running.countDown()
            while (keepGoing.get()) ocr.recognize(screen)
        }
        worker.start()
        running.await()
        return try {
            measureHandMs(tracker, photo, startAt)
        } finally {
            keepGoing.set(false)
            worker.join()
        }
    }

    private companion object {
        const val TAG = "OcrPerformanceTest"
        const val REPEATS = 5
        const val WARMUP = 3
        const val HAND_FRAMES = 30
    }
}
