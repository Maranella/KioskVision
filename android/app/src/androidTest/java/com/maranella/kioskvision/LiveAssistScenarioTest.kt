package com.maranella.kioskvision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maranella.kioskvision.assist.FrameSize
import com.maranella.kioskvision.assist.GuidanceDirection
import com.maranella.kioskvision.assist.SpokenMessage
import com.maranella.kioskvision.camera.AnalysisFrame
import com.maranella.kioskvision.hand.HandLandmarkTracker
import com.maranella.kioskvision.ocr.KioskOcrEngine
import com.maranella.kioskvision.pipeline.AssistancePhase
import com.maranella.kioskvision.pipeline.DebugFrame
import com.maranella.kioskvision.pipeline.LiveAssistPipeline
import com.maranella.kioskvision.pipeline.SessionSnapshot
import com.maranella.kioskvision.sensors.DeviceMotionMonitor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Drives the whole live-assist pipeline on this device with **real** Tesseract and **real**
 * MediaPipe, feeding rendered frames instead of camera frames.
 *
 * This covers the logic of the end-to-end scenario: acquisition, target selection, target
 * retention while a hand covers the label, fingertip tracking, direction changes, reaching the
 * target, a kiosk screen transition and reacquisition.
 *
 * It is **not** a substitute for pointing the phone at a kiosk: there is no real lens, no
 * autofocus, no motion blur, no parallax between a finger hovering above glass and the glass
 * itself, and no speech recognition. Those remain to be checked by hand.
 */
@RunWith(AndroidJUnit4::class)
class LiveAssistScenarioTest {

    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private val width = 576
    private val height = 1280
    private val frameSize = FrameSize(width, height)

    /** "გადახდა" is drawn centred here; the other labels sit around it. */
    private val paymentCentre = 150f to 700f

    private val spoken = Collections.synchronizedList(mutableListOf<SpokenMessage>())
    private val snapshots = Collections.synchronizedList(mutableListOf<SessionSnapshot>())
    @Volatile private var debug: DebugFrame? = null
    @Volatile private var focusRequests = 0

    private lateinit var pipeline: LiveAssistPipeline
    private lateinit var hand: Bitmap

    private val listener = object : LiveAssistPipeline.Listener {
        override fun onSpeak(message: SpokenMessage) {
            spoken += message
            Log.i(TAG, "SPOKE $message")
        }

        override fun onSession(snapshot: SessionSnapshot) {
            snapshots += snapshot
            Log.i(TAG, "SESSION $snapshot")
        }

        override fun onDebugFrame(frame: DebugFrame) {
            debug = frame
        }

        override fun onFocusRequested() {
            focusRequests++
        }
    }

    @Before
    fun setUp() {
        hand = testContext.assets.open("pointing_up.jpg").use { BitmapFactory.decodeStream(it) }
            .copy(Bitmap.Config.ARGB_8888, false)
        pipeline = LiveAssistPipeline(DeviceMotionMonitor(appContext), listener)
        pipeline.debugEnabled = true
        pipeline.setOcrEngine(KioskOcrEngine.create(appContext))
        pipeline.setHandTracker(HandLandmarkTracker.create(appContext))
    }

    @After
    fun tearDown() {
        pipeline.release()
        hand.recycle()
    }

    // --- Frame rendering --------------------------------------------------------------------

