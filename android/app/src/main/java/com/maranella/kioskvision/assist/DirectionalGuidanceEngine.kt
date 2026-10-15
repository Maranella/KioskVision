package com.maranella.kioskvision.assist

import kotlin.math.abs
import kotlin.math.max

enum class GuidanceDirection {
    LEFT,
    RIGHT,
    UP,
    DOWN,
    NEAR_TARGET,
    ON_TARGET,
    HAND_NOT_DETECTED,
    TARGET_UNCERTAIN,
}

/** Fingertip in frame pixels, after smoothing. */
data class FingertipPoint(val x: Float, val y: Float, val confidence: Float)

data class GuidanceInput(
    val timestampMs: Long,
    val target: TrackedTarget?,
    val fingertip: FingertipPoint?,
    val frame: FrameSize,
    /** False while the phone is rotating noticeably; shortens how long an anchor is trusted. */
    val deviceSteady: Boolean,
)

/**
 * Why no direction can be given. [GuidanceDirection.TARGET_UNCERTAIN] is one direction but
 * several distinct situations, and they call for different things from the user — so they are
 * reported apart rather than collapsed into one message about the screen.
 */
enum class GuidanceUnavailable {
    /** Nothing is selected. Not a failure; there is simply nothing to guide to. */
    NoTarget,

    /** A target is selected but OCR has not confirmed it yet. */
    Unconfirmed,

    /** The target's measured position has not been refreshed recently enough to aim at. */
    GeometryStale,
}

data class GuidanceOutput(
    val direction: GuidanceDirection,
    /** Fingertip minus target centre, in units of the on-target zone half-size; null when not computed. */
    val zoneOffsetX: Float? = null,
    val zoneOffsetY: Float? = null,
    /** Set whenever [direction] is [GuidanceDirection.TARGET_UNCERTAIN]. */
    val unavailable: GuidanceUnavailable? = null,
)

/**
 * Converts target and fingertip geometry into a semantic direction. Pure and deterministic;
 * not thread-safe (one instance per pipeline).
 *
 * All positions are frame pixels. For this prototype the frame is treated as the kiosk
 * screen plane, which only holds when the screen faces the camera roughly head-on (see
 * docs/SPIKES_AND_DECISIONS.md, R-07: a finger hovering above the glass is displaced by
 * about `height · tan(viewing angle)`).
 *
 * ### Thresholds (all relative to the target or frame, never fixed pixel counts)
 * - **On-target zone**: a rectangle around the label centre with half-width
 *   `max(0.30 × label width, 1.5 % of frame diagonal)` and half-height
 *   `max(0.60 × label height, 1.5 % of frame diagonal)`. The label box is usually smaller than
 *   its button, so the centre of the label is the safest point to aim at.
 * - **Exit hysteresis**: once inside, the finger must leave a 1.35× larger zone to count as
 *   outside again (dead zone against oscillation at the edge).
 * - **Dwell**: the fingertip must stay inside the zone for [ON_TARGET_DWELL_MS] before
 *   [GuidanceDirection.ON_TARGET]. This is proximity in the image, **not** a confirmed touch.
 * - **Near zone**: within 3× the on-target zone, guidance first says "slowly, you are close";
 *   if the finger stays there for [NEAR_STALL_MS] without reaching the target, it gives the
 *   direction again.
 * - **Axis choice**: the larger normalized offset wins, but the current axis is kept unless
 *   the other one is [AXIS_SWITCH_RATIO]× larger, so diagonal approaches do not flip-flop.
 * - **Confidence**: below [MIN_FINGERTIP_CONFIDENCE], the hand is treated as not detected.
 */
class DirectionalGuidanceEngine {

    private var inside = false
    private var insideSinceMs: Long? = null
    private var nearSinceMs: Long? = null
    private var horizontalAxis: Boolean? = null

    fun reset() {
        inside = false
        insideSinceMs = null
        nearSinceMs = null
        horizontalAxis = null
    }

