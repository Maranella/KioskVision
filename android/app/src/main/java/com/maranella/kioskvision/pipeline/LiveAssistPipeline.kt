package com.maranella.kioskvision.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.createBitmap
import com.maranella.kioskvision.assist.AnchorUpdate
import com.maranella.kioskvision.assist.AssistCommand
import com.maranella.kioskvision.assist.DetectedTextBlock
import com.maranella.kioskvision.assist.DirectionalGuidanceEngine
import com.maranella.kioskvision.assist.FingertipPoint
import com.maranella.kioskvision.assist.FrameSize
import com.maranella.kioskvision.assist.GuidanceAnnouncer
import com.maranella.kioskvision.assist.GuidanceDirection
import com.maranella.kioskvision.assist.GuidanceInput
import com.maranella.kioskvision.assist.GuidanceOutput
import com.maranella.kioskvision.assist.GuidanceUnavailable
import com.maranella.kioskvision.assist.OcrUpdate
import com.maranella.kioskvision.assist.GuidanceSpace
import com.maranella.kioskvision.assist.KioskScene
import com.maranella.kioskvision.assist.ObservedLabel
import com.maranella.kioskvision.camera.Point
import com.maranella.kioskvision.camera.ScreenDetector
import com.maranella.kioskvision.camera.ScreenLoss
import com.maranella.kioskvision.camera.ScreenSpace
import com.maranella.kioskvision.camera.ScreenTracker
import com.maranella.kioskvision.assist.OneEuroFilter2D
import com.maranella.kioskvision.assist.Rejection
import com.maranella.kioskvision.assist.ScreenReader
import com.maranella.kioskvision.assist.SpokenMessage
import com.maranella.kioskvision.assist.TargetMatch
import com.maranella.kioskvision.assist.TargetMatcher
import com.maranella.kioskvision.assist.TargetTracker
import com.maranella.kioskvision.assist.TextBox
import com.maranella.kioskvision.assist.TextNormalizer
import com.maranella.kioskvision.camera.AnalysisFrame
import com.maranella.kioskvision.camera.CameraFrameAnalyzer
import com.maranella.kioskvision.camera.FocusState
import com.maranella.kioskvision.camera.FrameQuality
import com.maranella.kioskvision.hand.HandLandmarkTracker
import com.maranella.kioskvision.ocr.KioskOcrEngine
import com.maranella.kioskvision.ocr.OcrUpscale
import com.maranella.kioskvision.sensors.MotionSource
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Where the session is, and therefore what the camera pipeline is for right now.
 *
 * The important distinction is between the phases that need to *understand* the screen
 * ([AcquiringScreen], [ReadingNewScreen]), which run OCR hard, and the phases that only need
 * to *track a finger* ([TargetLocked], [GuidingFinger]), which run OCR rarely and purely to
 * keep the locked target anchored and to notice a kiosk screen transition.
 */
enum class AssistancePhase {
    /** No session. Nothing is recognised; the camera preview is just a preview. */
    Idle,

    /** Reading the kiosk screen until several runs agree on its contents. */
    AcquiringScreen,

    /** The screen is understood and the user can name a target. */
    ScreenReady,

    /** Matching a spoken request against the recognised labels. */
    SelectingTarget,

    /** A target is chosen and held; guidance is about to start. */
    TargetLocked,

    /** Continuous fingertip tracking and spoken directions. */
    GuidingFinger,

    /** The user reached the button; the kiosk is expected to move on. */
    WaitingForScreenChange,

    /** A transition was confirmed; the new screen is being read. */
    ReadingNewScreen,

    /** Geometry or focus cannot be trusted. The target is kept, guidance is suspended. */
    Paused,

    /** A model needed for assistance is unavailable. */
    Error,
}

/** What the UI needs to know about the session; published only when it changes. */
data class SessionSnapshot(
    val phase: AssistancePhase = AssistancePhase.Idle,
    val targetLabel: String? = null,
    val direction: GuidanceDirection? = null,
)

/** Developer overlay data, in frame pixels. */
data class DebugFrame(
    val frame: FrameSize,
    val phase: AssistancePhase,
    val blocks: List<DetectedTextBlock>,
    val targetLabel: String?,
    val targetBox: TextBox?,
    val targetStable: Boolean,
    val targetEstimated: Boolean,
    val targetAnchorAgeMs: Long?,
    val fingertipRaw: FingertipPoint?,
    val fingertipSmoothed: FingertipPoint?,
    val handConfidence: Float?,
    val direction: GuidanceDirection?,
    val zoneOffsetX: Float?,
    val zoneOffsetY: Float?,
    val deviceSteady: Boolean,
    val gyroscopeAvailable: Boolean,
    val handMs: Long,
    val ocrMs: Long,
    val ocrRunning: Boolean,
    val ocrJobsStarted: Int,
    val anchorMs: Long,
    val anchorJobsStarted: Int,
    val anchorsRefreshed: Int,
    val anchorsNotSeen: Int,
    val sinceLastOcrMs: Long?,
    val screenChangeSuspected: Boolean,
    val lastOcrUpdate: String?,
    val labels: List<ObservedLabel>,
    val lastMatchOutcome: String?,
    val blurSkips: Int,
    val screenSignatureDistance: Float?,
    val focusState: FocusState,
    val sharpness: Float,
    val referenceSharpness: Float,
)

/**
 * Real-time core of live assist.
 *
 * Threading: all state below is confined to one "pipeline" thread, which is also the CameraX
 * analysis executor. OCR runs on its own thread and posts results back to the pipeline thread.
 * Commands from the UI are posted there too. The [Listener] is called on the pipeline thread.
 *
 * ### Two pipelines over one camera stream
 *
 * **Screen understanding** runs OCR, and only when the app actually needs to know what is on
 * the kiosk: while acquiring a screen, on an explicit rescan, to re-anchor a target after the
 * phone moved, and on a slow heartbeat to notice a screen transition. It never runs per frame,
 * never runs at all while [AssistancePhase.Idle], and never runs twice at once.
 *
 * **Finger tracking** runs on every frame CameraX delivers (`KEEP_ONLY_LATEST` drops the rest):
 * hand landmarks → fingertip smoothing → guidance → speech decisions. It reads the target that
 * the tracker already holds and never asks for OCR because the finger moved.
 */
