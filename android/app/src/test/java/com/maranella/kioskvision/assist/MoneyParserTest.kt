package com.maranella.kioskvision.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What counts as an amount.
 *
 * Every refusal here is a number that appears on the coffee-machine target and would be a
 * fabricated price if it were accepted.
 */
class MoneyParserTest {

    @Test
    fun `amounts with the lari sign`() {
        assertEquals(350, MoneyParser.parse("3.50 ₾")?.tetri)
        assertEquals(400, MoneyParser.parse("4.00 ₾")?.tetri)
        assertEquals(850, MoneyParser.parse("₾8.50")?.tetri)
        assertEquals(20, MoneyParser.parse("0.20 ₾")?.tetri)
    }

    @Test
    fun `the lari sign is often mangled, so the word is accepted too`() {
        assertEquals(500, MoneyParser.parse("5.00 ლარი")?.tetri)
        assertEquals(500, MoneyParser.parse("5 ლარი")?.tetri)
        assertEquals(500, MoneyParser.parse("5.00 GEL")?.tetri)
    }

    @Test
    fun `a comma is a decimal separator here`() {
        assertEquals(350, MoneyParser.parse("3,50 ₾")?.tetri)
        assertEquals(350, MoneyParser.parse("3,5 ₾")?.tetri)
    }

    @Test
    fun `a bare decimal is money, a bare integer is not`() {
        assertEquals(350, MoneyParser.parse("3.50")?.tetri)
        // 140 cups, 3 strength, 2 sugar: indistinguishable from tetri without a currency.
        assertNull(MoneyParser.parse("140"))
        assertNull(MoneyParser.parse("3"))
        assertNull(MoneyParser.parse("2"))
    }

    @Test
    fun `the amounts as this device actually read them`() {
        // Verbatim from logcat on the SM-S731B reading `coffee_machine_screen.png`: the lari
        // sign came back as C, დ, & and © across five amounts. A parser that insisted on ₾
        // would have refused every price on the screen.
        assertEquals(350, MoneyParser.parse("3.50 C")?.tetri)
        assertEquals(400, MoneyParser.parse("4.00 დ")?.tetri)
        assertEquals(500, MoneyParser.parse("5.00 &")?.tetri)
        assertEquals(500, MoneyParser.parse("5.00 ©")?.tetri)
        assertEquals(850, MoneyParser.parse("8.50 C")?.tetri)
    }

    @Test
    fun `a word beside a number is not a mangled currency sign`() {
        // The same screen's volumes. One stray character is a lost glyph; two or more are a
        // unit, and attaching "150 ml" to a drink as its price would be a fabricated amount.
        assertNull(MoneyParser.parse("150 ml"))
        assertNull(MoneyParser.parse("40 მლ"))
        assertNull(MoneyParser.parse("1.50 ml"))
    }

    @Test
    fun `clocks, dates, percentages and ids are not amounts`() {
        for (text in listOf("09:42", "12.10.2026", "82%", "TB-4417", "v1.0.4", "2 55 55 55", "80°")) {
            assertNull("$text parsed as money", MoneyParser.parse(text))
        }
    }

    @Test
    fun `a sentence about prices is not a price`() {
        assertNull(MoneyParser.parse("ფასი მოიცავს დღგ-ს"))
        assertNull(MoneyParser.parse("Price includes VAT 18%"))
    }

    @Test
    fun `an amount with its own words keeps them`() {
        assertEquals(20, MoneyParser.parse("მომსახურების საკომისიო 0.20 ₾")?.tetri)
        assertEquals("მომსახურების საკომისიო", MoneyParser.labelPartOf("მომსახურების საკომისიო 0.20 ₾"))
        assertNull(MoneyParser.labelPartOf("4.00 ₾"))
    }

    @Test
    fun `lari and tetri are split for speech`() {
        val value = MoneyParser.parse("3.50 ₾")!!
        assertEquals(3, value.lari)
        assertEquals(50, value.remainderTetri)
        val whole = MoneyParser.parse("4.00 ₾")!!
        assertEquals(4, whole.lari)
        assertEquals(0, whole.remainderTetri)
    }

    @Test
    fun `an implausible amount is a reading error`() {
        assertNull(MoneyParser.parse("99999.00 ₾"))
        assertNull(MoneyParser.parse("0.00 ₾"))
    }
}
