package com.maranella.kioskvision.assist

/** What the app can honestly say when asked what something costs. */
sealed interface PriceAnswer {

    /** Two options, and which of them is cheaper. */
    data class Cheaper(val cheaper: PricedOption, val dearer: PricedOption) : PriceAnswer

    /**
     * Two options at the same price.
     *
     * Kept apart from [Cheaper] because saying one "is cheaper" would be false, and because
     * both amounts are still worth hearing.
     */
    data class Same(val first: PricedOption, val second: PricedOption) : PriceAnswer

    /** One option and its amount. */
    data class Single(val option: PricedOption) : PriceAnswer

    /** No kiosk screen is being tracked. */
    data object NoScreen : PriceAnswer

    /**
     * There is a screen, but nothing said about it now would be about the screen the user is
     * standing in front of. See [KioskScene.describesScreenNow].
     */
    data object NotCurrent : PriceAnswer

    /** Amounts could not be attached to options reliably enough to speak. */
    data object NoPrices : PriceAnswer

    /** The user named something that is not among the priced options. */
    data class NotOnScreen(val query: String) : PriceAnswer
}

/**
 * Answers "what does it cost" and "which is cheaper" from one observed scene.
 *
 * ### Why this is not in the ViewModel
 *
 * It was, and that made the one thing worth testing untestable. An amount spoken to a blind
 * user is a claim they cannot check, so the decision to speak it — and above all the decision
 * **not** to — has to be a pure function over a scene and a clock.
 *
 * ### The defect this exists to prevent
 *
 * `KioskScene.observedAtMs` dates the *geometry*, which is re-detected several times a second.
 * The labels come from OCR, which is far slower. So between a kiosk advancing and the next
 * scan landing, a scene looks fresh while listing the previous screen's prices — and the old
 * code checked only `isStale`, which is driven by that geometry timestamp. A user asking
 * „რომელია უფრო იაფი?" in that window would have been told an amount from a screen that was
 * no longer in front of them, as present fact.
 *
 * `ConfirmationGate` already refused in exactly this situation. The rule now has one home and
 * both callers use it.
 */
object PriceQuestion {

    fun answer(scene: KioskScene, first: String?, second: String?, nowMs: Long): PriceAnswer {
        if (scene.readability == SceneReadability.NoScreen) return PriceAnswer.NoScreen
        if (!scene.describesScreenNow(nowMs)) return PriceAnswer.NotCurrent

        val priced = scene.pricedOptions
        if (priced.isEmpty()) return PriceAnswer.NoPrices

        val matchedFirst = first?.let { match(it, priced) }
        val matchedSecond = second?.let { match(it, priced) }
        if (first != null && matchedFirst == null) return PriceAnswer.NotOnScreen(first)
        if (second != null && matchedSecond == null) return PriceAnswer.NotOnScreen(second)

        val pair = when {
            matchedFirst != null && matchedSecond != null -> matchedFirst to matchedSecond
            // "Which is cheaper?" with nothing named is a question about the screen: the two
            // cheapest on it are what is actually being asked about.
            matchedFirst == null && matchedSecond == null && priced.size >= 2 ->
                priced.sortedBy { it.price.tetri }.let { it[0] to it[1] }

            else -> null
        }

        if (pair != null) {
            val (a, b) = pair
            if (a.price == b.price) return PriceAnswer.Same(a, b)
            return if (a.price < b.price) PriceAnswer.Cheaper(a, b) else PriceAnswer.Cheaper(b, a)
        }

        val single = matchedFirst ?: matchedSecond ?: priced.singleOrNull()
        return if (single != null) PriceAnswer.Single(single) else PriceAnswer.NoPrices
    }

    /**
     * The user's wording, resolved against the options that have a price.
     *
     * Through [TargetMatcher] rather than a string comparison, so „ამერიკანო" and
     * „ორმაგი ამერიკანო" are separated by the same rules that keep them apart when guiding —
     * and an ambiguous request resolves to nothing here too, rather than to whichever was read
     * first.
     */
    private fun match(query: String, priced: List<PricedOption>): PricedOption? {
        val blocks = priced.map {
            DetectedTextBlock(text = it.label, boundingBox = it.labelBounds, confidence = 1f)
        }
        val found = TargetMatcher.match(query, blocks) as? TargetMatch.Found ?: return null
        return priced.firstOrNull { it.label == found.block.text }
    }
}
