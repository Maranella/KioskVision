package com.maranella.kioskvision.assist

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * An option on the screen and the amount printed next to it.
 *
 * [price] is only ever an amount that was actually read, and [label] only ever a line that was
 * actually read. What this type adds is the *claim that the two belong together*, which is a
 * guess about layout — so it is made only when the geometry leaves little room for another
 * reading, and never at all when two options compete for the same amount.
 */
data class PricedOption(
    val label: String,
    val labelBounds: TextBox,
    val price: Money,
    val priceBounds: TextBox,
    val source: PriceSource,
)

/** How the amount came to be attached to the option. */
enum class PriceSource {
    /** The amount and the words were one OCR line: „მომსახურების საკომისიო 0.20 ₾". */
    SameLine,

    /** The amount sits directly under the option's name, in its column. */
    Below,

    /** The amount sits on the same row as the option's name, to its right. */
    SameRow,
}

/**
 * Pairs amounts with the options they belong to, using only the geometry of what was read.
 *
 * ### The rule, and why it refuses so much
 *
 * "Which is cheaper?" is answerable only if each amount belongs to exactly one option. The
 * failure that matters is not a missing answer; it is telling a blind user that an americano
 * costs 3.50 when 3.50 is the espresso beside it. So an amount is attached to an option only
 * when:
 *
 * - the amount is written like money (see [MoneyParser]) and was read confidently enough to
 *   be spoken as a number at all;
 * - the option is a trustworthy line of words that is not itself a recognised control — an
 *   amount is never attached to „გადახდა" or „გაუქმება";
 * - the option is directly above the amount in its column, or immediately to its left on the
 *   same row;
 * - and the runner-up candidate is clearly farther away ([MARGIN]). Two options equally close
 *   to one amount is exactly the case where a guess would be wrong half the time, so neither
 *   gets it.
 *
 * All distances are in the coordinate space of [SceneElement.screenBounds]. When the screen
 * was rectified that is the unit square over the kiosk screen, so the thresholds are fractions
 * of the screen and survive the phone moving. When it was not, the bounds are frame-relative
 * and [KioskScene.hasUsableGeometry] is false — callers that need a trustworthy association
 * check that first.
 */
object OptionPricing {

    /**
     * What an amount must be read at before it may be spoken as a price.
     *
     * Higher than the floor for words, and for the same reason `ScreenReader` holds numerals
     * to 0.8: a misread letter makes a label look odd, a misread digit makes a wrong price
     * sound exactly like a right one. Corroboration across runs substitutes for single-frame
     * confidence, as it does for labels (R-12), but from a higher floor.
     */
    const val MIN_PRICE_CONFIDENCE = 0.8f
    const val MIN_CORROBORATED_PRICE_CONFIDENCE = 0.6f
    const val MIN_CORROBORATING_RUNS = 2

    /** Vertical gap, as a fraction of the screen, within which an amount is still "under" a label. */
    const val MAX_GAP_BELOW = 0.16f

    /** Horizontal gap for an amount sitting to the right of its label on one row. */
    const val MAX_GAP_BESIDE = 0.28f

    /** How much nearer the winner must be than the runner-up before the pairing is claimed. */
    const val MARGIN = 1.4f