    fun update(input: GuidanceInput): GuidanceOutput {
        val target = input.target
        // Movement does not stop guidance. A phone held up to a kiosk is never truly still,
        // and demanding stillness makes the app unusable for the person it is for. What matters
        // is whether the target's measured position is still current, so moving only requires a
        // more recent OCR anchor — which region re-anchoring refreshes several times a second.
        val maxAnchorAge = if (input.deviceSteady) {
            TargetTracker.MAX_ANCHOR_AGE_MS
        } else {
            TargetTracker.MOVING_ANCHOR_AGE_MS
        }
        if (target == null) {
            reset()
            return GuidanceOutput(GuidanceDirection.TARGET_UNCERTAIN, unavailable = GuidanceUnavailable.NoTarget)
        }
        val unavailable = when {
            !target.isStable -> GuidanceUnavailable.Unconfirmed
            !target.isFreshAt(input.timestampMs, maxAnchorAge) -> GuidanceUnavailable.GeometryStale
            else -> null
        }
        if (unavailable != null) {
            reset()
            return GuidanceOutput(GuidanceDirection.TARGET_UNCERTAIN, unavailable = unavailable)
        }
        val tip = input.fingertip
        if (tip == null || tip.confidence < MIN_FINGERTIP_CONFIDENCE) {
            reset()
            return GuidanceOutput(GuidanceDirection.HAND_NOT_DETECTED)
        }

        val diagonalFloor = ZONE_FLOOR_OF_DIAGONAL * input.frame.diagonal
        val halfWidth = max(ZONE_WIDTH_FRACTION * target.box.width, diagonalFloor)
        val halfHeight = max(ZONE_HEIGHT_FRACTION * target.box.height, diagonalFloor)
        val dx = (tip.x - target.box.centerX) / halfWidth
        val dy = (tip.y - target.box.centerY) / halfHeight

        val limit = if (inside) EXIT_HYSTERESIS else 1f
        inside = abs(dx) <= limit && abs(dy) <= limit
        if (inside) {
            val since = insideSinceMs ?: input.timestampMs.also { insideSinceMs = it }
            val direction = if (input.timestampMs - since >= ON_TARGET_DWELL_MS) {
                GuidanceDirection.ON_TARGET
            } else {
                GuidanceDirection.NEAR_TARGET
            }
            return GuidanceOutput(direction, dx, dy)
        }
        insideSinceMs = null

        if (max(abs(dx), abs(dy)) <= NEAR_ZONE_MULTIPLIER) {
            val since = nearSinceMs ?: input.timestampMs.also { nearSinceMs = it }
            if (input.timestampMs - since < NEAR_STALL_MS) {
                return GuidanceOutput(GuidanceDirection.NEAR_TARGET, dx, dy)
            }
        } else {
            nearSinceMs = null
        }
        return GuidanceOutput(directionFor(dx, dy), dx, dy)
    }

    private fun directionFor(dx: Float, dy: Float): GuidanceDirection {
        val useHorizontal = when (horizontalAxis) {
            true -> abs(dy) <= AXIS_SWITCH_RATIO * abs(dx)
            false -> abs(dx) > AXIS_SWITCH_RATIO * abs(dy)
            null -> abs(dx) >= abs(dy)
        }
        horizontalAxis = useHorizontal
        // Image x grows to the right and y grows downward; the rear camera is not mirrored.
        return if (useHorizontal) {
            if (dx > 0) GuidanceDirection.LEFT else GuidanceDirection.RIGHT
        } else {
            if (dy > 0) GuidanceDirection.UP else GuidanceDirection.DOWN
        }
    }

    companion object {
        const val MIN_FINGERTIP_CONFIDENCE = 0.6f
        const val ON_TARGET_DWELL_MS = 600L
        const val NEAR_STALL_MS = 1500L
        const val ZONE_WIDTH_FRACTION = 0.30f
        const val ZONE_HEIGHT_FRACTION = 0.60f
        const val ZONE_FLOOR_OF_DIAGONAL = 0.015f
        const val EXIT_HYSTERESIS = 1.35f
        const val NEAR_ZONE_MULTIPLIER = 3f
        const val AXIS_SWITCH_RATIO = 1.3f
    }
}
