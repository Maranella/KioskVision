package com.maranella.kioskvision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maranella.kioskvision.assist.FrameSize
import com.maranella.kioskvision.assist.SpokenMessage
import com.maranella.kioskvision.camera.AnalysisFrame
import com.maranella.kioskvision.hand.HandLandmarkTracker
import com.maranella.kioskvision.haptics.HapticCue
import com.maranella.kioskvision.ocr.KioskOcrEngine
import com.maranella.kioskvision.pipeline.AssistancePhase
import com.maranella.kioskvision.pipeline.DebugFrame
import com.maranella.kioskvision.pipeline.LiveAssistPipeline
import com.maranella.kioskvision.pipeline.SessionSnapshot
import com.maranella.kioskvision.sensors.DeviceMotionMonitor
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * A full scan that crops itself to the detected screen must still be a **full scan**.
 *
 * `maybeStartOcr` used `region != null` to decide whether a run was a re-anchor. That was true
 * until a full acquisition scan learned to crop itself to the tracked screen (`f6a4e12`), after
 * which every acquisition run was counted and dispatched as an anchor: `ocrJobsStarted` and
 * `lastOcrStartMs` were never updated, nothing throttled OCR, and the result went to
 * `onAnchorResult`, which with no target returns `NoTarget` and **discards the recognised
 * text**. The screen was detected the whole time and not one line ever reached the tracker.
 *
 * Observed on a physical SM-S731B before the fix: 58 runs in ~40 s, `ocr=0ms/0`,
 * `anchor=516ms/58`, `update=Anchor:NoTarget`, `lines=0/2`, phase stuck in `AcquiringScreen`.
 *
 * `LiveAssistScenarioTest` could not have caught it: it paints a full-white frame, so no screen
 * quadrilateral is found, `screenScanRegion` returns null and the old proxy stayed accidentally
 * correct. **The frame here is deliberately a bright panel inset on a dark surround**, so the
 * detector fires and the crop is actually taken — which is the precondition for the bug, and is
 * asserted before anything else so this test can never pass vacuously.
 */
@RunWith(AndroidJUnit4::class)
class OcrAcquisitionRoutingTest {

    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    private val width = 576
    private val height = 1280
    private val frameSize = FrameSize(width, height)

    @Volatile private var debug: DebugFrame? = null

    private lateinit var pipeline: LiveAssistPipeline

    private val listener = object : LiveAssistPipeline.Listener {
        override fun onSpeak(message: SpokenMessage) = Unit
        override fun onSession(snapshot: SessionSnapshot) = Unit
        override fun onDebugFrame(frame: DebugFrame) { debug = frame }
        override fun onFocusRequested() = Unit
        override fun onHaptic(cue: HapticCue) = Unit
    }

    @Before
    fun setUp() {
        pipeline = LiveAssistPipeline(DeviceMotionMonitor(appContext), listener)
        pipeline.debugEnabled = true
        pipeline.setOcrEngine(KioskOcrEngine.create(appContext))
        pipeline.setHandTracker(HandLandmarkTracker.create(appContext))
    }

    @After
    fun tearDown() = pipeline.release()

