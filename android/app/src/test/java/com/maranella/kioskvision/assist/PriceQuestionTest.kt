package com.maranella.kioskvision.assist

import com.maranella.kioskvision.camera.Point
import com.maranella.kioskvision.camera.Quad
import com.maranella.kioskvision.camera.TrackedScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An amount spoken to a blind user is a claim they cannot check.
 *
 * These cover the defect the audit found: `comparePrices` checked only `isStale`, and
 * `KioskScene.observedAtMs` ages the **geometry** — re-detected several times a second —
 * while the labels come from OCR, which is far slower. A scene can therefore look fresh and
 * list the previous screen's prices, and the app would have quoted one as a present fact
 * about the screen in front of the user.
 */
class PriceQuestionTest {

    private val now = 10_000L

    private fun screen() = TrackedScreen(
        quad = Quad(Point(0f, 0f), Point(400f, 0f), Point(400f, 400f), Point(0f, 400f)),
        confidence = 0.8f,
        stable = true,
        ageMs = 50L,
    )

    /** One drink card: a prominent name with its amount below, as the device reads them. */
    private fun card(name: String, price: String, column: Int): List<ObservedLabel> {
        val left = 40f + column * 150f
        return listOf(
            label(name, left, 100f, left + 120f, 130f),
            label(price, left + 5f, 160f, left + 70f, 185f),
        )
    }

    private fun label(text: String, l: Float, t: Float, r: Float, b: Float) = ObservedLabel(
        block = DetectedTextBlock(text = text, boundingBox = TextBox(l, t, r, b), confidence = 0.95f),
        timesSeen = 3,
        bestConfidence = 0.95f,
    )

    private fun scene(
        labels: List<ObservedLabel> = card("ესპრესო", "3.50 ₾", 0) + card("ამერიკანო", "4.00 ₾", 1),
        observedAtMs: Long = now,
        changeSuspected: Boolean = false,
        trackedScreen: TrackedScreen? = screen(),
    ) = KioskScene.from(
        labels,
        trackedScreen,
        frameWidth = 400,
        frameHeight = 400,
        nowMs = observedAtMs,
        changeSuspected = changeSuspected,
    )

    // --- the trust predicate itself -----------------------------------------------------------

    @Test
    fun `a fresh scene with no suspected transition is trusted`() {
        assertTrue(scene().describesScreenNow(now))
    }

    @Test
    fun `a stale scene is not trusted`() {
        val old = scene(observedAtMs = now - KioskScene.STALE_LIMIT_MS - 1)
        assertFalse(old.describesScreenNow(now))
    }

    @Test
    fun `a fresh scene with a suspected transition is not trusted`() {
        // The case a timestamp cannot catch: the geometry was re-detected a moment ago, so
        // the scene is young, and the text in it is from the screen before.
        val changing = scene(changeSuspected = true)
        assertTrue("the fixture must be fresh, or this proves nothing", !changing.isStale(now))
        assertFalse(changing.describesScreenNow(now))
    }

    // --- what the user is told ----------------------------------------------------------------

    @Test
    fun `prices are quoted from a screen that is current`() {
        val answer = PriceQuestion.answer(scene(), "ესპრესო", "ამერიკანო", now)
        assertTrue("expected a comparison, got $answer", answer is PriceAnswer.Cheaper)
        val cheaper = answer as PriceAnswer.Cheaper
        assertEquals("ესპრესო", cheaper.cheaper.label)
        assertEquals(350, cheaper.cheaper.price.tetri)
        assertEquals(400, cheaper.dearer.price.tetri)
    }

    @Test
    fun `no price is quoted while a transition is suspected`() {
        // The defect. Same labels, same freshness, one bit of evidence that the kiosk moved
        // on — and the previous screen's price must not be spoken as a fact about this one.
        val answer = PriceQuestion.answer(scene(changeSuspected = true), "ესპრესო", "ამერიკანო", now)
        assertEquals(PriceAnswer.NotCurrent, answer)
    }

    @Test
    fun `no price is quoted from a scene too old to trust`() {
        val answer = PriceQuestion.answer(
            scene(observedAtMs = now - KioskScene.STALE_LIMIT_MS - 1),
            "ესპრესო",
            null,
            now,
        )
        assertEquals(PriceAnswer.NotCurrent, answer)
    }

    @Test
    fun `the comparison works again once the transition is resolved`() {
        // Trust returns with the next scene the pipeline publishes, not by anything the
        // question does to get an answer.
        val during = PriceQuestion.answer(scene(changeSuspected = true), null, null, now)
        assertEquals(PriceAnswer.NotCurrent, during)

        val after = PriceQuestion.answer(scene(changeSuspected = false), null, null, now)
        assertTrue("expected a comparison once settled, got $after", after is PriceAnswer.Cheaper)
    }

    // --- the rest of the contract -------------------------------------------------------------

    @Test
    fun `with nothing named the two cheapest are compared`() {
        val answer = PriceQuestion.answer(
            scene(
                card("ესპრესო", "3.50 ₾", 0) +
                    card("ამერიკანო", "4.00 ₾", 1) +
                    card("კაპუჩინო", "5.00 ₾", 2),
            ),
            null, null, now,
        ) as PriceAnswer.Cheaper
        assertEquals("ესპრესო", answer.cheaper.label)
        assertEquals("ამერიკანო", answer.dearer.label)
    }

    @Test
    fun `two equal prices are read out rather than called cheaper`() {
        val answer = PriceQuestion.answer(
            scene(card("კაპუჩინო", "5.00 ₾", 0) + card("ლატე", "5.00 ₾", 1)),
            "კაპუჩინო", "ლატე", now,
        )
        assertTrue("equal prices must not become a comparison, got $answer", answer is PriceAnswer.Same)
    }

    @Test
    fun `an option that is not on the screen is refused by name`() {
        val answer = PriceQuestion.answer(scene(), "მოხატო", null, now)
        assertEquals(PriceAnswer.NotOnScreen("მოხატო"), answer)
    }

    @Test
    fun `a screen with no readable amounts says so rather than guessing`() {
        val answer = PriceQuestion.answer(
            scene(listOf(label("ესპრესო", 40f, 100f, 160f, 130f), label("ამერიკანო", 190f, 100f, 310f, 130f))),
            null, null, now,
        )
        assertEquals(PriceAnswer.NoPrices, answer)
    }

    @Test
    fun `no tracked screen is its own answer`() {
        val answer = PriceQuestion.answer(scene(trackedScreen = null), "ესპრესო", null, now)
        assertEquals(PriceAnswer.NoScreen, answer)
    }
}
