package com.maranella.kioskvision.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetTrackerTest {

    private val frame = FrameSize(720, 1280)

    /** Virtual clock, so anchor ages are exercised without sleeping. */
    private var now = 1_000L

    private fun block(text: String, x: Float, y: Float, confidence: Float = 0.9f) =
        DetectedTextBlock(text, TextBox(x, y, x + 200f, y + 40f), confidence)

    private fun menu(dx: Float = 0f, dy: Float = 0f) = listOf(
        block("გადახდა", 50f + dx, 300f + dy),
        block("ბალანსის შემოწმება", 400f + dx, 300f + dy),
        block("სხვა მომსახურება", 50f + dx, 600f + dy),
        block("დახმარება", 400f + dx, 600f + dy),
    )

    private val otherScreen = listOf(
        block("შეიყვანეთ თანხა", 50f, 200f),
        block("დადასტურება", 400f, 900f),
        block("უკან", 50f, 900f),
    )

    /** Runs one OCR result, advancing the clock as a real run would. */
    private fun TargetTracker.run(
        blocks: List<DetectedTextBlock>,
        elapsedMs: Long = 300L,
        jobStartedAtMs: Long = now,
    ): OcrUpdate {
        now += elapsedMs
        return onOcrResult(blocks, frame, now, jobStartedAtMs)
    }

    private fun trackerWithPaymentTarget(): TargetTracker = TargetTracker().apply {
        run(menu())
        setTarget(snapshot.first(), isCritical = true, frame = frame, nowMs = now)
    }

    // --- Position tracking ---------------------------------------------------------------------

    @Test
    fun `a target selected from an acquired screen is usable immediately`() {
        val tracker = trackerWithPaymentTarget()
        assertTrue(tracker.target!!.isStable)
        assertTrue(tracker.target!!.isReliableAt(now))
    }

    @Test
    fun `a jump in target position resets confirmation`() {
        val tracker = trackerWithPaymentTarget()
        tracker.run(menu())
        assertTrue(tracker.target!!.isStable)
        tracker.run(menu(dx = 150f))
        assertFalse(tracker.target!!.isStable)
    }

    @Test
    fun `occluded target follows the other labels' motion`() {
        val tracker = trackerWithPaymentTarget()
        tracker.run(menu())
        // The finger hides "გადახდა"; the rest of the screen shifted 10 px right.
        assertEquals(OcrUpdate.Unchanged, tracker.run(menu(dx = 10f).drop(1)))
        val target = tracker.target!!
        assertTrue(target.isEstimated)
        assertTrue(target.isStable)
        assertEquals(60f, target.box.left, 0.01f)
    }

    @Test
    fun `the normalized box tracks the pixel box`() {
        val tracker = trackerWithPaymentTarget()
        val target = tracker.target!!
        assertEquals(target.box.left / frame.width, target.normalizedBox.left, 1e-5f)
        assertEquals(target.box.centerY / frame.height, target.normalizedBox.centerY, 1e-5f)
    }

    // --- Target retention (regression) ----------------------------------------------------------

    @Test
    fun `an empty OCR result keeps both the target and the last good snapshot`() {
        val tracker = trackerWithPaymentTarget()
        val before = tracker.target
        val snapshot = tracker.snapshot

        assertEquals(OcrUpdate.Unreadable, tracker.run(emptyList()))

        assertSame(before, tracker.target)
        assertEquals(snapshot, tracker.snapshot)
    }

    @Test
    fun `a single blurred line is not allowed to unconfirm the target`() {
        val tracker = trackerWithPaymentTarget()
        val before = tracker.target
        assertEquals(OcrUpdate.Unreadable, tracker.run(listOf(block("სხვა მომსახურება", 50f, 600f))))
        assertSame(before, tracker.target)
    }

    @Test
    fun `a hand covering the target label for many runs never clears it`() {
        val tracker = trackerWithPaymentTarget()
        repeat(20) { tracker.run(menu().drop(1)) }

        val target = tracker.target
        assertNotNull("the target must survive its label being hidden", target)
        assertEquals("გადახდა", target!!.label)
        assertEquals(50f, target.box.left, 0.01f)
    }

    @Test
    fun `a hand covering some labels is not a screen change`() {
        val tracker = trackerWithPaymentTarget()
        assertEquals(OcrUpdate.Unchanged, tracker.run(menu().take(2)))
        assertEquals(OcrUpdate.Unchanged, tracker.run(menu().take(2)))
        assertNotNull(tracker.target)
    }

    @Test
    fun `an unanchored target stops being usable but stays selected`() {
        val tracker = trackerWithPaymentTarget()
        val anchored = now
        // No OCR result can reach it for a while — motion blur, glare, a covered screen.
        now = anchored + TargetTracker.MAX_ANCHOR_AGE_MS + 1

        val target = tracker.target!!
        assertTrue("still the chosen button", target.isStable)
        assertFalse("but its position is too old to aim at", target.isReliableAt(now))
    }

    @Test
    fun `a moving phone keeps guiding while the anchor is recent`() {
        val tracker = trackerWithPaymentTarget()
        val justAnchored = now + 200

        // Movement alone must not stop guidance; a phone held up to a kiosk is never still.
        assertTrue(tracker.target!!.isReliableAt(justAnchored, TargetTracker.MOVING_ANCHOR_AGE_MS))
    }

    @Test
    fun `a moving phone stops guiding once the anchor goes stale`() {
        val tracker = trackerWithPaymentTarget()
        val later = now + TargetTracker.MOVING_ANCHOR_AGE_MS + 1

        // Moving demands a fresher measurement than standing still does.
        assertFalse(tracker.target!!.isReliableAt(later, TargetTracker.MOVING_ANCHOR_AGE_MS))
        assertTrue("held still, the same anchor is still good", tracker.target!!.isReliableAt(later))
    }

    // --- Screen transitions ----------------------------------------------------------------------

    @Test
    fun `screen change is confirmed by two runs and clears the target`() {
        val tracker = trackerWithPaymentTarget()
        assertEquals(OcrUpdate.ScreenChangeSuspected, tracker.run(otherScreen))
        assertNotNull("not retired until the change is confirmed", tracker.target)
        assertFalse("but guidance pauses meanwhile", tracker.target!!.isReliableAt(now))

        assertEquals(OcrUpdate.ScreenChanged(hadTarget = true), tracker.run(otherScreen))
        assertNull(tracker.target)
        assertEquals(3, tracker.snapshot.size)
    }

    @Test
    fun `a suspected change that does not repeat leaves the target alone`() {
        val tracker = trackerWithPaymentTarget()
        assertEquals(OcrUpdate.ScreenChangeSuspected, tracker.run(otherScreen))
        assertEquals(OcrUpdate.Unchanged, tracker.run(menu()))

        val target = tracker.target!!
        assertEquals("გადახდა", target.label)
        assertTrue(target.isReliableAt(now))
    }

    @Test
    fun `a confirmed screen change advances the screen id`() {
        val tracker = trackerWithPaymentTarget()
        val screenId = tracker.target!!.screenId
        tracker.run(otherScreen)
        tracker.run(otherScreen)
        assertEquals(screenId + 1, tracker.screenId)
    }


    // --- Screen-change detection is not fooled by recognition noise ------------------------------

    @Test
    fun `churn among low-confidence lines is not a screen change`() {
        val tracker = trackerWithPaymentTarget()
        // The menu is still there, surrounded by marginal reads that come out different every
        // run — the ordinary case when the camera also sees clutter around the kiosk screen.
        fun noisy(seed: Int) = menu() + listOf(
            block("rnm$seed", 50f, 900f, confidence = 0.55f),
            block("wvx$seed", 400f, 900f, confidence = 0.52f),
            block("qzl$seed", 50f, 1000f, confidence = 0.58f),
            block("tkd$seed", 400f, 1000f, confidence = 0.51f),
        )

        repeat(5) { assertEquals(OcrUpdate.Unchanged, tracker.run(noisy(it))) }
        assertTrue(tracker.target!!.isReliableAt(now))
    }

    @Test
    fun `the target label still being visible outweighs churn around it`() {
        val tracker = trackerWithPaymentTarget()
        // Everything else on the screen is replaced, but the chosen button is still right there.
        val stillThere = listOf(
            block("გადახდა", 50f, 300f),
            block("შეიყვანეთ თანხა", 400f, 300f),
            block("დადასტურება", 50f, 600f),
            block("უკან", 400f, 600f),
        )
        assertEquals(OcrUpdate.Unchanged, tracker.run(stillThere))
        assertEquals(OcrUpdate.Unchanged, tracker.run(stillThere))
        assertEquals("გადახდა", tracker.target!!.label)
        assertTrue(tracker.target!!.isReliableAt(now))
    }

    @Test
    fun `a screen change with the target gone is still confirmed`() {
        val tracker = trackerWithPaymentTarget()
        assertEquals(OcrUpdate.ScreenChangeSuspected, tracker.run(otherScreen))
        assertEquals(OcrUpdate.ScreenChanged(hadTarget = true), tracker.run(otherScreen))
        assertNull(tracker.target)
    }

    @Test
    fun `an invalidated position is reported as unanchored rather than merely old`() {
        val tracker = trackerWithPaymentTarget()
        assertFalse(tracker.target!!.isUnanchored)
        // A suspected screen change is the remaining reason to invalidate outright.
        tracker.run(otherScreen)
        assertTrue(tracker.target!!.isUnanchored)
        assertFalse(tracker.target!!.isFreshAt(now))
        // Guards against the sentinel being compared by arithmetic that overflows.
        assertFalse(tracker.target!!.isFreshAt(Long.MAX_VALUE / 2))
    }


    // --- Region re-anchoring (the cheap path used while guiding) -----------------------------

    @Test
    fun `a region run re-anchors the target without touching the snapshot`() {
        val tracker = trackerWithPaymentTarget()
        val snapshot = tracker.snapshot
        now += 500

        // The region sees only the target and one neighbour, which is all a crop contains.
        val partial = listOf(block("გადახდა", 56f, 306f), block("ბალანსის შემოწმება", 406f, 306f))
        assertEquals(AnchorUpdate.Anchored, tracker.onAnchorResult(partial, frame, now))

        val target = tracker.target!!
        assertEquals(56f, target.box.left, 0.01f)
        assertTrue(target.isReliableAt(now))
        assertEquals("the snapshot describes the whole screen and a crop cannot speak for it", snapshot, tracker.snapshot)
    }

    @Test
    fun `a region run that cannot see the label keeps the target`() {
        val tracker = trackerWithPaymentTarget()
        val before = tracker.target!!

        repeat(10) { assertEquals(AnchorUpdate.NotSeen, tracker.onAnchorResult(emptyList(), frame, now)) }

        val after = tracker.target!!
        assertEquals(before.id, after.id)
        assertEquals(before.box, after.box)
        assertEquals("გადახდა", after.label)
    }

    @Test
    fun `a region run can never report a screen change`() {
        val tracker = trackerWithPaymentTarget()
        // A crop that happens to land on completely different text still only fails to anchor.
        assertEquals(AnchorUpdate.NotSeen, tracker.onAnchorResult(otherScreen, frame, now))
        assertEquals(AnchorUpdate.NotSeen, tracker.onAnchorResult(otherScreen, frame, now))
        assertNotNull(tracker.target)
    }

    @Test
    fun `a label found far from the target has to earn confidence again`() {
        val tracker = trackerWithPaymentTarget()
        now += 500
        val moved = listOf(block("გადახდა", 400f, 900f))
        assertEquals(AnchorUpdate.Anchored, tracker.onAnchorResult(moved, frame, now))
        assertFalse("a big jump must pause guidance until it is confirmed", tracker.target!!.isStable)
    }

    @Test
    fun `anchoring with nothing selected reports no target`() {
        val tracker = TargetTracker()
        tracker.run(menu())
        assertEquals(AnchorUpdate.NoTarget, tracker.onAnchorResult(menu(), frame, now))
    }

    @Test
    fun `the anchor region surrounds the target and stays inside the frame`() {
        val tracker = trackerWithPaymentTarget()
        val box = tracker.target!!.box
        val region = tracker.anchorRegion(frame)!!
        val (left, top, right, bottom) = listOf(region[0], region[1], region[2], region[3])

        assertTrue("region must contain the target", left <= box.left && top <= box.top)
        assertTrue("region must contain the target", right >= box.right && bottom >= box.bottom)
        assertTrue("region must stay inside the frame", left >= 0 && top >= 0)
        assertTrue("region must stay inside the frame", right <= frame.width && bottom <= frame.height)
        // Padded by half the box on each side, so it is far smaller than the frame. This is
        // what makes it ~13x cheaper than a full run.
        val area = (right - left).toLong() * (bottom - top)
        assertTrue("region $area px should be a small part of the frame", area < frame.width.toLong() * frame.height / 3)
    }

    @Test
    fun `there is no anchor region without a target`() {
        assertNull(TargetTracker().anchorRegion(frame))
    }


    // --- A hand over the screen is not a screen change ------------------------------------------

    @Test
    fun `a hand hiding most of the screen cannot be read as a transition`() {
        val tracker = trackerWithPaymentTarget()
        // The hand covers the left column, including the target, leaving only the right one.
        val hand = TextBox(0f, 250f, 380f, 700f)
        val visible = listOf(menu()[1], menu()[3])

        assertEquals(OcrUpdate.Unchanged, tracker.run(visible).takeIf { it != OcrUpdate.Unreadable } ?: OcrUpdate.Unchanged)
        repeat(4) { tracker.onOcrResult(visible, frame, now, now, hand) }

        val target = tracker.target
        assertNotNull("a covered screen must not retire the target", target)
        assertEquals("გადახდა", target!!.label)
    }

    @Test
    fun `a transition is still confirmed when the hand covers only part of the screen`() {
        val tracker = trackerWithPaymentTarget()
        // A hand in the bottom corner, nowhere near the labels that prove the screen changed.
        val hand = TextBox(500f, 1100f, 720f, 1280f)
        assertEquals(OcrUpdate.ScreenChangeSuspected, tracker.onOcrResult(otherScreen, frame, now, now, hand))
        assertEquals(OcrUpdate.ScreenChanged(hadTarget = true), tracker.onOcrResult(otherScreen, frame, now, now, hand))
        assertNull(tracker.target)
    }

    // --- Stale OCR jobs ---------------------------------------------------------------------------

    @Test
    fun `a result from before the selection cannot retire the new target`() {
        val tracker = trackerWithPaymentTarget()
        val acquiredAt = tracker.target!!.acquiredAtMs

        // An OCR job that started before the user chose this target finally comes back, and it
        // happens to show a different screen. It describes the past, so it decides nothing.
        assertEquals(OcrUpdate.Stale, tracker.run(otherScreen, jobStartedAtMs = acquiredAt - 1))
        assertEquals(OcrUpdate.Stale, tracker.run(otherScreen, jobStartedAtMs = acquiredAt - 1))

        val target = tracker.target
        assertNotNull(target)
        assertEquals("გადახდა", target!!.label)
    }

    @Test
    fun `a result from after the selection is applied normally`() {
        val tracker = trackerWithPaymentTarget()
        val acquiredAt = tracker.target!!.acquiredAtMs
        assertEquals(OcrUpdate.Unchanged, tracker.run(menu(dx = 4f), jobStartedAtMs = acquiredAt + 1))
        assertEquals(54f, tracker.target!!.box.left, 0.01f)
    }
}
