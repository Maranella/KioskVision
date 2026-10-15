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
import com.maranella.kioskvision.assist.DetectedTextBlock
import com.maranella.kioskvision.assist.TargetMatch
import com.maranella.kioskvision.assist.FrameSize
import com.maranella.kioskvision.assist.TargetMatcher
import com.maranella.kioskvision.assist.TargetTracker
import com.maranella.kioskvision.assist.TextNormalizer
import com.maranella.kioskvision.ocr.KioskOcrEngine
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Diagnostic, not a pass/fail test: measures what OCR actually returns for "გადახდა" as the
 * camera moves, and what the matcher then decides.
 *
 * The reported symptom is that slight handheld movement makes the app claim the label is not
 * there. This reproduces translation, rotation and motion blur over the same rendered kiosk
 * screen and prints, for each case, the recognised text, its confidence, and the matcher's
 * verdict — so the cause can be identified before any threshold is touched.
 */
@RunWith(AndroidJUnit4::class)
class OcrMotionDiagnosticTest {

    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val width = 576
    private val height = 1280
    private val target = "გადახდა"

    private fun kioskScreen(): Bitmap {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 30); textSize = 44f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("რას გინდა გააკეთო?", width / 2f, 480f, heading)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(10, 10, 20); textSize = 30f; textAlign = Paint.Align.CENTER
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 130, 150); style = Paint.Style.STROKE; strokeWidth = 2f
        }
        listOf(
            target to (150f to 700f),
            "ბალანსი" to (430f to 700f),
            "მობილური" to (150f to 880f),
            "დახმარება" to (430f to 880f),
        ).forEach { (text, c) ->
            canvas.drawRect(c.first - 120f, c.second - 55f, c.first + 120f, c.second + 55f, outline)
            canvas.drawText(text, c.first, c.second - (label.descent() + label.ascent()) / 2, label)
        }
        return bitmap
    }

    /** Re-renders the screen as the camera would see it after a small handheld movement. */
    private fun moved(dx: Float, dy: Float, degrees: Float, blurPx: Float): Bitmap {
        val source = kioskScreen()
        val out = createBitmap(width, height)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        // Linear motion blur: the same frame smeared along the direction of travel, which is
        // what a rolling-shutter sensor produces during a hand movement.
        val steps = if (blurPx <= 0f) 1 else (blurPx / 2f).toInt().coerceAtLeast(2)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255f / steps).toInt().coerceAtLeast(1) }
        for (step in 0 until steps) {
            val t = if (steps == 1) 0f else step.toFloat() / (steps - 1) - 0.5f
            canvas.save()
            canvas.rotate(degrees, width / 2f, height / 2f)
            canvas.translate(dx + t * blurPx, dy)
            canvas.drawBitmap(source, 0f, 0f, paint)
            canvas.restore()
        }
        source.recycle()
        return out
    }

    private fun describe(blocks: List<DetectedTextBlock>): String {
        val hit = blocks.maxByOrNull { TextNormalizer.lineSimilarity(it.normalizedText, TextNormalizer.normalize(target)) }
        return if (hit == null) {
            "no blocks"
        } else {
            String.format(
                Locale.ROOT,
                "best='%s' conf=%.2f sim=%.2f",
                hit.text, hit.confidence,
                TextNormalizer.lineSimilarity(hit.normalizedText, TextNormalizer.normalize(target)),
            )
        }
    }

    /** Why the matcher said what it said, in terms of its own gates. */
    private fun verdict(blocks: List<DetectedTextBlock>, query: String): String {
        val match = TargetMatcher.match(query, blocks)
        val belowConfidence = blocks.filter {
            TextNormalizer.lineSimilarity(it.normalizedText, TextNormalizer.normalize(target)) > 0.6f &&
                it.confidence < TargetMatcher.MIN_TARGET_CONFIDENCE
        }
        val reason = when {
            match is TargetMatch.Found -> "FOUND '${match.block.text}'"
            match is TargetMatch.Ambiguous -> "AMBIGUOUS '${match.first.text}' vs '${match.second.text}'"
            belowConfidence.isNotEmpty() -> "NOT FOUND — label present but conf " +
                belowConfidence.joinToString { String.format(Locale.ROOT, "%.2f", it.confidence) } +
                " < ${TargetMatcher.MIN_TARGET_CONFIDENCE} gate"
            else -> "NOT FOUND — no line resembling the label survived OCR"
        }
        return reason
    }

    @Test
    fun whyTheTargetIsRejectedWhenTheCameraMoves() {
        val ocr = KioskOcrEngine.create(appContext)
        try {
            val cases = listOf(
                Triple("still", Triple(0f, 0f, 0f), 0f),
                Triple("shift 10px", Triple(10f, 0f, 0f), 0f),
                Triple("shift 40px", Triple(40f, 0f, 0f), 0f),
                Triple("shift 40px + down 25", Triple(40f, 25f, 0f), 0f),
                Triple("tilt 1deg", Triple(0f, 0f, 1f), 0f),
                Triple("tilt 3deg", Triple(0f, 0f, 3f), 0f),
                Triple("tilt 6deg", Triple(0f, 0f, 6f), 0f),
                Triple("blur 4px", Triple(0f, 0f, 0f), 4f),
                Triple("blur 8px", Triple(0f, 0f, 0f), 8f),
                Triple("blur 14px", Triple(0f, 0f, 0f), 14f),
                Triple("shift 20 + tilt 2 + blur 6", Triple(20f, 0f, 2f), 6f),
                Triple("shift 30 + tilt 4 + blur 10", Triple(30f, 10f, 4f), 10f),
            )

            Log.i(TAG, "=== OCR of 'გადახდა' under simulated handheld movement ===")
            for ((name, motion, blur) in cases) {
                val (dx, dy, deg) = motion
                val frame = moved(dx, dy, deg, blur)
                val started = SystemClock.elapsedRealtime()
                val blocks = ocr.recognize(frame)
                val ms = SystemClock.elapsedRealtime() - started
                Log.i(
                    TAG,
                    String.format(Locale.ROOT, "%-26s %4dms lines=%-3d %s", name, ms, blocks.size, describe(blocks)),
                )
                Log.i(TAG, "    query 'გადახდა'       -> ${verdict(blocks, "გადახდა")}")
                Log.i(TAG, "    query 'გადახდა მინდა' -> ${verdict(blocks, "გადახდა მინდა")}")
                frame.recycle()
            }
        } finally {
            ocr.close()
        }
    }

    /**
     * The same blurred frames, but fed through the tracker the way the app does, so successive
     * runs can corroborate one another. This is the measured check that the fix for R-12 works
     * against real Tesseract output rather than only against synthetic confidences.
     */
    @Test
    fun blurredFramesBecomeUsableOnceCorroborated() {
        val ocr = KioskOcrEngine.create(appContext)
        val size = FrameSize(width, height)
        try {
            for (blur in listOf(4f, 8f, 14f)) {
                val tracker = TargetTracker()
                var clock = 1_000L
                Log.i(TAG, "=== handheld sequence, ${blur}px blur ===")

                // Three successive frames, each blurred and slightly displaced, as a hand would.
                for ((index, drift) in listOf(0f, 7f, 15f).withIndex()) {
                    val frame = moved(drift, drift / 2f, 0.5f * index, blur)
                    val blocks = ocr.recognize(frame)
                    clock += 400
                    tracker.onOcrResult(blocks, size, clock, clock)
                    frame.recycle()

                    val payment = tracker.corroborated
                        .maxByOrNull { TextNormalizer.lineSimilarity(it.block.normalizedText, TextNormalizer.normalize(target)) }
                    Log.i(
                        TAG,
                        String.format(
                            Locale.ROOT,
                            "  run %d: best='%s' conf=%.2f seen=x%d trusted=%b",
                            index + 1,
                            payment?.block?.text ?: "-",
                            payment?.bestConfidence ?: 0f,
                            payment?.timesSeen ?: 0,
                            payment?.let { TargetMatcher.isTrustworthy(it) } ?: false,
                        ),
                    )
                }

                val match = TargetMatcher.matchObserved("გადახდა მინდა", tracker.corroborated)
                val verdict = when (match) {
                    is TargetMatch.Found -> "FOUND '${match.block.text}'"
                    is TargetMatch.Ambiguous -> "AMBIGUOUS"
                    is TargetMatch.NotFound -> "NOT FOUND (${match.reason})"
                }
                Log.i(TAG, "  after 3 handheld frames -> $verdict")
            }
        } finally {
            ocr.close()
        }
    }

    /**
     * Recognition rate over repeated trials with randomised handheld movement, comparing the
     * old single-frame gate against the corroboration the app now uses.
     *
     * Each trial is three frames with its own random drift, tilt and blur, as a hand produces.
     * The "single frame" column is what the previous implementation would have decided from the
     * last frame alone.
     */
    @Test
    fun recognitionRateAcrossRepeatedTrials() {
        val ocr = KioskOcrEngine.create(appContext)
        val size = FrameSize(width, height)
        val random = java.util.Random(20261014)
        try {
            for ((band, maxBlur) in listOf("steady" to 2f, "light movement" to 6f, "heavy movement" to 12f)) {
                var singleFrame = 0
                var corroborated = 0
                var totalMs = 0L
                var runs = 0

                repeat(TRIALS) {
                    val tracker = TargetTracker()
                    var clock = 1_000L
                    var lastBlocks: List<DetectedTextBlock> = emptyList()
                    repeat(3) { index ->
                        val blur = random.nextFloat() * maxBlur
                        val frame = moved(
                            random.nextFloat() * 20f - 10f,
                            random.nextFloat() * 14f - 7f,
                            random.nextFloat() * 3f - 1.5f,
                            blur,
                        )
                        val started = SystemClock.elapsedRealtime()
                        lastBlocks = ocr.recognize(frame)
                        totalMs += SystemClock.elapsedRealtime() - started
                        runs++
                        clock += 400
                        tracker.onOcrResult(lastBlocks, size, clock, clock)
                        frame.recycle()
                    }
                    // What the old single-frame gate would have concluded from the final frame.
                    if (TargetMatcher.match("გადახდა მინდა", lastBlocks) is TargetMatch.Found) singleFrame++
                    if (TargetMatcher.matchObserved("გადახდა მინდა", tracker.corroborated) is TargetMatch.Found) corroborated++
                }

                Log.i(
                    TAG,
                    String.format(
                        Locale.ROOT,
                        "%-16s single-frame %2d/%d (%3.0f%%)   corroborated %2d/%d (%3.0f%%)   OCR %dms/run",
                        band,
                        singleFrame, TRIALS, 100f * singleFrame / TRIALS,
                        corroborated, TRIALS, 100f * corroborated / TRIALS,
                        totalMs / runs,
                    ),
                )
            }
        } finally {
            ocr.close()
        }
    }

    /**
     * At what on-screen size does Tesseract stop reading `გადახდა`?
     *
     * The real-camera sessions framed the whole host display, so the kiosk occupied a small part
     * of a 576x1280 analysis frame and its labels were only a dozen-odd pixels tall. This
     * measures the floor, which decides whether "hold the phone closer" is advice or whether the
     * display region has to be cropped and upscaled before OCR.
     */
    @Test
    fun smallestReadableLabelHeight() {
        val ocr = KioskOcrEngine.create(appContext)
        try {
            Log.i(TAG, "=== recognition of 'გადახდა' by rendered text size ===")
            for (textSize in listOf(44f, 36f, 30f, 24f, 20f, 16f, 13f, 10f)) {
                val bitmap = createBitmap(width, height)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(10, 10, 20); this.textSize = textSize; textAlign = Paint.Align.CENTER
                }
                canvas.drawText(target, width / 2f, height / 2f, paint)
                val bounds = android.graphics.Rect()
                paint.getTextBounds(target, 0, target.length, bounds)

                val blocks = ocr.recognize(bitmap)
                val best = blocks.maxByOrNull {
                    TextNormalizer.lineSimilarity(it.normalizedText, TextNormalizer.normalize(target))
                }
                val matched = TargetMatcher.match(target, blocks) is TargetMatch.Found
                Log.i(
                    TAG,
                    String.format(
                        Locale.ROOT,
                        "textSize %4.0fpx (glyphs %2dpx tall)  read='%s' conf=%.2f  matched=%b",
                        textSize, bounds.height(),
                        best?.text ?: "-", best?.confidence ?: 0f, matched,
                    ),
                )
                bitmap.recycle()
            }
        } finally {
            ocr.close()
        }
    }

    private companion object {
        const val TAG = "OcrMotionDiagnostic"
        const val TRIALS = 12
    }
}
