package com.maranella.kioskvision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maranella.kioskvision.assist.TargetTracker
import com.maranella.kioskvision.diag.LatencyTracker
import com.maranella.kioskvision.ocr.KioskOcrEngine
import com.maranella.kioskvision.ocr.OcrUpscale
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Why does OCR read nothing on a real machine screen, and which preprocessing would change it?
 *
 * Runs **the production engine** — `kat+eng`, `PSM_SPARSE_TEXT`, the real intake filter — over
 * one captured frame, under one transformation at a time. The baseline is kept untouched so
 * every row is comparable to what the app actually does today.
 *
 * ### This test skips unless you give it a frame
 *
 * It needs `androidTest/assets/machine_screen.png`: the image the engine actually read, which
 * `KioskOcrEngine.dumpNextInput` writes on the device. Capture it like this:
 *
 * ```
 * # debug build, at the machine, turn the developer overlay on — that arms a one-shot dump
 * adb shell run-as com.maranella.kioskvision ls files/ocr-dumps
 * adb exec-out run-as com.maranella.kioskvision cat files/ocr-dumps/<name>.png > machine_screen.png
 * cp machine_screen.png android/app/src/androidTest/assets/
 * ```
 *
 * Optionally add `machine_screen.crop` containing `left,top,right,bottom` to restrict the run
 * to the screen region, if the dump is a whole frame.
 *
 * Without that file the test reports why it skipped and asserts nothing. **It does not
 * simulate a machine screen**: a rendered approximation would answer a different question than
 * the one being asked, which is the mistake R-17 and R-21 were both written about.
 */
@RunWith(AndroidJUnit4::class)
class OcrPreprocessingComparisonTest {

    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private fun asset(name: String): ByteArray? =
        runCatching { testContext.assets.open(name).use { it.readBytes() } }.getOrNull()

    private class Variant(val name: String, val transform: (Bitmap) -> Bitmap)

