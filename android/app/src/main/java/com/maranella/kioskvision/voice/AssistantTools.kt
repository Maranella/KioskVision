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

    /** Guidance was stopped as asked. */
    Stopped,

    /** The screen is being read aloud. */
    Reading;

    /** The single field sent back to the model. */
    val wireValue: String get() = name.lowercase()
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

    fun stopGuidance(): ToolOutcome

    fun readScreen(): ToolOutcome
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

    const val QUERY_ARGUMENT = "query"

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

            STOP_GUIDANCE -> actions.stopGuidance()
            READ_SCREEN -> actions.readScreen()

            // Not a crash, and not silence: the model is told its request did nothing, which
            // is the truth and lets it say so.
            else -> ToolOutcome.NotOnScreen
        }
}
