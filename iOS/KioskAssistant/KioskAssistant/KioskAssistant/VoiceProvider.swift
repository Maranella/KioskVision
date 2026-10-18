import Foundation

/// Public lifecycle states of a voice session.
enum VoiceSessionState: Equatable, Sendable {
    case idle
    case connecting
    case listening       // microphone streaming
    case speaking        // model speaking
    case reconnecting
    case stopped
    case error(String)
}

/// Final (not partial) user utterance after end-of-turn detection,
/// plus the matching model reply when available.
struct TranscriptEvent: Equatable, Sendable {
    let userUtterance: String?
    let modelReply: String?
}

/// What the local app did in response to a tool call. Mirrors
/// Android `voice/AssistantTools.kt` `ToolOutcome` enum: the model
/// learns whether its request succeeded, not what is on the kiosk
/// screen (ADR-009 — no labels, coordinates, or OCR text leave the
/// device). The granular cases let the model prompt the user to
/// steady the phone / point at the screen / wait for confirmation.
enum ToolOutcome: Equatable, Sendable {
    /// A real control was found and guidance has started.
    case guiding
    /// Nothing on the screen resembles the request.
    case notOnScreen
    /// Something resembling it was read but not reliably enough to act on.
    case unconfirmed
    /// Several controls match; the local app is asking the user which.
    case ambiguous
    /// A screen is in view but nothing legible was read from it.
    case screenNotReadable
    /// No kiosk screen is being tracked at all.
    case noScreen
    /// A control was found; waiting for the user to say yes/no.
    case needsConfirmation
    /// The user agreed; guidance has started.
    case confirmed
    /// There was nothing waiting to be confirmed.
    case nothingPending
    /// Guidance was stopped as asked.
    case stopped
    /// The screen is being read aloud.
    case reading

    /// The single field sent back to the model as the function
    /// response. Lowercase alphanumeric, matching Android
    /// `ToolOutcome.wireValue`.
    var wireValue: String {
        switch self {
        case .guiding:            return "guiding"
        case .notOnScreen:        return "notonscreen"
        case .unconfirmed:        return "unconfirmed"
        case .ambiguous:          return "ambiguous"
        case .screenNotReadable:  return "screennotreadable"
        case .noScreen:           return "noscreen"
        case .needsConfirmation:  return "needsconfirmation"
        case .confirmed:          return "confirmed"
        case .nothingPending:     return "nothingpending"
        case .stopped:            return "stopped"
        case .reading:            return "reading"
        }
    }
}

/// Handler the voice provider calls on main actor when Gemini
/// invokes a function tool. Mirrors Android `AssistantActions`
/// interface. Implementations must be callable from the SDK's own
/// thread and must not block.
@MainActor
protocol AssistantActions: AnyObject {
    func selectTarget(query: String) async -> ToolOutcome
    func stopGuidance() async -> ToolOutcome
    func readScreen() async -> ToolOutcome
    /// The user agreed to be guided to whatever was last offered.
    func confirmTarget() async -> ToolOutcome
    /// The user declined it. The offer is dropped.
    func cancelTarget() async -> ToolOutcome
}

/// Pluggable voice backend.
@MainActor
protocol VoiceProvider: AnyObject, ObservableObject {
    var state: VoiceSessionState { get }
    func start(actions: AssistantActions) async
    func stop() async
    var transcriptHistory: [TranscriptEvent] { get }
}
