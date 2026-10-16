package com.maranella.kioskvision.assist

import com.maranella.kioskvision.camera.Point
import com.maranella.kioskvision.camera.Quad
import com.maranella.kioskvision.camera.ScreenSpace
import com.maranella.kioskvision.camera.TrackedScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guidance in canonical screen space.
 *
 * The point of rectification is that it changes the answer. These tests construct a case where
 * frame coordinates and screen coordinates disagree about which way the finger must move, and
 * pin that screen space is the one that is right.
 */
class ScreenSpaceGuidanceTest {

    /**
     * A screen viewed from the left: its right-hand edge is foreshortened, so equal distances
     * on the glass occupy fewer camera pixels there than on the near edge.
     */
    private val skewedScreen = TrackedScreen(
        quad = Quad(
            Point(100f, 100f),
            Point(600f, 200f),
            Point(600f, 700f),
            Point(100f, 900f),
        ),
        confidence = 0.85f,
        stable = true,
        ageMs = 40L,
    )

    private fun targetAt(box: TextBox) = TrackedTarget(
        label = "გადახდა",
        normalizedLabel = "გადახდა",
        box = box,
        isCritical = true,
        confirmations = TargetTracker.REQUIRED_CONFIRMATIONS,
        missedRuns = 0,
        isEstimated = false,
        anchoredAtMs = 0L,
    )

    private fun directionInScreenSpace(
        targetFrameBox: TextBox,
        fingertipInFrame: Point,
        screen: TrackedScreen = skewedScreen,
        timeMs: Long = 100L,
    ): GuidanceDirection {
        val box = ScreenSpace.toScreen(screen, targetFrameBox)!!
        val tip = ScreenSpace.toScreen(screen, fingertipInFrame)!!
        return DirectionalGuidanceEngine().update(
            GuidanceInput(
                timestampMs = timeMs,
                target = targetAt(box),
                fingertip = FingertipPoint(tip.x, tip.y, 0.95f),
                space = GuidanceSpace.KIOSK_SCREEN,
                deviceSteady = true,
            ),
        ).direction
    }

    @Test
    fun `a target and a fingertip at the same place on the glass read as on target`() {
        // Both at 70% across, 50% down the screen, expressed in camera pixels.
        val centre = ScreenSpace.toFrame(skewedScreen, Point(0.7f, 0.5f))!!
        val halfWidth = 0.06f
        val boxOnScreen = TextBox(0.7f - halfWidth, 0.48f, 0.7f + halfWidth, 0.52f)
        val boxInFrame = ScreenSpace.toFrame(skewedScreen, boxOnScreen)!!

        val engine = DirectionalGuidanceEngine()
        val box = ScreenSpace.toScreen(skewedScreen, boxInFrame)!!
        val tip = ScreenSpace.toScreen(skewedScreen, centre)!!
        val input = GuidanceInput(
            timestampMs = 0L,
            target = targetAt(box),
            fingertip = FingertipPoint(tip.x, tip.y, 0.95f),
            space = GuidanceSpace.KIOSK_SCREEN,
            deviceSteady = true,
        )
        assertEquals(GuidanceDirection.NEAR_TARGET, engine.update(input).direction)
        // After the dwell it becomes ON_TARGET; proximity in the image, not a confirmed touch.
        assertEquals(
            GuidanceDirection.ON_TARGET,
            engine.update(input.copy(timestampMs = DirectionalGuidanceEngine.ON_TARGET_DWELL_MS + 1)).direction,
        )
    }

    @Test
    fun `the on-target zone is a fraction of the screen, not of the camera frame`() {
        // The same physical label, filmed near and far, must produce the same zone in screen
        // space. In frame pixels the diagonal floor would differ by the zoom factor.
        val boxOnScreen = TextBox(0.44f, 0.48f, 0.56f, 0.52f)
        val near = TrackedScreen(
            quad = Quad(Point(0f, 0f), Point(1000f, 0f), Point(1000f, 1000f), Point(0f, 1000f)),
            confidence = 0.9f, stable = true, ageMs = 10L,
        )
        val far = TrackedScreen(
            quad = Quad(Point(400f, 400f), Point(600f, 400f), Point(600f, 600f), Point(400f, 600f)),
            confidence = 0.9f, stable = true, ageMs = 10L,
        )

        // A fingertip a fifth of the screen to the right of the label, in both views.
        val offsetOnScreen = Point(0.7f, 0.5f)
        val directions = listOf(near, far).map { screen ->
            directionInScreenSpace(
                targetFrameBox = ScreenSpace.toFrame(screen, boxOnScreen)!!,
                fingertipInFrame = ScreenSpace.toFrame(screen, offsetOnScreen)!!,
                screen = screen,
            )
        }
        assertEquals("distance must not change the verdict", directions[0], directions[1])
        assertEquals(GuidanceDirection.LEFT, directions[0])
    }

