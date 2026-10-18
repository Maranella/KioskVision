import Foundation

/// Firebase AI Logic model identifiers, voice, language, and the
/// system instruction given to Gemini Live. Kept in lock-step with
/// Android's `com.maranella.kioskvision.ai.AiConfig` for intent,
/// tool contract, and safety constraints — translated to English.
enum AiConfig {

    /// Live-API model name.
    ///
    /// Historical note: Android uses `gemini-3.8-live` which uses
    /// the newest server-side protocol. `FirebaseAILogic 13.0.1`
    /// (the current iOS SDK) returns `InvalidMessageTypeError`
    /// against it because the SDK hasn't shipped parsers for the
    /// newest message types yet. Falling back to an older preview
    /// model fixes the parse error at the cost of newer features.
    ///
    /// Probe pick — stable (no "preview" suffix) sibling of the
    /// 2.5-flash family. Trying this because the server accepts
    /// `gemini-3.8-live` on this project (confirmed) but the iOS
    /// Firebase AI Logic SDK can't parse its messages, and all
    /// 2.0-era ids are rejected server-side. 2.5-flash sits in
    /// between — may be both server-accepted and SDK-parseable.
    ///
    /// Observed on this project:
    ///   "gemini-live-2.5-flash-preview"        // v1main: not found
    ///   "gemini-2.0-flash-live-preview-04-09"  // v1main: not found
    ///   "gemini-2.0-flash-live-001"            // v1main: not found
    ///   "gemini-3.8-live"                      // accepted; SDK can't parse
    ///   "gemini-live-2.5-flash"                // v1main: not found
    static let liveModel = "gemini-2.5-flash-native-audio-preview-12-2025"

    /// Text model used by `GeminiLiveVoiceProvider.diagnose()` and
    /// the optional non-streaming fallback. The server itself
    /// recommends `gemini-3.8-flash` as the current model (returns
    /// a 404 with that recommendation if you try the older
    /// `gemini-2.0-flash`), so this matches the Android project.
    static let textModel = "gemini-3.8-flash"

    /// BCP-47 code for user-facing voice I/O. English per product
    /// directive; kiosk OCR remains Georgian (handled separately).
    static let spokenLanguage = "en-US"

    /// Prebuilt voice. CHARON reads English clearly on the Live API
    /// at the time of writing; a wrong name fails at connect time,
    /// not compile time.
    static let voiceName = "CHARON"

    /// How many recent exchanges the UI shows for sighted debug.
    static let transcriptHistoryLimit = 12

    /// Reconnect budget. Matches Android `ReconnectPolicy.
    /// MAX_ATTEMPTS = 4`. Permanent-failure detection (quota,
    /// billing, app check, model-not-found, unsupported-message)
    /// still short-circuits the loop so unrecoverable errors do
    /// not drain it.
    static let maxReconnectAttempts = 4

    /// Base delay for the first failure retry. Doubles each attempt,
    /// capped at `maxReconnectDelayMs`. Matches Android
    /// `BASE_DELAY_MS = 1_000`.
    static let baseReconnectDelayMs = 1_000

    /// Ceiling for exponential-backoff retries. Matches Android
    /// `MAX_DELAY_MS = 15_000`.
    static let maxReconnectDelayMs = 15_000

    /// Fast reconnect after a planned server rollover (goingAway
    /// notice). This is **not** a failure, so it does not count
    /// against the retry budget. Matches Android
    /// `ROLLOVER_DELAY_MS = 250`.
    static let plannedRolloverDelayMs = 250

    /// Whether the user may interrupt a model turn by speaking.
    /// Off for a kiosk flow where cue intelligibility matters more
    /// than snappy barge-in.
    static let enableInterruptions = false

    /// Single-voice mode: silence Gemini's own speaker and route
    /// the server-provided **output transcription** through the
    /// local `SpeechSynthesizer`, so one engine speaks for the whole
    /// app.
    ///
    /// The model (`gemini-2.5-flash-native-audio-preview-12-2025`)
    /// is native-audio-only and rejects `responseModalities: [TEXT]`.
    /// So we keep `[.audio]` on the wire (the model is happy), but
    /// in the pump loop we **drop every `InlineDataPart` audio
    /// chunk unplayed** and instead buffer the parallel
    /// `outputAudioTranscription` fragments, speaking them via
    /// `AudioOutputCoordinator.speak(...)` when the server marks
    /// the turn complete. Net effect: one voice (local TTS), no
    /// server-side modality rejection.
    ///
    /// Costs: Gemini still generates + streams audio bytes we
    /// discard — a small amount of wasted bandwidth per turn.
    static let singleVoice = true

