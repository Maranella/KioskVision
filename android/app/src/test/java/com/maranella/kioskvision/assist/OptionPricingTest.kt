package com.maranella.kioskvision.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prices, and the refusal to invent one.
 *
 * The layout below is the one in `assets/ocr_test/coffee_machine_screen.png`, reduced to the
 * lines and positions that matter: four drinks in a row of cards, each with a name above a
 * price, plus the numbers on that screen which are *not* prices — a clock, a terminal id, a
 * strength stepper, a cup count, a date, a percentage.
 */
class OptionPricingTest {

    private fun element(
        label: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        confidence: Float = 0.9f,
        timesSeen: Int = 3,
    ) = SceneElement(
        label = label,
        screenBounds = TextBox(left, top, right, bottom),
        boundsAreScreenRelative = true,
        confidence = confidence,
        timesSeen = timesSeen,
        role = ElementRole.of(label),
    )

    /** One card: the drink's name, and its price below it in the same column. */
    private fun card(name: String, price: String, column: Int): List<SceneElement> {
        val left = 0.04f + column * 0.24f
        return listOf(
            element(name, left, 0.30f, left + 0.20f, 0.35f),
            element(price, left + 0.02f, 0.44f, left + 0.16f, 0.49f),
        )
    }

    private val menu: List<SceneElement> =
        card("ესპრესო", "3.50 ₾", 0) +
            card("ამერიკანო", "4.00 ₾", 1) +
            card("კაპუჩინო", "5.00 ₾", 2) +
            card("ლატე", "5.50 ₾", 3) +
            listOf(
                element("09:42", 0.80f, 0.02f, 0.95f, 0.06f),
                element("Terminal ID: TB-4417", 0.60f, 0.02f, 0.78f, 0.06f),
                element("სიძლიერე", 0.04f, 0.60f, 0.20f, 0.64f),
                element("3", 0.22f, 0.60f, 0.26f, 0.64f),
                element("ჭიქები", 0.04f, 0.72f, 0.18f, 0.76f),
                element("140", 0.20f, 0.72f, 0.26f, 0.76f),
                element("12.10.2026", 0.60f, 0.72f, 0.78f, 0.76f),
                element("82%", 0.40f, 0.72f, 0.46f, 0.76f),
                element("გაგრძელება", 0.70f, 0.86f, 0.95f, 0.92f),
            )

    @Test
    fun `each drink keeps its own price`() {
        val priced = OptionPricing.pair(menu).associate { it.label to it.price.tetri }
        assertEquals(
            mapOf("ესპრესო" to 350, "ამერიკანო" to 400, "კაპუჩინო" to 500, "ლატე" to 550),
            priced,
        )
    }

    @Test
    fun `the cheaper of two named drinks is the cheaper one`() {
        val priced = OptionPricing.pair(menu)
        val espresso = priced.first { it.label == "ესპრესო" }
        val americano = priced.first { it.label == "ამერიკანო" }
        assertTrue(espresso.price < americano.price)
        assertEquals("ესპრესო", OptionPricing.cheapest(priced)?.label)
    }

    @Test
    fun `a stepper, a clock, a cup count, a date and a percentage are not prices`() {
        val priced = OptionPricing.pair(menu).map { it.label }
        assertTrue(priced.none { it.contains("სიძლიერე") })
        assertTrue(priced.none { it.contains("ჭიქები") })
        for (notMoney in listOf("09:42", "3", "140", "12.10.2026", "82%", "Terminal ID: TB-4417")) {
            assertNull("$notMoney parsed as money", MoneyParser.parse(notMoney))
        }
    }

    @Test
    fun `a control is never given a price`() {
        // „გაგრძელება" sits below and right of the last card. Even if the geometry reached it,
        // telling a user that continuing costs 5.50 is a claim about the kiosk's behaviour.
        assertTrue(OptionPricing.pair(menu).none { it.label.contains("გაგრძელება") })
    }

    @Test
    fun `two options equally close to one amount leave it unattached`() {
        // The amount sits exactly between the two names. Either reading is as good as the
        // other, which is precisely when a guess is wrong half the time.
        val ambiguous = listOf(
            element("ამერიკანო", 0.10f, 0.30f, 0.30f, 0.35f),
            element("ორმაგი ამერიკანო", 0.50f, 0.30f, 0.70f, 0.35f),
            element("4.00 ₾", 0.37f, 0.44f, 0.43f, 0.49f),
        )
        assertEquals(emptyList<PricedOption>(), OptionPricing.pair(ambiguous))
    }

