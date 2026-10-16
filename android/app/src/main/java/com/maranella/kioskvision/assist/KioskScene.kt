package com.maranella.kioskvision.assist

import com.maranella.kioskvision.camera.ScreenSpace
import com.maranella.kioskvision.camera.TrackedScreen

/**
 * What an element on the kiosk screen appears to be.
 *
 * Derived from the label text through the one concept dictionary in [TargetMatcher]. It is a
 * guess about wording, not knowledge of what the kiosk will do: a button reading „გადახდა" is
 * [Payment] because of its text, and nothing here has any idea what pressing it performs.
 */
enum class ElementRole {
    Payment,
    Confirm,
    Cancel,
    Back,
    Balance,
    Help,
    OtherService,
    Unknown;

    companion object {
        fun of(text: String): ElementRole {
            val concepts = TargetMatcher.conceptsOf(text)
            return when {
                "payment" in concepts -> Payment
                "confirm" in concepts -> Confirm
                "cancel" in concepts -> Cancel
                "back" in concepts -> Back
                "balance" in concepts -> Balance
                "help" in concepts -> Help
                "other" in concepts -> OtherService
                else -> Unknown
            }
        }
    }
}

/**
 * One candidate control on the kiosk screen.
 *
 * [screenBounds] is in **canonical screen space** (the unit square over the detected screen),
 * so it survives the phone being moved. [boundsAreScreenRelative] records whether that is
 * genuinely true: when the screen could not be detected, the bounds fall back to normalised
 * frame coordinates, which are *not* comparable across viewpoints. Guidance must check this
 * before giving a precise direction.
 *
 * The box is around the recognised *text*, which is smaller than the touchable button
 * containing it. Nothing here knows where the button's edges are.
 */
data class SceneElement(
    val label: String,
    val screenBounds: TextBox,
    val boundsAreScreenRelative: Boolean,
    val confidence: Float,
    val timesSeen: Int,
    val role: ElementRole,
) {
    val isCritical: Boolean
        get() = TargetMatcher.conceptsOf(label).any { TargetMatcher.isCritical(it) }

    /** Whether this element is solid enough to offer as a target; see [TargetMatcher]. */
    val isTrustworthy: Boolean
        get() = confidence >= TargetMatcher.MIN_TARGET_CONFIDENCE ||
            (timesSeen >= TargetMatcher.MIN_CORROBORATING_RUNS &&
                confidence >= TargetMatcher.MIN_CORROBORATED_CONFIDENCE)
}

/** How much of the screen could actually be read, as distinct from whether it was found. */
enum class SceneReadability {
    /** No screen is being tracked, so there is nothing to read. */
    NoScreen,

    /** A screen is tracked but OCR returned nothing usable from it. */
    Unreadable,

    /** Some labels were read, but few enough that a missing button may simply be unread. */
    Partial,

    /** Enough labels to answer "what is on this screen" honestly. */
    Readable,
}

/**
 * The kiosk screen as currently observed: a structured snapshot assembled from the camera,
 * the screen tracker and OCR, and the single thing both the guidance logic and any
 * explanation to the user are allowed to be built from.
 *
 * It is a snapshot, not a live view. [observedAtMs] and [geometryAgeMs] are part of the data
 * precisely because anything derived from it has to be able to ask how old it is: a scene from
 * two seconds ago describes a screen the user may have already left.
 */
data class KioskScene(
    val elements: List<SceneElement>,
    val readability: SceneReadability,
    /** Confidence in the screen quadrilateral itself, not in the text on it. */
    val screenConfidence: Float,
    val screenStable: Boolean,
    /** Milliseconds since the screen geometry was last confirmed by a detection. */
    val geometryAgeMs: Long,
    val observedAtMs: Long,
) {
    val hasScreen: Boolean get() = readability != SceneReadability.NoScreen

    /** True when element bounds are genuinely screen-relative and safe to guide from. */
    val hasUsableGeometry: Boolean
        get() = hasScreen && elements.all { it.boundsAreScreenRelative }

    fun age(nowMs: Long): Long = nowMs - observedAtMs

    fun isStale(nowMs: Long, limitMs: Long = STALE_LIMIT_MS): Boolean = age(nowMs) > limitMs

    /**
     * The element most likely to be the screen's title: the topmost trustworthy label that is
     * not itself a recognisable control. A heuristic, offered as a guess and never as fact.
     */
    val likelyTitle: String?
        get() = elements
            .filter { it.isTrustworthy && it.role == ElementRole.Unknown }
            .minByOrNull { it.screenBounds.top }
            ?.label

    companion object {
        /** Beyond this a scene describes the past rather than the present. */
        const val STALE_LIMIT_MS = 2_000L

        /** Trustworthy labels below which "I may simply not have read it" stays true. */
        const val PARTIAL_BELOW = 3

        fun empty(nowMs: Long) = KioskScene(
            elements = emptyList(),
            readability = SceneReadability.NoScreen,
            screenConfidence = 0f,
            screenStable = false,
            geometryAgeMs = 0L,
            observedAtMs = nowMs,
        )

        /**
         * Builds a scene from what the pipeline has observed.
         *
         * [screen] is null when the screen could not be detected. The scene is still built in
         * that case — the labels were genuinely read and the user can still be told what the
         * text says — but every element is flagged as not screen-relative, which stops
         * precise directional guidance downstream rather than letting it run on coordinates
         * that only happen to be right when the phone is square-on.
         */
        fun from(
            labels: List<ObservedLabel>,
            screen: TrackedScreen?,
            frameWidth: Int,
            frameHeight: Int,
            nowMs: Long,
        ): KioskScene {
            val elements = labels.mapNotNull { label ->
                val screenRelative = screen?.let { ScreenSpace.toScreen(it, label.block.boundingBox) }
                val bounds = screenRelative ?: normalisedInFrame(label.block.boundingBox, frameWidth, frameHeight)
                    ?: return@mapNotNull null
                SceneElement(
                    label = label.block.text.trim(),
                    screenBounds = bounds,
                    boundsAreScreenRelative = screenRelative != null,
                    confidence = label.bestConfidence,
                    timesSeen = label.timesSeen,
                    role = ElementRole.of(label.block.text),
                )
            }

            val trustworthy = elements.count { it.isTrustworthy }
            val readability = when {
                screen == null -> SceneReadability.NoScreen
                trustworthy == 0 -> SceneReadability.Unreadable
                trustworthy < PARTIAL_BELOW -> SceneReadability.Partial
                else -> SceneReadability.Readable
            }

            return KioskScene(
                elements = elements,
                readability = readability,
                screenConfidence = screen?.confidence ?: 0f,
                screenStable = screen?.stable ?: false,
                geometryAgeMs = screen?.ageMs ?: 0L,
                observedAtMs = nowMs,
            )
        }

        private fun normalisedInFrame(box: TextBox, width: Int, height: Int): TextBox? {
            if (width <= 0 || height <= 0) return null
            return TextBox(
                left = box.left / width,
                top = box.top / height,
                right = box.right / width,
                bottom = box.bottom / height,
            )
        }
    }
}
