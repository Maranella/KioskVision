package com.maranella.kioskvision.assist

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * A box as fractions of the frame. Stored alongside the pixel box so the target stays
 * comparable if the analysis resolution ever changes mid-session.
 */
data class NormalizedBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

fun TextBox.normalizedIn(frame: FrameSize) = NormalizedBox(
    left = left / frame.width,
    top = top / frame.height,
    right = right / frame.width,
    bottom = bottom / frame.height,
)

/**
 * The selected target as currently known.
 *
 * [box] is in frame pixels (see `camera/FrameGeometry.kt`) and is the only geometry guidance
 * uses. Identity ([id], [screenId], [acquiredAtMs]) exists so a late OCR result can be told
 * apart from one that applies to the current selection.
 */
data class TrackedTarget(
    val label: String,
    val normalizedLabel: String,
    val box: TextBox,
    val isCritical: Boolean,
    /** Consecutive OCR runs that found the target at a consistent position. */
    val confirmations: Int,
    /** OCR runs since the label itself was last seen (it is often hidden under the finger). */
    val missedRuns: Int,
    /** True when [box] was carried forward from other labels' motion, not seen directly. */
    val isEstimated: Boolean,
    /** Stable across the whole selection; changes only when a different target is chosen. */
    val id: Long = 0L,
    /** The screen this target belongs to. A confirmed screen transition retires it. */
    val screenId: Long = 0L,
    val normalizedBox: NormalizedBox = NormalizedBox(0f, 0f, 1f, 1f),
    /** OCR confidence of the block this target was matched to. */
    val confidence: Float = 1f,
    val acquiredAtMs: Long = 0L,
    /**
     * Last time [box] was grounded in an OCR observation, directly or via a consensus shift.
     * Guidance pauses once this is older than [TargetTracker.MAX_ANCHOR_AGE_MS]; the target
     * itself is never dropped for being stale.
     */
    val anchoredAtMs: Long = Long.MAX_VALUE / 2,
) {
    val isStable: Boolean get() = confirmations >= TargetTracker.REQUIRED_CONFIRMATIONS

    /** True when the position has been deliberately invalidated rather than merely aged. */
    val isUnanchored: Boolean get() = anchoredAtMs == TargetTracker.UNANCHORED

    /**
     * The geometry is recent enough to point a finger at.
     *
     * [maxAgeMs] is shorter while the phone is moving, because that is when a measured box goes
     * out of date fastest — not because movement is itself disqualifying.
     */
    fun isFreshAt(nowMs: Long, maxAgeMs: Long = TargetTracker.MAX_ANCHOR_AGE_MS): Boolean =
        !isUnanchored && nowMs - anchoredAtMs <= maxAgeMs

    /** Safe to guide towards: confirmed by OCR *and* not measured too long ago. */
    fun isReliableAt(nowMs: Long, maxAgeMs: Long = TargetTracker.MAX_ANCHOR_AGE_MS): Boolean =
        isStable && isFreshAt(nowMs, maxAgeMs)
}

/** Result of re-anchoring the target from a partial view of the screen. */
enum class AnchorUpdate {
    /** The label was seen; the target's position is fresh again. */
    Anchored,

    /** The label was not in the region. The target is kept; only its anchor ages. */
    NotSeen,

    /** Nothing is selected. */
    NoTarget,
}

/**
 * A label as seen across the last few OCR runs.
 *
 * [timesSeen] is what makes low-confidence readings usable: Tesseract's confidence collapses
 * under motion blur even when the text is exactly right (R-12), so the same string read at the
 * same place in several runs is stronger evidence than any single frame's score.
 */
data class ObservedLabel(
    /** The most recent observation; its text is what gets spoken back to the user. */
    val block: DetectedTextBlock,
    /** Recent OCR runs that read this label at this position. */
    val timesSeen: Int,
    /** Best confidence across those runs. */
    val bestConfidence: Float,
)

sealed interface OcrUpdate {
    /** Nothing that affects guidance happened. */
    data object Unchanged : OcrUpdate

