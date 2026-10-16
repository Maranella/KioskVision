package com.maranella.kioskvision.assist

import com.maranella.kioskvision.camera.Point
import com.maranella.kioskvision.camera.Quad
import com.maranella.kioskvision.camera.TrackedScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resolver is the boundary the safety invariants rest on: upstream may say what the user
 * wants, only this decides whether it exists on screen and where. Most of these tests are
 * about it refusing.
 */
class TargetResolverTest {

    private val now = 10_000L

    private fun screen() = TrackedScreen(
        quad = Quad(Point(0f, 0f), Point(400f, 0f), Point(400f, 400f), Point(0f, 400f)),
        confidence = 0.8f,
        stable = true,
        ageMs = 50L,
    )

    private fun labels(vararg specs: Triple<String, Float, Int>): List<ObservedLabel> =
        specs.mapIndexed { index, (text, confidence, seen) ->
            ObservedLabel(
                block = DetectedTextBlock(
                    text = text,
                    boundingBox = TextBox(40f, 40f + index * 60f, 200f, 90f + index * 60f),
                    confidence = confidence,
                ),
                timesSeen = seen,
                bestConfidence = confidence,
            )
        }

    private fun scene(
        labels: List<ObservedLabel>,
        trackedScreen: TrackedScreen? = screen(),
        observedAtMs: Long = now,
    ) = KioskScene.from(labels, trackedScreen, frameWidth = 400, frameHeight = 400, nowMs = observedAtMs)

    @Test
    fun `resolves a payment button to screen-relative bounds`() {
        val built = scene(
            labels(
                Triple("კომუნალური გადასახადი", 0.9f, 3),
                Triple("ბალანსი", 0.9f, 3),
                Triple("უკან", 0.9f, 3),
            ),
        )
        val result = TargetResolver().resolve(UserIntent("გადახდა"), built, now)

        assertTrue("expected a resolved target, got $result", result is TargetResolution.Resolved)
        val target = (result as TargetResolution.Resolved).target
        assertEquals(ElementRole.Payment, target.role)
        assertTrue("a payment target must be flagged critical", target.isCritical)
        // Screen space, not frame pixels: inside the unit square.
        assertTrue(
            "bounds ${target.screenBounds} are not in screen space",
            target.screenBounds.left in 0f..1f && target.screenBounds.bottom in 0f..1f,
        )
    }

    @Test
    fun `refuses when no screen is being tracked`() {
        val built = scene(labels(Triple("გადახდა", 0.9f, 3)), trackedScreen = null)
        val result = TargetResolver().resolve(UserIntent("გადახდა"), built, now)

        assertEquals(
            TargetResolution.Unresolved(ResolutionFailure.NoScreen),
            result,
        )
    }

    @Test
    fun `refuses to point when the screen could not be rectified, even though the text was read`() {
        // A degenerate quadrilateral: detected, but no homography can come from it.
        val collapsed = TrackedScreen(
            quad = Quad(Point(5f, 5f), Point(5f, 5f), Point(5f, 5f), Point(5f, 5f)),
            confidence = 0.9f,
            stable = true,
            ageMs = 10L,
        )
        val built = scene(labels(Triple("გადახდა", 0.95f, 3)), trackedScreen = collapsed)
        val result = TargetResolver().resolve(UserIntent("გადახდა"), built, now)

        assertTrue(
            "expected a refusal rather than frame coordinates passed off as screen ones, got $result",
            result is TargetResolution.Unresolved,
        )
    }

    @Test
    fun `refuses a request for something that is not on the screen`() {
        val built = scene(labels(Triple("ბალანსი", 0.9f, 3), Triple("უკან", 0.9f, 3)))
        val result = TargetResolver().resolve(UserIntent("ბილეთი"), built, now)

        assertEquals(
            TargetResolution.Unresolved(ResolutionFailure.NoSimilarLabel),
            result,
        )
    }

