package com.maranella.kioskvision.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.graphics.scale
import com.maranella.kioskvision.assist.DetectedTextBlock
import com.maranella.kioskvision.assist.OcrWord
import com.maranella.kioskvision.assist.PhraseGrouper
import com.maranella.kioskvision.assist.TextBox
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Tesseract 5 (via Tesseract4Android) with the Georgian `kat` and English `eng` LSTM models
 * from `tessdata_fast`, bundled in `assets/tessdata` (provenance in `app/MODELS.md`).
 *
 * Not thread-safe: create, use and close it on a single background thread.
 */
class KioskOcrEngine private constructor(private val tess: TessBaseAPI) : Closeable {

    /**
     * Recognizes text in [bitmap] and returns one block per label-like phrase (see
     * [PhraseGrouper]). Boxes are in the bitmap's pixel coordinates.
     *
     * When [region] is given, only that part of the bitmap is recognized. This is how the
     * pipeline re-anchors a locked target cheaply: it costs a fraction of a full-frame run and
     * needs no cropped copy of the bitmap. Returned boxes stay in **full-bitmap** coordinates,
     * so a region result drops straight into the same geometry as a full-frame one.
     */
    fun recognize(bitmap: Bitmap, region: Rect? = null, upscale: Float = 1f): List<DetectedTextBlock> {
        if (upscale > 1f) return recognizeUpscaled(bitmap, region, upscale)
        tess.setImage(bitmap)
        try {
            if (region != null) tess.setRectangle(region)
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
            tess.clear()
        }
    }

    /**
     * Recognizes an enlarged copy and maps every box back to [bitmap]'s coordinates.
     *
     * Measured on device: at 2x, exact recognition of kiosk labels rose from 52% to 67% on a
     * rendered corpus, the gain coming entirely from small text (12 px CER 5% -> 0%). Boxes are
     * divided by the same factor they were magnified by, so nothing downstream sees the change.
     */
    private fun recognizeUpscaled(bitmap: Bitmap, region: Rect?, upscale: Float): List<DetectedTextBlock> {
        // With a region, only that part is enlarged, which keeps the copy small.
        val source = region ?: Rect(0, 0, bitmap.width, bitmap.height)
        if (source.width() < 2 || source.height() < 2) return emptyList()
        val cropped = Bitmap.createBitmap(bitmap, source.left, source.top, source.width(), source.height())
        val enlarged = cropped.scale((source.width() * upscale).toInt(), (source.height() * upscale).toInt())
        if (enlarged !== cropped) cropped.recycle()
        return try {
            recognize(enlarged).map { block ->
                block.copy(
                    boundingBox = TextBox(
                        left = block.boundingBox.left / upscale + source.left,
                        top = block.boundingBox.top / upscale + source.top,
                        right = block.boundingBox.right / upscale + source.left,
                        bottom = block.boundingBox.bottom / upscale + source.top,
                    ),
                )
            }
        } finally {
            enlarged.recycle()
        }
    }

    override fun close() {
        tess.recycle()
    }

    companion object {
        const val LANGUAGES = "kat+eng"
        private const val LINE = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
        private const val WORD = TessBaseAPI.PageIteratorLevel.RIL_WORD
        private val TRAINED_DATA = listOf("kat.traineddata", "eng.traineddata")
        /** Bump when the bundled trained data changes, so devices re-copy it. */
        private const val DATA_VERSION = 1

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
            return KioskOcrEngine(tess)
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