    /**
     * The run returned too little to judge anything by — a blurred frame, glare, or a hand over
     * most of the screen. The previous snapshot and the target are kept untouched.
     */
    data object Unreadable : OcrUpdate

    /** OCR suggests a different screen; guidance pauses until the next run confirms or refutes it. */
    data object ScreenChangeSuspected : OcrUpdate

    /** Two runs in a row agree the screen changed. Any target has been cleared. */
    data class ScreenChanged(val hadTarget: Boolean) : OcrUpdate

    /** A result from a job that started before the current selection; snapshot only. */
    data object Stale : OcrUpdate
}

/**
 * Keeps the target's position grounded in real OCR observations between runs.
 *
 * - When the target label is seen, its box is used directly.
 * - When the label is hidden (typically by the user's own hand), its last box is shifted by
 *   the median motion of the *other* labels, but only when at least two of them agree.
 * - When neither is possible the box simply stays where it was and stops being refreshed.
 *   Guidance pauses on its own once the anchor goes stale ([TrackedTarget.isFreshAt]).
 *
 * **A target is only ever cleared by an explicit decision**: a confirmed screen transition,
 * a new selection, or the user stopping. A blurred frame, a hand over the label, glare or an
 * empty OCR result are not evidence that the button went away, and never discard it.
 *
 * Not thread-safe; owned by the pipeline thread.
 */
class TargetTracker {

    var snapshot: List<DetectedTextBlock> = emptyList()
        private set
    var target: TrackedTarget? = null
        private set

    /** Incremented on every confirmed screen transition; stamped onto each target. */
    var screenId: Long = 0L
        private set

    /**
     * Padding around the target when re-anchoring, as a multiple of its box.
     *
     * Settable so the value can be swept against real recognition rates rather than assumed:
     * [ANCHOR_PAD] was originally validated against a 240x120 *button*, but an OCR box bounds
     * the *text line* and is far shorter, making the resulting crop much tighter than intended.
     */
    var anchorPad: Float = ANCHOR_PAD

    private var suspectedChangeRuns = 0

    /** The last few informative full-frame runs, oldest first, for corroboration. */
    private val recentRuns = ArrayDeque<Pair<List<DetectedTextBlock>, FrameSize>>()
    private var nextTargetId = 1L
    private var lastAppliedJobMs = Long.MIN_VALUE

    /**
     * Locks [block] as the target. [confirmations] carries over the evidence gathered while
     * acquiring the screen, so guidance does not have to wait for another OCR run to start.
     */
    fun setTarget(
        block: DetectedTextBlock,
        isCritical: Boolean,
        frame: FrameSize,
        nowMs: Long,
        confirmations: Int = REQUIRED_CONFIRMATIONS,
    ) {
        target = TrackedTarget(
            label = TextNormalizer.forSpeech(block.text),
            normalizedLabel = block.normalizedText,
            box = block.boundingBox,
            isCritical = isCritical,
            confirmations = confirmations.coerceIn(1, MAX_CONFIRMATIONS),
            missedRuns = 0,
            isEstimated = false,
            id = nextTargetId++,
            screenId = screenId,
            normalizedBox = block.boundingBox.normalizedIn(frame),
            confidence = block.confidence,
            acquiredAtMs = nowMs,
            anchoredAtMs = nowMs,
        )
    }

    fun clearTarget() {
        target = null
    }

    /**
     * Forgets everything learned about the screen: target, snapshot, corroboration history and
     * change suspicion. Used to start an experiment trial from a known state — without it,
     * cached OCR from the previous trial carries over and trials are not independent.
     */
    fun reset() {
        target = null
        snapshot = emptyList()
        recentRuns.clear()
        suspectedChangeRuns = 0
        lastAppliedJobMs = Long.MIN_VALUE
    }