    @Test
    fun `separates an unconfirmed label from an absent one`() {
        // Read once, at a confidence no single frame can justify acting on.
        val built = scene(labels(Triple("გადახდა", 0.2f, 1)))
        val result = TargetResolver().resolve(UserIntent("გადახდა"), built, now)

        assertEquals(
            TargetResolution.Unresolved(ResolutionFailure.UnconfirmedLabel),
            result,
        )
    }

    @Test
    fun `a stale scene is refused rather than guided from`() {
        val built = scene(labels(Triple("გადახდა", 0.9f, 3)), observedAtMs = now - 5_000L)
        val result = TargetResolver().resolve(UserIntent("გადახდა"), built, now)

        assertEquals(
            TargetResolution.Unresolved(ResolutionFailure.SceneStale),
            result,
        )
    }

    @Test
    fun `asks which one when two labels are equally plausible`() {
        val built = scene(
            labels(
                Triple("გადახდა", 0.9f, 3),
                Triple("გადახდა", 0.9f, 3),
            ),
        )
        val result = TargetResolver().resolve(UserIntent("გადახდა"), built, now)

        assertTrue("expected ambiguity, got $result", result is TargetResolution.Ambiguous)
    }

    @Test
    fun `a role hint only breaks a tie when it picks out exactly one candidate`() {
        val built = scene(
            labels(
                Triple("გადახდა", 0.9f, 3),
                Triple("გადახდა", 0.9f, 3),
            ),
        )
        // Both candidates carry the Payment role, so the hint cannot separate them and the
        // ambiguity must survive rather than being resolved arbitrarily.
        val result = TargetResolver()
            .resolve(UserIntent("გადახდა", expectedRole = ElementRole.Payment), built, now)

        assertTrue("expected ambiguity to survive, got $result", result is TargetResolution.Ambiguous)
    }

    @Test
    fun `revalidation withdraws a target once the screen has changed under it`() {
        val resolver = TargetResolver()
        val before = scene(labels(Triple("გადახდა", 0.9f, 3), Triple("უკან", 0.9f, 3)))
        val target = (resolver.resolve(UserIntent("გადახდა"), before, now)
            as TargetResolution.Resolved).target

        // The kiosk advanced: the payment button is gone.
        val after = scene(
            labels(Triple("აბონენტის ნომერი", 0.9f, 3), Triple("უკან", 0.9f, 3)),
            observedAtMs = now + 500L,
        )
        val result = resolver.revalidate(target, after, now + 600L)

        assertTrue("a withdrawn target must not resolve, got $result", result !is TargetResolution.Resolved)
    }

    @Test
    fun `revalidation keeps a target that is still on screen`() {
        val resolver = TargetResolver()
        val built = scene(labels(Triple("გადახდა", 0.9f, 3), Triple("უკან", 0.9f, 3)))
        val target = (resolver.resolve(UserIntent("გადახდა"), built, now)
            as TargetResolution.Resolved).target

        val later = scene(
            labels(Triple("გადახდა", 0.9f, 3), Triple("უკან", 0.9f, 3)),
            observedAtMs = now + 500L,
        )
        assertTrue(resolver.revalidate(target, later, now + 600L) is TargetResolution.Resolved)
    }

    @Test
    fun `an element's role comes from its text, never from what it might do`() {
        assertEquals(ElementRole.Payment, ElementRole.of("კომუნალური გადასახადი"))
        assertEquals(ElementRole.Back, ElementRole.of("უკან"))
        assertEquals(ElementRole.Balance, ElementRole.of("ბალანსი"))
        assertEquals(ElementRole.Unknown, ElementRole.of("აბონენტის ნომერი"))
    }

    @Test
    fun `a scene with no legible text reports itself unreadable`() {
        val built = scene(labels(Triple("ა", 0.05f, 1)))
        assertEquals(SceneReadability.Unreadable, built.readability)
    }
}
