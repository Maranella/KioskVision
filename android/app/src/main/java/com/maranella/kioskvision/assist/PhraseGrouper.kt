package com.maranella.kioskvision.assist

import kotlin.math.max

/** One OCR word, as reported by the engine. */
data class OcrWord(
    val text: String,
    val box: TextBox,
    /** 0..1 */
    val confidence: Float,
    /** True for the first word of an OCR text line. */
    val startsLine: Boolean,
)

/**
 * Splits OCR text lines into phrases at large horizontal gaps.
 *
 * Tesseract groups everything on one baseline into a single line, so two buttons side by side
 * ("გადახდა" | "ბალანსის შემოწმება") would become one block whose centre lies *between* the
 * buttons. Word spacing inside a label is typically 0.3–0.5× the text height; a gap larger
 * than [MAX_GAP_TO_HEIGHT]× the height is treated as a boundary between separate labels.
 */
object PhraseGrouper {

    const val MAX_GAP_TO_HEIGHT = 1.0f

    fun group(words: List<OcrWord>): List<DetectedTextBlock> {
        val phrases = mutableListOf<MutableList<OcrWord>>()
        for (word in words) {
            if (word.text.isBlank()) continue
            val current = phrases.lastOrNull()
            val previous = current?.lastOrNull()
            val split = previous == null || word.startsLine ||
                word.box.left - previous.box.right > MAX_GAP_TO_HEIGHT * max(word.box.height, previous.box.height)
            if (split) phrases += mutableListOf(word) else current!! += word
        }
        return phrases.map { phrase ->
            DetectedTextBlock(
                text = phrase.joinToString(" ") { it.text.trim() },
                boundingBox = TextBox(
                    left = phrase.minOf { it.box.left },
                    top = phrase.minOf { it.box.top },
                    right = phrase.maxOf { it.box.right },
                    bottom = phrase.maxOf { it.box.bottom },
                ),
                // Weighted by length, so one short misread word does not dominate.
                confidence = phrase.sumOf { it.confidence.toDouble() * it.text.length }.toFloat() /
                    phrase.sumOf { it.text.length },
            )
        }
    }
}