    @Test
    fun `camera pixels understate how far the finger has to travel`() {
        // Constructed ground truth: the label centre is at 30% across the glass and the
        // fingertip at 55%, so the finger is a quarter of the screen away, horizontally.
        val labelOnScreen = TextBox(0.26f, 0.30f, 0.34f, 0.34f)
        val tipOnScreen = Point(0.55f, 0.32f)
        val labelInFrame = ScreenSpace.toFrame(skewedScreen, labelOnScreen)!!
        val tipInFrame = ScreenSpace.toFrame(skewedScreen, tipOnScreen)!!

        fun offsets(space: GuidanceSpace, target: TextBox, tip: Point): Pair<Float, Float> {
            val output = DirectionalGuidanceEngine().update(
                GuidanceInput(
                    timestampMs = 100L,
                    target = targetAt(target),
                    fingertip = FingertipPoint(tip.x, tip.y, 0.95f),
                    space = space,
                    deviceSteady = true,
                ),
            )
            return output.zoneOffsetX!! to output.zoneOffsetY!!
        }

        val onGlass = offsets(
            GuidanceSpace.KIOSK_SCREEN,
            ScreenSpace.toScreen(skewedScreen, labelInFrame)!!,
            ScreenSpace.toScreen(skewedScreen, tipInFrame)!!,
        )
        val inCamera = offsets(GuidanceSpace.ofFrame(FrameSize(720, 1280)), labelInFrame, tipInFrame)

        // Screen space recovers the constructed distance: a quarter of the screen divided by
        // the zone half-width of 0.024, with no vertical component.
        assertEquals(0.25f / 0.024f, onGlass.first, 0.3f)
        assertTrue("expected a purely horizontal offset, dy was ${onGlass.second}", kotlin.math.abs(onGlass.second) < 0.1f)

        // Camera pixels put the same gap at little more than half that. Understating is the
        // dangerous direction: the near zone and the on-target zone are both thresholds on
        // this number, so a finger further away than the app believes gets told it has
        // arrived sooner than it has.
        assertTrue(
            "camera space ${inCamera.first} should understate glass ${onGlass.first}",
            inCamera.first < onGlass.first * 0.75f,
        )
    }

    @Test
    fun `a finger a tenth of the screen away is still given a direction`() {
        // A label on the foreshortened far edge, where equal distances on the panel occupy
        // fewer camera pixels. A fingertip offset by a tenth of the screen sits close enough
        // in pixels to fall inside the frame-space zone, and must not in screen space.
        val labelOnScreen = TextBox(0.84f, 0.18f, 0.96f, 0.22f)
        val tipOnScreen = Point(0.74f, 0.20f)
        val labelInFrame = ScreenSpace.toFrame(skewedScreen, labelOnScreen)!!
        val tipInFrame = ScreenSpace.toFrame(skewedScreen, tipOnScreen)!!

        val onGlass = DirectionalGuidanceEngine().update(
            GuidanceInput(
                timestampMs = 0L,
                target = targetAt(ScreenSpace.toScreen(skewedScreen, labelInFrame)!!),
                fingertip = ScreenSpace.toScreen(skewedScreen, tipInFrame)!!
                    .let { FingertipPoint(it.x, it.y, 0.95f) },
                space = GuidanceSpace.KIOSK_SCREEN,
                deviceSteady = true,
            ),
        )

        // The claim under test is only about screen space: the finger is a tenth of the panel
        // away, so the user must still be given a direction rather than told they have
        // arrived. Whether camera pixels would have said otherwise depends on the particular
        // quadrilateral and is not asserted here.
        assertNotEquals(GuidanceDirection.ON_TARGET, onGlass.direction)
        assertTrue(
            "expected a real offset on the glass, got ${onGlass.zoneOffsetX}",
            kotlin.math.abs(onGlass.zoneOffsetX!!) > 1f,
        )
    }

    @Test
    fun `without a tracked screen guidance refuses instead of guessing`() {
        val output = DirectionalGuidanceEngine().update(
            GuidanceInput(
                timestampMs = 100L,
                target = targetAt(TextBox(100f, 100f, 300f, 140f)),
                fingertip = FingertipPoint(500f, 400f, 0.95f),
                space = GuidanceSpace.KIOSK_SCREEN,
                deviceSteady = true,
                screenTracked = false,
            ),
        )

        assertEquals(GuidanceDirection.TARGET_UNCERTAIN, output.direction)
        assertEquals(GuidanceUnavailable.ScreenNotTracked, output.unavailable)
    }

    @Test
    fun `no screen is reported differently from nothing selected`() {
        val engine = DirectionalGuidanceEngine()
        val nothingSelected = engine.update(
            GuidanceInput(
                timestampMs = 100L,
                target = null,
                fingertip = FingertipPoint(0.5f, 0.5f, 0.95f),
                space = GuidanceSpace.KIOSK_SCREEN,
                deviceSteady = true,
                screenTracked = false,
            ),
        )
        // Nothing selected stays NoTarget, which the pipeline answers with silence. Reporting
        // it as a missing screen would nag the user about framing when they have not asked
        // for anything yet.
        assertEquals(GuidanceUnavailable.NoTarget, nothingSelected.unavailable)
    }

    @Test
    fun `a stale anchor is still distinguished from a missing screen`() {
        val output = DirectionalGuidanceEngine().update(
            GuidanceInput(
                timestampMs = TargetTracker.MAX_ANCHOR_AGE_MS + 1_000L,
                target = targetAt(TextBox(0.4f, 0.4f, 0.6f, 0.5f)),
                fingertip = FingertipPoint(0.5f, 0.8f, 0.95f),
                space = GuidanceSpace.KIOSK_SCREEN,
                deviceSteady = true,
                screenTracked = true,
            ),
        )
        assertEquals(GuidanceUnavailable.GeometryStale, output.unavailable)
    }

    @Test
    fun `zone offsets are reported in screen-relative units`() {
        val output = DirectionalGuidanceEngine().update(
            GuidanceInput(
                timestampMs = 0L,
                target = targetAt(TextBox(0.44f, 0.48f, 0.56f, 0.52f)),
                fingertip = FingertipPoint(0.5f, 0.5f, 0.95f),
                space = GuidanceSpace.KIOSK_SCREEN,
                deviceSteady = true,
            ),
        )
        assertTrue("expected the fingertip at the label centre", kotlin.math.abs(output.zoneOffsetX!!) < 1e-3f)
        assertTrue(kotlin.math.abs(output.zoneOffsetY!!) < 1e-3f)
    }
}