    fun pair(elements: List<SceneElement>): List<PricedOption> {
        val amounts = elements.mapNotNull { element ->
            MoneyParser.parse(element.label)?.let { element to it }
        }
        if (amounts.isEmpty()) return emptyList()
        val amountBoxes = amounts.map { it.first.screenBounds }.toSet()

        val options = elements.filter { candidate ->
            candidate.isTrustworthy &&
                candidate.screenBounds !in amountBoxes &&
                // Never price a control. „გაგრძელება 5.00 ₾" would be read by a user as the
                // cost of continuing, which is a claim about the kiosk's behaviour that
                // nothing here is entitled to make.
                candidate.role == ElementRole.Unknown &&
                TextNormalizer.letterCount(candidate.label) >= MIN_OPTION_LETTERS &&
                // A line with digits in it is a description of an option, not its name: a
                // volume, a count, a terminal id. Measured on the device against the coffee
                // machine target, this is the line that was winning — „40 მლ / 40 ml" sits
                // directly above „3.50 ₾" in the same column, nearer to it than the drink's
                // own name, so the nearest-label rule attached every price to a volume.
                candidate.label.none(Char::isDigit)
        }

        val paired = mutableListOf<PricedOption>()
        for ((element, money) in amounts) {
            if (!isReadConfidently(element)) continue

            // An amount that names itself needs no geometry at all.
            val selfLabel = MoneyParser.labelPartOf(element.label)
            if (selfLabel != null) {
                paired += PricedOption(
                    label = selfLabel,
                    labelBounds = element.screenBounds,
                    price = money,
                    priceBounds = element.screenBounds,
                    source = PriceSource.SameLine,
                )
                continue
            }

            val scored = options
                .mapNotNull { option -> score(option.screenBounds, element.screenBounds)?.let { option to it } }
            if (scored.isEmpty()) continue

            // Nearest is not the same as *named by*. A product card stacks a name, a
            // translation and a volume above its price, and the price belongs to the name —
            // which is the largest text on the card and usually the farthest of the three
            // from the amount. So the most prominent tier is chosen first, and distance only
            // decides between labels of the same prominence.
            //
            // Typographic prominence is evidence, not a guess: it is how the screen itself
            // says which line is the title. On the device this is 52 px against 27 and 22.
            val tallest = scored.maxOf { it.first.screenBounds.height }
            val tier = scored.filter { it.first.screenBounds.height >= tallest * SAME_TIER }
                .sortedBy { it.second.distance }

            val best = tier.first()
            val runnerUp = tier.getOrNull(1)
            // Ambiguous: two labels of the same prominence with equal claim to one amount.
            // Neither is used, and nothing is said about either price.
            if (runnerUp != null && runnerUp.second.distance < best.second.distance * MARGIN) continue

            paired += PricedOption(
                label = best.first.label,
                labelBounds = best.first.screenBounds,
                price = money,
                priceBounds = element.screenBounds,
                source = best.second.source,
            )
        }

        // One option, one price. A label that attracted two amounts is a layout this does not
        // understand, so it keeps the nearer and drops the other rather than averaging them.
        return paired
            .groupBy { it.label }
            .values
            .map { group -> group.minByOrNull { it.labelBounds.centerDistanceTo(it.priceBounds) }!! }
            .sortedBy { it.labelBounds.top }
    }

    /** The cheapest of [options], or null when fewer than two can be compared. */
    fun cheapest(options: List<PricedOption>): PricedOption? =
        options.minByOrNull { it.price.tetri }

    private fun isReadConfidently(element: SceneElement): Boolean =
        element.confidence >= MIN_PRICE_CONFIDENCE ||
            (element.timesSeen >= MIN_CORROBORATING_RUNS &&
                element.confidence >= MIN_CORROBORATED_PRICE_CONFIDENCE)

    private data class Score(val distance: Float, val source: PriceSource)

    private fun score(label: TextBox, price: TextBox): Score? {
        val overlapX = min(label.right, price.right) - max(label.left, price.left)
        val sameColumn = overlapX > 0f || abs(label.centerX - price.centerX) < COLUMN_TOLERANCE
        val gapBelow = price.top - label.bottom
        if (sameColumn && gapBelow >= -label.height && gapBelow <= MAX_GAP_BELOW) {
            // Distance measured centre to centre so a tall label does not beat a near one.
            return Score(max(gapBelow, 0f) + abs(label.centerX - price.centerX), PriceSource.Below)
        }

        val sameRow = abs(label.centerY - price.centerY) <= max(label.height, price.height) * 0.6f
        val gapBeside = price.left - label.right
        if (sameRow && gapBeside >= -label.width * 0.1f && gapBeside <= MAX_GAP_BESIDE) {
            return Score(max(gapBeside, 0f) + abs(label.centerY - price.centerY), PriceSource.SameRow)
        }
        return null
    }

    /** Letters a line needs before it can name an option. */
    private const val MIN_OPTION_LETTERS = 2

    /**
     * How close in height two lines must be to count as the same typographic tier.
     *
     * 0.8 separates the measured card cleanly — a 52 px drink name against a 27 px
     * translation and a 22 px volume — while keeping two labels set in the same size
     * competing with each other, which is the case that must stay ambiguous.
     */
    private const val SAME_TIER = 0.8f

    /** Columns are allowed to be this far out of alignment and still count as one. */
    private const val COLUMN_TOLERANCE = 0.06f
}
