package com.maranella.kioskvision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maranella.kioskvision.assist.DetectedTextBlock
import com.maranella.kioskvision.assist.FrameSize
import com.maranella.kioskvision.assist.ObservedLabel
import com.maranella.kioskvision.assist.TargetMatch
import com.maranella.kioskvision.assist.TargetMatcher
import com.maranella.kioskvision.assist.TextNormalizer
import com.maranella.kioskvision.camera.FrameQuality
import com.maranella.kioskvision.ocr.KioskOcrEngine
import com.maranella.kioskvision.ocr.OcrUpscale
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Runs the frozen OCR configurations over **real photographs** of kiosk screens and reports
 * accuracy, control localization, target selection and latency per image and per condition.
 *
 * This test reads its dataset from the device rather than from the APK, so photographs and
 * annotations can be added without rebuilding:
 *
 * ```
 * adb push photos/.      /sdcard/Android/data/com.maranella.kioskvision/files/ocr-eval/
 * adb push dataset.json  /sdcard/Android/data/com.maranella.kioskvision/files/ocr-eval/
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.maranella.kioskvision.RealKioskEvaluationTest
 * adb pull /sdcard/Android/data/com.maranella.kioskvision/files/ocr-eval/results.csv
 * ```
 *
 * With no dataset present the test **skips** rather than fails, so the suite stays green on a
 * machine that has not been given photographs.
 *
 * Nothing here touches production behaviour: it calls the shipped engine and matcher exactly as
 * the pipeline does, and writes only to its own directory.
 */
@RunWith(AndroidJUnit4::class)
class RealKioskEvaluationTest {

    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The two configurations frozen before the first real-photo run.
     *
     * [UPSCALED] is what the app ships today: 2x only when the median label height on the
     * screen is below [OcrUpscale.SMALL_TEXT_FRACTION] of the frame. [BASELINE] is the
     * configuration before that change. Nothing else differs.
     */
    private enum class Config { BASELINE, UPSCALED }

    private class Image(
        val file: String,
        val screen: String,
        val conditions: Map<String, String>,
        val lines: List<String>,
        val controls: List<Control>,
        val queries: List<Query>,
    )

    private class Control(val label: String, val box: FloatArray)
    private class Query(val say: String, val expect: String)

    private class Row(
        val image: String,
        val screen: String,
        val config: Config,
        val cer: Double,
        val wer: Double,
        val exactLabels: Int,
        val totalLabels: Int,
        val exactNumeric: Int,
        val totalNumeric: Int,
        val controlsLocalized: Int,
        val totalControls: Int,
        val targetsCorrect: Int,
        val targetsWrong: Int,
        val targetsAmbiguous: Int,
        val targetsAbstained: Int,
        val latencyMs: Long,
        val sharpness: Float,
        val conditions: Map<String, String>,
    )

    private fun datasetDir(): File = File(appContext.getExternalFilesDir(null), "ocr-eval")

    private fun loadDataset(): List<Image> {
        val manifest = File(datasetDir(), "dataset.json")
        if (!manifest.isFile) return emptyList()
        val root = JSONObject(manifest.readText())
        val images = root.optJSONArray("images") ?: return emptyList()
        return (0 until images.length()).map { i ->
            val o = images.getJSONObject(i)
            val conditions = o.optJSONObject("conditions")?.let { c ->
                c.keys().asSequence().associateWith { c.get(it).toString() }
            } ?: emptyMap()
            val lines = o.optJSONArray("lines")?.let { a ->
                (0 until a.length()).map { a.getString(it) }
            } ?: emptyList()
            val controls = o.optJSONArray("controls")?.let { a ->
                (0 until a.length()).map { k ->
                    val c = a.getJSONObject(k)
                    val b = c.getJSONArray("box")
                    Control(c.getString("label"), FloatArray(4) { b.getDouble(it).toFloat() })
                }
            } ?: emptyList()
            val queries = o.optJSONArray("queries")?.let { a ->
                (0 until a.length()).map { k ->
                    val q = a.getJSONObject(k)
                    Query(q.getString("say"), q.getString("expect"))
                }
            } ?: emptyList()
            Image(o.getString("file"), o.optString("screen", "?"), conditions, lines, controls, queries)
        }
    }

    // --- Metrics -------------------------------------------------------------------------------