    /**
     * Folds one OCR run into the tracker state.
     *
     * [jobStartedAtMs] is when the analysed frame was captured. A result from a job that
     * started before the current target was selected describes the screen as it was *before*
     * the choice, so it updates the snapshot but is never allowed to move, unconfirm or
     * retire that target.
     */
    fun onOcrResult(
        blocks: List<DetectedTextBlock>,
        frame: FrameSize,
        nowMs: Long = 0L,
        jobStartedAtMs: Long = Long.MAX_VALUE,
        handRegion: TextBox? = null,
    ): OcrUpdate {
        // Kept deliberately low. Every consumer applies its own, stricter rule — screen-change
        // voting needs CHANGE_CONFIDENCE, offering a target needs TargetMatcher's gate, reading
        // aloud needs ScreenReader's — so the intake filter only has to exclude pure noise. A
        // higher floor here would discard the blurred-but-correct readings that corroboration
        // exists to rescue (R-12), before they ever reach it.
        val current = blocks.filter {
            it.confidence >= MIN_RETAINED_CONFIDENCE && TextNormalizer.letterCount(it.text) >= 2
        }
        val previous = snapshot

        // A run that read almost nothing is evidence of a bad frame, not of a changed screen.
        if (previous.isNotEmpty() && current.size < MIN_INFORMATIVE_LINES) return OcrUpdate.Unreadable

        // Out-of-order or pre-selection results: keep the newest view of the screen, but never
        // let an older frame reinterpret a newer selection.
        val applied = target?.let { jobStartedAtMs >= it.acquiredAtMs } ?: (jobStartedAtMs >= lastAppliedJobMs)
        if (!applied) {
            if (previous.isEmpty()) snapshot = current
            return OcrUpdate.Stale
        }
        lastAppliedJobMs = jobStartedAtMs

        rememberRun(current, frame)

        if (previous.isEmpty()) {
            snapshot = current
            return updateTarget(current, emptyList(), frame, nowMs)
        }

        val pairs = pairLines(previous, current)

        // Whether the *screen* changed is judged only on lines OCR was confident about, and
        // only on lines the user's hand is not sitting on. Marginal reads flicker between runs
        // on any busy image, and a hand held close to the lens can hide most of the screen —
        // letting either vote turns ordinary occlusion into a stream of phantom transitions.
        val minHeight = CHANGE_LINE_HEIGHT_OF_DIAGONAL * frame.diagonal
        fun votes(block: DetectedTextBlock) =
            block.confidence >= CHANGE_CONFIDENCE &&
                // Small text is chrome, watermarks and clutter around the kiosk, and OCR reads
                // it differently on every frame. A kiosk transition replaces the *large* text.
                block.boundingBox.height >= minHeight &&
                (handRegion == null || !block.boundingBox.intersects(handRegion))

        val confidentBefore = previous.filter(::votes)
        val confidentPairs = pairs.count { (old, _) -> votes(old) }
        val retained = if (confidentBefore.isEmpty()) 1f else confidentPairs.toFloat() / confidentBefore.size
        val newLines = current.count { block -> votes(block) && pairs.none { it.second === block } }
        val lostLines = confidentBefore.size - confidentPairs
        // With too little of the previous screen still exposed, this run simply cannot say
        // whether the kiosk moved on, so it is not allowed to claim that it did.
        val judgeable = confidentBefore.size >= MIN_INFORMATIVE_LINES
        // A real transition replaces most of the screen at once. Requiring several lines to
        // have both gone and arrived keeps ordinary recognition churn — a line or two flipping
        // between runs on a live camera — from being read as the kiosk moving on.
        val looksDifferent = judgeable && retained < 0.5f && (
            (lostLines >= MIN_CHANGED_LINES && newLines >= MIN_CHANGED_LINES) ||
                (confidentPairs == 0 && newLines >= MIN_INFORMATIVE_LINES)
            )

        // Seeing the chosen label right now is direct evidence this is still its screen, which
        // outranks any amount of churn among the lines around it.
        val targetStillVisible = target?.let { held ->
            current.any { TextNormalizer.lineSimilarity(it.normalizedText, held.normalizedLabel) >= SAME_LINE_SIMILARITY }
        } ?: false

        if (looksDifferent && !targetStillVisible) {
            suspectedChangeRuns++
            if (suspectedChangeRuns >= CHANGE_CONFIRMATION_RUNS) {
                suspectedChangeRuns = 0
                val hadTarget = target != null
                target = null
                screenId++
                // The previous screen's readings must not corroborate anything on this one.
                recentRuns.clear()
                rememberRun(current, frame)
                snapshot = current
                return OcrUpdate.ScreenChanged(hadTarget)
            }
            // Pause guidance while the suspicion is open, without discarding the target.
            target = target?.copy(anchoredAtMs = UNANCHORED)
            return OcrUpdate.ScreenChangeSuspected
        }
        suspectedChangeRuns = 0
        snapshot = current
        return updateTarget(current, pairs, frame, nowMs)
    }


