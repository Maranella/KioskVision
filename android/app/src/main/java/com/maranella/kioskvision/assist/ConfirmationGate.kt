package com.maranella.kioskvision.assist

/** What answering "yes" to an offered control should actually do. */
sealed interface ConfirmDecision {

    /** Start guiding. [queries] is what the pipeline matches against; [visited] marks the goal. */
    data class Guide(val queries: List<String>, val visited: String) : ConfirmDecision

    /**
     * Something else on the screen now reads like the offered control.
     *
     * Handed back to the pipeline so it asks which, rather than picking the one that happened
     * to be offered a second earlier.
     */
    data class AskWhich(val queries: List<String>) : ConfirmDecision

    /**
     * It was read, but not well enough to act on — almost always motion blur (R-12).
     *
     * The pipeline holds such a request open for several seconds while it looks for a better
     * frame, which is a far more useful answer than refusing.
     */
    data class HoldOpen(val queries: List<String>) : ConfirmDecision

    /** The control cannot be guided to. [message] is what the user hears, and why. */
    data class Refuse(val reason: ResolutionFailure, val message: SpokenMessage) : ConfirmDecision

    /** Nothing had been offered. */
    data object NothingPending : ConfirmDecision

    /** The user withdrew between the offer and this answer. */
    data object UserStopped : ConfirmDecision
}

/**
 * The gate between "I found გადახდა, shall I take you to it?" and a finger being guided.
 *
 * ### Why it re-checks
 *
 * Saying „დიახ" takes a second or two, and a kiosk can advance in that time — on a timeout, on
 * a card going in, or because the previous press went through. Confirming from the target *as
 * offered* would send a finger to where a button was, which is the failure the whole
 * screen-tracking apparatus exists to prevent.
 *
 * `TargetResolver.revalidate` was written for this, carries `sceneObservedAtMs` for this, and
 * for several revisions was called by nothing at all — the same "written, tested, never
 * reached" shape as R-27, on the one gate that stands between a blind user and a wrong press.
 *
 * ### Why it is out here
 *
 * It lives apart from the ViewModel so it can be tested without a device, a camera or a voice
 * session. Everything it needs is a value: the pending offer, the screen as it is now, and the
 * clock. The ViewModel keeps the side effects — speaking, clearing the offer, starting the
 * pipeline — and makes none of the decisions.
 */
object ConfirmationGate {

    fun decide(
        pending: ResolvedTarget?,
        query: String?,
        scene: KioskScene,
        nowMs: Long,
        userStopped: Boolean,
        resolver: TargetResolver,
    ): ConfirmDecision {
        if (userStopped) return ConfirmDecision.UserStopped
        val target = pending ?: return ConfirmDecision.NothingPending
        val asked = query ?: target.label

        // Nothing on a screen that has moved on, or gone too old to trust, may be confirmed.
        // The labels in such a scene may belong to the screen before it, and revalidating
        // against them would say yes to a button that has already gone.
        //
        // The two causes are told apart for the user — the screen changed, or hold still —
        // and reported to the model as the same thing, which is the nearest its closed
        // vocabulary gets and is also true: nothing can be acted on until the next scan.
        if (!scene.describesScreenNow(nowMs)) {
            return ConfirmDecision.Refuse(
                ResolutionFailure.SceneStale,
                if (scene.changeSuspected) SpokenMessage.ScreenChanged else SpokenMessage.HoldStill,
            )
        }
        // Both wordings: what the user called it, and what the screen calls it. The pipeline
        // tries each, which is how a request survives the two disagreeing.
        val queries = listOf(asked, target.label).distinct()

        return when (val again = resolver.revalidate(target, scene, nowMs)) {
            is TargetResolution.Resolved -> ConfirmDecision.Guide(queries, visited = target.label)

            is TargetResolution.Ambiguous -> ConfirmDecision.AskWhich(listOf(asked))

            is TargetResolution.Unresolved -> when (again.reason) {
                ResolutionFailure.UnconfirmedLabel -> ConfirmDecision.HoldOpen(queries)

                // Each refusal names what the user should do about it. Collapsing them into
                // one sentence is how a readable screen produces a confident false denial.
                else -> ConfirmDecision.Refuse(
                    reason = again.reason,
                    message = when (again.reason) {
                        ResolutionFailure.NoSimilarLabel -> SpokenMessage.TargetNotFound(asked)
                        ResolutionFailure.SceneStale -> SpokenMessage.HoldStill
                        ResolutionFailure.SceneUnreadable -> SpokenMessage.CannotReadText
                        else -> SpokenMessage.ScreenNotTracked
                    },
                )
            }
        }
    }
}
