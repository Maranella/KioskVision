package com.maranella.kioskvision.assist

import com.maranella.kioskvision.camera.Point
import com.maranella.kioskvision.camera.Quad
import com.maranella.kioskvision.camera.TrackedScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate between "shall I take you to გადახდა?" and a finger being guided.
 *
 * A regression test in the strict sense: `TargetResolver.revalidate` existed, was tested, and
 * was reached by no production code, so confirmation ran against the screen **as it had been
 * offered**. Everything here is about the seconds between the offer and the answer, which is
 * exactly when a kiosk advances.
 */
class ConfirmationGateTest {

    private val now = 10_000L
    private val resolver = TargetResolver()

    private fun screen() = TrackedScreen(
        quad = Quad(Point(0f, 0f), Point(400f, 0f), Point(400f, 400f), Point(0f, 400f)),
        confidence = 0.8f,
        stable = true,
        ageMs = 50L,
    )

    private fun labels(vararg specs: Pair<String, Float>): List<ObservedLabel> =
        specs.mapIndexed { index, (text, confidence) ->
            ObservedLabel(
                block = DetectedTextBlock(
                    text = text,
                    boundingBox = TextBox(40f, 40f + index * 60f, 200f, 90f + index * 60f),
                    confidence = confidence,
                ),
                timesSeen = 3,
                bestConfidence = confidence,
            )
        }

    private fun scene(
        labels: List<ObservedLabel>,
        trackedScreen: TrackedScreen? = screen(),
        observedAtMs: Long = now,
        changeSuspected: Boolean = false,
    ) = KioskScene.from(
        labels,
        trackedScreen,
        frameWidth = 400,
        frameHeight = 400,
        nowMs = observedAtMs,
        changeSuspected = changeSuspected,
    )

    /** The offer, as it was made against the screen the user was shown. */
    private fun offered(label: String = "გადახდა"): ResolvedTarget {
        val built = scene(labels(label to 0.9f, "ბალანსი" to 0.9f, "უკან" to 0.9f))
        val resolution = resolver.resolve(UserIntent(label), built, now)
        return (resolution as TargetResolution.Resolved).target
    }

    private fun decide(
        pending: ResolvedTarget? = offered(),
        query: String? = "გადახდა",
        scene: KioskScene,
        nowMs: Long = now,
        userStopped: Boolean = false,
    ) = ConfirmationGate.decide(pending, query, scene, nowMs, userStopped, resolver)

    @Test
    fun `a control still on the screen is guided to`() {
        val decision = decide(scene = scene(labels("გადახდა" to 0.9f, "ბალანსი" to 0.9f, "უკან" to 0.9f)))
        assertTrue("expected Guide, got $decision", decision is ConfirmDecision.Guide)
        val guide = decision as ConfirmDecision.Guide
        assertTrue("the pipeline must be given something to match", guide.queries.isNotEmpty())
        assertEquals("გადახდა", guide.visited)
    }

    @Test
    fun `a kiosk that moved on between the offer and the yes cannot be confirmed`() {
        // The defect this file exists for: the payment screen has advanced to a PIN prompt
        // while the user was answering. Confirming here would guide a finger to where the
        // button was.
        val after = scene(labels("შეიყვანეთ პინ კოდი" to 0.9f, "გაუქმება" to 0.9f))
        val decision = decide(scene = after)

        assertTrue("a withdrawn control must be refused, got $decision", decision is ConfirmDecision.Refuse)
        val refuse = decision as ConfirmDecision.Refuse
        assertEquals(ResolutionFailure.NoSimilarLabel, refuse.reason)
        assertEquals(SpokenMessage.TargetNotFound("გადახდა"), refuse.message)
    }

    @Test
    fun `a screen that is no longer tracked cannot be confirmed`() {
        val lost = scene(labels("გადახდა" to 0.9f), trackedScreen = null)
        val decision = decide(scene = lost)
        assertTrue("expected Refuse, got $decision", decision is ConfirmDecision.Refuse)
        assertEquals(SpokenMessage.ScreenNotTracked, (decision as ConfirmDecision.Refuse).message)
    }