    /**
     * Re-anchors the target from OCR over a **region** around its last known position.
     *
     * This is the cheap path used while guiding: on a cluttered 576×1280 frame a full run cost
     * 568 ms against 43 ms for a region of 13% of the frame (measured, see R-11), which is what
     * makes it affordable to keep the position fresh without full OCR running continuously.
     *
     * A region sees only a corner of the screen, so it deliberately does **not** touch
     * [snapshot] and can never report a screen change — it has no basis to judge one. Deciding
     * the kiosk moved on stays with [onOcrResult] over the whole frame.
     */
    fun onAnchorResult(blocks: List<DetectedTextBlock>, frame: FrameSize, nowMs: Long): AnchorUpdate {
        val target = target ?: return AnchorUpdate.NoTarget
        // Same reasoning as the intake filter: under blur the label is read correctly at a
        // poor score, and what re-anchoring needs from it is its position. Identity is guarded
        // by the text similarity below, and a position far from the last one resets confidence.
        val usable = blocks.filter {
            it.confidence >= MIN_RETAINED_CONFIDENCE && TextNormalizer.letterCount(it.text) >= 2
        }
        val tolerance = max(0.6f * target.box.height, POSITION_TOLERANCE_OF_DIAGONAL * frame.diagonal)
        val seen = usable
            .filter { TextNormalizer.lineSimilarity(it.normalizedText, target.normalizedLabel) >= SAME_LINE_SIMILARITY }
            .minByOrNull { it.boundingBox.centerDistanceTo(target.box) }

        if (seen == null) {
            // Almost always the user's own hand over the label. Keep everything; the anchor
            // simply stops being renewed, and guidance pauses by itself if that persists.
            this.target = target.copy(missedRuns = target.missedRuns + 1)
            return AnchorUpdate.NotSeen
        }

        val consistent = seen.boundingBox.centerDistanceTo(target.box) <= tolerance
        this.target = target.copy(
            box = seen.boundingBox,
            normalizedBox = seen.boundingBox.normalizedIn(frame),
            // A label found far from where it was has to earn confidence again before guidance
            // resumes, exactly as in the full-frame path.
            confirmations = if (consistent) (target.confirmations + 1).coerceAtMost(MAX_CONFIRMATIONS) else 1,
            missedRuns = 0,
            isEstimated = false,
            anchoredAtMs = nowMs,
        )
        return AnchorUpdate.Anchored
    }

    /**
     * The region to recognize when re-anchoring: the target's box padded by [ANCHOR_PAD] of its
     * own size, so small drifts and a neighbouring label stay in view, clamped to the frame.
     */
    fun anchorRegion(frame: FrameSize): IntArray? {
        val box = target?.box ?: return null
        val padX = box.width * anchorPad
        val padY = box.height * anchorPad
        val left = (box.left - padX).toInt().coerceIn(0, frame.width)
        val top = (box.top - padY).toInt().coerceIn(0, frame.height)
        val right = (box.right + padX).toInt().coerceIn(0, frame.width)
        val bottom = (box.bottom + padY).toInt().coerceIn(0, frame.height)
        if (right - left < MIN_REGION_PX || bottom - top < MIN_REGION_PX) return null
        return intArrayOf(left, top, right, bottom)
    }


