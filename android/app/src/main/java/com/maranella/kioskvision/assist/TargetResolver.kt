package com.maranella.kioskvision.assist

/**
 * What the user wants, as a structured value.
 *
 * This is the only shape in which an intent may reach the resolver. A language model may
 * produce one of these — it is the part of the system a model is genuinely good at — but it
 * carries no coordinates and names no element. Where the thing actually is on screen is
 * decided here, from what the camera observed.
 */
data class UserIntent(
    /** What the user called it, in their own words. */
    val query: String,
    /** A role the interpreter believes was meant, used only to break ties. */
    val expectedRole: ElementRole? = null,
)

/** A target that has been checked against the scene and may be guided to. */
data class ResolvedTarget(
    val label: String,
    /** Canonical screen space. Guaranteed screen-relative: see [TargetResolver]. */
    val screenBounds: TextBox,
    val confidence: Float,
    val role: ElementRole,
    val isCritical: Boolean,
    /** The scene this was resolved against, so staleness can be re-checked later. */
    val sceneObservedAtMs: Long,
)

/** Why a request could not be turned into a target. Each reason is said differently. */
enum class ResolutionFailure {
    /** No screen is being tracked at all. */
    NoScreen,

    /** A screen is tracked but nothing legible was read from it. */
    SceneUnreadable,

    /** The scene is too old to act on; the phone or the screen has probably moved. */
    SceneStale,

    /**
     * The screen was found but could not be rectified, so positions on it are not
     * comparable. Reading the screen aloud is fine; pointing at it is not.
     */
    GeometryUnusable,

    /** Nothing on the screen resembles the request. */
    NoSimilarLabel,

    /** Something resembling it was read, but not reliably enough to act on. */
    UnconfirmedLabel,
}

sealed interface TargetResolution {
    data class Resolved(val target: ResolvedTarget) : TargetResolution

    /**
     * Several candidates are equally plausible. The user is asked which, rather than the
     * most-recently-read one being picked and presented as certain.
     */
    data class Ambiguous(val options: List<SceneElement>) : TargetResolution

    data class Unresolved(val reason: ResolutionFailure) : TargetResolution
}

/**
 * Turns an intent into a verified on-screen target, or refuses with a reason.
 *
 * This is the boundary the safety invariants rest on. Everything upstream — including a
 * language model — may only say *what the user wants*. Only this layer decides *whether that
 * exists on the screen and where*, and it answers solely from observations the camera
 * produced. A request for a button that is not there comes back as a refusal, never as a
 * plausible-sounding position.
 *
 * Semantic matching is delegated to [TargetMatcher] rather than reimplemented, so the Georgian
 * concept dictionary and the corroboration rules from R-12 apply here too.
 */
class TargetResolver(
    private val staleLimitMs: Long = KioskScene.STALE_LIMIT_MS,
) {

    fun resolve(intent: UserIntent, scene: KioskScene, nowMs: Long): TargetResolution {
        if (scene.readability == SceneReadability.NoScreen) {
            return TargetResolution.Unresolved(ResolutionFailure.NoScreen)
        }
        // Deliberately not short-circuiting on SceneReadability.Unreadable. A scene counts as
        // unreadable when nothing on it is *trustworthy*, but the request may be for exactly
        // the untrustworthy label that was read — and "I saw something like it but cannot act
        // on it" is a different answer from "it is not there" (R-12). Only a scene with no
        // text at all is unreadable as far as resolution is concerned; the matcher draws the
        // finer line itself.
        if (scene.elements.isEmpty()) {
            return TargetResolution.Unresolved(ResolutionFailure.SceneUnreadable)
        }
        // Too old to act on, or the kiosk has moved on and no scan has settled it yet. The
        // second is the case a timestamp cannot catch: `observedAtMs` ages the geometry,
        // which is re-detected several times a second, while the labels come from OCR. A
        // scene can look fresh and describe the screen before this one.
        //
        // Here rather than at each caller, because every path that turns an intent into a
        // target comes through this function — selection, the goal carried across screens,
        // and revalidation at confirmation.
        if (!scene.describesScreenNow(nowMs, staleLimitMs)) {
            return TargetResolution.Unresolved(ResolutionFailure.SceneStale)
        }

        // Matching happens over everything that was read, so "it is there but I cannot trust
        // it" stays distinguishable from "it is not there" — the two need different answers.
        val observed = scene.elements.map { it.asObservedLabel() }
        return when (val match = TargetMatcher.matchObserved(intent.query, observed)) {
            is TargetMatch.NotFound -> TargetResolution.Unresolved(
                when (match.reason) {
                    Rejection.NothingRead -> ResolutionFailure.SceneUnreadable
                    Rejection.NoSimilarLabel -> ResolutionFailure.NoSimilarLabel
                    Rejection.UnconfirmedLabel -> ResolutionFailure.UnconfirmedLabel
                },
            )

            is TargetMatch.Ambiguous -> {
                val first = scene.elementFor(match.first)
                val second = scene.elementFor(match.second)
                // A role hint can separate two labels that read alike, but only when it picks
                // out exactly one of them. Anything less and the ambiguity is real.
                val byRole = intent.expectedRole
                    ?.let { role -> listOfNotNull(first, second).filter { it.role == role } }
                    ?.singleOrNull()
                if (byRole != null) {
                    verified(byRole, scene)
                } else {
                    TargetResolution.Ambiguous(listOfNotNull(first, second))
                }
            }

            is TargetMatch.Found -> {
                val element = scene.elementFor(match.block)
                    ?: return TargetResolution.Unresolved(ResolutionFailure.NoSimilarLabel)
                verified(element, scene)
            }
        }
    }

    /**
     * Re-checks a target that was resolved earlier against the scene as it is now.
     *
     * Needed because a target outlives the scene that produced it: the user takes seconds to
     * move a finger, and in that time the kiosk may advance a screen. A target that no longer
     * matches anything observed is withdrawn rather than guided to.
     */
    fun revalidate(target: ResolvedTarget, scene: KioskScene, nowMs: Long): TargetResolution =
        resolve(UserIntent(target.label, target.role), scene, nowMs)

    private fun verified(element: SceneElement, scene: KioskScene): TargetResolution {
        // The last gate, and the one that matters most: a position is only a position if the
        // screen it refers to was actually rectified. Without that the bounds are frame
        // coordinates wearing screen coordinates' clothes.
        if (!element.boundsAreScreenRelative) {
            return TargetResolution.Unresolved(ResolutionFailure.GeometryUnusable)
        }
        return TargetResolution.Resolved(
            ResolvedTarget(
                label = element.label,
                screenBounds = element.screenBounds,
                confidence = element.confidence,
                role = element.role,
                isCritical = element.isCritical,
                sceneObservedAtMs = scene.observedAtMs,
            ),
        )
    }
}

private fun SceneElement.asObservedLabel(): ObservedLabel = ObservedLabel(
    block = DetectedTextBlock(text = label, boundingBox = screenBounds, confidence = confidence),
    timesSeen = timesSeen,
    bestConfidence = confidence,
)

private fun KioskScene.elementFor(block: DetectedTextBlock): SceneElement? =
    elements.firstOrNull { it.label == block.text && it.screenBounds == block.boundingBox }
        ?: elements.firstOrNull { it.label == block.text }