    @Test
    fun `similar names keep separate prices when the layout is unambiguous`() {
        val two = listOf(
            element("ამერიკანო", 0.05f, 0.30f, 0.25f, 0.35f),
            element("4.00 ₾", 0.07f, 0.40f, 0.21f, 0.45f),
            element("ორმაგი ამერიკანო", 0.55f, 0.30f, 0.85f, 0.35f),
            element("6.00 ₾", 0.57f, 0.40f, 0.71f, 0.45f),
        )
        val priced = OptionPricing.pair(two).associate { it.label to it.price.tetri }
        assertEquals(mapOf("ამერიკანო" to 400, "ორმაგი ამერიკანო" to 600), priced)
    }

    @Test
    fun `an amount read too uncertainly is not spoken as a price`() {
        val shaky = listOf(
            element("ამერიკანო", 0.05f, 0.30f, 0.25f, 0.35f),
            element("4.00 ₾", 0.07f, 0.40f, 0.21f, 0.45f, confidence = 0.45f, timesSeen = 1),
        )
        assertEquals(emptyList<PricedOption>(), OptionPricing.pair(shaky))

        // The same reading, corroborated by a second run, clears the lower floor (R-12).
        val corroborated = listOf(
            element("ამერიკანო", 0.05f, 0.30f, 0.25f, 0.35f),
            element("4.00 ₾", 0.07f, 0.40f, 0.21f, 0.45f, confidence = 0.65f, timesSeen = 2),
        )
        assertEquals(400, OptionPricing.pair(corroborated).single().price.tetri)
    }

    @Test
    fun `an amount on the far side of the screen belongs to nothing`() {
        val distant = listOf(
            element("ამერიკანო", 0.05f, 0.05f, 0.25f, 0.10f),
            element("4.00 ₾", 0.75f, 0.90f, 0.90f, 0.95f),
        )
        assertEquals(emptyList<PricedOption>(), OptionPricing.pair(distant))
    }

    @Test
    fun `a line that names its own amount needs no geometry`() {
        val fee = listOf(element("მომსახურების საკომისიო 0.20 ₾", 0.10f, 0.80f, 0.60f, 0.84f))
        val priced = OptionPricing.pair(fee).single()
        assertEquals(20, priced.price.tetri)
        assertEquals(PriceSource.SameLine, priced.source)
        assertTrue(priced.label.contains("საკომისიო"))
    }

    @Test
    fun `an amount beside its label on one row is attached to it`() {
        val row = listOf(
            element("ჯამი", 0.30f, 0.80f, 0.42f, 0.85f),
            element("8.50 ₾", 0.48f, 0.80f, 0.62f, 0.85f),
        )
        val priced = row.let(OptionPricing::pair).single()
        assertEquals("ჯამი", priced.label)
        assertEquals(850, priced.price.tetri)
        assertEquals(PriceSource.SameRow, priced.source)
    }

    @Test
    fun `a price belongs to the drink, not to the volume printed above it`() {
        // The real card, at the proportions measured on the device: a 52 px Georgian name, a
        // 27 px English one, a 22 px volume, then the price. The volume is *nearest* to the
        // amount, and it is what the first version of this rule attached every price to —
        // „40 მლ / 40 ml = 3.50 ₾" on all four drinks, confirmed in logcat on the SM-S731B.
        val card = listOf(
            element("ესპრესო", 0.05f, 0.28f, 0.25f, 0.34f),        // tall: the name
            element("Espresso", 0.05f, 0.355f, 0.20f, 0.385f),     // smaller: the translation
            element("40 მლ / 40 ml", 0.05f, 0.40f, 0.22f, 0.425f), // smaller still, and nearest
            element("3.50 ₾", 0.05f, 0.46f, 0.18f, 0.50f),
        )
        val priced = card.let(OptionPricing::pair).single()
        assertEquals("ესპრესო", priced.label)
        assertEquals(350, priced.price.tetri)
    }

    @Test
    fun `a line with digits in it cannot name an option`() {
        // Volumes, counts and ids are descriptions. Even alone beside an amount, none of them
        // is what the amount is the price *of*.
        val onlyDescriptors = listOf(
            element("150 მლ / 150 ml", 0.05f, 0.40f, 0.22f, 0.425f),
            element("4.00 ₾", 0.05f, 0.46f, 0.18f, 0.50f),
        )
        assertEquals(emptyList<PricedOption>(), OptionPricing.pair(onlyDescriptors))
    }

    @Test
    fun `two names of the same size still refuse to share one amount`() {
        // The prominence rule must not become a way around the ambiguity rule: same tier,
        // equal claim, so neither gets it.
        val twins = listOf(
            element("ამერიკანო", 0.10f, 0.30f, 0.30f, 0.36f),
            element("ორმაგი ამერიკანო", 0.50f, 0.30f, 0.70f, 0.36f),
            element("4.00 ₾", 0.37f, 0.44f, 0.43f, 0.49f),
        )
        assertEquals(emptyList<PricedOption>(), OptionPricing.pair(twins))
    }
}