    /**
     * A bright kiosk panel on a dark housing: what the detector is built to find.
     *
     * The panel is 440x720 of a 576x1280 frame — 43 % coverage, inside the detector's 8-95 %
     * bounds, and wider and taller than the quarter-frame minimum `screenScanRegion` needs
     * before it will return a crop at all.
     */
    private fun kioskPanel(): Bitmap {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(24, 26, 32))
        val left = 68f
        val top = 280f
        canvas.drawRect(left, top, left + 440f, top + 720f, Paint().apply { color = Color.WHITE })
        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 30); textSize = 44f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("რას გინდა გააკეთო?", width / 2f, 400f, heading)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(10, 10, 20); textSize = 34f; textAlign = Paint.Align.CENTER
        }
        listOf("გადახდა" to 560f, "ბალანსი" to 700f, "მობილური" to 840f, "დახმარება" to 950f)
            .forEach { (text, y) -> canvas.drawText(text, width / 2f, y, label) }
        return bitmap
    }

    private fun pumpUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            pipeline.submitFrame(AnalysisFrame(kioskPanel(), frameSize))
            TimeUnit.MILLISECONDS.sleep(66)
        }
        return condition()
    }

    @Test
    fun aFullScanCroppedToTheScreenTakesTheAcquisitionPath() {
        pipeline.start()

        // Precondition. Without a tracked screen and an actual crop this test exercises
        // nothing, so it fails loudly rather than passing for the wrong reason.
        val cropped = pumpUntil(20_000) { debug?.scanCroppedToScreen == true }
        Log.i(TAG, "precondition: cropped=$cropped quad=${debug?.screenQuad != null} input=${debug?.ocrInput}")
        assertTrue(
            "the detector must find the panel and the scan must be cropped to it, " +
                "or this test cannot reach the bug it guards (scanCropped=${debug?.scanCroppedToScreen})",
            cropped,
        )

        val progressed = pumpUntil(25_000) { (debug?.ocrJobsStarted ?: 0) > 0 && (debug?.retainedLines ?: 0) > 0 }
        val frame = debug
        Log.i(
            TAG,
            "ocrJobs=${frame?.ocrJobsStarted} anchorJobs=${frame?.anchorJobsStarted} " +
                "lines=${frame?.retainedLines} raw=${frame?.ocrRawBlocks} lastOcr=${frame?.lastOcrUpdate} " +
                "phase=${frame?.phase}",
        )

        // The counter. It stayed at zero for every run before the fix.
        assertTrue(
            "a full scan must be counted as one; ocrJobsStarted=${frame?.ocrJobsStarted} " +
                "while anchorJobsStarted=${frame?.anchorJobsStarted}",
            (frame?.ocrJobsStarted ?: 0) > 0,
        )
        // The result. This is the part the user sees: recognised text reaching the screen model.
        assertTrue(
            "recognised blocks must populate the tracker snapshot; retainedLines=${frame?.retainedLines}",
            progressed && (frame?.retainedLines ?: 0) > 0,
        )
        // The dispatch. "Anchor:" here means the result went to onAnchorResult and was dropped.
        assertFalse(
            "an acquisition scan must not be reported as an anchor run (${frame?.lastOcrUpdate})",
            frame?.lastOcrUpdate.orEmpty().startsWith("Anchor:"),
        )
    }

    @Test
    fun aGenuineAnchorRunStillTakesTheAnchorPath() {
        pipeline.start()
        val ready = pumpUntil(30_000) { debug?.phase == AssistancePhase.ScreenReady }
        Log.i(TAG, "acquired=$ready phase=${debug?.phase} lines=${debug?.retainedLines}")
        assertTrue("the screen must be acquired before a target can be anchored", ready)

        pipeline.selectTarget(listOf("გადახდა"))
        val locked = pumpUntil(12_000) { debug?.targetLabel != null }
        assertTrue("target was never locked (lines=${debug?.retainedLines})", locked)

        val anchorsBefore = debug?.anchorJobsStarted ?: 0
        val anchored = pumpUntil(12_000) {
            (debug?.anchorJobsStarted ?: 0) > anchorsBefore &&
                debug?.lastOcrUpdate.orEmpty().startsWith("Anchor:")
        }
        Log.i(
            TAG,
            "anchorJobs ${anchorsBefore} -> ${debug?.anchorJobsStarted} lastOcr=${debug?.lastOcrUpdate} " +
                "refreshed=${debug?.anchorsRefreshed}",
        )
        assertTrue(
            "re-anchoring a held target must still run as an anchor; " +
                "anchorJobs=${debug?.anchorJobsStarted} lastOcr=${debug?.lastOcrUpdate}",
            anchored,
        )
    }

    private companion object {
        const val TAG = "OcrAcquisitionRouting"
    }
}