    /// Domain vocabulary biased into the system instruction so
    /// short English utterances the decoder has no context for do
    /// not drift to other topics. Mirrors Android's list of kiosk
    /// services (electricity, gas, water, internet, mobile, TV,
    /// fines, debts, credit, account, confirmation, cancel, back,
    /// next, operation, utilities, amount, code, number).
    static let domainVocabulary: [String] = [
        "electricity", "gas", "water", "internet",
        "mobile", "television", "utilities",
        "payment", "balance", "help",
        "confirm", "cancel", "back", "next",
        "operation", "fine", "debt", "credit",
        "account", "check", "select", "enter",
        "code", "number", "amount", "later"
    ]

    /// English system instruction given to Gemini Live at session
    /// start. Preserves every constraint from the Android
    /// `AssistantPrompt.kt`: tool-call behaviour, outcome
    /// vocabulary, safety boundaries, response brevity.
    static func systemInstruction() -> String {
        let vocab = domainVocabulary.joined(separator: ", ")
        return """
        LANGUAGE RULE — read first, obey always: every reply you \
        speak and every transcript you emit MUST be in English. \
        Interpret incoming audio as English even when it sounds \
        like it could be Georgian, Russian, or any other language. \
        If a word genuinely doesn't resolve to English, say "I \
        didn't catch that" in English and ask the user to repeat. \
        Do NOT switch languages to match the user — the user has \
        asked for English. Ignore any cultural, geographic, or \
        contextual hints that might suggest another language.

        The user is blind or visually impaired and is standing at \
        a self-service payment kiosk. Utterances are often a \
        single short word. Expect these words and prefer them over \
        similar-sounding ones: \(vocab).

        You are a helpful assistant, not a transcript. Be useful: \
        work out what the user wants, ask one short clarifying \
        question when the request is vague, then act by calling a \
        function. When they ask where something is, which button \
        to press, or how to continue, call `select_target`. When \
        they ask what is on the screen, call `read_screen`. When \
        they ask you to stop, call `stop_guidance`. Prefer calling \
        a function over describing what you would do.

        `select_target` answers with one outcome. Say what it \
        actually means:
        - `guiding` — guidance has started, tell them to move \
          their finger and follow the directions;
        - `notonscreen` — it is not on this screen, say so and \
          suggest looking for another screen;
        - `unconfirmed` — you may have seen it but cannot be \
          sure, ask them to hold the phone steady for a moment;
        - `ambiguous` — there are several, the app is asking \
          which one, so say nothing more;
        - `needsconfirmation` — the app found it and has asked \
          the user whether to go ahead; say nothing, wait for \
          their answer, then call `confirm_target` or \
          `cancel_target`;
        - `confirmed` — guidance has started, tell them to \
          follow the directions;
        - `reading` — the app is reading the screen aloud right \
          now, so say nothing and do not announce it;
        - `screennotreadable` — ask them to move a little closer;
        - `noscreen` — ask them to point the phone at the kiosk \
          screen.
        NEVER say you will show or find something when the \
        outcome was not `guiding` or `confirmed`.

        After a function call, the app itself tells the user the \
        details out loud, so keep your own reply to one short \
        sentence and do not repeat those details. ALWAYS SAY \
        SOMETHING. Never reply with an empty message. If you did \
        not understand, say so and ask them to repeat.

        Once guidance has started, the app speaks the directions \
        — left, right, up, down — so do not repeat or comment on \
        them. Answer the user normally if they speak to you.

        Safety (not permitted):
        - YOU ARE NOT THE KIOSK AND YOU CANNOT SEE ITS SCREEN. \
          Never state that money was received, that an amount was \
          confirmed, that a payment went through, that a button \
          was pressed, or that any step of a transaction has \
          completed. Never describe what the kiosk screen shows \
          or where anything is on it — you have not seen it. If \
          asked, say that you cannot see the screen yourself but \
          the app will read it, and call `read_screen`.
        - The user presses every button themselves; never imply \
          you pressed anything.
        - Never ask for or repeat a PIN, password, or card \
          number.

        Reply in English, in one or two short sentences.
        """
    }
}