    private fun kioskMenu(): Bitmap {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 30); textSize = 44f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("რას გინდა გააკეთო?", width / 2f, 480f, heading)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(10, 10, 20); textSize = 30f; textAlign = Paint.Align.CENTER
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 130, 150); style = Paint.Style.STROKE; strokeWidth = 2f
        }
        listOf(
            "გადახდა" to paymentCentre,
            "ბალანსი" to (430f to 700f),
            "მობილური" to (150f to 880f),
            "დახმარება" to (430f to 880f),
        ).forEach { (text, c) ->
            canvas.drawRect(c.first - 120f, c.second - 55f, c.first + 120f, c.second + 55f, outline)
            canvas.drawText(text, c.first, c.second - (label.descent() + label.ascent()) / 2, label)
        }
        return bitmap
    }

    /** The screen a kiosk shows after "გადახდა" is pressed: entirely different labels. */
    private fun amountScreen(): Bitmap {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 30); textSize = 42f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("შეიყვანეთ თანხა", width / 2f, 420f, heading)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(10, 10, 20); textSize = 32f; textAlign = Paint.Align.CENTER
        }
        listOf(
            "დადასტურება" to (150f to 760f),
            "გაუქმება" to (430f to 760f),
            "უკან" to (150f to 950f),
            "შეცვლა" to (430f to 950f),
        ).forEach { (text, c) ->
            canvas.drawText(text, c.first, c.second, label)
        }
        return bitmap
    }

    /**
     * Draws the reference hand photo so its index fingertip lands on ([tipX], [tipY]).
     * The fingertip sits at about (172, 68) of the 358×376 source.
     */
    private fun Bitmap.withHandTipAt(tipX: Float, tipY: Float, scale: Float = 1.2f): Bitmap {
        val canvas = Canvas(this)
        val w = hand.width * scale
        val h = hand.height * scale
        val left = tipX - 172f * scale
        val top = tipY - 68f * scale
        canvas.drawBitmap(hand, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        return this
    }

    // --- Frame pumping ------------------------------------------------------------------------

    /** Feeds frames at roughly camera rate until [condition] holds or [timeoutMs] elapses. */
    private fun pumpUntil(timeoutMs: Long, render: () -> Bitmap, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            pipeline.submitFrame(AnalysisFrame(render(), frameSize))
            TimeUnit.MILLISECONDS.sleep(66)
        }
        return condition()
    }

    private fun pumpFor(durationMs: Long, render: () -> Bitmap) {
        pumpUntil(durationMs, render) { false }
    }

    private fun phase() = debug?.phase
    private fun directionsSpoken() = spoken.filterIsInstance<SpokenMessage.Direction>().map { it.direction }

    // --- The scenario ---------------------------------------------------------------------------

    @Test
    fun theWholeAssistScenario() {
        // 1. Acquire the kiosk screen.
        pipeline.start()
        val acquired = pumpUntil(25_000, ::kioskMenu) { phase() == AssistancePhase.ScreenReady }
        Log.i(TAG, "1. acquired=$acquired phase=${phase()} lines=${debug?.blocks?.size} focusRequests=$focusRequests")
        assertTrue("screen was never acquired (phase ${phase()})", acquired)
        assertTrue("focus must be requested during acquisition", focusRequests > 0)

        // 2. Select "გადახდა".
        pipeline.selectTarget(listOf("გადახდა"))
        val selected = pumpUntil(10_000, ::kioskMenu) { debug?.targetLabel != null }
        Log.i(TAG, "2. selected=$selected label=${debug?.targetLabel} box=${debug?.targetBox}")
        assertTrue("target was never locked", selected)
        assertEquals("გადახდა", debug?.targetLabel)
        assertTrue(spoken.any { it is SpokenMessage.TargetSelected })
        val lockedBox = debug!!.targetBox!!
        val targetId = debug!!.targetLabel

        // 3. A hand covers the label. The target must survive it.
        // The fingertip is held well above the button so the hand *body* lies over the label
        // while the tip is still approaching: putting the tip on the button instead would
        // legitimately end guidance with ON_TARGET before the directions can be exercised.
        pumpFor(6_000) { kioskMenu().withHandTipAt(paymentCentre.first, paymentCentre.second - 150f) }
        Log.i(TAG, "3. after occlusion: label=${debug?.targetLabel} box=${debug?.targetBox} anchorAge=${debug?.targetAnchorAgeMs}")
        assertEquals("the target must survive its own label being covered", targetId, debug?.targetLabel)
        assertNotNull(debug?.targetBox)
        assertEquals("the target must not drift while hidden", lockedBox.centerX, debug!!.targetBox!!.centerX, 25f)

        // 4. The hand tracker follows the fingertip.
        // The landmarker runs in VIDEO mode, which tracks across frames, so a hand that jumps
        // between two positions — as it does between test steps but never in a real session —
        // can take several frames to re-acquire. Wait for it rather than sampling once.
        val tipFar = 480f to 1050f
        // Waiting for "a fingertip exists" would be satisfied instantly by the previous step's
        // frame, so wait for one that has actually reached the new position.
        pumpUntil(8_000, { kioskMenu().withHandTipAt(tipFar.first, tipFar.second) }) {
            debug?.fingertipRaw?.let {
                kotlin.math.abs(it.x - tipFar.first) < 90f && kotlin.math.abs(it.y - tipFar.second) < 90f
            } == true
        }
        val seen = debug?.fingertipRaw
        Log.i(TAG, "4. fingertip=$seen expected≈$tipFar confidence=${debug?.handConfidence}")
        assertNotNull("no fingertip was detected", seen)
        assertTrue(
            "fingertip ${seen!!.x},${seen.y} is not near the drawn tip $tipFar",
            kotlin.math.abs(seen.x - tipFar.first) < 90f && kotlin.math.abs(seen.y - tipFar.second) < 90f,
        )

        // 5. Directions respond to where the finger is.
        spoken.clear()
        pumpFor(4_000) { kioskMenu().withHandTipAt(480f, paymentCentre.second) }   // right of target
        val fromRight = directionsSpoken()
        spoken.clear()
        // 900, not lower: the hand photo extends ~370 px below its fingertip, and MediaPipe
        // needs the whole hand in frame, so a tip near the bottom edge is simply not detectable.
        pumpFor(4_000) { kioskMenu().withHandTipAt(paymentCentre.first, 900f) }    // below target
        val fromBelow = directionsSpoken()
        Log.i(TAG, "5. fromRight=$fromRight fromBelow=$fromBelow")
        assertTrue("expected LEFT when the finger is right of the target, got $fromRight", GuidanceDirection.LEFT in fromRight)
        assertTrue("expected UP when the finger is below the target, got $fromBelow", GuidanceDirection.UP in fromBelow)

        // 6. Reaching the target is announced.
        spoken.clear()
        val reached = pumpUntil(12_000, { kioskMenu().withHandTipAt(paymentCentre.first, paymentCentre.second + 10f) }) {
            GuidanceDirection.ON_TARGET in directionsSpoken()
        }
        Log.i(TAG, "6. reached=$reached phase=${phase()} spoken=${directionsSpoken()}")
        assertTrue("reaching the target was never announced", reached)
        val settled = pumpUntil(
            5_000,
            { kioskMenu().withHandTipAt(paymentCentre.first, paymentCentre.second + 10f) },
        ) { phase() == AssistancePhase.WaitingForScreenChange }
        assertTrue("phase after arrival was ${phase()}", settled)

        // 7. The kiosk moves on. The old target must not survive it.
        spoken.clear()
        val changed = pumpUntil(25_000, ::amountScreen) { spoken.any { it is SpokenMessage.ScreenChanged } }
        assertTrue("a real screen transition was not detected", changed)
        // The announcement is published from the OCR result; the overlay catches up on the next
        // frame, so the retirement is waited for rather than read in the same instant.
        val retired = pumpUntil(5_000, ::amountScreen) { debug?.targetBox == null }
        Log.i(TAG, "7. changed=$changed retired=$retired phase=${phase()} target=${debug?.targetLabel}")
        assertTrue("the old button must be given up on a new screen", retired)

        // 8. The new screen is reacquired and a new target can be chosen.
        val reacquired = pumpUntil(25_000, ::amountScreen) { phase() == AssistancePhase.ScreenReady }
        Log.i(TAG, "8a. reacquired=$reacquired lines=${debug?.blocks?.size}")
        assertTrue("the new screen was never acquired (phase ${phase()})", reacquired)

        pipeline.selectTarget(listOf("დადასტურება"))
        val reselected = pumpUntil(10_000, ::amountScreen) { debug?.targetLabel != null }
        Log.i(TAG, "8b. reselected=$reselected label=${debug?.targetLabel}")
        assertTrue("no target could be chosen on the new screen", reselected)
        assertEquals("დადასტურება", debug?.targetLabel)

        Log.i(TAG, "SCENARIO COMPLETE: ocrJobs=${debug?.ocrJobsStarted} anchorJobs=${debug?.anchorJobsStarted} " +
            "fullOcr=${debug?.ocrMs}ms anchorOcr=${debug?.anchorMs}ms hand=${debug?.handMs}ms")
    }

    private companion object {
        const val TAG = "LiveAssistScenario"
    }
}