    @Test
    fun `a scene too old to act on asks the user to hold still, not that the button is gone`() {
        // The distinction that matters: "I cannot see it right now" and "it is not there"
        // send the user to do completely different things.
        val stale = scene(
            labels("გადახდა" to 0.9f, "ბალანსი" to 0.9f),
            observedAtMs = now - KioskScene.STALE_LIMIT_MS - 500L,
        )
        val decision = decide(scene = stale)
        assertTrue("expected Refuse, got $decision", decision is ConfirmDecision.Refuse)
        val refuse = decision as ConfirmDecision.Refuse
        assertEquals(ResolutionFailure.SceneStale, refuse.reason)
        assertEquals(SpokenMessage.HoldStill, refuse.message)
    }

    @Test
    fun `a blurred reading is held open rather than denied`() {
        // Tesseract returns the right Georgian string at ~0.20 under motion blur (R-12).
        // Refusing here would end a request that is about to succeed.
        val blurred = scene(
            listOf(
                ObservedLabel(
                    block = DetectedTextBlock("გადახდა", TextBox(40f, 40f, 200f, 90f), confidence = 0.2f),
                    timesSeen = 1,
                    bestConfidence = 0.2f,
                ),
                ObservedLabel(
                    block = DetectedTextBlock("ბალანსი", TextBox(40f, 100f, 200f, 150f), confidence = 0.2f),
                    timesSeen = 1,
                    bestConfidence = 0.2f,
                ),
            ),
        )
        val decision = decide(scene = blurred)
        assertTrue("a blurred read must be held open, got $decision", decision is ConfirmDecision.HoldOpen)
        assertTrue((decision as ConfirmDecision.HoldOpen).queries.contains("გადახდა"))
    }

    @Test
    fun `a stop that lands between the offer and the yes wins`() {
        val decision = decide(
            scene = scene(labels("გადახდა" to 0.9f, "ბალანსი" to 0.9f)),
            userStopped = true,
        )
        assertEquals(ConfirmDecision.UserStopped, decision)
    }

    @Test
    fun `a yes with nothing pending confirms nothing`() {
        val decision = decide(pending = null, scene = scene(labels("გადახდა" to 0.9f)))
        assertEquals(ConfirmDecision.NothingPending, decision)
    }

    @Test
    fun `the user's wording and the screen's wording are both offered to the pipeline`() {
        val decision = decide(
            query = "გადავიხადო",
            scene = scene(labels("გადახდა" to 0.9f, "ბალანსი" to 0.9f, "უკან" to 0.9f)),
        )
        val guide = decision as ConfirmDecision.Guide
        assertTrue("both wordings must reach the pipeline: ${guide.queries}", guide.queries.size == 2)
        assertTrue(guide.queries.containsAll(listOf("გადავიხადო", "გადახდა")))
    }

    @Test
    fun `a screen caught mid-change cannot be confirmed, even though its labels still match`() {
        // The window the scene's own timestamp cannot see: geometry is re-detected several
        // times a second, OCR is not, so these labels may belong to the screen before this
        // one. They still match the offer perfectly — that is the point.
        val changing = scene(
            labels("გადახდა" to 0.9f, "ბალანსი" to 0.9f, "უკან" to 0.9f),
            changeSuspected = true,
        )
        val decision = decide(scene = changing)

        assertTrue("a screen mid-change must refuse, got $decision", decision is ConfirmDecision.Refuse)
        assertEquals(SpokenMessage.ScreenChanged, (decision as ConfirmDecision.Refuse).message)
    }

    @Test
    fun `the same scene without the suspicion confirms normally`() {
        // So the guard above is doing the work, and not some other property of the fixture.
        val decision = decide(scene = scene(labels("გადახდა" to 0.9f, "ბალანსი" to 0.9f, "უკან" to 0.9f)))
        assertTrue("expected Guide, got $decision", decision is ConfirmDecision.Guide)
    }
}