    private fun rememberRun(blocks: List<DetectedTextBlock>, frame: FrameSize) {
        recentRuns.addLast(blocks to frame)
        while (recentRuns.size > CORROBORATION_RUNS) recentRuns.removeFirst()
    }

    /**
     * The latest run's labels, each annotated with how many recent runs agreed on it.
     *
     * Matching a spoken request works from this rather than from one frame, so a label that
     * blur scored poorly can still be acted on when earlier runs read it the same way.
     */
    val corroborated: List<ObservedLabel>
        get() {
            val (latest, frame) = recentRuns.lastOrNull() ?: return emptyList()
            val older = recentRuns.dropLast(1)
            val tolerance = CORROBORATION_DISTANCE_OF_DIAGONAL * frame.diagonal
            return latest.map { block ->
                var seen = 1
                var best = block.confidence
                for ((run, _) in older) {
                    val agreeing = run.firstOrNull {
                        // Stricter than the similarity used for tracking: corroboration is a
                        // licence to trust a low score, so near-identical text is required.
                        TextNormalizer.lineSimilarity(it.normalizedText, block.normalizedText) >= CORROBORATION_SIMILARITY &&
                            it.boundingBox.centerDistanceTo(block.boundingBox) <= tolerance
                    }
                    if (agreeing != null) {
                        seen++
                        best = max(best, agreeing.confidence)
                    }
                }
                ObservedLabel(block, timesSeen = seen, bestConfidence = best)
            }
        }

    private fun updateTarget(
        current: List<DetectedTextBlock>,
        pairs: List<Pair<DetectedTextBlock, DetectedTextBlock>>,
        frame: FrameSize,
        nowMs: Long,
    ): OcrUpdate {
        val target = target ?: return OcrUpdate.Unchanged
        val tolerance = max(0.6f * target.box.height, POSITION_TOLERANCE_OF_DIAGONAL * frame.diagonal)
        val shift = consensusShift(pairs, tolerance)
        val predicted = if (shift != null) target.box.offset(shift.first, shift.second) else target.box
        // A large consensus shift means the camera moved, not that the button did; either way
        // the new position has to earn its confirmations again.
        val cameraMoved = shift != null && hypot(shift.first, shift.second) > tolerance

        val seen = current
            .filter { TextNormalizer.lineSimilarity(it.normalizedText, target.normalizedLabel) >= SAME_LINE_SIMILARITY }
            .minByOrNull { it.boundingBox.centerDistanceTo(predicted) }

        this.target = when {
            seen != null -> {
                val consistent = seen.boundingBox.centerDistanceTo(predicted) <= tolerance
                target.copy(
                    box = seen.boundingBox,
                    normalizedBox = seen.boundingBox.normalizedIn(frame),
                    confirmations = if (consistent && !cameraMoved) {
                        (target.confirmations + 1).coerceAtMost(MAX_CONFIRMATIONS)
                    } else {
                        1
                    },
                    missedRuns = 0,
                    isEstimated = false,
                    anchoredAtMs = nowMs,
                )
            }
            shift != null -> target.copy(
                box = predicted,
                normalizedBox = predicted.normalizedIn(frame),
                confirmations = if (cameraMoved) 1 else target.confirmations,
                missedRuns = target.missedRuns + 1,
                isEstimated = true,
                anchoredAtMs = nowMs,
            )
            // Label hidden and nothing else moved consistently enough to carry it. This is the
            // normal case while the user's hand is over the button they are reaching for, so
            // the box is kept exactly where it was; only its freshness stops being renewed.
            else -> target.copy(missedRuns = target.missedRuns + 1)
        }
        return OcrUpdate.Unchanged
    }

