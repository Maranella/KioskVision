package com.maranella.kioskvision.assist

data class ScreenReading(
    /** Lines to speak, in reading order. */
    val lines: List<String>,
    /** True if some text was left out because it could not be read reliably. */
    val omittedUncertain: Boolean,
)

/**
 * Turns OCR output into an ordered list of lines that is safe to read aloud.
 */
object ScreenReader {

    private const val MIN_CONFIDENCE = 0.55f
    /** Amounts and other numbers are only read when OCR is very sure of them. */
    private const val MIN_NUMERIC_CONFIDENCE = 0.8f

    private val sensitiveContext = listOf("pin", "პინ", "კოდ", "პაროლ", "password", "code")
    private val maskedOrDigitsOnly = Regex("^[\\d\\s*•●·xX-]{3,}$")

    fun read(blocks: List<DetectedTextBlock>): ScreenReading {
        val sensitiveScreen = blocks.any { block -> sensitiveContext.any { block.normalizedText.contains(it) } }
        var omitted = false
        val readable = blocks.filter { block ->
            val text = block.text.trim()
            val hasDigits = text.any { it.isDigit() }
            when {
                // On PIN/code screens, never read digit or masked fields aloud.
                sensitiveScreen && maskedOrDigitsOnly.matches(text) -> false
                TextNormalizer.letterCount(text) < 2 && !hasDigits -> false
                block.confidence < MIN_CONFIDENCE -> { omitted = true; false }
                hasDigits && block.confidence < MIN_NUMERIC_CONFIDENCE -> { omitted = true; false }
                else -> true
            }
        }
        return ScreenReading(lines = readingOrder(readable).map { it.text.trim() }, omittedUncertain = omitted)
    }

    /**
     * Top-to-bottom, then left-to-right. Lines whose vertical centres are within half a line
     * height of each other are treated as one row, so side-by-side buttons read left to right.
     */
    fun readingOrder(blocks: List<DetectedTextBlock>): List<DetectedTextBlock> {
        val rows = mutableListOf<MutableList<DetectedTextBlock>>()
        for (block in blocks.sortedBy { it.boundingBox.centerY }) {
            val row = rows.lastOrNull()
            val rowCenter = row?.map { it.boundingBox.centerY }?.average()?.toFloat()
            val tolerance = 0.5f * block.boundingBox.height
            if (row != null && rowCenter != null && kotlin.math.abs(block.boundingBox.centerY - rowCenter) <= tolerance) {
                row += block
            } else {
                rows += mutableListOf(block)
            }
        }
        return rows.flatMap { row -> row.sortedBy { it.boundingBox.left } }
    }
}