    /** Pixels of [bitmap] as packed ARGB. */
    private fun pixelsOf(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    private fun fromPixels(pixels: IntArray, width: Int, height: Int): Bitmap =
        createBitmap(width, height).also { it.setPixels(pixels, 0, width, 0, 0, width, height) }

    private fun luma(p: Int): Int =
        (299 * (p shr 16 and 0xFF) + 587 * (p shr 8 and 0xFF) + 114 * (p and 0xFF)) / 1000

    private fun gray(value: Int): Int {
        val v = value.coerceIn(0, 255)
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    /** Grayscale with the histogram stretched to the full range. One transformation. */
    private fun contrastNormalised(source: Bitmap): Bitmap {
        val pixels = pixelsOf(source)
        val lumas = IntArray(pixels.size) { luma(pixels[it]) }
        val sorted = lumas.sortedArray()
        // Percentile clipping, so one specular highlight does not define the white point —
        // which on a machine with a chrome body is the normal case, not an edge case.
        val low = sorted[(sorted.size * 0.02).toInt()]
        val high = sorted[(sorted.size * 0.98).toInt().coerceAtMost(sorted.size - 1)]
        val span = (high - low).coerceAtLeast(1)
        return fromPixels(IntArray(pixels.size) { gray((lumas[it] - low) * 255 / span) }, source.width, source.height)
    }

    /** Local mean threshold. Handles uneven backlight, which a global threshold cannot. */
    private fun adaptiveThreshold(source: Bitmap, window: Int = 15, offset: Int = 10): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = pixelsOf(source)
        val lumas = IntArray(pixels.size) { luma(pixels[it]) }
        // Summed-area table, so the window cost does not depend on its size.
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            for (x in 0 until w) {
                rowSum += lumas[y * w + x]
                integral[(y + 1) * (w + 1) + (x + 1)] = integral[y * (w + 1) + (x + 1)] + rowSum
            }
        }
        fun mean(cx: Int, cy: Int): Int {
            val x0 = (cx - window / 2).coerceAtLeast(0)
            val y0 = (cy - window / 2).coerceAtLeast(0)
            val x1 = (cx + window / 2).coerceAtMost(w - 1)
            val y1 = (cy + window / 2).coerceAtMost(h - 1)
            val area = (x1 - x0 + 1) * (y1 - y0 + 1)
            val sum = integral[(y1 + 1) * (w + 1) + (x1 + 1)] -
                integral[y0 * (w + 1) + (x1 + 1)] -
                integral[(y1 + 1) * (w + 1) + x0] +
                integral[y0 * (w + 1) + x0]
            return (sum / area).toInt()
        }
        val out = IntArray(pixels.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                out[i] = if (lumas[i] < mean(x, y) - offset) gray(0) else gray(255)
            }
        }
        return fromPixels(out, w, h)
    }

    @Test
    fun comparePreprocessingOnACapturedFrame() {
        val bytes = asset("machine_screen.png") ?: asset("machine_screen.jpg")
        assumeTrue(
            "No captured frame. Put the device's OCR input dump at " +
                "androidTest/assets/machine_screen.png — see this test's KDoc for the adb lines. " +
                "Nothing is asserted without one, because a rendered stand-in would answer a " +
                "different question.",
            bytes != null,
        )

        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes!!.size)
            .copy(Bitmap.Config.ARGB_8888, false)
        val crop = String(asset("machine_screen.crop") ?: ByteArray(0)).trim()
            .takeIf { it.isNotEmpty() }
            ?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 4 }
            ?.let { Rect(it[0], it[1], it[2], it[3]) }
        val source = crop?.let {
            Bitmap.createBitmap(full, it.left, it.top, it.width(), it.height())
        } ?: full

        val variants = listOf(
            // Baseline first and untouched: every other row is read against this one.
            Variant("1 baseline (production)") { it },
            Variant("2 grayscale + contrast", ::contrastNormalised),
            Variant("3 adaptive threshold") { adaptiveThreshold(it) },
            Variant("4 upscale ${OcrUpscale.FACTOR}x") {
                it.scale((it.width * OcrUpscale.FACTOR).toInt(), (it.height * OcrUpscale.FACTOR).toInt())
            },
        )

        val engine = KioskOcrEngine.create(appContext)
        try {
            Log.i(TAG, "=== OCR preprocessing comparison ===")
            Log.i(TAG, "input ${source.width}x${source.height}" + (crop?.let { "  crop $it" } ?: "  (whole dump)"))
            Log.i(TAG, "languages ${KioskOcrEngine.LANGUAGES}")
            Log.i(
                TAG,
                String.format(Locale.ROOT, "%-26s %8s %6s %6s", "variant", "p50 ms", "raw", "kept"),
            )

            var baselineRaw = -1
            for (variant in variants) {
                val image = variant.transform(source)
                val latency = LatencyTracker()
                var blocks = emptyList<com.maranella.kioskvision.assist.DetectedTextBlock>()
                repeat(REPEATS) {
                    val started = SystemClock.elapsedRealtime()
                    blocks = engine.recognize(image)
                    latency.record(SystemClock.elapsedRealtime() - started)
                }
                // The production intake rule, not a copy of it — so "Tesseract read it" and
                // "the app kept it" are separated using the same predicate the app applies.
                val kept = blocks.count(TargetTracker::isRetainable)
                if (baselineRaw < 0) baselineRaw = blocks.size

                Log.i(
                    TAG,
                    String.format(
                        Locale.ROOT,
                        "%-26s %8d %6d %6d",
                        variant.name, latency.percentile(0.5) ?: -1L, blocks.size, kept,
                    ),
                )
                // Screen content stays on the device: this is an instrumented run on a machine
                // the developer is holding, not a shipped log.
                blocks.take(RAW_LINES).forEach {
                    val verdict = TargetTracker.rejectionFor(it)?.let { why -> "DROPPED: $why" } ?: "kept"
                    Log.i(TAG, String.format(Locale.ROOT, "    %.2f  %s  [%s]", it.confidence, it.text.trim(), verdict))
                }
                if (image !== source) image.recycle()
            }

            Log.i(
                TAG,
                "Read the `raw` column for recognition and `kept` for filtering: raw 0 everywhere " +
                    "is an image problem, raw > 0 with kept 0 is the intake filter, and a variant " +
                    "that lifts raw is the preprocessing worth adopting.",
            )
            assertTrue("the baseline must at least have run", baselineRaw >= 0)
        } finally {
            engine.close()
            if (source !== full) source.recycle()
            full.recycle()
        }
    }

    private companion object {
        const val TAG = "OcrPreprocess"
        const val REPEATS = 3
        const val RAW_LINES = 12
    }
}