    /**
     * Median motion of labels seen in both runs, or null unless at least two labels agree
     * (every pair within [tolerance] of the median).
     */
    private fun consensusShift(
        pairs: List<Pair<DetectedTextBlock, DetectedTextBlock>>,
        tolerance: Float,
    ): Pair<Float, Float>? {
        if (pairs.size < 2) return null
        val dxs = pairs.map { (old, new) -> new.boundingBox.centerX - old.boundingBox.centerX }.sorted()
        val dys = pairs.map { (old, new) -> new.boundingBox.centerY - old.boundingBox.centerY }.sorted()
        val dx = dxs[dxs.size / 2]
        val dy = dys[dys.size / 2]
        val agreeing = pairs.count { (old, new) ->
            abs(new.boundingBox.centerX - old.boundingBox.centerX - dx) <= tolerance &&
                abs(new.boundingBox.centerY - old.boundingBox.centerY - dy) <= tolerance
        }
        return if (agreeing >= 2 && agreeing * 2 > pairs.size) dx to dy else null
    }

    /** Greedily pairs each previous line with its most similar unused current line. */
    private fun pairLines(
        previous: List<DetectedTextBlock>,
        current: List<DetectedTextBlock>,
    ): List<Pair<DetectedTextBlock, DetectedTextBlock>> {
        val unused = current.toMutableList()
        return previous.mapNotNull { old ->
            val best = unused.maxByOrNull { TextNormalizer.lineSimilarity(old.normalizedText, it.normalizedText) }
            if (best != null && TextNormalizer.lineSimilarity(old.normalizedText, best.normalizedText) >= SAME_LINE_SIMILARITY) {
                unused.remove(best)
                old to best
            } else {
                null
            }
        }
    }

    companion object {
        const val REQUIRED_CONFIRMATIONS = 2
        const val MAX_CONFIRMATIONS = 5

        /**
         * How long a target's measured position stays usable without a fresh OCR anchor.
         * Past this, guidance pauses (`TARGET_UNCERTAIN`) rather than pointing at a position
         * that may have drifted. The target itself survives.
         */
        const val MAX_ANCHOR_AGE_MS = 4_000L

        /**
         * How long an anchor is trusted while the phone is moving. Region re-anchoring costs
         * a few milliseconds and runs several times a second, so this stays achievable in
         * normal handheld use instead of demanding that the user hold still.
         */
        const val MOVING_ANCHOR_AGE_MS = 1_200L

        /** Below this many readable lines, a run says nothing about the screen. */
        const val MIN_INFORMATIVE_LINES = 2

        /** Marker for a position deliberately invalidated (device motion, suspected change). */
        const val UNANCHORED = Long.MIN_VALUE / 4

        /** Only lines at least this confident get a vote on whether the screen changed. */
        const val CHANGE_CONFIDENCE = 0.7f

        /** And only lines at least this tall, as a fraction of the frame diagonal. */
        const val CHANGE_LINE_HEIGHT_OF_DIAGONAL = 0.012f

        /** Lines that must both disappear and appear before a transition is suspected. */
        const val MIN_CHANGED_LINES = 3

        /** How many recent full-frame runs are kept for corroborating a low-confidence label. */
        const val CORROBORATION_RUNS = 3

        /** Text similarity required before two runs count as having read the same label. */
        const val CORROBORATION_SIMILARITY = 0.9f

        /** And how far apart those readings may sit, as a fraction of the frame diagonal. */
        private const val CORROBORATION_DISTANCE_OF_DIAGONAL = 0.15f

        /** Re-anchor region padding, as a fraction of the target box. Measured sweet spot. */
        const val ANCHOR_PAD = 0.5f
        private const val MIN_REGION_PX = 24

        private const val CHANGE_CONFIRMATION_RUNS = 2
        private const val SAME_LINE_SIMILARITY = 0.75f
        /** Floor for keeping an OCR line at all; below this it is treated as noise. */
        const val MIN_RETAINED_CONFIDENCE = 0.15f

        /** Positional tolerance floor, as a fraction of the frame diagonal (resolution-independent). */
        private const val POSITION_TOLERANCE_OF_DIAGONAL = 0.015f
    }
}
