package com.maranella.kioskvision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.googlecode.tesseract.android.TessBaseAPI
import com.maranella.kioskvision.diag.LatencyTracker
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Is the `eng` model earning its place beside `kat`?
 *
 * The app initialises Tesseract with `kat+eng` (`KioskOcrEngine.LANGUAGES`). In LSTM mode a
 * second language is not free: every line is put through both models and arbitrated, and R-11
 * established that Tesseract's cost on this workload tracks **line count**, which is exactly
 * what a second language multiplies. Georgian kiosk screens are overwhelmingly Georgian, so
 * `kat` alone is a plausible and large saving on the full-frame scan that costs 2.6-6.3 s on
 * real camera frames.
 *
 * It is only plausible. Dropping `eng` risks the Latin content that does appear on these
 * screens - amounts written `GEL`, terminal identifiers, brand names - and safety requirement 4
 * is specifically about reading those reliably. So this measures rather than assumes, and
 * **changes nothing**: `KioskOcrEngine` still ships `kat+eng` and will until there are numbers.
 *
 * ### Why it is built this way
 *
 * - **Both arms share one tessdata directory and one corpus.** The language string is the only
 *   variable; nothing else differs between the two engines.
 * - **The arms are interleaved per sample, not run end to end.** R-24 is the reason: an A/B
 *   whose arms are separated in time is not controlled when the thing under test drifts, and a
 *   phone warming up under sustained OCR drifts. Alternating means thermal throttling lands on
 *   both arms equally instead of on whichever ran second.
 * - **It asserts almost nothing.** This is a measurement harness. The decision belongs to a
 *   person reading the table, not to a threshold invented here.
 *
 * Run with `./gradlew connectedDebugAndroidTest --tests '*OcrLanguageComparisonTest*'` and read
 * the `OcrLangCompare` lines in logcat.
 *
 * **The corpus is rendered, not photographed** - no glare, no optics, no real kiosk fonts. It
 * ranks the two configurations against each other; it is not an estimate of field accuracy.
 */
@RunWith(AndroidJUnit4::class)
class OcrLanguageComparisonTest {

    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    private class Sample(val name: String, val lines: List<String>, val textSize: Float)

    /**
     * Georgian-only screens, plus the mixed-script cases that are the whole risk of dropping
     * `eng`. If `kat` alone reads these as well, there is nothing to protect.
     */
    private val samples = listOf(
        Sample(
            "menu",
            listOf("რას გინდა გააკეთო?", "გადახდა", "ბალანსის შემოწმება", "დახმარება"),
            34f,
        ),
        Sample(
            "amount",
            listOf("შეიყვანეთ თანხა", "25.50 ლარი", "დადასტურება", "გაუქმება"),
            34f,
        ),
        Sample(
            "mixed-latin",
            listOf("TBC Pay", "ბალანსი: 25.50 GEL", "Terminal ID: TB-4417", "გაგრძელება"),
            34f,
        ),
        Sample(
            "small-mixed",
            listOf("ქვითარი", "Receipt no. 88213", "მომსახურების საკომისიო 0.20 GEL"),
            18f,
        ),
    )