class LiveAssistPipeline(
    private val motion: MotionSource,
    private val listener: Listener,
) {
    interface Listener {
        fun onSpeak(message: SpokenMessage)
        fun onSession(snapshot: SessionSnapshot)
        fun onDebugFrame(frame: DebugFrame)

        /** Asks the camera layer to focus on the kiosk screen. Called off the main thread. */
        fun onFocusRequested()
    }

    private val pipelineThread = Executors.newSingleThreadExecutor { Thread(it, "assist-pipeline") }
    private val ocrThread = Executors.newSingleThreadExecutor {
        Thread({
            // Background priority: screen understanding may take seconds and must not
            // steal CPU from the finger tracking that runs on every frame.
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            it.run()
        }, "assist-ocr")
    }

    /**
     * Analysis executor. After [release], tasks run inline so CameraX can still deliver (and
     * the analyzer still closes) any in-flight frame instead of the task being dropped.
     */
    val executor = Executor { task ->
        try {
            pipelineThread.execute(task)
        } catch (_: RejectedExecutionException) {
            task.run()
        }
    }

    @Volatile private var released = false
    @Volatile var debugEnabled = false

    /** Developer builds only: mirrors pipeline state to logcat once a second. */
    @Volatile var diagnosticsEnabled = false

    /**
     * Magnification used when re-anchoring from a region. Exposed so an experiment can hold
     * everything else constant and vary only this.
     */
    @VisibleForTesting
    @Volatile var regionUpscale = OcrUpscale.FACTOR

    /** Re-anchor region padding; see [TargetTracker.anchorPad]. */
    @VisibleForTesting
    var anchorPad: Float
        get() = tracker.anchorPad
        set(value) { tracker.anchorPad = value }

    /** True while the microphone is open, so guidance speech is not recognized as a command. */
    @Volatile var speechMuted = false

    val analyzer = CameraFrameAnalyzer(isActive = { !released }, onFrame = ::processFrame)

    /**
     * Feeds one already-upright frame, exactly as [analyzer] does for a camera frame.
     *
     * Exists so the whole pipeline — real OCR, real hand landmarks, real scheduling — can be
     * driven from an instrumented test with rendered frames instead of a camera. Takes
     * ownership of the bitmap, as the analyzer path does.
     */
    @VisibleForTesting
    fun submitFrame(frame: AnalysisFrame) = executor.execute { processFrame(frame) }

    // --- Pipeline-thread state -------------------------------------------------------------
    private var handTracker: HandLandmarkTracker? = null
    private var ocrEngine: KioskOcrEngine? = null
    private val tracker = TargetTracker()
    private val screenDetector = ScreenDetector()
    private val screenTracker = ScreenTracker()
    private var lastScreenDetectMs = 0L
    private val luminanceScratch = IntArray(SIGNATURE_SOURCE * SIGNATURE_SOURCE)
    private val engine = DirectionalGuidanceEngine()
    private val announcer = GuidanceAnnouncer()
    private val smoother = OneEuroFilter2D()

    private var phase = AssistancePhase.Idle
    private var published = SessionSnapshot()

    private var ocrInFlight = false
    private var ocrJobsStarted = 0
    private var lastOcrStartMs = Long.MIN_VALUE / 2
    private var lastOcrFinishedMs: Long? = null
    private var lastOcrMs = 0L
    private var lastAnchorMs = Long.MIN_VALUE / 2
    private var lastAnchorOcrMs = 0L
    private var anchorJobsStarted = 0
    private var anchorsRefreshed = 0
    private var anchorsNotSeen = 0
    private var consistentRuns = 0
    private var unreadableRuns = 0
    private var acquisitionRuns = 0

    /** A spoken selection waiting for a frame good enough to answer it from. */
    private var pendingQueries: List<String>? = null
    private var pendingDeadlineMs = 0L
    private var rescanRequested = false
    private var reanchorRequested = false

    private var lastSignature: FloatArray? = null
    private var lastSignatureMs = Long.MIN_VALUE / 2
    private var lastSignatureDistance: Float? = null
    private var screenChangeSuspected = false
    private var lastOcrUpdate: String? = null
    /** Why the last spoken request was accepted or turned down, for the developer overlay. */
    private var lastMatchOutcome: String? = null

    private var focusState = FocusState.Unknown
    private var sharpness = 0f
    private var referenceSharpness = 0f
    /** Best sharpness/reference ratio among usable windows; negative when none were. */
    private var sharpnessRatio = -1f
    private var blurSkips = 0
    private val windowReference = FloatArray(SHARPNESS_WINDOW_CENTRES.size)
    private var blurredSinceMs: Long? = null
    private var lastFocusRequestMs = Long.MIN_VALUE / 2
    /** Where the hand is, so OCR results are not asked to explain what it is covering. */
    private var lastHandRegion: TextBox? = null
    private var lastSharpnessMs = Long.MIN_VALUE / 2
    private var lastHandledMotionMs = Long.MIN_VALUE / 2
    private var lastDebugMs = Long.MIN_VALUE / 2
    private var lastDebugLogMs = Long.MIN_VALUE / 2
    private var loggedPhase: AssistancePhase? = null
    private var loggedUnavailable: GuidanceUnavailable? = null

    /** Size of the most recent frame, so command handling can normalize a target box. */
    private var lastFrameSize = FrameSize(1, 1)

    // Reused across frames so measuring quality allocates nothing per frame.
    private var scratchBitmap: Bitmap? = null
    private var scratchCanvas: Canvas? = null
    private val scratchPixels = IntArray(SIGNATURE_SOURCE * SIGNATURE_SOURCE)
    private val scratchPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scratchMatrix = Matrix()
    private var sharpnessPixels: IntArray? = null

    fun setHandTracker(tracker: HandLandmarkTracker) = executor.execute {
        if (released) tracker.close() else handTracker = tracker
    }

    fun setOcrEngine(engine: KioskOcrEngine) = executor.execute {
        if (released) ocrThread.execute { engine.close() } else ocrEngine = engine
    }

    /** A model that assistance depends on could not be loaded. */
    fun onEngineUnavailable() = executor.execute {
        phase = AssistancePhase.Error
        publish(direction = null)
    }

    /** The assist screen became visible: start understanding the kiosk screen. */
    fun start() = executor.execute {
        if (released || phase == AssistancePhase.Error) return@execute
        if (phase == AssistancePhase.Idle) beginAcquisition(AssistancePhase.AcquiringScreen)
    }

    /** Starts an experiment trial from a known state: no target, no cached OCR, no counters. */
    @VisibleForTesting
    fun resetForTest() = executor.execute {
        tracker.reset()
        // Screen geometry has to go with it. R-17 was caused by state leaking between trials,
        // and a quadrilateral carried over from a previous run is exactly that kind of leak.
        screenTracker.reset()
        lastScreenDetectMs = 0L
        latestScene = KioskScene.empty(0L)
        cachedLabels = emptyList()
        resetGuidance()
        clearPending()
        phase = AssistancePhase.Idle
        published = SessionSnapshot()
        consistentRuns = 0
        unreadableRuns = 0
        acquisitionRuns = 0
        anchorsRefreshed = 0
        anchorsNotSeen = 0
        lastSignature = null
        screenChangeSuspected = false
        lastOcrUpdate = null
        resetSharpnessReference()
        blurredSinceMs = null
        lastAnchorMs = Long.MIN_VALUE / 2
        lastOcrStartMs = Long.MIN_VALUE / 2
    }

    fun submit(command: AssistCommand) = executor.execute { handleCommand(command) }

    /** Tries each recognized alternative as a target query, preferring a definite match. */
    fun selectTarget(queries: List<String>) = executor.execute { handleSelect(queries) }

    /** Re-reads the kiosk screen from scratch. The current target is given up deliberately. */
    fun rescan() = executor.execute {
        if (released || phase == AssistancePhase.Error) return@execute
        tracker.clearTarget()
        beginAcquisition(AssistancePhase.AcquiringScreen)
        publish(direction = null)
    }

    /** Stops guidance without speaking, e.g. when the app leaves the foreground. */
    fun stopSilently() = executor.execute { stopGuidance(announce = false) }

    fun onFocusState(state: FocusState) = executor.execute {
        focusState = state
        // The lens just settled: whatever sharpness we see now is the best this scene gets,
        // and later frames are judged against it.
        if (state == FocusState.Locked) resetSharpnessReference()
    }

    fun release() {
        released = true
        executor.execute {
            handTracker?.close()
            handTracker = null
            val ocr = ocrEngine
            ocrEngine = null
            scratchBitmap?.recycle()
            scratchBitmap = null
            scratchCanvas = null
            // Closed on the OCR thread, after any recognition still running there.
            ocrThread.execute { ocr?.close() }
            ocrThread.shutdown()
        }
        pipelineThread.shutdown()
    }

    // --- Frames -------------------------------------------------------------------------------

    private fun processFrame(frame: AnalysisFrame) {
        if (released) {
            frame.bitmap.recycle()
            return
        }
        val now = SystemClock.elapsedRealtime()
        lastFrameSize = frame.size

        // --- Pipeline B: hand tracking, every frame ---------------------------------------
        val handStart = SystemClock.elapsedRealtime()
        val hand = try {
            handTracker?.detect(frame.bitmap, now)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Hand detection failed", e)
            null
        }
        val handMs = SystemClock.elapsedRealtime() - handStart
        val raw = hand?.let { FingertipPoint(it.x, it.y, it.score) }
        val smoothed = raw?.let {
            val (sx, sy) = smoother.filter(it.x / frame.size.width, it.y / frame.size.height, now)
            FingertipPoint(sx * frame.size.width, sy * frame.size.height, it.confidence)
        }
        if (raw == null) smoother.reset()

        if (motion.lastMotionMs > lastHandledMotionMs) {
            lastHandledMotionMs = motion.lastMotionMs
            // The measured position is now ageing fast, so a fresh anchor is requested at once.
            // It is deliberately not discarded: throwing it away on every movement is what made
            // the app demand that the user hold the phone still.
            if (tracker.target != null) reanchorRequested = true
        }
        val steady = motion.isSteady(now)

        // --- Frame quality ----------------------------------------------------------------
        maybeRequestFocus(now)
        measureSharpness(frame, raw, now)
        val blurred = updateBlurState(now)

        // --- Screen geometry --------------------------------------------------------------
        maybeDetectScreen(frame, now)

        // --- Pipeline A: screen understanding, on demand ----------------------------------
        lastHandRegion = raw?.let { handRegionAround(it, frame.size) }
        val bitmapHandedOff = maybeStartOcr(frame, raw, now, blurred, steady)

        // --- Guidance ---------------------------------------------------------------------
        var output: GuidanceOutput? = null
        if (phase == AssistancePhase.GuidingFinger) {
            output = engine.update(guidanceInput(now, smoothed, steady))
            val toSpeak = announcer.onDirection(output.direction, now)
            if (toSpeak != null && !speechMuted) {
                // TARGET_UNCERTAIN is one direction but several situations, and saying "I have
                // lost the button, keep the screen in view" when the real problem is unreadable
                // text sends the user to fix the wrong thing.
                val message = if (toSpeak == GuidanceDirection.TARGET_UNCERTAIN) {
                    messageForUncertainty(output.unavailable)
                } else {
                    SpokenMessage.Direction(toSpeak)
                }
                message?.let(listener::onSpeak)
            }
            if (output.direction == GuidanceDirection.ON_TARGET) {
                // Guidance ends here; the user presses the button themselves.
                phase = AssistancePhase.WaitingForScreenChange
            }
            publish(direction = output.direction)
        }

        logTransitions(output, now)
        logState(smoothed, output, steady, handMs, now)

        if (debugEnabled && now - lastDebugMs >= DEBUG_INTERVAL_MS) {
            lastDebugMs = now
            emitDebugFrame(frame, raw, smoothed, output, steady, handMs, now)
        }
        if (!bitmapHandedOff) frame.bitmap.recycle()
    }

    /**
     * Asks for focus until the camera actually reports back.
     *
     * A session can start before CameraX has finished binding, and the first request then has
     * no camera to act on; without a retry the lens is left in whatever mode it happened to be
     * in. Both this and [ScreenFocusController] rate-limit, so retrying cannot become hunting.
     */
    private fun maybeRequestFocus(now: Long) {
        if (phase != AssistancePhase.AcquiringScreen && phase != AssistancePhase.ReadingNewScreen) return
        if (focusState == FocusState.Locked || focusState == FocusState.Unsupported) return
        if (now - lastFocusRequestMs < FOCUS_RETRY_MS) return
        lastFocusRequestMs = now
        listener.onFocusRequested()
    }

    // --- Screen understanding -------------------------------------------------------------

    private fun beginAcquisition(next: AssistancePhase) {
        phase = next
        consistentRuns = 0
        unreadableRuns = 0
        acquisitionRuns = 0
        lastAnchorMs = Long.MIN_VALUE / 2
        screenChangeSuspected = false
        lastSignature = null
        blurredSinceMs = null
        resetSharpnessReference()
        resetGuidance()
        // A new screen is worth one deliberate focus run; nothing else ever triggers one.
        listener.onFocusRequested()
    }

    /** Starts OCR on [frame] if this phase needs it; returns true if the OCR thread owns the bitmap. */
    private fun maybeStartOcr(
        frame: AnalysisFrame,
        fingertip: FingertipPoint?,
        now: Long,
        blurred: Boolean,
        steady: Boolean,
    ): Boolean {
        val ocr = ocrEngine ?: return false
        if (ocrInFlight) return false
        if (now - lastOcrStartMs < OCR_MIN_INTERVAL_MS) return false

        // A badly soft frame costs a full OCR run and yields text that will be rejected
        // anyway (R-12). Wait for a steadier one — but never for longer than
        // [BLUR_SKIP_LIMIT_MS], so a persistently soft scene still gets looked at.
        if (sharpnessRatio in 0f..SKIP_OCR_SHARPNESS_RATIO && now - lastOcrStartMs < BLUR_SKIP_LIMIT_MS) {
            blurSkips++
            return false
        }

        val acquiring = phase == AssistancePhase.AcquiringScreen || phase == AssistancePhase.ReadingNewScreen
        val watching = when (phase) {
            AssistancePhase.ScreenReady,
            AssistancePhase.SelectingTarget,
            AssistancePhase.TargetLocked,
            AssistancePhase.GuidingFinger,
            AssistancePhase.WaitingForScreenChange,
            AssistancePhase.Paused,
            -> true
            else -> false
        }
        if (!acquiring && !watching) return false

        val target = tracker.target
        // While a target is held, keeping its position fresh is a region job costing a fraction
        // of a full run, so full-frame OCR is reserved for the questions only it can answer:
        // has the kiosk moved on, and what is on the new screen.
        val anchoring = target != null && !acquiring
        val anchorRegion = if (anchoring) tracker.anchorRegion(frame.size) else null
        val anchorStale = target != null && !target.isFreshAt(now)

        val due: OcrKind? = when {
            acquiring -> {
                val settled = !blurred
                val floor = if (acquisitionRuns >= ACQUIRE_MAX_FAST_RUNS) ACQUIRE_BACKOFF_MS else 0L
                val ready = now - lastOcrStartMs >= floor &&
                    (settled || now - lastOcrStartMs >= ACQUIRE_FALLBACK_MS)
                if (ready) OcrKind.Full else null
            }
            // Explicit user request always re-reads everything.
            rescanRequested -> OcrKind.Full
            // Something changed away from the hand: only a full frame can confirm a transition.
            maybeScreenChanged(frame, fingertip, now) -> OcrKind.Full
            // Defined fallback: region runs have stopped finding the label for long enough that
            // the position is no longer usable. Find out why by reading the whole screen.
            anchorStale && now - lastOcrStartMs >= STALE_FALLBACK_MS -> OcrKind.Full
            // The phone moved: re-anchor immediately rather than waiting for the heartbeat.
            anchorRegion != null && reanchorRequested -> OcrKind.Region
            anchorRegion != null &&
                now - lastAnchorMs >= (if (steady) ANCHOR_INTERVAL_MS else MOVING_ANCHOR_INTERVAL_MS) ->
                OcrKind.Region
            // Nothing selected: a slow heartbeat is all that is needed to notice a new screen.
            target == null && now - lastOcrStartMs >= WATCH_INTERVAL_MS -> OcrKind.Full
            else -> null
        } ?: return false

        val region = if (due == OcrKind.Region) {
            anchorRegion?.let { Rect(it[0], it[1], it[2], it[3]) } ?: return false
        } else {
            null
        }

        reanchorRequested = false
        ocrInFlight = true
        if (region == null) {
            rescanRequested = false
            if (acquiring) acquisitionRuns++
            ocrJobsStarted++
            lastOcrStartMs = now
        } else {
            anchorJobsStarted++
            lastAnchorMs = now
        }
        val upscale = upscaleFor(region != null, frame.size)
        val bitmap = frame.bitmap
        val size = frame.size
        val startedAt = now
        try {
            ocrThread.execute {
                val started = SystemClock.elapsedRealtime()
                val result = runCatching { ocr.recognize(bitmap, region, upscale) }
                val elapsed = SystemClock.elapsedRealtime() - started
                bitmap.recycle()
                executor.execute { onOcrResult(result, size, elapsed, startedAt, region != null) }
            }
        } catch (_: RejectedExecutionException) {
            ocrInFlight = false
            return false
        }
        return true
    }

    /**
     * Whether to enlarge the image before recognition, and by how much.
     *
     * Applied where it pays: always for a re-anchor region, which is small enough that the cost
     * is slight, and for a full frame only when the labels this screen actually showed were
     * small. The rule itself lives in [OcrUpscale] so the evaluation harness can apply exactly
     * the same one to still photographs.
     */
    private fun upscaleFor(isRegion: Boolean, frame: FrameSize): Float =
        if (isRegion) {
            regionUpscale
        } else {
            OcrUpscale.forLabelHeights(tracker.snapshot.map { it.boundingBox.height }, frame.height)
        }

    private enum class OcrKind { Full, Region }

    private fun onOcrResult(
        result: Result<List<DetectedTextBlock>>,
        size: FrameSize,
        elapsedMs: Long,
        startedAtMs: Long,
        wasRegion: Boolean,
    ) {
        ocrInFlight = false
        if (released) return
        val now = SystemClock.elapsedRealtime()
        val blocks = result.getOrElse {
            Log.e(TAG, "OCR failed", it)
            return
        }

        if (wasRegion) {
            lastAnchorOcrMs = elapsedMs
            // A region result can only re-anchor; it never votes on the screen's identity.
            val outcome = tracker.onAnchorResult(blocks, size, now)
            // Counted separately: unreadableRuns only tracks full-frame runs, so without this
            // a stale anchor alongside dozens of anchor jobs looks like a contradiction.
            when (outcome) {
                AnchorUpdate.Anchored -> anchorsRefreshed++
                AnchorUpdate.NotSeen -> anchorsNotSeen++
                AnchorUpdate.NoTarget -> Unit
            }
            lastOcrUpdate = "Anchor:" + outcome.name
            return
        }

        lastOcrMs = elapsedMs
        lastOcrFinishedMs = now

        val update = tracker.onOcrResult(blocks, size, now, startedAtMs, lastHandRegion)
        // The only point at which what OCR has read can change.
        cachedLabels = tracker.corroborated
        lastOcrUpdate = update::class.simpleName
        when (update) {
            is OcrUpdate.ScreenChanged -> {
                screenChangeSuspected = false
                consistentRuns = 0
                if (update.hadTarget) {
                    resetGuidance()
                    speak(SpokenMessage.ScreenChanged)
                }
                // Read the new screen before anything else is said about it.
                phase = AssistancePhase.ReadingNewScreen
                lastSignature = null
                publish(direction = null)
            }

            OcrUpdate.ScreenChangeSuspected -> {
                screenChangeSuspected = true
                consistentRuns = 0
            }

            // A frame OCR could not use says nothing: no state moves, nothing is spoken, and
            // above all the locked target is left exactly as it was.
            OcrUpdate.Stale -> Unit

            OcrUpdate.Unreadable -> {
                unreadableRuns++
                // Nothing is at stake while no target is held, so a screen that has been
                // unreadable for this long is better re-acquired than answered from memory.
                if (tracker.target == null &&
                    phase == AssistancePhase.ScreenReady &&
                    unreadableRuns >= MAX_UNREADABLE_RUNS
                ) {
                    beginAcquisition(AssistancePhase.AcquiringScreen)
                    publish(direction = null)
                }
            }

            OcrUpdate.Unchanged -> {
                screenChangeSuspected = false
                unreadableRuns = 0
                lastAnchorMs = now
                consistentRuns++
                onScreenConsistent()
                // A request held open for a steadier frame gets another chance on each
                // usable run, rather than waiting for the user to ask again.
                if (tracker.target == null) pendingQueries?.let { handleSelect(it) }
            }
        }
    }

    /** One more OCR run agreed with the previous one. */
    private fun onScreenConsistent() {
        val acquiring = phase == AssistancePhase.AcquiringScreen || phase == AssistancePhase.ReadingNewScreen
        if (!acquiring) return
        if (consistentRuns < ACQUISITION_RUNS) return
        if (tracker.snapshot.size < TargetTracker.MIN_INFORMATIVE_LINES) return

        phase = AssistancePhase.ScreenReady
        acquisitionRuns = 0
        publish(direction = null)
        // A request that arrived while the screen was still being read can be answered now.
        pendingQueries?.let { handleSelect(it) }
    }

    /** Forgets what "sharp" looked like, e.g. after the lens is sent somewhere new. */
    private fun resetSharpnessReference() {
        windowReference.fill(0f)
        referenceSharpness = 0f
        sharpnessRatio = -1f
    }

    /** A rough box around the hand behind [tip]; the fingers extend from it in some direction. */
    private fun handRegionAround(tip: FingertipPoint, frame: FrameSize): TextBox {
        val radius = HAND_MASK_RADIUS_OF_DIAGONAL * frame.diagonal
        return TextBox(tip.x - radius, tip.y - radius, tip.x + radius, tip.y + radius)
    }

    /**
     * What to say when no direction can be given.
     *
     * The app has no kiosk-screen detector — the frame *is* the screen (see `FrameGeometry`) —
     * so it must never claim to have lost a screen it never tracked. Each case names what is
     * actually wrong and what would fix it.
     */
    private fun messageForUncertainty(reason: GuidanceUnavailable?): SpokenMessage? = when {
        // Nothing is selected: guidance is not running, so there is nothing to report.
        reason == GuidanceUnavailable.NoTarget -> null

        // OCR is currently producing nothing usable. Almost always the text is too small at
        // this distance, so the useful instruction is to come closer — not to re-frame.
        screenUnreadableNow() -> SpokenMessage.CannotReadText

        // No rectified screen at all. The user has to re-frame the kiosk; telling them the
        // button's position has gone stale would send them to fix the wrong thing.
        reason == GuidanceUnavailable.ScreenNotTracked -> SpokenMessage.ScreenNotTracked

        // Chosen, but not yet corroborated by OCR.
        reason == GuidanceUnavailable.Unconfirmed -> SpokenMessage.ReadingScreen

        // The button is still selected; only its measured position has gone stale.
        else -> SpokenMessage.TargetPositionLost
    }

    /** True when recent OCR produced nothing the matcher could use. */
    private fun screenUnreadableNow(): Boolean =
        tracker.snapshot.isEmpty() || unreadableRuns > 0

    // --- Screen-change detection ------------------------------------------------------------

    /**
     * True when the image changed enough, **away from the hand**, to be worth re-reading early.
     *
     * This only brings an OCR run forward. Whether the kiosk really moved on is decided by
     * [TargetTracker], which compares recognised text across two consecutive runs; a pixel
     * difference on its own never invalidates a screen or a target.
     */
    private fun maybeScreenChanged(frame: AnalysisFrame, fingertip: FingertipPoint?, now: Long): Boolean {
        if (now - lastSignatureMs < SIGNATURE_INTERVAL_MS) return false
        lastSignatureMs = now
        val signature = signatureOf(frame.bitmap) ?: return false
        val previous = lastSignature
        lastSignature = signature
        if (previous == null) return false

        // The user's own hand is the one thing in view that is meant to move. Comparing the
        // cells it covers would make every reach for a button look like a new screen.
        val mask = fingertip?.let {
            FrameQuality.cellsAround(
                x = it.x,
                y = it.y,
                frameWidth = frame.size.width,
                frameHeight = frame.size.height,
                radius = HAND_MASK_RADIUS_OF_DIAGONAL * frame.size.diagonal,
            )
        }
        val distance = FrameQuality.maskedDistance(previous, signature, mask)
        lastSignatureDistance = distance
        return distance > SCREEN_CHANGE_SIGNATURE_DISTANCE
    }

    /**
     * Expresses the target and the fingertip in canonical screen space before guidance runs.
     *
     * This is where rectification earns its place. In frame pixels a label and a fingertip are
     * two points in a photograph: they coincide only when the phone faces the screen squarely,
     * and at any other angle the offset between them is partly the viewing angle rather than
     * the distance the finger has to travel. Guiding from that produces directions that are
     * confidently wrong, which for a blind user is worse than no direction at all.
     *
     * When no screen is being tracked the input is returned with no target, so the engine
     * refuses with [GuidanceUnavailable.ScreenNotTracked] rather than falling back to frame
     * coordinates. The fallback would work on a head-on screen and mislead on every other one,
     * which is precisely the failure that cannot be allowed to be silent.
     */
    private fun guidanceInput(now: Long, fingertip: FingertipPoint?, steady: Boolean): GuidanceInput {
        val target = tracker.target
        val screen = screenTracker.geometry(now)

        if (target == null) {
            return GuidanceInput(now, null, fingertip, GuidanceSpace.KIOSK_SCREEN, steady)
        }
        if (screen == null) {
            // The target is passed through so the engine can tell "no screen" from "nothing
            // selected", but screenTracked = false makes it refuse before it reads the box,
            // which is still in camera coordinates here.
            return GuidanceInput(
                timestampMs = now,
                target = target,
                fingertip = fingertip,
                space = GuidanceSpace.KIOSK_SCREEN,
                deviceSteady = steady,
                screenTracked = false,
            )
        }

        val boxOnScreen = ScreenSpace.toScreen(screen, target.box)
        val tipOnScreen = fingertip?.let { tip ->
            ScreenSpace.toScreen(screen, Point(tip.x, tip.y))
                ?.let { FingertipPoint(it.x, it.y, tip.confidence) }
        }
        if (boxOnScreen == null) {
            // Found but not rectifiable from this frame — the same situation as not having it.
            return GuidanceInput(
                timestampMs = now,
                target = target,
                fingertip = tipOnScreen,
                space = GuidanceSpace.KIOSK_SCREEN,
                deviceSteady = steady,
                screenTracked = false,
            )
        }

        return GuidanceInput(
            timestampMs = now,
            target = target.copy(box = boxOnScreen),
            fingertip = tipOnScreen,
            space = GuidanceSpace.KIOSK_SCREEN,
            deviceSteady = steady,
        )
    }

    /**
     * Looks for the kiosk screen, on a timer rather than on every frame.
     *
     * A handheld phone does not move appreciably between two consecutive frames, so detecting
     * per frame would repeat the same answer at the cost of the hand tracking that genuinely
     * does need every frame. The tracker works in wall-clock time, so this interval can change
     * without altering when geometry goes stale.
     */
    private fun maybeDetectScreen(frame: AnalysisFrame, now: Long) {
        if (phase == AssistancePhase.Idle) return
        if (now - lastScreenDetectMs < SCREEN_DETECT_INTERVAL_MS) return
        lastScreenDetectMs = now

        val pixels = scaledPixels(frame.bitmap) ?: return
        for (i in pixels.indices) {
            val argb = pixels[i]
            // Rec. 601 luma, integer-weighted to stay off the floating-point path on a
            // per-pixel loop that runs several times a second.
            luminanceScratch[i] = (
                299 * ((argb shr 16) and 0xFF) +
                    587 * ((argb shr 8) and 0xFF) +
                    114 * (argb and 0xFF)
                ) / 1000
        }

        val candidate = screenDetector.detect(luminanceScratch, SIGNATURE_SOURCE, SIGNATURE_SOURCE)
        // The grid is square and the frame is not, so each axis is unsquashed separately.
        val inFrame = candidate?.let {
            it.copy(
                quad = it.quad.scaled(
                    frame.size.width.toFloat() / SIGNATURE_SOURCE,
                    frame.size.height.toFloat() / SIGNATURE_SOURCE,
                ),
            )
        }
        screenTracker.onDetection(inFrame, now)
        latestScene = currentScene(now)
    }

    /**
     * The most recent scene, readable from any thread.
     *
     * The Live SDK calls its function handler synchronously on its own thread, and that
     * handler has to answer "does this exist on the screen" without blocking the audio
     * session. Hopping to the pipeline thread and waiting would risk stalling capture, so the
     * scene is published here instead and read as a snapshot. Published from the pipeline
     * thread, read from anywhere; a reader may see a scene a few hundred milliseconds old,
     * which is why [KioskScene.observedAtMs] travels with it.
     */
    @Volatile
    var latestScene: KioskScene = KioskScene.empty(0L)
        private set

    /**
     * Corroborated labels as of the last OCR result.
     *
     * [TargetTracker.corroborated] recomputes agreement across recent runs, comparing every
     * label against every earlier one by string similarity. That is affordable when OCR
     * finishes — a few times a second at most — and wasteful on the screen-detection timer,
     * which fires far more often and cannot change what OCR read. Caching it keeps the
     * expensive part on the thread that must keep up with the camera down to a homography per
     * label.
     */
    private var cachedLabels: List<ObservedLabel> = emptyList()

    /**
     * The kiosk screen as currently observed, for anything that needs the whole picture rather
     * than one target: explaining the screen, resolving an intent, or deciding that neither is
     * possible right now.
     *
     * Called on the pipeline thread.
     */
    fun currentScene(now: Long = SystemClock.elapsedRealtime()): KioskScene {
        val size = lastFrameSize ?: return KioskScene.empty(now)
        return KioskScene.from(
            labels = cachedLabels,
            screen = screenTracker.geometry(now),
            frameWidth = size.width,
            frameHeight = size.height,
            nowMs = now,
        )
    }

    /** Why screen geometry is unusable, or null when it is fine. Pipeline thread. */
    fun screenLoss(now: Long = SystemClock.elapsedRealtime()): ScreenLoss? = screenTracker.loss(now)

    /** Draws the frame into the reusable scratch bitmap and returns its luminance signature. */
    private fun signatureOf(bitmap: Bitmap): FloatArray? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        scaledPixels(bitmap) ?: return null
        return FrameQuality.signature(scratchPixels, SIGNATURE_SOURCE, SIGNATURE_SOURCE)
    }

    /**
     * Draws the frame into the one reusable scratch bitmap and returns its pixels.
     *
     * Shared by the screen-change signature and screen detection so a frame is downscaled at
     * most once, and so the two can never end up looking at differently scaled versions of it.
     * The returned array is the scratch buffer itself and is overwritten by the next call.
     */
    private fun scaledPixels(bitmap: Bitmap): IntArray? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val scratch = scratchBitmap ?: createBitmap(SIGNATURE_SOURCE, SIGNATURE_SOURCE).also {
            scratchBitmap = it
            scratchCanvas = Canvas(it)
        }
        val canvas = scratchCanvas ?: return null
        scratchMatrix.setScale(
            SIGNATURE_SOURCE.toFloat() / bitmap.width,
            SIGNATURE_SOURCE.toFloat() / bitmap.height,
        )
        canvas.drawBitmap(bitmap, scratchMatrix, scratchPaint)
        scratch.getPixels(scratchPixels, 0, SIGNATURE_SOURCE, 0, 0, SIGNATURE_SOURCE, SIGNATURE_SOURCE)
        return scratchPixels
    }

    // --- Focus and sharpness ------------------------------------------------------------------

    /**
     * Measures how sharp the kiosk screen looks, from full-resolution windows down the middle
     * of the frame — downscaling is itself a blur and would hide the thing being measured.
     *
     * Each window keeps **its own** best-ever sharpness and is judged against that. Windows
     * differ in how much text they happen to contain, so a single pooled reference is not
     * comparable once the set of usable windows changes: with the hand over the busiest window,
     * the remaining one would look blurred merely for being plainer, and guidance would pause
     * exactly when the user is reaching for the button.
     *
     * Windows the hand covers are skipped, and windows with too little texture to say anything
     * (a blank area of the screen) never vote.
     */
    private fun measureSharpness(frame: AnalysisFrame, fingertip: FingertipPoint?, now: Long) {
        val acquiring = phase == AssistancePhase.AcquiringScreen || phase == AssistancePhase.ReadingNewScreen
        val interval = if (acquiring) ACQUIRE_SHARPNESS_INTERVAL_MS else SHARPNESS_INTERVAL_MS
        if (now - lastSharpnessMs < interval) return
        lastSharpnessMs = now

        val bitmap = frame.bitmap
        val side = minOf(SHARPNESS_WINDOW, bitmap.width, bitmap.height)
        if (side < 2) return
        val pixels = sharpnessPixels?.takeIf { it.size >= side * side } ?: IntArray(side * side).also {
            sharpnessPixels = it
        }
        val handRadius = HAND_MASK_RADIUS_OF_DIAGONAL * frame.size.diagonal
        val left = (bitmap.width - side) / 2

        var bestRatio = -1f
        for ((index, fraction) in SHARPNESS_WINDOW_CENTRES.withIndex()) {
            val top = (bitmap.height * fraction - side / 2f).toInt().coerceIn(0, bitmap.height - side)
            if (fingertip != null) {
                val dx = fingertip.x - (left + side / 2f)
                val dy = fingertip.y - (top + side / 2f)
                if (dx * dx + dy * dy < handRadius * handRadius) continue
            }
            bitmap.getPixels(pixels, 0, side, left, top, side, side)
            val measured = FrameQuality.sharpness(pixels, side, side)
            if (measured > windowReference[index]) windowReference[index] = measured

            val reference = windowReference[index]
            if (reference < MIN_REFERENCE_SHARPNESS) continue
            val ratio = measured / reference
            if (ratio > bestRatio) {
                bestRatio = ratio
                sharpness = measured
                referenceSharpness = reference
            }
        }
        // Every window was under the hand or too plain to judge: this frame says nothing.
        sharpnessRatio = bestRatio
    }

    /**
     * Returns true while the image is too soft to read, and moves guidance in and out of
     * [AssistancePhase.Paused] rather than guiding from text that cannot be trusted.
     */
    private fun updateBlurState(now: Long): Boolean {
        // Negative means no window could be judged this frame; that is not evidence of blur.
        if (sharpnessRatio < 0f) return false
        val blurred = sharpnessRatio < BLUR_RATIO
        if (!blurred) {
            blurredSinceMs = null
            if (phase == AssistancePhase.Paused) {
                phase = if (tracker.target != null) AssistancePhase.GuidingFinger else AssistancePhase.ScreenReady
                publish(direction = null)
            }
            return false
        }
        val since = blurredSinceMs ?: now.also { blurredSinceMs = it }
        if (now - since < BLUR_CONFIRM_MS) return false

        val guiding = phase == AssistancePhase.GuidingFinger || phase == AssistancePhase.TargetLocked
        if (guiding) {
            // Stop talking before asking the camera to fix itself: a direction derived from a
            // blurred screen is exactly the kind of guidance that must never be spoken.
            resetGuidance()
            phase = AssistancePhase.Paused
            speak(SpokenMessage.ScreenOutOfFocus)
            publish(direction = null)
        }
        if (now - lastOcrStartMs > BLUR_CONFIRM_MS) listener.onFocusRequested()
        return true
    }

    // --- Commands -----------------------------------------------------------------------------

    private fun handleCommand(command: AssistCommand) {
        when (command) {
            is AssistCommand.SelectTarget -> handleSelect(listOf(command.query))
            AssistCommand.ReadScreen -> when {
                ocrEngine == null -> speak(SpokenMessage.TextRecognitionNotReady)
                tracker.snapshot.isEmpty() -> {
                    requestScreen()
                    speak(SpokenMessage.ReadingScreen)
                }
                else -> speak(SpokenMessage.ScreenContents(ScreenReader.read(tracker.snapshot)))
            }
            AssistCommand.Stop -> stopGuidance(announce = true)
            // Handled by the ViewModel, which owns the last spoken message.
            AssistCommand.Repeat, AssistCommand.Unknown -> Unit
        }
    }

    private fun handleSelect(queries: List<String>) {
        if (ocrEngine == null) return speak(SpokenMessage.TextRecognitionNotReady)
        val now = SystemClock.elapsedRealtime()
        val labels = tracker.corroborated

        // Nothing read yet: remember the request and answer it once the screen is understood.
        if (labels.isEmpty() || phase == AssistancePhase.Idle) {
            defer(queries, now, SpokenMessage.ReadingScreen)
            requestScreen()
            return
        }

        phase = AssistancePhase.SelectingTarget
        val matches = queries.map { TargetMatcher.matchObserved(it, labels) }
        val found = matches.filterIsInstance<TargetMatch.Found>().firstOrNull()
        val ambiguous = matches.filterIsInstance<TargetMatch.Ambiguous>().firstOrNull()
        val unconfirmed = matches.any {
            it is TargetMatch.NotFound && it.reason == Rejection.UnconfirmedLabel
        }

        lastMatchOutcome = when {
            found != null -> "FOUND '${found.block.text.trim()}'"
            ambiguous != null -> "AMBIGUOUS"
            unconfirmed -> "UNCONFIRMED (blur) — holding request"
            imageUnreliable() -> "IMAGE POOR — holding request"
            else -> "NO SIMILAR LABEL"
        } + " for '${queries.first()}'"

        if (diagnosticsEnabled) {
            Log.i(TAG, "SELECT heard='${queries.joinToString("' / '")}' -> $lastMatchOutcome")
            Log.i(TAG, "  screen has ${labels.size} labels, ${tracker.snapshot.size} in last run:")
            TargetMatcher.explain(queries.first(), labels).take(12).forEach { Log.i(TAG, "    $it") }
        }

        when {
            found != null -> {
                clearPending()
                // The screen behind these labels was already confirmed by several consistent
                // runs, so the target starts fully confirmed instead of waiting for more OCR.
                tracker.setTarget(
                    block = found.block,
                    isCritical = found.isCritical,
                    frame = lastFrameSize,
                    nowMs = now,
                )
                resetGuidance()
                phase = AssistancePhase.GuidingFinger
                speak(SpokenMessage.TargetSelected(TextNormalizer.forSpeech(found.block.text), found.isCritical))
                publish(direction = null)
            }

            ambiguous != null -> {
                clearPending()
                phase = AssistancePhase.ScreenReady
                speak(
                    SpokenMessage.TargetAmbiguous(
                        TextNormalizer.forSpeech(ambiguous.first.text),
                        TextNormalizer.forSpeech(ambiguous.second.text),
                    ),
                )
                publish(direction = null)
            }

            // Something matching the request *was* read, just not well enough to act on —
            // almost always motion blur (R-12). Asking for a steadier frame is honest; saying
            // the button is not there would not be.
            // Either the label was read but not confirmed, or the picture is currently too
            // poor to conclude anything from. Neither justifies telling the user the button
            // is not there.
            (unconfirmed || imageUnreliable()) && stillWorthRetrying(now) -> {
                defer(queries, now, SpokenMessage.HoldStill)
                requestScreen()
                phase = AssistancePhase.ScreenReady
                publish(direction = null)
            }

            else -> {
                clearPending()
                phase = AssistancePhase.ScreenReady
                speak(SpokenMessage.TargetNotFound(queries.first()))
                publish(direction = null)
            }
        }
    }

    /**
     * Holds a request open while better frames are sought, speaking [message] only the first
     * time so a retry does not turn into repetition.
     */
    private fun defer(queries: List<String>, nowMs: Long, message: SpokenMessage) {
        val firstTime = pendingQueries == null
        if (firstTime) pendingDeadlineMs = nowMs + SELECT_RETRY_WINDOW_MS
        pendingQueries = queries
        if (firstTime) speak(message)
    }

    /**
     * True when the picture is currently too soft for an absent label to mean the button is
     * absent. Judged on sharpness alone: a moving phone whose image is still readable is not a
     * reason to refuse an answer.
     */
    private fun imageUnreliable(): Boolean = sharpnessRatio in 0f..BLUR_RATIO

    /** True while the retry window for a deferred request is still open. */
    private fun stillWorthRetrying(nowMs: Long): Boolean {
        if (pendingQueries == null) {
            pendingDeadlineMs = nowMs + SELECT_RETRY_WINDOW_MS
            return true
        }
        return nowMs < pendingDeadlineMs
    }

    private fun clearPending() {
        pendingQueries = null
        pendingDeadlineMs = 0L
    }

    /** Makes sure the screen gets read, without discarding anything already known. */
    private fun requestScreen() {
        rescanRequested = true
        if (phase == AssistancePhase.Idle || phase == AssistancePhase.ScreenReady) {
            beginAcquisition(AssistancePhase.AcquiringScreen)
            publish(direction = null)
        }
    }

    private fun stopGuidance(announce: Boolean) {
        val wasActive = phase != AssistancePhase.Idle || tracker.target != null
        tracker.clearTarget()
        resetGuidance()
        clearPending()
        phase = AssistancePhase.Idle
        publish(direction = null)
        if (announce && wasActive) speak(SpokenMessage.GuidanceStopped)
    }

    private fun resetGuidance() {
        engine.reset()
        announcer.reset()
    }

    private fun speak(message: SpokenMessage) = listener.onSpeak(message)

    private fun publish(direction: GuidanceDirection?) {
        val snapshot = SessionSnapshot(phase = phase, targetLabel = tracker.target?.label, direction = direction)
        if (snapshot != published) {
            published = snapshot
            listener.onSession(snapshot)
        }
    }

    /**
     * Mirrors the pipeline's state to logcat once a second so a real session can be reviewed
     * as a timeline rather than a series of screenshots.
     *
     * Gated on [diagnosticsEnabled], which the ViewModel sets only for a debuggable build:
     * the line names the selected target, and that is kiosk screen content.
     */
    /**
     * Logs the moment a state changes, with the evidence behind it. The 1 Hz snapshot shows
     * what is true now; this shows *when and why* it changed, which is what a field report
     * like "it suddenly said it lost the screen" needs in order to be diagnosable.
     */
    private fun logTransitions(output: GuidanceOutput?, now: Long) {
        if (!diagnosticsEnabled) return
        if (phase != loggedPhase) {
            val target = tracker.target
            val heights = tracker.snapshot.map { it.boundingBox.height }.sorted()
            Log.i(
                TAG,
                "PHASE ${loggedPhase ?: "-"} -> $phase  lines=${tracker.snapshot.size}" +
                    (if (heights.isEmpty()) "" else "  textPx=${heights[heights.size / 2].toInt()}") +
                    "  target=${target?.label ?: "-"}" +
                    "  lastOcr=${lastOcrUpdate ?: "-"}" +
                    String.format(Locale.ROOT, "  sharp=%.3f/%.3f", sharpness, referenceSharpness) +
                    "  focus=$focusState",
            )
            loggedPhase = phase
        }
        val unavailable = output?.unavailable
        if (unavailable != loggedUnavailable) {
            val target = tracker.target
            Log.i(
                TAG,
                "GUIDANCE ${loggedUnavailable ?: "available"} -> ${unavailable ?: "available"}" +
                    (target?.let { "  anchoredAgoMs=${if (it.isUnanchored) -1 else now - it.anchoredAtMs}" } ?: "") +
                    "  confirmations=${target?.confirmations ?: 0}" +
                    "  lines=${tracker.snapshot.size}  unreadableRuns=$unreadableRuns" +
                    "  anchorJobs=$anchorJobsStarted refreshed=$anchorsRefreshed notSeen=$anchorsNotSeen  ocrJobs=$ocrJobsStarted",
            )
            loggedUnavailable = unavailable
        }
    }

    private fun logState(
        smoothed: FingertipPoint?,
        output: GuidanceOutput?,
        steady: Boolean,
        handMs: Long,
        now: Long,
    ) {
        if (!diagnosticsEnabled) return
        if (now - lastDebugLogMs < DEBUG_LOG_INTERVAL_MS) return
        lastDebugLogMs = now
        val target = tracker.target
        val tip = smoothed?.let { String.format(Locale.ROOT, "%.0f,%.0f@%.2f", it.x, it.y, it.confidence) }
            ?: ("none: " + (handTracker?.lastMiss ?: "tracker not loaded"))
        val box = target?.let { String.format(Locale.ROOT, " box=%.0f,%.0f", it.box.centerX, it.box.centerY) } ?: ""
        val usable = target?.let { if (it.isReliableAt(now)) " usable" else " UNUSABLE" } ?: ""
        Log.i(
            TAG,
            "phase=$phase hand=${handMs}ms tip=$tip dir=${output?.direction ?: "-"} " +
                "target=${target?.label ?: "-"}$box$usable " +
                "lines=${tracker.snapshot.size} ocr=${lastOcrMs}ms/$ocrJobsStarted " +
                "anchor=${lastAnchorOcrMs}ms/$anchorJobsStarted update=$lastOcrUpdate " +
                "focus=$focusState " +
                String.format(Locale.ROOT, "sharp=%.3f/%.3f ", sharpness, referenceSharpness) +
                "steady=$steady",
        )
    }

    // --- Debug ---------------------------------------------------------------------------------

    private fun emitDebugFrame(
        frame: AnalysisFrame,
        raw: FingertipPoint?,
        smoothed: FingertipPoint?,
        output: GuidanceOutput?,
        steady: Boolean,
        handMs: Long,
        now: Long,
    ) {
        val target = tracker.target
        listener.onDebugFrame(
            DebugFrame(
                frame = frame.size,
                phase = phase,
                blocks = tracker.snapshot,
                targetLabel = target?.label,
                targetBox = target?.box,
                targetStable = target?.isReliableAt(now) == true,
                targetEstimated = target?.isEstimated == true,
                targetAnchorAgeMs = target?.takeIf { !it.isUnanchored }?.let { now - it.anchoredAtMs },
                fingertipRaw = raw,
                fingertipSmoothed = smoothed,
                handConfidence = raw?.confidence,
                direction = output?.direction,
                zoneOffsetX = output?.zoneOffsetX,
                zoneOffsetY = output?.zoneOffsetY,
                deviceSteady = steady,
                gyroscopeAvailable = motion.isAvailable,
                handMs = handMs,
                ocrMs = lastOcrMs,
                ocrRunning = ocrInFlight,
                ocrJobsStarted = ocrJobsStarted,
                anchorMs = lastAnchorOcrMs,
                anchorJobsStarted = anchorJobsStarted,
                anchorsRefreshed = anchorsRefreshed,
                anchorsNotSeen = anchorsNotSeen,
                sinceLastOcrMs = lastOcrFinishedMs?.let { now - it },
                screenChangeSuspected = screenChangeSuspected,
                lastOcrUpdate = lastOcrUpdate,
                labels = tracker.corroborated,
                lastMatchOutcome = lastMatchOutcome,
                blurSkips = blurSkips,
                screenSignatureDistance = lastSignatureDistance,
                focusState = focusState,
                sharpness = sharpness,
                referenceSharpness = referenceSharpness,
            ),
        )
    }

    private companion object {
        const val TAG = "LiveAssistPipeline"

        /** Floor between any two OCR runs, whatever the reason. */
        const val OCR_MIN_INTERVAL_MS = 250L



        /**
         * Heartbeat for region re-anchoring while a target is held. Measured at ~43 ms per run
         * on this hardware (R-11), so it can be this brisk without starving hand tracking.
         */
        const val ANCHOR_INTERVAL_MS = 400L

        /** Faster while moving, to keep the anchor inside [TargetTracker.MOVING_ANCHOR_AGE_MS]. */
        const val MOVING_ANCHOR_INTERVAL_MS = 250L

        /**
         * Defined fallback: when region runs have failed to find the label for long enough that
         * the position went stale, read the whole frame once to find out what changed.
         */
        const val STALE_FALLBACK_MS = 2_000L

        /** Heartbeat with no target to anchor: only watching for the kiosk to move on. */
        const val WATCH_INTERVAL_MS = 3_000L

        /** Consistent OCR runs required before a screen counts as understood. */
        const val ACQUISITION_RUNS = 2

        /** Last-resort interval that keeps acquisition moving on an unhelpful camera. */
        const val ACQUIRE_FALLBACK_MS = 2_000L

        /** Back-to-back acquisition runs allowed before falling back to a heartbeat. */
        const val ACQUIRE_MAX_FAST_RUNS = 6
        const val ACQUIRE_BACKOFF_MS = 1_500L

        /** How often an unanswered focus request is repeated while acquiring. */
        const val FOCUS_RETRY_MS = 2_000L

        /** Unreadable runs, with nothing selected, after which the screen is re-acquired. */
        const val MAX_UNREADABLE_RUNS = 4

        /** Side of the square the frame is drawn into for the screen signature. */
        const val SIGNATURE_SOURCE = 64

        /**
         * How often the kiosk screen is looked for. Fast enough to follow a phone being
         * repositioned, far slower than the frame rate, which the hand tracking owns.
         */
        const val SCREEN_DETECT_INTERVAL_MS = 220L
        const val SIGNATURE_INTERVAL_MS = 200L

        /** Mean absolute luminance change, outside the hand, that brings an OCR run forward. */
        const val SCREEN_CHANGE_SIGNATURE_DISTANCE = 0.08f

        /** Radius of the masked-out hand region, as a fraction of the frame diagonal. */
        const val HAND_MASK_RADIUS_OF_DIAGONAL = 0.18f

        /** Side of the full-resolution window used to measure sharpness. */
        const val SHARPNESS_WINDOW = 256

        /** Window centres down the frame, as fractions of its height. */
        val SHARPNESS_WINDOW_CENTRES = floatArrayOf(0.25f, 0.5f, 0.75f)
        const val SHARPNESS_INTERVAL_MS = 400L
        const val ACQUIRE_SHARPNESS_INTERVAL_MS = 100L

        /** Fraction of the best observed sharpness below which the image counts as soft. */
        const val BLUR_RATIO = 0.55f

        /** Below this sharpness ratio an OCR run is deferred rather than wasted. */
        const val SKIP_OCR_SHARPNESS_RATIO = 0.45f
        const val BLUR_SKIP_LIMIT_MS = 2_000L

        /** A window with less edge energy than this carries no focus information. */
        const val MIN_REFERENCE_SHARPNESS = 0.01f
        const val BLUR_CONFIRM_MS = 700L

        const val DEBUG_INTERVAL_MS = 66L
        const val DEBUG_LOG_INTERVAL_MS = 1_000L

        /** How long a spoken request is held open while waiting for a frame worth using. */
        const val SELECT_RETRY_WINDOW_MS = 6_000L
    }
}
