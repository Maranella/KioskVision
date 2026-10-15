package com.maranella.kioskvision.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuidanceTest {

    private val frame = FrameSize(720, 1280)

    /** Label centred at (360, 640), 200×40 px → on-target zone ±60 × ±24 px (diagonal floor 22 px). */
    private val target = TrackedTarget(
        label = "გადახდა",
        normalizedLabel = "გადახდა",
        box = TextBox(260f, 620f, 460f, 660f),
        isCritical = true,
        confirmations = TargetTracker.REQUIRED_CONFIRMATIONS,
        missedRuns = 0,
        isEstimated = false,
    )

    private fun input(
        tipX: Float?,
        tipY: Float?,
        timeMs: Long = 0,
        confidence: Float = 0.95f,
        target: TrackedTarget? = this.target,
        steady: Boolean = true,
    ) = GuidanceInput(
        timestampMs = timeMs,
        target = target,
        fingertip = if (tipX != null && tipY != null) FingertipPoint(tipX, tipY, confidence) else null,
        frame = frame,
        deviceSteady = steady,
    )

    private fun direction(engine: DirectionalGuidanceEngine, x: Float, y: Float, t: Long = 0) =
        engine.update(input(x, y, t)).direction

    // --- Directions --------------------------------------------------------------------------

    @Test
    fun `finger left of target is told to move right`() {
        assertEquals(GuidanceDirection.RIGHT, direction(DirectionalGuidanceEngine(), 60f, 640f))
    }

    @Test
    fun `finger right of target is told to move left`() {
        assertEquals(GuidanceDirection.LEFT, direction(DirectionalGuidanceEngine(), 680f, 640f))
    }

    @Test
    fun `finger below target is told to move up`() {
        assertEquals(GuidanceDirection.UP, direction(DirectionalGuidanceEngine(), 360f, 1100f))
    }

    @Test
    fun `finger above target is told to move down`() {
        assertEquals(GuidanceDirection.DOWN, direction(DirectionalGuidanceEngine(), 360f, 150f))
    }

    @Test
    fun `aligned finger is near first and on target only after the dwell`() {
        val engine = DirectionalGuidanceEngine()
        assertEquals(GuidanceDirection.NEAR_TARGET, direction(engine, 365f, 645f, t = 0))
        assertEquals(GuidanceDirection.NEAR_TARGET, direction(engine, 362f, 642f, t = DirectionalGuidanceEngine.ON_TARGET_DWELL_MS - 1))
        assertEquals(GuidanceDirection.ON_TARGET, direction(engine, 360f, 640f, t = DirectionalGuidanceEngine.ON_TARGET_DWELL_MS))
    }

    @Test
    fun `leaving the zone resets the dwell`() {
        val engine = DirectionalGuidanceEngine()
        direction(engine, 360f, 640f, t = 0)
        direction(engine, 600f, 640f, t = 300) // well outside
        assertEquals(GuidanceDirection.NEAR_TARGET, direction(engine, 360f, 640f, t = 700))
    }

    @Test
    fun `hysteresis keeps a finger at the zone edge inside`() {
        val engine = DirectionalGuidanceEngine()
        direction(engine, 360f, 640f, t = 0) // inside (zone half-width 60 px)
        // 70 px off-centre: outside the entry zone but inside the 1.35× exit zone.
        assertEquals(GuidanceDirection.ON_TARGET, direction(engine, 430f, 640f, t = 700))
    }

    @Test
    fun `near zone says slow down, then gives the direction if the finger stalls`() {
        val engine = DirectionalGuidanceEngine()
        // 120 px right: 2 zone widths, inside the 3× near zone.
        assertEquals(GuidanceDirection.NEAR_TARGET, direction(engine, 480f, 640f, t = 0))
        assertEquals(GuidanceDirection.LEFT, direction(engine, 480f, 640f, t = DirectionalGuidanceEngine.NEAR_STALL_MS))
    }

    @Test
    fun `diagonal approach does not flip axes on small changes`() {
        val engine = DirectionalGuidanceEngine()
        // dx = 300/60 = 5 zones, dy = 100/24 ≈ 4.2 zones → horizontal.
        assertEquals(GuidanceDirection.RIGHT, direction(engine, 60f, 740f))
        // dy slightly larger than dx now, but not by the 1.3× switch ratio → stays horizontal.
        assertEquals(GuidanceDirection.RIGHT, direction(engine, 60f, 770f))
    }

    @Test
    fun `thresholds scale with the target, not fixed pixels`() {
        val big = target.copy(box = TextBox(160f, 600f, 560f, 680f)) // 400×80 label
        val engine = DirectionalGuidanceEngine()
        // 100 px right is outside the small label's zone (60) but inside the big one's (120).
        assertEquals(GuidanceDirection.NEAR_TARGET, engine.update(input(460f, 640f, target = big)).direction)
    }

    // --- Validity ----------------------------------------------------------------------------

    @Test
    fun `no hand or low confidence pauses guidance`() {
        val engine = DirectionalGuidanceEngine()
        assertEquals(GuidanceDirection.HAND_NOT_DETECTED, engine.update(input(null, null)).direction)
        assertEquals(GuidanceDirection.HAND_NOT_DETECTED, engine.update(input(360f, 640f, confidence = 0.3f)).direction)
    }

    @Test
    fun `unconfirmed or missing target gives no direction`() {
        val engine = DirectionalGuidanceEngine()
        assertEquals(GuidanceDirection.TARGET_UNCERTAIN, engine.update(input(60f, 640f, target = target.copy(confirmations = 1))).direction)
        assertEquals(GuidanceDirection.TARGET_UNCERTAIN, engine.update(input(60f, 640f, target = null)).direction)
    }


    // --- Why guidance is unavailable, kept apart ------------------------------------------------

    @Test
    fun `no target and a stale target are different situations`() {
        val engine = DirectionalGuidanceEngine()

        val none = engine.update(input(60f, 640f, target = null))
        assertEquals(GuidanceDirection.TARGET_UNCERTAIN, none.direction)
        assertEquals(GuidanceUnavailable.NoTarget, none.unavailable)

        val unconfirmed = engine.update(input(60f, 640f, target = target.copy(confirmations = 1)))
        assertEquals(GuidanceUnavailable.Unconfirmed, unconfirmed.unavailable)

        val late = TargetTracker.MAX_ANCHOR_AGE_MS + 1
        val stale = engine.update(input(60f, 640f, timeMs = late, target = target.copy(anchoredAtMs = 0L)))
        assertEquals(GuidanceUnavailable.GeometryStale, stale.unavailable)
    }

    @Test
    fun `a usable target reports no unavailability`() {
        assertNull(DirectionalGuidanceEngine().update(input(60f, 640f)).unavailable)
    }

    @Test
    fun `a moving phone reports stale geometry, not a missing target`() {
        val engine = DirectionalGuidanceEngine()
        val late = TargetTracker.MOVING_ANCHOR_AGE_MS + 1
        val output = engine.update(
            input(60f, 640f, timeMs = late, target = target.copy(anchoredAtMs = 0L), steady = false),
        )
        // The button is still selected; only its position aged out. Saying the screen was lost
        // would send the user to re-frame a screen that is in view.
        assertEquals(GuidanceUnavailable.GeometryStale, output.unavailable)
    }

    // --- Announcer -----------------------------------------------------------------------------

    @Test
    fun `speaks a direction once it is stable and does not repeat it within the cooldown`() {
        val announcer = GuidanceAnnouncer()
        assertNull(announcer.onDirection(GuidanceDirection.LEFT, 0))
        assertEquals(GuidanceDirection.LEFT, announcer.onDirection(GuidanceDirection.LEFT, 300))
        assertNull(announcer.onDirection(GuidanceDirection.LEFT, 1000))
        assertNull(announcer.onDirection(GuidanceDirection.LEFT, 2700))
        assertEquals(GuidanceDirection.LEFT, announcer.onDirection(GuidanceDirection.LEFT, 2800))
    }

    @Test
    fun `brief flicker is not spoken`() {
        val announcer = GuidanceAnnouncer()
        announcer.onDirection(GuidanceDirection.LEFT, 0)
        announcer.onDirection(GuidanceDirection.LEFT, 300)
        assertNull(announcer.onDirection(GuidanceDirection.UP, 1000))
        assertNull(announcer.onDirection(GuidanceDirection.LEFT, 1100)) // back before UP was stable
    }

    @Test
    fun `losing the hand is spoken only after a delay, and on-target immediately`() {
        val announcer = GuidanceAnnouncer()
        assertNull(announcer.onDirection(GuidanceDirection.HAND_NOT_DETECTED, 0))
        assertNull(announcer.onDirection(GuidanceDirection.HAND_NOT_DETECTED, 1400))
        assertEquals(GuidanceDirection.HAND_NOT_DETECTED, announcer.onDirection(GuidanceDirection.HAND_NOT_DETECTED, 1500))
        assertEquals(GuidanceDirection.ON_TARGET, announcer.onDirection(GuidanceDirection.ON_TARGET, 1600))
    }


    // --- Target stability under finger movement -----------------------------------------------

    @Test
    fun `the target centre does not move as the finger moves`() {
        val engine = DirectionalGuidanceEngine()
        // Zone half-width is 60 px for this 200 px label, so the centre can be recovered from
        // the reported offset. It must come out the same on every frame.
        val centres = (0..29).map { i ->
            val tipX = 100f + i * 15f
            val output = engine.update(input(tipX, 640f, timeMs = i * 33L))
            tipX - output.zoneOffsetX!! * 60f
        }
        centres.forEach { assertEquals(360f, it, 0.01f) }
    }

    @Test
    fun `the same fingertip position gives the same guidance however it got there`() {
        val engine = DirectionalGuidanceEngine()
        val first = engine.update(input(480f, 640f, timeMs = 0))
        repeat(10) { engine.update(input(200f + it * 10f, 700f, timeMs = 100L + it * 33L)) }
        val again = engine.update(input(480f, 640f, timeMs = 1_000))
        assertEquals(first.zoneOffsetX, again.zoneOffsetX)
        assertEquals(first.zoneOffsetY, again.zoneOffsetY)
    }

    // --- Uncertain geometry never announces the target ------------------------------------------

    @Test
    fun `a target whose position is too old never reports on target`() {
        val engine = DirectionalGuidanceEngine()
        // Anchored at t=0 and never re-anchored: by now the measured box may have drifted.
        val unanchored = target.copy(anchoredAtMs = 0L)
        val late = TargetTracker.MAX_ANCHOR_AGE_MS + 1

        // The finger is dead centre and held there well past the dwell time.
        repeat(10) { i ->
            val output = engine.update(input(360f, 640f, timeMs = late + i * 100L, target = unanchored))
            assertEquals(GuidanceDirection.TARGET_UNCERTAIN, output.direction)
        }
    }

    @Test
    fun `an on-target finger is reported again once the target is re-anchored`() {
        val engine = DirectionalGuidanceEngine()
        val stale = target.copy(anchoredAtMs = 0L)
        val late = TargetTracker.MAX_ANCHOR_AGE_MS + 1
        assertEquals(GuidanceDirection.TARGET_UNCERTAIN, engine.update(input(360f, 640f, timeMs = late, target = stale)).direction)

        val reanchored = target.copy(anchoredAtMs = late)
        engine.update(input(360f, 640f, timeMs = late, target = reanchored))
        val output = engine.update(
            input(360f, 640f, timeMs = late + DirectionalGuidanceEngine.ON_TARGET_DWELL_MS, target = reanchored),
        )
        assertEquals(GuidanceDirection.ON_TARGET, output.direction)
    }

    @Test
    fun `losing the hand mid-approach does not leak an on-target announcement`() {
        val engine = DirectionalGuidanceEngine()
        val announcer = GuidanceAnnouncer()
        // Finger reaches the zone, then the hand detection drops out before the dwell elapses.
        assertEquals(GuidanceDirection.NEAR_TARGET, engine.update(input(360f, 640f, timeMs = 0)).direction)
        assertEquals(GuidanceDirection.HAND_NOT_DETECTED, engine.update(input(null, null, timeMs = 300)).direction)
        // Coming back restarts the dwell rather than completing the earlier one.
        assertEquals(GuidanceDirection.NEAR_TARGET, engine.update(input(360f, 640f, timeMs = 600)).direction)

        assertNull(announcer.onDirection(GuidanceDirection.HAND_NOT_DETECTED, 300))
        assertNull("a brief dropout stays silent", announcer.onDirection(GuidanceDirection.HAND_NOT_DETECTED, 600))
    }

    // --- Smoothing -----------------------------------------------------------------------------

    @Test
    fun `one euro filter damps jitter but follows real movement`() {
        val filter = OneEuroFilter2D()
        var t = 0L
        var last = 0f
        // Jitter of ±0.01 around 0.5 is reduced.
        repeat(30) { i ->
            t += 33
            last = filter.filter(if (i % 2 == 0) 0.51f else 0.49f, 0.5f, t).first
        }
        assert(kotlin.math.abs(last - 0.5f) < 0.006f) { "jitter not damped: $last" }
        // A real move to 0.8 is followed within a few frames.
        repeat(8) { t += 33; last = filter.filter(0.8f, 0.5f, t).first }
        assert(last > 0.75f) { "lagging: $last" }
    }
}
