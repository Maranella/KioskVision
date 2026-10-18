package com.maranella.kioskvision.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import androidx.core.graphics.scale
import com.maranella.kioskvision.assist.DetectedTextBlock
import com.maranella.kioskvision.assist.OcrWord
import com.maranella.kioskvision.assist.PhraseGrouper
import com.maranella.kioskvision.assist.TextBox
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Tesseract 5 (via Tesseract4Android) with the Georgian `kat` and English `eng` LSTM models
 * from `tessdata_fast`, bundled in `assets/tessdata` (provenance in `app/MODELS.md`).
 *
 * Not thread-safe: create, use and close it on a single background thread.
 */
class KioskOcrEngine private constructor(
    private val tess: TessBaseAPI,
    /** App-private directory for diagnostic dumps. Never leaves the device. */
    private val dumpDir: File,
) : Closeable {

    /**
     * Writes the next image Tesseract is given to [dumpDir], once, then clears itself.
     *
     * Exists because every OCR investigation so far has been blocked by the same thing: no
     * frame of the failing machine, so nothing is reproducible on a desk and every hypothesis
     * stays a hypothesis. This captures **exactly** what the engine reads — after cropping and
     * after magnification — which is the only image that can settle whether a failure is the
     * crop, the resolution or the recognition.
     *
     * Debug use only, one shot, app-private storage. Nothing is uploaded and nothing is
     * written unless a developer asks for it.
     */
    @Volatile
    var dumpNextInput: Boolean = false

    private fun dumpIfRequested(bitmap: Bitmap, label: String) {
        if (!dumpNextInput) return
        dumpNextInput = false
        runCatching {
            if (!dumpDir.isDirectory) dumpDir.mkdirs()
            val file = File(dumpDir, "ocr-input-$label-${System.currentTimeMillis()}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(TAG, "OCR input written to ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
        }.onFailure { Log.w(TAG, "could not write OCR input dump", it) }
    }

    /**
     * Recognizes text in [bitmap] and returns one block per label-like phrase (see
     * [PhraseGrouper]). Boxes are in **[bitmap]'s** pixel coordinates whatever was read, so a
     * region result drops straight into the same geometry as a full-frame one.
     *
     * When [region] is given, only that part is read. This is how the pipeline re-anchors a
     * locked target cheaply: it costs a fraction of a full-frame run (~34 ms against 442-568,
     * R-11). [upscale] magnifies the image for text that is too small to resolve (R-16); the
     * contrast normalisation in [OcrPreprocess] is applied either way, for text that is
     * legible but faint.
     */
    fun recognize(bitmap: Bitmap, region: Rect? = null, upscale: Float = 1f): List<DetectedTextBlock> {
        val bounds = Rect(0, 0, bitmap.width, bitmap.height)
        // Clamped, not trusted. A region arrives from screen geometry that may have been
        // computed against a frame of a different size, and reading past the edge of the
        // bitmap throws — which the pipeline would catch and report as "OCR found nothing",
        // the one failure that looks exactly like a dark screen.
        val source = region?.let { Rect(it).apply { if (!intersect(bounds)) setEmpty() } } ?: bounds
        if (source.width() < 2 || source.height() < 2) return emptyList()

        // One prepared image per run: the crop, contrast-normalised, then magnified if the
        // caller asked. Tesseract is handed exactly this and nothing else, which is also what
        // `dumpNextInput` writes out — so the image in a bug report is the image that failed.
        //
        // `setRectangle` on the original frame used to serve the crop. It is gone because
        // preprocessing has to see the crop's own histogram: a bright kiosk screen in a dark
        // room is stretched by what is on the screen, not by the room around it.
        // Never below 1: shrinking would throw detail away, and the box arithmetic below
        // divides by this factor, so a zero here would be a crash rather than a soft image.
        val factor = upscale.coerceAtLeast(1f)
        val normalised = OcrPreprocess.normalisedCrop(bitmap, source)
        val prepared = if (factor > 1f) {
            normalised.scale((source.width() * factor).toInt(), (source.height() * factor).toInt())
                .also { if (it !== normalised) normalised.recycle() }
        } else {
            normalised
        }
        dumpIfRequested(prepared, label(region, factor))

        return try {
            recognizePrepared(prepared).map { block ->
                block.copy(
                    boundingBox = TextBox(
                        left = block.boundingBox.left / factor + source.left,
                        top = block.boundingBox.top / factor + source.top,
                        right = block.boundingBox.right / factor + source.left,
                        bottom = block.boundingBox.bottom / factor + source.top,
                    ),
                )
            }
        } finally {
            prepared.recycle()
        }
    }

    private fun label(region: Rect?, upscale: Float): String =
        (if (region == null) "whole" else "crop") + "-%.1fx".format(upscale)

    /** Recognition itself, over an image that is already cropped, normalised and magnified. */
    private fun recognizePrepared(image: Bitmap): List<DetectedTextBlock> {
        val budget = startBudget()
        tess.setImage(image)
        try {
            // Runs recognition; the iterator below reads its per-word results.
            tess.getUTF8Text()
            val iterator = tess.resultIterator ?: return emptyList()
            val words = mutableListOf<OcrWord>()
            try {
                iterator.begin()
                do {
                    val text = iterator.getUTF8Text(WORD)?.trim().orEmpty()
                    if (text.isEmpty()) continue
                    val rect = iterator.getBoundingRect(WORD)
                    words += OcrWord(
                        text = text,
                        box = TextBox(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()),
                        confidence = (iterator.confidence(WORD) / 100f).coerceIn(0f, 1f),
                        startsLine = iterator.isAtBeginningOf(LINE),
                    )
                } while (iterator.next(WORD))
            } finally {
                iterator.delete()
            }
            return PhraseGrouper.group(words)
        } finally {
            budget.cancel(false)
            tess.clear()
        }
    }

    /** One thread, only ever used to interrupt a run that has overstayed its budget. */
    private val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ocr-watchdog").apply { isDaemon = true }
    }

    /**
     * Asks Tesseract to give up if a single run outlasts [BUDGET_MS].
     *
     * Measured on the device: one whole-frame run took **99 174 ms** and returned **1599
     * blocks**, which the pipeline then discarded as noise — a minute and a half of both CPU
     * and battery spent on an image that was never going to yield a screen, during which
     * `ocrInFlight` blocked every other scan. Legitimate runs on the same hardware cost
     * 585-1525 ms, and R-11's worst real-camera full frame was 6.3 s, so the budget sits above
     * anything that has ever produced a usable result and far below the pathology.
     *
     * `stop()` asks the current recognition to end early; the iterator then yields whatever was
     * finished, which the caller treats like any other short result. Returning something partial
     * beats spending another minute on noise.
     */
    private fun startBudget(): ScheduledFuture<*> = watchdog.schedule(
        {
            Log.w(TAG, "OCR exceeded ${BUDGET_MS}ms; asking Tesseract to stop")
            runCatching { tess.stop() }
        },
        BUDGET_MS,
        TimeUnit.MILLISECONDS,
    )

    override fun close() {
        watchdog.shutdownNow()
        tess.recycle()
    }

    companion object {
        const val LANGUAGES = "kat+eng"
        private const val LINE = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
        private const val WORD = TessBaseAPI.PageIteratorLevel.RIL_WORD
        private val TRAINED_DATA = listOf("kat.traineddata", "eng.traineddata")
        /** Bump when the bundled trained data changes, so devices re-copy it. */
        private const val DATA_VERSION = 1
        private const val TAG = "KioskOcrEngine"

        /**
         * Longest a single recognition may run. Above every measured useful run (585-1525 ms
         * here, 6.3 s worst case in R-11) and far below the 99 s pathology it exists to stop.
         */
        private const val BUDGET_MS = 8_000L

        /** App-private, developer-triggered only. Retrieve with `adb run-as`. */
        const val DUMP_DIR = "ocr-dumps"

        /** Copies the trained data out of the APK if needed and initializes Tesseract. Blocking. */
        @Throws(IOException::class)
        fun create(context: Context): KioskOcrEngine {
            val root = File(context.filesDir, "tesseract-v$DATA_VERSION")
            installTrainedData(context, File(root, "tessdata"))
            val tess = TessBaseAPI()
            if (!tess.init(root.absolutePath, LANGUAGES, TessBaseAPI.OEM_LSTM_ONLY)) {
                tess.recycle()
                throw IOException("Tesseract failed to initialize with $LANGUAGES")
            }
            // Kiosk screens are scattered labels rather than paragraphs.
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
            return KioskOcrEngine(tess, File(context.filesDir, DUMP_DIR))
        }

        private fun installTrainedData(context: Context, dir: File) {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
            for (name in TRAINED_DATA) {
                val target = File(dir, name)
                if (target.isFile && target.length() > 0) continue
                val partial = File(dir, "$name.partial")
                context.assets.open("tessdata/$name").use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
                if (!partial.renameTo(target)) throw IOException("Cannot install $target")
            }
        }
    }
}