    private fun <T> editDistance(a: List<T>, b: List<T>): Int {
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
    private fun words(s: String) = s.split(" ", " ").filter { it.isNotEmpty() }
    private fun isNumeric(s: String) = s.any { it.isDigit() }

    /** Intersection over union of two boxes given as [left, top, right, bottom], normalized. */
    private fun iou(a: FloatArray, b: FloatArray): Float {
        val left = max(a[0], b[0]); val top = max(a[1], b[1])
        val right = min(a[2], b[2]); val bottom = min(a[3], b[3])
        if (right <= left || bottom <= top) return 0f
        val inter = (right - left) * (bottom - top)
        val areaA = (a[2] - a[0]) * (a[3] - a[1])
        val areaB = (b[2] - b[0]) * (b[3] - b[1])
        return inter / (areaA + areaB - inter)
    }

    private fun evaluate(image: Image, bitmap: Bitmap, blocks: List<DetectedTextBlock>, config: Config,
                         latency: Long, sharpness: Float): Row {
        var charErrors = 0; var charTotal = 0
        var wordErrors = 0; var wordTotal = 0
        var exactLabels = 0; var totalLabels = 0
        var exactNumeric = 0; var totalNumeric = 0

        for (truth in image.lines) {
            val best = blocks.minByOrNull { editDistance(chars(truth), chars(it.text.trim())) }?.text?.trim()
            val distance = best?.let { editDistance(chars(truth), chars(it)) } ?: truth.length
            charErrors += min(distance, truth.length); charTotal += truth.length
            val wordDistance = best?.let { editDistance(words(truth), words(it)) } ?: words(truth).size
            wordErrors += min(wordDistance, words(truth).size); wordTotal += words(truth).size
            if (isNumeric(truth)) {
                totalNumeric++
                if (best == truth) exactNumeric++
            } else {
                totalLabels++
                if (best == truth) exactLabels++
            }
        }

        // Control localization: the recognised block for a control must overlap its annotation.
        var localized = 0
        val frame = FrameSize(bitmap.width, bitmap.height)
        for (control in image.controls) {
            val match = blocks
                .filter { TextNormalizer.lineSimilarity(it.normalizedText, TextNormalizer.normalize(control.label)) >= 0.7f }
                .maxByOrNull {
                    iou(
                        control.box,
                        floatArrayOf(
                            it.boundingBox.left / frame.width, it.boundingBox.top / frame.height,
                            it.boundingBox.right / frame.width, it.boundingBox.bottom / frame.height,
                        ),
                    )
                }
            if (match != null) {
                val overlap = iou(
                    control.box,
                    floatArrayOf(
                        match.boundingBox.left / frame.width, match.boundingBox.top / frame.height,
                        match.boundingBox.right / frame.width, match.boundingBox.bottom / frame.height,
                    ),
                )
                if (overlap >= MIN_IOU) localized++
            }
        }

        // Target selection through the shipped matcher, with one observation per label — the
        // single-frame case, which is the hardest for corroboration.
        var correct = 0; var wrong = 0; var ambiguous = 0; var abstained = 0
        val observed = blocks.map { ObservedLabel(it, timesSeen = 1, bestConfidence = it.confidence) }
        for (query in image.queries) {
            when (val match = TargetMatcher.matchObserved(query.say, observed)) {
                is TargetMatch.Found -> {
                    val similar = TextNormalizer.lineSimilarity(
                        TextNormalizer.normalize(match.block.text),
                        TextNormalizer.normalize(query.expect),
                    )
                    if (similar >= 0.7f) correct++ else wrong++
                }
                is TargetMatch.Ambiguous -> ambiguous++
                is TargetMatch.NotFound -> abstained++
            }
        }

        return Row(
            image.file, image.screen, config,
            100.0 * charErrors / max(1, charTotal), 100.0 * wordErrors / max(1, wordTotal),
            exactLabels, totalLabels, exactNumeric, totalNumeric,
            localized, image.controls.size,
            correct, wrong, ambiguous, abstained,
            latency, sharpness, image.conditions,
        )
    }

    private fun sharpnessOf(bitmap: Bitmap): Float {
        val side = min(256, min(bitmap.width, bitmap.height))
        if (side < 2) return 0f
        val pixels = IntArray(side * side)
        bitmap.getPixels(pixels, 0, side, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        return FrameQuality.sharpness(pixels, side, side)
    }

    @Test
    fun evaluateAgainstRealKioskPhotos() {
        val dataset = loadDataset()
        if (dataset.isEmpty()) {
            // Reported rather than asserted: Gradle renders an assumption failure as a red
            // test, and a machine with no photographs yet should not look like a regression.
            Log.w(TAG, "SKIPPED — no dataset at ${datasetDir()}; push photos and dataset.json to run this")
            return
        }
        Log.i(TAG, "=== real kiosk evaluation: ${dataset.size} images ===")

        val engine = KioskOcrEngine.create(appContext)
        val rows = mutableListOf<Row>()
        try {
            for (image in dataset) {
                val file = File(datasetDir(), image.file)
                if (!file.isFile) {
                    Log.w(TAG, "missing image ${image.file}")
                    continue
                }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    ?.copy(Bitmap.Config.ARGB_8888, false) ?: continue
                val sharpness = sharpnessOf(bitmap)

                for (config in Config.entries) {
                    // The shipped rule: enlarge only when this screen's text is small. Applied
                    // here exactly as the pipeline applies it, from a first pass at 1x.
                    val upscale = if (config == Config.BASELINE) {
                        1f
                    } else {
                        val probe = engine.recognize(bitmap)
                        OcrUpscale.forLabelHeights(probe.map { it.boundingBox.height }, bitmap.height)
                    }
                    val started = SystemClock.elapsedRealtime()
                    val blocks = engine.recognize(bitmap, null, upscale)
                    val latency = SystemClock.elapsedRealtime() - started
                    rows += evaluate(image, bitmap, blocks, config, latency, sharpness)
                }
                bitmap.recycle()
            }
        } finally {
            engine.close()
        }

        report(rows)
        export(rows)
    }

    private fun report(rows: List<Row>) {
        for (config in Config.entries) {
            val group = rows.filter { it.config == config }
            if (group.isEmpty()) continue
            val latencies = group.map { it.latencyMs }.sorted()
            Log.i(TAG, "--- ${config.name} (n=${group.size} images) ---")
            Log.i(
                TAG,
                String.format(
                    Locale.ROOT,
                    "CER %.1f%%  WER %.1f%%  labels %d/%d  numeric %d/%d  controls %d/%d  p50 %d ms  p95 %d ms",
                    group.sumOf { it.cer * it.totalLabels } / max(1, group.sumOf { it.totalLabels }),
                    group.sumOf { it.wer * it.totalLabels } / max(1, group.sumOf { it.totalLabels }),
                    group.sumOf { it.exactLabels }, group.sumOf { it.totalLabels },
                    group.sumOf { it.exactNumeric }, group.sumOf { it.totalNumeric },
                    group.sumOf { it.controlsLocalized }, group.sumOf { it.totalControls },
                    latencies[latencies.size / 2],
                    latencies[min(latencies.size - 1, (latencies.size * 95) / 100)],
                ),
            )
            Log.i(
                TAG,
                "targets: correct ${group.sumOf { it.targetsCorrect }}  wrong ${group.sumOf { it.targetsWrong }}  " +
                    "ambiguous ${group.sumOf { it.targetsAmbiguous }}  abstained ${group.sumOf { it.targetsAbstained }}",
            )
            // Per-condition breakdown, so a single hard condition is visible rather than averaged away.
            group.flatMap { row -> row.conditions.map { (k, v) -> "$k=$v" to row } }
                .groupBy({ it.first }, { it.second })
                .toSortedMap()
                .forEach { (bucket, bucketRows) ->
                    if (bucketRows.size >= 2) {
                        Log.i(
                            TAG,
                            String.format(
                                Locale.ROOT, "    %-22s n=%d  CER %.1f%%  labels %d/%d",
                                bucket, bucketRows.size,
                                bucketRows.sumOf { it.cer * it.totalLabels } / max(1, bucketRows.sumOf { it.totalLabels }),
                                bucketRows.sumOf { it.exactLabels }, bucketRows.sumOf { it.totalLabels },
                            ),
                        )
                    }
                }
        }
    }

    private fun export(rows: List<Row>) {
        val csv = File(datasetDir(), "results.csv")
        csv.printWriter().use { out ->
            out.println(
                "image,screen,config,cer,wer,exact_labels,total_labels,exact_numeric,total_numeric," +
                    "controls_localized,total_controls,targets_correct,targets_wrong,targets_ambiguous," +
                    "targets_abstained,latency_ms,sharpness,conditions",
            )
            for (r in rows) {
                out.println(
                    listOf(
                        r.image, r.screen, r.config.name,
                        String.format(Locale.ROOT, "%.2f", r.cer), String.format(Locale.ROOT, "%.2f", r.wer),
                        r.exactLabels, r.totalLabels, r.exactNumeric, r.totalNumeric,
                        r.controlsLocalized, r.totalControls,
                        r.targetsCorrect, r.targetsWrong, r.targetsAmbiguous, r.targetsAbstained,
                        r.latencyMs, String.format(Locale.ROOT, "%.4f", r.sharpness),
                        r.conditions.entries.joinToString(";") { "${it.key}=${it.value}" },
                    ).joinToString(","),
                )
            }
        }
        Log.i(TAG, "wrote ${csv.absolutePath}")
    }

    private companion object {
        const val TAG = "RealKioskEval"

        /** Overlap required before a control counts as localized. */
        const val MIN_IOU = 0.3f
    }
}
