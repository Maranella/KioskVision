import Foundation

/// Tool / function declarations the Gemini Live model may call.
/// Names + descriptions kept in lock-step with Android
/// `voice/AssistantContract.kt` so the two platforms expose the
/// same contract.
enum AssistantTools {

    static let selectTarget = "select_target"
    static let stopGuidance = "stop_guidance"
    static let readScreen = "read_screen"
    static let confirmTarget = "confirm_target"
    static let cancelTarget = "cancel_target"

    static let queryArgument = "query"

    static let selectTargetDescription = """
    Guide the user's finger to a control on the kiosk screen. Call \
    this whenever the user asks where something is, which button to \
    press, or how to continue. You cannot see the screen: pass what \
    the user asked for in their own words and the app will decide \
    whether it exists.
    """

    static let selectTargetQueryDescription = """
    What the user asked for, in English, e.g. 'payment' or \
    'electricity bill'.
    """

    static let stopGuidanceDescription = """
    Stop guiding the finger. Call this when the user asks you to stop.
    """

    static let readScreenDescription = """
    Read the kiosk screen aloud to the user. Call this when they ask \
    what is on the screen. The app reads it; you will not be told \
    the text.
    """

    static let confirmTargetDescription = """
    The user agreed to be guided to the control you last offered. \
    Call this when they say yes, correct, go ahead, or similar.
    """

    static let cancelTargetDescription = """
    The user declined the control you last offered. Call this when \
    they say no, not that one, or something else.
    """

    /// Routes one call to `actions`. An unknown function or missing
    /// argument is answered rather than thrown — the Live API is a
    /// preview product that may invent calls, and an exception
    /// inside the SDK's synchronous handler would take the audio
    /// session down with it.
    @MainActor
    static func dispatch(
        name: String,
        arguments: [String: String?],
        actions: AssistantActions
    ) async -> ToolOutcome {
        switch name {
        case selectTarget:
            let query = arguments[queryArgument]??
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if let q = query, !q.isEmpty {
                return await actions.selectTarget(query: q)
            }
            return .notOnScreen
        case stopGuidance:
            return await actions.stopGuidance()
        case readScreen:
            return await actions.readScreen()
        case confirmTarget:
            return await actions.confirmTarget()
        case cancelTarget:
            return await actions.cancelTarget()
        default:
            return .notOnScreen
        }
    }

    /// Single-field response the model receives. **Key is
    /// `outcome` to match Android** (`buildJsonObject { put("outcome",
    /// ...) }`). The system prompt instructs the model to interpret
    /// specific outcome values — using the wrong key would silently
    /// cause it to ignore every function result.
    static func responsePayload(for outcome: ToolOutcome) -> [String: String] {
        ["outcome": outcome.wireValue]
    }
}
