package com.maranella.kioskvision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.googlecode.tesseract.android.TessBaseAPI
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Which Tesseract configuration actually reads Georgian kiosk text best on this device?
 *
 * The app ships `kat.traineddata` from `tessdata_fast`, the speed-optimised variant, with
 * `PSM_SPARSE_TEXT` and no preprocessing. None of those three choices had been measured. This
 * sweeps model x page-segmentation mode x preprocessing against known ground truth and reports
 * CER, WER, exact-label rate and latency for each.
 *
 * **The corpus is rendered, not photographed.** It varies text size, contrast and blur, but it
 * has no glare, no real optics and no real kiosk fonts, so these numbers rank configurations
 * against each other — they are not an estimate of field accuracy.
 */
@RunWith(AndroidJUnit4::class)
class GeorgianOcrAccuracyTest {

    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    /** Ground truth: what each rendered sample says, in reading order. */
    private val samples = listOf(
        "menu" to listOf(
            "რას გინდა გააკეთო?", "გადახდა", "ბალანსის შემოწმება",
            "მობილურის შევსება", "კომუნალური გადასახადები", "დახმარება",
        ),
        "amount" to listOf(
            "შეიყვანეთ თანხა", "25.50 ლარი", "დადასტურება", "გაუქმება", "უკან დაბრუნება",
        ),
        "transport" to listOf(
            "ტრანსპორტი და ბილეთები", "მეტრო", "ავტობუსი", "ბარათის შევსება", "10 ლარი",
        ),
    )

    private class Condition(val name: String, val textSize: Float, val contrast: Int, val blur: Float)

    private class Config(
        val name: String,
        val model: String,
        val psm: Int,
        val upscale: Float,
        val grayscale: Boolean,
    )

