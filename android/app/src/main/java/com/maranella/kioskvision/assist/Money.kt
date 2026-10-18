package com.maranella.kioskvision.assist

/**
 * An amount read from the kiosk screen, in tetri (1 lari = 100 tetri).
 *
 * Integer minor units rather than a float, because this is compared and spoken: 3.50 and 3.5
 * must be one value and must never drift by a rounding error into a different one.
 *
 * [raw] is the text it was read from, kept so a spoken amount can always be traced back to the
 * OCR line that produced it.
 */
data class Money(val tetri: Int, val raw: String) : Comparable<Money> {
    val lari: Int get() = tetri / 100
    val remainderTetri: Int get() = tetri % 100

    override fun compareTo(other: Money): Int = tetri.compareTo(other.tetri)
}

/**
 * Reads an amount out of one OCR line — or refuses.
 *
 * ### Why this is deliberately strict
 *
 * A kiosk screen is full of numbers that are not prices: a terminal id, a clock, a date, a cup
 * count, a percentage, a strength setting. Associating any of those with a product and
 * speaking it as its price is exactly the fabrication the safety invariants forbid, and it is
 * worse than silence because the user cannot check it.
 *
 * A line is an amount only when it carries a currency marker, or is written the way money is
 * written and carries no words that would make it something else. Bare integers are always
 * refused: `140` cups and `140` tetri are indistinguishable from the text alone, and a guess
 * is not worth a wrong price.
 *
 * ### What OCR does to the lari sign
 *
 * ₾ (U+20BE) is frequently mangled. The marker set therefore includes the Georgian and Latin
 * spellings, which survive recognition far better than the glyph.
 */
object MoneyParser {

    /** Currency markers, lowercased. Georgian stems included: they survive OCR better than ₾. */
    private val currencyMarkers = listOf("₾", "ლარ", "gel", "lari")

    /** Time, dates, percentages and ranges: present in a line, it is not a price. */
    private val disqualifying = Regex("[%:/]|\\d{4}")

    /** `3.50`, `3,50`, `3.5`, `12` — one number, with an optional one-or-two-digit fraction. */
    private val amountPattern = Regex("(\\d{1,4})(?:[.,](\\d{1,2}))?")

    /** Beyond this a "price" is a reading error, not a drink. */
    private const val MAX_TETRI = 10_000_00

    fun parse(text: String): Money? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        if (disqualifying.containsMatchIn(lower)) return null

        val hasCurrency = currencyMarkers.any { lower.contains(it) }

        // More than one number on the line (`− 3 +`, a phone number) makes it ambiguous which
        // one the amount would be, so none of them is used.
        val match = amountPattern.findAll(lower).toList().singleOrNull() ?: return null

        val whole = match.groupValues[1].toIntOrNull() ?: return null
        val fractionText = match.groupValues[2]
        val hasFraction = fractionText.isNotEmpty()

        // Written as money: a decimal fraction, or an explicit currency. A bare integer with
        // neither is a count, a setting or a temperature far more often than it is a price.
        if (!hasFraction && !hasCurrency) return null

        if (!hasCurrency) {
            // No currency word, so the only other evidence is the shape of the line. What is
            // left after the number must be nothing, or a single stray character — which is
            // what ₾ becomes when OCR cannot place it.
            //
            // Measured on the SM-S731B over the coffee-machine target: the five amounts came
            // back as `3.50 C`, `4.00 დ`, `5.00 &`, `5.00 ©` and `8.50 C`. Never the sign
            // itself, and never twice the same substitute. Requiring the glyph would have
            // discarded every price on the screen; allowing a *word* beside a number would
            // have accepted „150 მლ" as an amount. One character is the line between them.
            if (residualOf(lower).length > MAX_MANGLED_CURRENCY_CHARS) return null
        }

        val fraction = when (fractionText.length) {
            0 -> 0
            1 -> fractionText.toInt() * 10
            else -> fractionText.toInt()
        }
        val tetri = whole * 100 + fraction
        if (tetri <= 0 || tetri > MAX_TETRI) return null

        return Money(tetri = tetri, raw = trimmed)
    }

    /**
     * The words on an amount line, when there are enough of them to name what the amount is
     * for — „მომსახურების საკომისიო 0.20 ₾" labels itself, `4.00 ₾` does not.
     *
     * Null when the line is only an amount, which is the case the geometry has to solve.
     */
    fun labelPartOf(text: String): String? {
        var remainder = text
        for (marker in currencyMarkers) {
            remainder = remainder.replace(marker, " ", ignoreCase = true)
        }
        remainder = remainder.replace(Regex("[\\d.,·–—−+-]"), " ").trim().replace(Regex("\\s+"), " ")
        return remainder.takeIf { TextNormalizer.letterCount(it) >= MIN_SELF_LABEL_LETTERS }
    }

    /** Letters an amount line needs before it counts as naming itself. */
    const val MIN_SELF_LABEL_LETTERS = 3

    /** How much unexplained text may sit beside a bare number before it stops being money. */
    const val MAX_MANGLED_CURRENCY_CHARS = 1

    /** What is left of a line once the number, the separators and the currency are taken out. */
    private fun residualOf(lower: String): String {
        var remainder = lower
        for (marker in currencyMarkers) remainder = remainder.replace(marker, "")
        return remainder.replace(Regex("[\\d.,\\s]"), "")
    }
}
