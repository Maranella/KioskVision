package com.maranella.kioskvision.voice

/**
 * What the app did in response to a tool call.
 *
 * Deliberately a closed set of outcomes and **nothing else**. The model learns whether its
 * request succeeded, not what is on the kiosk screen: no labels, no coordinates, no text. That
 * keeps ADR-009 intact even though the model can now trigger actions — it may ask for
 * something and be told yes or no, and it still has no idea what the screen says.
 *
 * It also means no new data leaves the device. The spoken detail the user actually needs
 * ("there are two similar buttons" — and which ones) is said locally from `SpokenMessage`,
 * where it is grounded in what the camera saw.
 */
enum class ToolOutcome {
    /** A real control was found and guidance has started. */
    Guiding,

    /** Nothing on the screen resembles the request. */
    NotOnScreen,

    /** Something resembling it was read, but not reliably enough to act on. */
    Unconfirmed,

    /** Several controls match. The app asks the user which, in its own voice. */
    Ambiguous,

    /** A screen is in view but nothing legible was read from it. */
    ScreenNotReadable,

    /** No kiosk screen is being tracked at all. */
    NoScreen,

    /**
     * A control was found, but nothing has started.
     *
     * The app has said what it found and is waiting to be told to go ahead. Nothing moves and
     * nothing is pressed until the user says yes — which is the whole point for a person who
     * cannot see what they are about to be sent towards.
     */
    NeedsConfirmation,

    /** The user agreed; guidance has started. */
    Confirmed,

    /** There was nothing waiting to be confirmed. */
    NothingPending,

    /** Guidance was stopped as asked. */
    Stopped,

    /** The screen is being read aloud. */
    Reading,

    /**
     * The app is saying the amounts out loud itself, from what the camera read.
     *
     * The model is told that and nothing more: not the prices, not which option won. It asked
     * a question about the screen it cannot see, and the device answered the user directly.
     */
    Comparing,

    /**
     * Amounts could not be attached to options reliably enough to speak.
     *
     * Distinct from [NotOnScreen]: the option may well be there, and so may a price — what is
     * missing is confidence that the two belong together.
     */
    PriceUnknown;

    /** The single field sent back to the model. */
    val wireValue: String get() = name.lowercase()
}

/**
 * Why a request could not be turned into a target, as the model is told it.
 *
 * One mapping, used by every path that resolves an intent, so "it is not on this screen" and
 * "I could not read the screen" cannot come to mean different things depending on which
 * function the model happened to call.
 */
fun com.maranella.kioskvision.assist.ResolutionFailure.asOutcome(): ToolOutcome = when (this) {
    com.maranella.kioskvision.assist.ResolutionFailure.NoScreen -> ToolOutcome.NoScreen
    // The screen is in view but was not rectified, so nothing on it can be pointed at. The
    // user has to re-frame the kiosk, which is the same thing they must do with no screen.
    com.maranella.kioskvision.assist.ResolutionFailure.GeometryUnusable -> ToolOutcome.NoScreen
    com.maranella.kioskvision.assist.ResolutionFailure.SceneUnreadable -> ToolOutcome.ScreenNotReadable
    com.maranella.kioskvision.assist.ResolutionFailure.SceneStale -> ToolOutcome.ScreenNotReadable
    com.maranella.kioskvision.assist.ResolutionFailure.NoSimilarLabel -> ToolOutcome.NotOnScreen
    com.maranella.kioskvision.assist.ResolutionFailure.UnconfirmedLabel -> ToolOutcome.Unconfirmed
}

/**
 * The actions the assistant is allowed to ask for.
 *
 * This is the whole surface. The model cannot move a pointer, press anything, read a frame or
 * learn a coordinate; it can express one of three intents and is told the outcome. Every
 * decision about whether the thing exists, and where, is made on the device from what the
 * camera observed.
 *
 * Implementations must be callable from the SDK's own thread and must not block: the handler
 * is synchronous and runs inside the audio conversation.
 */
interface AssistantActions {
    /**
     * The user wants to be guided to a control. [query] is the user's own wording, as the
     * model understood it — not a label the model read from anywhere.
     */
    fun selectTarget(query: String): ToolOutcome

    /**
     * The user asked what something costs, or which of two things is cheaper.
     *
     * [first] and [second] are the user's own words for the options, as the model understood
     * them — never labels the model read, because it has read nothing. With neither, the app
     * answers about the cheapest option it can see. The comparison itself happens on the
     * device, against amounts OCR actually produced, and the answer is spoken locally.
     */
    fun comparePrices(first: String?, second: String?): ToolOutcome

    fun stopGuidance(): ToolOutcome

    fun readScreen(): ToolOutcome

    /** The user agreed to be guided to whatever was last offered. */
    fun confirmTarget(): ToolOutcome

    /** The user declined it. The offer is dropped and nothing is guided to. */
    fun cancelTarget(): ToolOutcome
}

/**
 * Names and arguments of the functions the model may call.
 *
 * Kept apart from the provider so the contract can be read in one place, and so the mapping
 * from a call to an action is unit-testable without a network or an SDK.
 */
object AssistantTools {

    const val SELECT_TARGET = "select_target"
    const val STOP_GUIDANCE = "stop_guidance"
    const val READ_SCREEN = "read_screen"
    const val CONFIRM_TARGET = "confirm_target"
    const val CANCEL_TARGET = "cancel_target"
    const val COMPARE_PRICES = "compare_prices"

    const val QUERY_ARGUMENT = "query"
    const val SECOND_QUERY_ARGUMENT = "other"

    /**
     * Routes one call to [actions].
     *
     * An unknown function or a missing argument is answered rather than thrown: the model is a
     * preview product that may invent a call, and an exception inside the SDK's synchronous
     * handler would take the audio session down with it.
     */
    fun dispatch(name: String, arguments: Map<String, String?>, actions: AssistantActions): ToolOutcome =
        when (name) {
            SELECT_TARGET -> {
                val query = arguments[QUERY_ARGUMENT]?.trim()
                if (query.isNullOrEmpty()) ToolOutcome.NotOnScreen else actions.selectTarget(query)
            }

            COMPARE_PRICES -> actions.comparePrices(
                arguments[QUERY_ARGUMENT]?.trim()?.ifEmpty { null },
                arguments[SECOND_QUERY_ARGUMENT]?.trim()?.ifEmpty { null },
            )

            STOP_GUIDANCE -> actions.stopGuidance()
            READ_SCREEN -> actions.readScreen()
            CONFIRM_TARGET -> actions.confirmTarget()
            CANCEL_TARGET -> actions.cancelTarget()

            // Not a crash, and not silence: the model is told its request did nothing, which
            // is the truth and lets it say so.
            else -> ToolOutcome.NotOnScreen
        }
}