    private fun render(lines: List<String>, textSize: Float, contrast: Int, blurPx: Float): Bitmap {
        val bitmap = createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val steps = if (blurPx <= 0.5f) 1 else (blurPx / 2f).toInt().coerceAtLeast(2)
        for (step in 0 until steps) {
            val t = if (steps == 1) 0f else step.toFloat() / (steps - 1) - 0.5f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(contrast, contrast, contrast + 10)
                this.textSize = textSize
                textAlign = Paint.Align.CENTER
                alpha = (255f / steps).toInt().coerceAtLeast(1)
            }
            lines.forEachIndexed { index, line ->
                canvas.drawText(line, WIDTH / 2f + t * blurPx, 220f + index * (textSize * 2.4f), paint)
            }
        }
        if (NOISE > 0) addSensorNoise(bitmap)
        return bitmap
    }

    /** Luminance noise, which rendered text lacks and every real camera frame has. */
    private fun addSensorNoise(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val random = java.util.Random(7)
        for (i in pixels.indices) {
            val delta = ((random.nextGaussian()) * NOISE).toInt()
            val p = pixels[i]
            val r = ((p shr 16 and 0xFF) + delta).coerceIn(0, 255)
            val g = ((p shr 8 and 0xFF) + delta).coerceIn(0, 255)
            val b = ((p and 0xFF) + delta).coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun preprocess(bitmap: Bitmap, config: Config): Bitmap {
        var out = bitmap
        if (config.upscale != 1f) {
            out = out.scale((out.width * config.upscale).toInt(), (out.height * config.upscale).toInt())
        }
        if (config.grayscale) {
            val gray = createBitmap(out.width, out.height)
            val canvas = Canvas(gray)
            val paint = Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(
                    android.graphics.ColorMatrix().apply { setSaturation(0f) },
                )
            }
            canvas.drawBitmap(out, 0f, 0f, paint)
            if (out !== bitmap) out.recycle()
            out = gray
        }
        return out
    }

    /** Installs a trained-data file under its own directory and opens an engine on it. */
    private fun engineFor(model: String, psm: Int): TessBaseAPI {
        val root = File(appContext.cacheDir, "ocr-eval-$model")
        val tessdata = File(root, "tessdata")
        tessdata.mkdirs()
        val target = File(tessdata, "kat.traineddata")
        if (!target.isFile || target.length() == 0L) {
            val stream = if (model == "fast") {
                appContext.assets.open("tessdata/kat.traineddata")
            } else {
                testContext.assets.open("kat_best.traineddata")
            }
            stream.use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        val tess = TessBaseAPI()
        check(tess.init(root.absolutePath, "kat", TessBaseAPI.OEM_LSTM_ONLY)) { "init failed for $model" }
        tess.setPageSegMode(psm)
        return tess
    }

    private fun recognize(tess: TessBaseAPI, bitmap: Bitmap): List<String> {
        tess.setImage(bitmap)
        return try {
            tess.getUTF8Text().orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        } finally {
            tess.clear()
        }
    }

    private fun editDistance(a: List<String>, b: List<String>): Int {
        val previous = IntArray(b.size + 1) { it }
        val current = IntArray(b.size + 1)
        for (i in 1..a.size) {
            current[0] = i
            for (j in 1..b.size) {
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            current.copyInto(previous)
        }
        return previous[b.size]
    }

    private fun chars(s: String) = s.map { it.toString() }
    private fun words(s: String) = s.split(" ").filter { it.isNotEmpty() }

    @Test
    fun sweepGeorgianOcrConfigurations() {
        val configs = listOf(
            Config("fast  sparse  1x", "fast", TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT, 1f, false),
            Config("fast  sparse  2x", "fast", TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT, 2f, false),
            Config("fast  sparse  2x gray", "fast", TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT, 2f, true),
            Config("fast  auto    1x", "fast", TessBaseAPI.PageSegMode.PSM_AUTO, 1f, false),
            Config("best  sparse  1x", "best", TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT, 1f, false),
            Config("best  sparse  2x", "best", TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT, 2f, false),
            Config("best  auto    1x", "best", TessBaseAPI.PageSegMode.PSM_AUTO, 1f, false),
        )

        // Deliberately at and past the edge of readability: a corpus everything scores 100%
        // on cannot rank anything. Names kept for the per-condition table.
        val conditions = listOf(
            Condition("easy 34px", 34f, 20, 0f),
            Condition("small 16px", 16f, 20, 0f),
            Condition("tiny 12px", 12f, 20, 0f),
            Condition("faint 150", 30f, 150, 0f),
            Condition("blur 10px", 30f, 20, 10f),
            Condition("small+blur", 18f, 60, 6f),
        )

        Log.i(TAG, "=== Georgian OCR configuration sweep (rendered corpus) ===")
        Log.i(TAG, String.format(Locale.ROOT, "%-22s %6s %6s %8s %8s", "config", "CER", "WER", "exact", "p50 ms"))

        for (config in configs) {
            val tess = engineFor(config.model, config.psm)
            try {
                var charErrors = 0; var charTotal = 0
                var wordErrors = 0; var wordTotal = 0
                var exact = 0; var expectedCount = 0
                val latencies = mutableListOf<Long>()

                val perCondition = linkedMapOf<String, Pair<Int, Int>>()
                for ((_, truthLines) in samples) {
                    for (condition in conditions) {
                        val raw = render(truthLines, condition.textSize, condition.contrast, condition.blur)
                        val input = preprocess(raw, config)
                        val started = SystemClock.elapsedRealtime()
                        val got = recognize(tess, input)
                        latencies += SystemClock.elapsedRealtime() - started
                        if (input !== raw) input.recycle()
                        raw.recycle()

                        for (truth in truthLines) {
                            expectedCount++
                            // Best-matching recognised line, so segmentation differences do not
                            // masquerade as character errors.
                            val best = got.minByOrNull { editDistance(chars(truth), chars(it)) }
                            val distance = best?.let { editDistance(chars(truth), chars(it)) } ?: truth.length
                            charErrors += min(distance, truth.length)
                            charTotal += truth.length
                            val wordDistance = best?.let { editDistance(words(truth), words(it)) } ?: words(truth).size
                            wordErrors += min(wordDistance, words(truth).size)
                            wordTotal += words(truth).size
                            if (best != null && best == truth) exact++
                            val bucket = perCondition.getOrPut(condition.name) { 0 to 0 }
                            perCondition[condition.name] =
                                (bucket.first + min(distance, truth.length)) to (bucket.second + truth.length)
                        }
                    }
                }
                latencies.sort()
                Log.i(
                    TAG,
                    String.format(
                        Locale.ROOT,
                        "%-22s %5.1f%% %5.1f%% %6.1f%% %7d   (n=%d labels)",
                        config.name,
                        100.0 * charErrors / max(1, charTotal),
                        100.0 * wordErrors / max(1, wordTotal),
                        100.0 * exact / max(1, expectedCount),
                        latencies[latencies.size / 2],
                        expectedCount,
                    ),
                )
                Log.i(
                    TAG,
                    "    by condition: " + perCondition.entries.joinToString("  ") { (name, v) ->
                        String.format(Locale.ROOT, "%s %.0f%%", name, 100.0 * v.first / max(1, v.second))
                    },
                )
            } finally {
                tess.recycle()
            }
        }
    }

    /**
     * Enlarging the image must not move anything: a box found at 2x has to come back in the
     * original frame's coordinates, or guidance would point at the wrong part of the screen.
     */
    @Test
    fun upscalingPreservesCoordinates() {
        val engine = com.maranella.kioskvision.ocr.KioskOcrEngine.create(appContext)
        try {
            val bitmap = createBitmap(WIDTH, HEIGHT)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(15, 15, 25); textSize = 18f; textAlign = Paint.Align.CENTER
            }
            val centreX = 200f
            val centreY = 640f
            canvas.drawText("გადახდა", centreX, centreY - (paint.descent() + paint.ascent()) / 2, paint)

            val scaled = engine.recognize(bitmap, null, 2f).firstOrNull { it.text.contains("გადახ") }
            Log.i(TAG, "full 2x -> ${scaled?.text}@${scaled?.boundingBox}")
            org.junit.Assert.assertNotNull("upscaled pass must find the label", scaled)
            org.junit.Assert.assertEquals("centre x", centreX, scaled!!.boundingBox.centerX, 20f)
            org.junit.Assert.assertEquals("centre y", centreY, scaled.boundingBox.centerY, 20f)

            // And through a region, which crops before enlarging.
            val region = android.graphics.Rect(60, 580, 360, 700)
            val viaRegion = engine.recognize(bitmap, region, 2f).firstOrNull { it.text.contains("გადახ") }
            Log.i(TAG, "region 2x -> ${viaRegion?.text}@${viaRegion?.boundingBox}")
            org.junit.Assert.assertNotNull("region pass must find the label", viaRegion)
            org.junit.Assert.assertEquals("region centre x", centreX, viaRegion!!.boundingBox.centerX, 20f)
            org.junit.Assert.assertEquals("region centre y", centreY, viaRegion.boundingBox.centerY, 20f)
            bitmap.recycle()
        } finally {
            engine.close()
        }
    }

    private companion object {
        const val TAG = "GeorgianOcrEval"
        /** Standard deviation of the added luminance noise. */
        const val NOISE = 9.0

        const val WIDTH = 576
        const val HEIGHT = 1280
    }
}
