package com.maranella.kioskvision.speech

import com.maranella.kioskvision.assist.SpokenMessage
import com.maranella.kioskvision.voice.ToolOutcome

/**
 * What the app says for itself when there is no assistant to say it.
 *
 * The same actions serve both voice paths, and they answer with a [ToolOutcome] because that is
 * all the model is ever allowed to learn. With the assistant switched off nobody turns that
 * word into a sentence, and the app would act — or refuse — in silence, which for a blind user
 * is indistinguishable from a crash.
 *
 * Null means the sentence has already been said: the actions themselves speak the cases that
 * carry detail (a control offered, a request held open for a steadier frame, two similar
 * labels), and repeating them here would say everything twice.
 */
object LocalReply {

    fun forOutcome(outcome: ToolOutcome, query: String): SpokenMessage? = when (outcome) {
        ToolOutcome.NotOnScreen -> SpokenMessage.TargetNotFound(query)
        ToolOutcome.ScreenNotReadable -> SpokenMessage.CannotReadText
        ToolOutcome.NoScreen -> SpokenMessage.ScreenNotTracked
        ToolOutcome.PriceUnknown -> null
        ToolOutcome.NothingPending -> SpokenMessage.NotUnderstood

        // Already spoken, by the action that produced it.
        ToolOutcome.NeedsConfirmation,
        ToolOutcome.Ambiguous,
        ToolOutcome.Unconfirmed,
        ToolOutcome.Guiding,
        ToolOutcome.Confirmed,
        ToolOutcome.Stopped,
        ToolOutcome.Reading,
        ToolOutcome.Comparing,
        -> null
    }
}