    private fun render(sample: Sample): Bitmap {
        val bitmap = createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 30)
            textSize = sample.textSize
            textAlign = Paint.Align.CENTER
        }
        sample.lines.forEachIndexed { index, line ->
            canvas.drawText(line, WIDTH / 2f, 200f + index * (sample.textSize * 2.4f), paint)
        }
        return bitmap
    }

    /** One tessdata directory holding both models, so only the language string differs. */
    private fun sharedTessdata(): File {
        val root = File(appContext.cacheDir, "ocr-lang-compare")
        val tessdata = File(root, "tessdata")
        tessdata.mkdirs()
        for (name in listOf("kat.traineddata", "eng.traineddata")) {
            val target = File(tessdata, name)
            if (target.isFile && target.length() > 0) continue
            appContext.assets.open("tessdata/$name").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        return root
    }

    private fun engineFor(root: File, languages: String): TessBaseAPI {
        val tess = TessBaseAPI()
        check(tess.init(root.absolutePath, languages, TessBaseAPI.OEM_LSTM_ONLY)) {
            "Tesseract failed to initialise with $languages"
        }
        // Matches production: kiosk screens are scattered labels, not paragraphs.
        tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
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

    private fun editDistance(a: String, b: String): Int {
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            current.copyInto(previous)
        }
        return previous[b.length]
    }

    private class Arm(val name: String, val tess: TessBaseAPI) {
        val latency = LatencyTracker()
        var charErrors = 0
        var charTotal = 0
        var exact = 0
        var expected = 0
        var latinExact = 0
        var latinExpected = 0
    }

    @Test
    fun compareKatAgainstKatPlusEng() {
        val root = sharedTessdata()
        val arms = listOf(
            Arm("kat", engineFor(root, "kat")),
            Arm("kat+eng", engineFor(root, KioskOcrEngineLanguages)),
        )

        try {
            val rendered = samples.map { it to render(it) }
            // Warm both engines before timing: the first run of either pays one-off setup.
            for (arm in arms) recognize(arm.tess, rendered.first().second)

            repeat(REPEATS) {
                for ((sample, bitmap) in rendered) {
                    // Interleaved, so drift hits both arms equally (R-24).
                    for (arm in arms) {
                        val started = SystemClock.elapsedRealtime()
                        val read = recognize(arm.tess, bitmap)
                        arm.latency.record(SystemClock.elapsedRealtime() - started)

                        val hasLatin = sample.lines.any { line -> line.any { it in 'A'..'z' } }
                        for ((index, truth) in sample.lines.withIndex()) {
                            val got = read.getOrNull(index).orEmpty()
                            arm.charErrors += editDistance(truth, got)
                            arm.charTotal += truth.length
                            arm.expected++
                            if (got == truth) arm.exact++
                            if (hasLatin && truth.any { it in 'A'..'z' }) {
                                arm.latinExpected++
                                if (got == truth) arm.latinExact++
                            }
                        }
                    }
                }
            }
            rendered.forEach { it.second.recycle() }

            Log.i(TAG, "=== kat vs kat+eng, interleaved, $REPEATS passes over ${samples.size} samples ===")
            Log.i(
                TAG,
                String.format(Locale.ROOT, "%-10s %7s %8s %12s %9s %9s", "lang", "CER", "exact", "latin exact", "p50 ms", "p90 ms"),
            )
            for (arm in arms) {
                Log.i(
                    TAG,
                    String.format(
                        Locale.ROOT,
                        "%-10s %6.1f%% %5d/%-3d %7d/%-4d %8d %9d",
                        arm.name,
                        100.0 * arm.charErrors / arm.charTotal.coerceAtLeast(1),
                        arm.exact, arm.expected,
                        arm.latinExact, arm.latinExpected,
                        arm.latency.percentile(0.5) ?: -1L,
                        arm.latency.percentile(0.9) ?: -1L,
                    ),
                )
            }
            val single = arms[0].latency.percentile(0.5) ?: 0L
            val both = arms[1].latency.percentile(0.5) ?: 0L
            Log.i(
                TAG,
                "kat is ${String.format(Locale.ROOT, "%.2f", both.toDouble() / single.coerceAtLeast(1))}x " +
                    "faster than kat+eng at p50. Decide from the CER and latin-exact columns too; " +
                    "production stays kat+eng until this says otherwise.",
            )

            // The only assertion: both arms ran and produced samples. Everything else is a
            // measurement for a person to read, not a threshold invented here.
            assertTrue("both arms must have timed samples", arms.all { it.latency.count > 0 })
        } finally {
            arms.forEach { it.tess.recycle() }
        }
    }

    private companion object {
        const val TAG = "OcrLangCompare"
        const val WIDTH = 720
        const val HEIGHT = 1280

        /** Enough passes for a stable p50 without cooking the device. */
        const val REPEATS = 5

        /** Production's language string, so the arm really is what the app ships. */
        const val KioskOcrEngineLanguages = "kat+eng"
    }
}
