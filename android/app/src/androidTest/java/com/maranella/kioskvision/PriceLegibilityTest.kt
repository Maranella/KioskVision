package com.maranella.kioskvision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.scale
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.googlecode.tesseract.android.TessBaseAPI
import com.maranella.kioskvision.ocr.KioskOcrEngine
import com.maranella.kioskvision.ocr.OcrPreprocess
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Why the amounts on a kiosk screen are the hardest text on it, and what recovers them.
 *
 * Measured, not argued. The first run of this on the SM-S731B read 42 lines of
 * `coffee_machine_screen.png` — every drink name, both languages, the volumes, the stepper
 * values — and **one** amount. The amounts are the one kind of text on that screen printed in
 * an accent colour: amber on cream, a strong colour difference and a weak *luminance*
 * difference, and luminance is all Tesseract's binarisation sees.
 *
 * That matters more than it sounds. A label the app misses is a button it cannot offer. An
 * amount the app misses is the answer to "რომელია უფრო იაფი?", and the user has no other way
 * to get it.
 *
 * ### The arm that has to be raw
 *
 * An earlier version of this test used `KioskOcrEngine.recognize` for its own baseline, which
 * stopped being a baseline the moment the engine learned to normalise contrast: every arm then
 * got the production transformation and the comparison measured nothing. It reported
 * `{baseline=5, grayscale+clip=5, 2x=5}` and failed, which was the test being wrong rather
 * than the fix.
 *
 * So the untouched arm now drives `TessBaseAPI` directly, with the same model and page
 * segmentation mode the app configures, and the production arm goes through the real engine.
 * One is what Tesseract does with the image; the other is what the app does.
 */
@RunWith(AndroidJUnit4::class)
class PriceLegibilityTest {

    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    /** The amounts printed on the target, by their digits alone — OCR mangles ₾ (U+20BE). */
    private val amounts = listOf("3.50", "4.00", "5.00", "8.50", "0.20")

    @Test
    fun whichPreprocessingRecoversTheAmounts() {
        val source = appContext.assets.open("ocr_test/coffee_machine_screen.png").use {
            BitmapFactory.decodeStream(it)
        }
        // 1280 px wide is the realistic case from the asset's README: a 720-wide analysis
        // frame, screen filling it, magnified 2x.
        val frame = source.scale(1280, 1280 * source.height / source.width)
        source.recycle()

        val found = mutableMapOf<String, Int>()
        val raw = rawTesseract()
        val engine = KioskOcrEngine.create(appContext)
        try {
            found[UNTOUCHED] = report(UNTOUCHED) { recognizeRaw(raw, frame) }
            found["grayscale+clip (raw engine)"] = report("grayscale+clip (raw engine)") {
                val normalised = OcrPreprocess.normalisedCrop(frame, Rect(0, 0, frame.width, frame.height))
                try {
                    recognizeRaw(raw, normalised)
                } finally {
                    normalised.recycle()
                }
            }
            found["2x (raw engine)"] = report("2x (raw engine)") {
                val enlarged = frame.scale(frame.width * 2, frame.height * 2)
                try {
                    recognizeRaw(raw, enlarged)
                } finally {
                    enlarged.recycle()
                }
            }
            found[PRODUCTION] = report(PRODUCTION) {
                engine.recognize(frame, null, 1f).map { it.text }
            }
        } finally {
            raw.recycle()
            engine.close()
            frame.recycle()
        }

        val untouched = found.getValue(UNTOUCHED)
        val production = found.getValue(PRODUCTION)
        Log.i(TAG, "untouched=$untouched production=$production all=$found")

        // The claim the production preprocessing rests on, stated as a comparison so it stays
        // meaningful if recognition improves for other reasons.
        assertTrue(
            "the production path must read at least as many amounts as untouched Tesseract: $found",
            production >= untouched,
        )
        assertTrue(
            "if the untouched image already reads every amount, this target can no longer " +
                "justify the preprocessing and the claim needs a harder one: $found",
            untouched < amounts.size,
        )
    }

    private fun report(name: String, recognize: () -> List<String>): Int {
        val started = SystemClock.elapsedRealtime()
        val lines = recognize()
        val elapsed = SystemClock.elapsedRealtime() - started
        val seen = amounts.filter { amount -> lines.any { amount in it } }
        Log.i(
            TAG,
            "$name: ${elapsed}ms lines=${lines.size} amounts=${seen.size}/${amounts.size} $seen | " +
                lines.filter { line -> line.any(Char::isDigit) }.joinToString { "'${it.trim()}'" },
        )
        return seen.size
    }

    /** The app's own model and page segmentation mode, with nothing else done to the image. */
    private fun rawTesseract(): TessBaseAPI {
        val root = File(appContext.cacheDir, "price-legibility")
        val tessdata = File(root, "tessdata")
        tessdata.mkdirs()
        for (name in listOf("kat.traineddata", "eng.traineddata")) {
            val target = File(tessdata, name)
            if (target.isFile && target.length() > 0) continue
            appContext.assets.open("tessdata/$name").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        val tess = TessBaseAPI()
        check(tess.init(root.absolutePath, KioskOcrEngine.LANGUAGES, TessBaseAPI.OEM_LSTM_ONLY)) {
            "Tesseract failed to initialise"
        }
        tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
        return tess
    }

    private fun recognizeRaw(tess: TessBaseAPI, bitmap: Bitmap): List<String> {
        tess.setImage(bitmap)
        return try {
            tess.getUTF8Text().orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        } finally {
            tess.clear()
        }
    }

    private companion object {
        const val TAG = "PriceLegibility"
        const val UNTOUCHED = "untouched (raw engine)"
        const val PRODUCTION = "production engine"
    }
}
