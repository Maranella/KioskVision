package com.maranella.kioskvision.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One poor scan must not hide a screen the app has already read.
 *
 * `corroborated` — the list every spoken request is matched against — used to be built from the
 * **latest run only**, so a label that scan happened to miss became invisible even when the runs
 * before it had read it clearly. On a hand-held phone that is constant: the device logs show the
 * retained line count going 44 → 15 → 5 → 2 between consecutive scans. Each dip answered a
 * perfectly valid request with "that is not on this screen", which is the intermittent failure
 * this suite exists to prevent.
 *
 * The retention is bounded by **runs, not by a clock**: at most `CORROBORATION_RUNS` are kept,
 * and a confirmed screen transition clears them outright, so nothing survives into a screen that
 * does not show it.
 */
class ScreenModelRetentionTest {

    private val frame = FrameSize(576, 1280)

    private fun block(text: String, x: Float, y: Float, confidence: Float = 0.9f) =
        DetectedTextBlock(
            text = text,
            boundingBox = TextBox(x - 80f, y - 15f, x + 80f, y + 15f),
            confidence = confidence,
        )

    private fun menu() = listOf(
        block("ამერიკანო", 150f, 400f),
        block("ორმაგი ამერიკანო", 400f, 400f),
        block("კაპუჩინო", 150f, 600f),
        block("ლატე", 400f, 600f),
    )

    private var clock = 1_000L
    private fun TargetTracker.scan(blocks: List<DetectedTextBlock>): OcrUpdate {
        clock += 500
        return onOcrResult(blocks, frame, nowMs = clock, jobStartedAtMs = clock - 100, handRegion = null)
    }

    private fun TargetTracker.sees(text: String) =
        corroborated.any { TextNormalizer.lineSimilarity(it.block.normalizedText, TextNormalizer.normalize(text)) >= 0.9f }

    @Test
    fun `a scan that reads almost nothing does not hide the screen`() {
        val tracker = TargetTracker()
        tracker.scan(menu())
        tracker.scan(menu())
        assertTrue(tracker.sees("ორმაგი ამერიკანო"))

        // One bad frame: blur, a hand, a reflection. Two lines survive intake, so this is not
        // even classed as unreadable — it simply read less.
        tracker.scan(listOf(block("ლატე", 400f, 600f), block("კაპუჩინო", 150f, 600f)))

        assertTrue(
            "a label two good runs agreed on must survive one scan that missed it",
            tracker.sees("ორმაგი ამერიკანო"),
        )
    }

    @Test
    fun `a carried label says how stale it is`() {
        val tracker = TargetTracker()
        tracker.scan(menu())
        tracker.scan(listOf(block("ლატე", 400f, 600f), block("კაპუჩინო", 150f, 600f)))

        val carried = tracker.corroborated.single {
            it.block.normalizedText == TextNormalizer.normalize("ორმაგი ამერიკანო")
        }
        assertEquals("the caller must be able to tell a carried label from a fresh one", 1, carried.runsAgo)

        val fresh = tracker.corroborated.single { it.block.normalizedText == TextNormalizer.normalize("ლატე") }
        assertEquals(0, fresh.runsAgo)
    }

    @Test
    fun `a confirmed screen transition drops every option from the old screen`() {
        val tracker = TargetTracker()
        tracker.scan(menu())
        tracker.scan(menu())
        assertTrue(tracker.sees("ორმაგი ამერიკანო"))

        // A different screen, twice, which is what a confirmed transition takes.
        val payment = listOf(
            block("შეიყვანეთ თანხა", 280f, 300f),
            block("დადასტურება", 150f, 800f),
            block("გაუქმება", 400f, 800f),
            block("უკან დაბრუნება", 280f, 950f),
        )
        tracker.scan(payment)
        tracker.scan(payment)

        assertTrue("the new screen must be visible", tracker.sees("დადასტურება"))
        assertTrue(
            "no option from the previous screen may survive into this one",
            !tracker.sees("ორმაგი ამერიკანო") && !tracker.sees("კაპუჩინო"),
        )
    }

    @Test
    fun `retention is bounded by runs, not by a clock`() {
        val tracker = TargetTracker()
        tracker.scan(menu())
        // Enough later runs without the label that it falls out of the retained window, even
        // though almost no time has passed. The bound is the run count, by design — an
        // arbitrary cache timeout would keep a vanished button alive on a fast screen and
        // discard a valid one on a slow scan.
        val partial = listOf(block("ლატე", 400f, 600f), block("კაპუჩინო", 150f, 600f))
        repeat(TargetTracker.CORROBORATION_RUNS) { tracker.scan(partial) }

        assertTrue(
            "a label no recent run has read must stop being offered",
            !tracker.sees("ორმაგი ამერიკანო"),
        )
    }

    @Test
    fun `a moved screen does not offer the same option twice`() {
        val tracker = TargetTracker()
        tracker.scan(menu())
        // The phone shifted: every label is read again, far from where it was.
        tracker.scan(menu().map { block(it.text, it.boundingBox.centerX + 260f, it.boundingBox.centerY) })

        val matches = tracker.corroborated.filter {
            it.block.normalizedText == TextNormalizer.normalize("ორმაგი ამერიკანო")
        }
        assertEquals("a stale copy must not compete with the fresh reading", 1, matches.size)
        assertEquals(0, matches.single().runsAgo)
    }

    @Test
    fun `an option the screen never showed is still absent`() {
        val tracker = TargetTracker()
        tracker.scan(menu())
        tracker.scan(menu())
        // Retention must not become invention: nothing may appear that OCR never read.
        assertTrue(!tracker.sees("ესპრესო"))
        assertNull(tracker.corroborated.firstOrNull { it.block.text.contains("ესპრესო") })
        assertNotNull(tracker.corroborated.firstOrNull { it.block.text.contains("ამერიკანო") })
    }
}
