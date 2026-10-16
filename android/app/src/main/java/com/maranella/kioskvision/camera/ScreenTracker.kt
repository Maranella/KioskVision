package com.maranella.kioskvision.camera

import kotlin.math.hypot

/**
 * The kiosk screen as currently understood, in **frame pixels**, with the transform into
 * canonical screen space.
 *
 * [ageMs] is how long ago the geometry was last confirmed by a detection. Guidance must read
 * it: a quadrilateral from two seconds ago is a memory, not an observation, and directions
 * computed from it point where the screen used to be.
 */
data class TrackedScreen(
    val quad: Quad,
    val confidence: Float,
    val stable: Boolean,
    val ageMs: Long,
) {
    /** Frame pixels to the unit square. Null when the quadrilateral is degenerate. */
    val toScreenSpace: Homography? by lazy { Homography.quadToUnitSquare(quad) }
}

/** Why screen geometry is unusable, kept distinct so the app can say something specific. */
enum class ScreenLoss {
    /** No screen has been found yet in this session. */
    NeverDetected,

    /** It was being tracked and detection stopped succeeding. */
    TrackingLost,

    /** Still detected, but the last confirmation is too old to guide from. */
    Stale,

    /** Detected, but the shape is not convincingly a screen. */
    LowConfidence,
}

/**
 * Holds screen geometry across frames: smooths it, notices when it jumps, and decides when it
 * has gone stale or been lost.
 *
 * Detection runs far less often than the camera delivers frames — finding the screen is
 * expensive and the screen does not move between two consecutive frames of a handheld phone.
 * Everything here therefore works in wall-clock time rather than frame counts, so the
 * behaviour does not change with frame rate or with a dropped frame.
 */
class ScreenTracker(
    private val minConfidence: Float = MIN_CONFIDENCE,
    private val staleAfterMs: Long = STALE_AFTER_MS,
    private val lostAfterMs: Long = LOST_AFTER_MS,
) {

    private var smoothed: Quad? = null
    private var confidence: Float = 0f
    private var lastAcceptedAtMs: Long = 0L
    private var consecutiveAccepted: Int = 0
    private var everDetected: Boolean = false

    /**
     * Feeds one detection attempt. A null [candidate] means the detector looked and found
     * nothing, which is information in its own right and not the same as not having looked.
     */
    fun onDetection(candidate: ScreenCandidate?, nowMs: Long) {
        if (candidate == null || candidate.confidence < minConfidence) {
            consecutiveAccepted = 0
            return
        }

        everDetected = true
        val previous = smoothed
        if (previous == null || jumped(previous, candidate.quad)) {
            // A large jump is a different screen, the phone swinging, or a misdetection.
            // Smoothing across it would invent a quadrilateral that was never observed, so
            // the track restarts instead and reports itself unstable until it settles.
            smoothed = candidate.quad
            consecutiveAccepted = 1
        } else {
            smoothed = blend(previous, candidate.quad, SMOOTHING)
            consecutiveAccepted++
        }
        confidence = candidate.confidence
        lastAcceptedAtMs = nowMs
    }

    /**
     * The current geometry, or null when it must not be used. Callers that need to explain
     * themselves should ask [loss] instead of inventing a reason.
     */
    fun geometry(nowMs: Long): TrackedScreen? {
        val quad = smoothed ?: return null
        val age = nowMs - lastAcceptedAtMs
        if (age > lostAfterMs || age > staleAfterMs) return null
        return TrackedScreen(
            quad = quad,
            confidence = confidence,
            stable = consecutiveAccepted >= STABLE_RUN,
            ageMs = age,
        )
    }

    /** Why [geometry] is null, for a spoken explanation. Returns null when geometry is fine. */
    fun loss(nowMs: Long): ScreenLoss? {
        if (smoothed == null) {
            return if (everDetected) ScreenLoss.TrackingLost else ScreenLoss.NeverDetected
        }
        val age = nowMs - lastAcceptedAtMs
        return when {
            age > lostAfterMs -> ScreenLoss.TrackingLost
            age > staleAfterMs -> ScreenLoss.Stale
            confidence < minConfidence -> ScreenLoss.LowConfidence
            else -> null
        }
    }

    fun reset() {
        smoothed = null
        confidence = 0f
        lastAcceptedAtMs = 0L
        consecutiveAccepted = 0
        everDetected = false
    }

    /** True once the track has been confirmed enough times to be worth guiding from. */
    fun isStable(): Boolean = consecutiveAccepted >= STABLE_RUN

    private fun jumped(previous: Quad, next: Quad): Boolean {
        val scale = previous.corners.let { c ->
            hypot(c[2].x - c[0].x, c[2].y - c[0].y).coerceAtLeast(1f)
        }
        val shift = previous.corners.zip(next.corners)
            .maxOf { (a, b) -> hypot(b.x - a.x, b.y - a.y) }
        return shift > scale * JUMP_FRACTION
    }

    private fun blend(previous: Quad, next: Quad, alpha: Float): Quad {
        fun mix(a: Point, b: Point) = Point(
            a.x + (b.x - a.x) * alpha,
            a.y + (b.y - a.y) * alpha,
        )
        return Quad(
            mix(previous.topLeft, next.topLeft),
            mix(previous.topRight, next.topRight),
            mix(previous.bottomRight, next.bottomRight),
            mix(previous.bottomLeft, next.bottomLeft),
        )
    }

    private companion object {
        const val MIN_CONFIDENCE = 0.45f

        /**
         * Weight of a new detection. Low enough that one poor fit cannot drag the corners,
         * high enough that the geometry follows a genuinely moving phone.
         */
        const val SMOOTHING = 0.35f

        /** A corner moving further than this fraction of the screen diagonal is a new track. */
        const val JUMP_FRACTION = 0.25f

        /** Detections in a row before the geometry counts as settled. */
        const val STABLE_RUN = 3

        /** Beyond this, geometry is a memory; guidance from it would point at the past. */
        const val STALE_AFTER_MS = 900L
        const val LOST_AFTER_MS = 2_500L
    }
}
