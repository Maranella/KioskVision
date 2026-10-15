package com.maranella.kioskvision.assist

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object TextNormalizer {

    /**
     * Lower-cases (which also folds Georgian Mtavruli capitals to Mkhedruli), replaces every
     * non-letter/non-digit with a space, and collapses whitespace.
     */
    fun normalize(text: String): String = buildString(text.length) {
        var lastWasSpace = true
        for (ch in text.lowercase(Locale.ROOT)) {
            if (ch.isLetterOrDigit()) {
                append(ch)
                lastWasSpace = false
            } else if (!lastWasSpace) {
                append(' ')
                lastWasSpace = true
            }
        }
    }.trimEnd()

    fun tokens(normalized: String): List<String> =
        if (normalized.isEmpty()) emptyList() else normalized.split(' ')

    fun letterCount(text: String): Int = text.count { it.isLetter() }

    /**
     * The recognised text as it should be spoken: the original wording, with OCR edge
     * artefacts trimmed. Blur routinely adds a leading '|' or a trailing '.' to a label, and
     * reading those aloud is noise. Nothing between the first and last letter is touched.
     */
    fun forSpeech(text: String): String {
        val trimmed = text.trim().trim { !it.isLetterOrDigit() }
        return trimmed.ifEmpty { text.trim() }
    }

    /**
     * Similarity of two normalized words in 0..1, tolerant of Georgian inflection
     * ("გადახდა" / "გადახდის" share the stem "გადახდ") and of single-character OCR errors.
     */
    fun wordSimilarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val prefix = commonPrefixLength(a, b)
        val stemScore = if (prefix >= MIN_STEM) 0.9f * prefix / min(a.length, b.length) else 0f
        val editScore = 1f - editDistance(a, b) / max(a.length, b.length)
        return max(stemScore, editScore)
    }

    /** Prefix length, counting a confusable letter as a match so a stem survives one misread. */
    private fun commonPrefixLength(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && substitutionCost(a[i], b[i]) < 1f) i++
        return i
    }

    /**
     * How well [query] is covered by [candidate], in 0..1: the mean, over query words, of the
     * best-matching candidate word.
     */
    fun phraseSimilarity(query: String, candidate: String): Float {
        val queryWords = tokens(query)
        val candidateWords = tokens(candidate)
        if (queryWords.isEmpty() || candidateWords.isEmpty()) return 0f
        return queryWords.map { q -> candidateWords.maxOf { wordSimilarity(q, it) } }.average().toFloat()
    }

    /** Symmetric phrase similarity, used to recognize the same OCR line across frames. */
    fun lineSimilarity(a: String, b: String): Float =
        min(phraseSimilarity(a, b), phraseSimilarity(b, a))

    private const val MIN_STEM = 4

    /**
     * Georgian letters Tesseract confuses on kiosk screens, each pair taken from a real
     * misreading captured on device (R-14):
     *
     * - `ლ`/`ღ` — `ბალანსის` read as `ბაღანსის`, `მობილურის` as `მობიღურის`
     * - `ხ`/`ს` — `დახმარება` read as `დასმარება`
     * - `წ`/`ნ` and `მ`/`შ` — `შემოწმება` read as `შემონშება`
     *
     * Substituting within a pair costs a fraction of an edit, so one confused letter cannot
     * push a correctly located label below the matching threshold. The cost is deliberately
     * non-zero: these are still errors, and two of them should weigh like most of one edit.
     */
    private val confusablePairs = listOf("ლღ", "ხს", "წნ", "მშ")

    private val confusionGroup: Map<Char, Int> = buildMap {
        confusablePairs.forEachIndexed { group, letters -> letters.forEach { put(it, group) } }
    }

    private const val CONFUSABLE_COST = 0.4f

    private fun substitutionCost(a: Char, b: Char): Float = when {
        a == b -> 0f
        confusionGroup[a] != null && confusionGroup[a] == confusionGroup[b] -> CONFUSABLE_COST
        else -> 1f
    }

    /** Levenshtein distance where a known OCR confusion costs [CONFUSABLE_COST] instead of 1. */
    private fun editDistance(a: String, b: String): Float {
        var previous = FloatArray(b.length + 1) { it.toFloat() }
        var current = FloatArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i.toFloat()
            for (j in 1..b.length) {
                current[j] = minOf(
                    current[j - 1] + 1f,
                    previous[j] + 1f,
                    previous[j - 1] + substitutionCost(a[i - 1], b[j - 1]),
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
