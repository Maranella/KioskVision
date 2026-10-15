package com.maranella.kioskvision.assist

sealed interface TargetMatch {
    data class Found(val block: DetectedTextBlock, val isCritical: Boolean) : TargetMatch
    data class Ambiguous(val first: DetectedTextBlock, val second: DetectedTextBlock) : TargetMatch

    /**
     * No label could be offered. [reason] says which gate stopped it, so the app can tell the
     * user to hold the phone still instead of claiming the button is not there.
     */
    data class NotFound(val reason: Rejection) : TargetMatch
}

enum class Rejection {
    /** Nothing has been read from the screen yet. */
    NothingRead,

    /** No line resembles the request. The button really does not appear to be there. */
    NoSimilarLabel,

    /**
     * A line matching the request was read, but only at a confidence that one frame cannot
     * justify acting on, and no other recent run corroborated it. Usually motion blur (R-12).
     */
    UnconfirmedLabel,
}

/**
 * Matches a spoken query against the text that OCR actually found on screen. The result is
 * always one of the OCR blocks; a screen position is never invented from the command alone.
 */
object TargetMatcher {

    /** Confidence at which a single frame is enough to offer a line as a target. */
    const val MIN_TARGET_CONFIDENCE = 0.6f

    /**
     * Floor for a label corroborated by several runs.
     *
     * Tesseract returns the right string at ~0.20 under 4 px of motion blur (R-12), so a gate
     * that only looks at one frame throws away correct readings. Agreement between runs is
     * substituted for single-frame confidence — never simply added to it.
     */
    const val MIN_CORROBORATED_CONFIDENCE = 0.15f

    /** Recent runs that must agree before [MIN_CORROBORATED_CONFIDENCE] applies. */
    const val MIN_CORROBORATING_RUNS = 2
    private const val ACCEPT_SCORE = 0.62f
    /** Two candidates closer than this are reported as ambiguous instead of guessing. */
    private const val AMBIGUITY_MARGIN = 0.05f

    /**
     * A small alias dictionary of word stems. A query and a label that both contain a stem of
     * the same concept match even when worded differently ("გადავიხადო" vs "გადახდა").
     */
    private val concepts: Map<String, List<String>> = mapOf(
        // "გადასახად" covers the common kiosk wording "და სხვა გადასახადები" (other
        // payments/charges), which is the payments button on screens that have no literal
        // "გადახდა" label.
        "payment" to listOf("გადახდ", "გადასახად", "გადავიხად", "გადაიხად", "pay"),
        "balance" to listOf("ბალანს", "ნაშთ", "balance"),
        "help" to listOf("დახმარ", "დამეხმარ", "help"),
        "other" to listOf("სხვა მომსახურ", "სხვა სერვის", "other service"),
        "back" to listOf("უკან", "back"),
        "cancel" to listOf("გაუქმ", "cancel"),
        "confirm" to listOf("დადასტურ", "დაადასტურ", "confirm"),
    )

    /** Concepts that move money or commit an action; the user is reminded to verify details. */
    private val criticalConcepts = setOf("payment", "confirm")

    /** Convenience for callers holding a single run's blocks, e.g. tests. */
    fun match(query: String, blocks: List<DetectedTextBlock>): TargetMatch =
        matchObserved(query, blocks.map { ObservedLabel(it, timesSeen = 1, bestConfidence = it.confidence) })

    fun matchObserved(query: String, labels: List<ObservedLabel>): TargetMatch {
        val normalizedQuery = TextNormalizer.normalize(query)
        if (normalizedQuery.isEmpty()) return TargetMatch.NotFound(Rejection.NothingRead)
        if (labels.isEmpty()) return TargetMatch.NotFound(Rejection.NothingRead)
        val queryConcepts = conceptsIn(normalizedQuery)

        val resembling = labels
            .filter { TextNormalizer.letterCount(it.block.text) >= 2 }
            .map { label -> label to score(normalizedQuery, queryConcepts, label.block.normalizedText) }
            .filter { (_, score) -> score >= ACCEPT_SCORE }

        if (resembling.isEmpty()) return TargetMatch.NotFound(Rejection.NoSimilarLabel)

        val ranked = resembling
            .filter { (label, _) -> isTrustworthy(label) }
            .sortedByDescending { (_, score) -> score }

        // Something matching the words was read, but not well enough to act on. That is a
        // different situation from the button being absent, and the caller says so differently.
        val best = ranked.firstOrNull() ?: return TargetMatch.NotFound(Rejection.UnconfirmedLabel)

        val runnerUp = ranked.getOrNull(1)
        if (runnerUp != null && best.second - runnerUp.second < AMBIGUITY_MARGIN) {
            return TargetMatch.Ambiguous(best.first.block, runnerUp.first.block)
        }
        val critical = conceptsIn(best.first.block.normalizedText).any { it in criticalConcepts }
        return TargetMatch.Found(best.first.block, isCritical = critical)
    }

    /**
     * Whether a label may be offered: confident enough on its own, or read the same way at the
     * same place by enough recent runs to stand in for that confidence.
     */
    fun isTrustworthy(label: ObservedLabel): Boolean =
        label.bestConfidence >= MIN_TARGET_CONFIDENCE ||
            (label.timesSeen >= MIN_CORROBORATING_RUNS && label.bestConfidence >= MIN_CORROBORATED_CONFIDENCE)

    /**
     * Per-candidate reasoning for the developer log: what each visible label scored against the
     * request and which gate, if any, kept it out. Answers "why did it say it cannot find it".
     */
    fun explain(query: String, labels: List<ObservedLabel>): List<String> {
        val normalizedQuery = TextNormalizer.normalize(query)
        val queryConcepts = conceptsIn(normalizedQuery)
        return labels
            .map { label -> label to score(normalizedQuery, queryConcepts, label.block.normalizedText) }
            .sortedByDescending { (_, score) -> score }
            .map { (label, score) ->
                val gate = when {
                    TextNormalizer.letterCount(label.block.text) < 2 -> "too short"
                    score < ACCEPT_SCORE -> "score < $ACCEPT_SCORE"
                    !isTrustworthy(label) -> "needs conf >= $MIN_TARGET_CONFIDENCE, " +
                        "or >= $MIN_CORROBORATED_CONFIDENCE seen x$MIN_CORROBORATING_RUNS"
                    else -> "eligible"
                }
                String.format(
                    java.util.Locale.ROOT,
                    "'%s' score=%.2f conf=%.2f x%d -> %s",
                    label.block.text.trim(), score, label.bestConfidence, label.timesSeen, gate,
                )
            }
    }

    private fun score(query: String, queryConcepts: Set<String>, label: String): Float {
        val direct = TextNormalizer.phraseSimilarity(query, label)
        val sharesConcept = queryConcepts.isNotEmpty() && conceptsIn(label).any { it in queryConcepts }
        // A shared concept alone is enough to accept (0.7), while the direct similarity still
        // separates e.g. "გადახდა" from "გადახდის ისტორია" when both mention payment.
        return if (sharesConcept) maxOf(direct, 0.7f + 0.3f * direct) else direct
    }

    private fun conceptsIn(normalized: String): Set<String> =
        concepts.filterValues { stems -> stems.any { normalized.contains(it) } }.keys
}
