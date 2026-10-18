package com.maranella.kioskvision.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Re-anchoring a bilingual label, which is how a coffee machine captions every button.
 *
 * The full-frame pass reads „კაპუჩინო / Cappuccino" as one phrase, because the two halves sit
 * on one baseline. The tight re-anchor crop re-runs layout analysis and routinely returns only
 * one half — and a half scored **0.500** against the whole, under a 0.75 threshold. Every such
 * run was recorded as `NotSeen`: **48 of 51** at a real machine. The anchor aged out, guidance
 * was withdrawn, the on-target dwell was cleared on every withdrawal, and arrival could never
 * be announced.
 *
 * These tests pin both halves of the rule: a distinctive part must re-anchor, and a part short
 * enough to be shared between buttons must not.
 */
class BilingualAnchorTest {

    private fun box(centerX: Float, centerY: Float, width: Float = 160f, height: Float = 30f) =
        TextBox(centerX - width / 2, centerY - height / 2, centerX + width / 2, centerY + height / 2)

    private fun block(text: String, centerX: Float = 240f, centerY: Float = 430f) =
        DetectedTextBlock(text = text, boundingBox = box(centerX, centerY), confidence = 0.9f)

    private val frame = FrameSize(576, 1280)

    private fun trackerHolding(label: String): TargetTracker = TargetTracker().apply {
        setTarget(block(label), isCritical = false, frame = frame, nowMs = 1_000L)
    }

    @Test
    fun `the measured similarity of a half against the whole is below the threshold`() {
        // The number that caused it. Kept as a test so the premise cannot silently change.
        val whole = TextNormalizer.normalize("კაპუჩინო / Cappuccino")
        assertTrue(
            "a half must indeed score poorly against the whole, or this fix addresses nothing",
            TextNormalizer.lineSimilarity(TextNormalizer.normalize("კაპუჩინო"), whole) < 0.75f,
        )
    }

    @Test
    fun `the Georgian half alone re-anchors the target`() {
        val tracker = trackerHolding("კაპუჩინო / Cappuccino")
        val update = tracker.onAnchorResult(listOf(block("კაპუჩინო")), frame, 1_200L)
        assertEquals(AnchorUpdate.Anchored, update)
    }

    @Test
    fun `the English half alone re-anchors the target`() {
        val tracker = trackerHolding("კაპუჩინო / Cappuccino")
        assertEquals(
            AnchorUpdate.Anchored,
            tracker.onAnchorResult(listOf(block("Cappuccino")), frame, 1_200L),
        )
    }

    @Test
    fun `the whole label still re-anchors`() {
        val tracker = trackerHolding("კაპუჩინო / Cappuccino")
        assertEquals(
            AnchorUpdate.Anchored,
            tracker.onAnchorResult(listOf(block("კაპუჩინო / Cappuccino")), frame, 1_200L),
        )
    }

    @Test
    fun `a different drink does not re-anchor`() {
        val tracker = trackerHolding("კაპუჩინო / Cappuccino")
        assertEquals(
            AnchorUpdate.NotSeen,
            tracker.onAnchorResult(listOf(block("ამერიკანო / Americano")), frame, 1_200L),
        )
    }

    /**
     * The collision this rule is bounded against. On the machine that produced the bug, four
     * cards carry „ლატე": Latte Macchiato, Caramel Latte, Caramel Latte XL and Chocolate Latte.
     * A four-character word must never be enough to move a target between them.
     */
    @Test
    fun `a short word shared by several buttons does not re-anchor`() {
        val tracker = trackerHolding("კარამელის ლატე / Caramel latte")
        assertEquals(
            "„ლატე\" appears on four different buttons; it cannot identify one",
            AnchorUpdate.NotSeen,
            tracker.onAnchorResult(listOf(block("ლატე")), frame, 1_200L),
        )
    }

    @Test
    fun `a distinctive word from a multi-word Georgian label still re-anchors`() {
        val tracker = trackerHolding("ბალანსის შემოწმება")
        assertEquals(
            AnchorUpdate.Anchored,
            tracker.onAnchorResult(listOf(block("შემოწმება")), frame, 1_200L),
        )
    }

    /**
     * The position check, with the numbers the device produced.
     *
     * Target „ამერიკანო / Americano" held at centre x=197. Re-anchors landed at 180 and 210 —
     * the Georgian half and the English half, each beside the combined centre by construction.
     * The old single radius was 21 px, so a 30 px step scored inconsistent, `confirmations`
     * fell to 1 and guidance was withdrawn as `Unconfirmed` about twice a second.
     */
    @Test
    fun `a half landing beside the combined centre still counts as the same label`() {
        val tracker = trackerHolding("ამერიკანო / Americano")
        // 210 against a held centre of 197: a 13 px step that used to be fine, then 180, a
        // 30 px step from there, which did not survive the old radius.
        assertEquals(
            AnchorUpdate.Anchored,
            tracker.onAnchorResult(listOf(block("Americano", centerX = 210f, centerY = 430f)), frame, 1_200L),
        )
        assertEquals(
            AnchorUpdate.Anchored,
            tracker.onAnchorResult(listOf(block("ამერიკანო", centerX = 180f, centerY = 430f)), frame, 1_400L),
        )
        assertTrue(
            "confidence must survive the oscillation, or guidance keeps being withdrawn",
            tracker.target!!.isStable,
        )
    }

    @Test
    fun `a label on another row is still refused`() {
        val tracker = trackerHolding("ამერიკანო / Americano")
        // Vertical stays tight on purpose: a different control is on a different row, and that
        // is the confusion that actually matters.
        tracker.onAnchorResult(listOf(block("ამერიკანო", centerX = 197f, centerY = 530f)), frame, 1_200L)
        assertEquals(
            "a match a row away must re-earn confidence before guidance resumes",
            1,
            tracker.target!!.confirmations,
        )
    }

    /**
     * The full-frame path must agree with the re-anchor path.
     *
     * Both were fixed from the same device evidence, but only the anchor path at first. A
     * full-frame run that read one half of a bilingual label still failed to recognise its own
     * target, so the target's confidence rose or fell depending on which kind of run came back
     * last — and every fall withdrew guidance and reset the on-target dwell.
     */
    @Test
    fun `a full-frame run also recognises the target from one half`() {
        val tracker = trackerHolding("ამერიკანო / Americano")
        // Two full-frame runs: the first establishes a previous snapshot, the second re-reads
        // the screen with only the Georgian half grouped as its own line.
        tracker.onOcrResult(
            listOf(block("ამერიკანო / Americano"), block("ესპრესო / Espresso", centerY = 560f)),
            frame, nowMs = 1_200L, jobStartedAtMs = 1_100L, handRegion = null,
        )
        tracker.onOcrResult(
            listOf(block("ამერიკანო", centerX = 180f), block("ესპრესო / Espresso", centerY = 560f)),
            frame, nowMs = 1_600L, jobStartedAtMs = 1_500L, handRegion = null,
        )
        assertTrue(
            "the target must survive a full-frame run that read only one half of its label",
            tracker.target!!.isStable,
        )
    }

    @Test
    fun `a single-word label is unaffected, which keeps the payment case as it was`() {
        // „გადახდა" has no parts to fall back on, so terminal labels behave exactly as before.
        val tracker = trackerHolding("გადახდა")
        assertEquals(AnchorUpdate.Anchored, tracker.onAnchorResult(listOf(block("გადახდა")), frame, 1_200L))
        assertEquals(AnchorUpdate.NotSeen, tracker.onAnchorResult(listOf(block("გაუქმება")), frame, 1_200L))
    }
}
