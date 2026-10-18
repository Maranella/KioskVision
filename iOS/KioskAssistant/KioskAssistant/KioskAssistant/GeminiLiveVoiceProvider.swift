import Foundation
import KioskVisionCore
import Combine
import FirebaseAILogic
import FirebaseCore

/// Firebase AI Logic / Gemini Live voice provider. Currently
/// unused in the UI flow (ContentView always runs the local path),
/// but kept wired for when Firebase + Georgian are revisited.
@MainActor
final class GeminiLiveVoiceProvider: ObservableObject, VoiceProvider {

    @Published private(set) var state: VoiceSessionState = .idle
    @Published private(set) var transcriptHistory: [TranscriptEvent] = []

    // --- Debug observability ---------------------------------------
    /// Short description of the most recent SDK event: "connected",
    /// "audio out 1234 B", "tool call: select_target(...)", etc.
    /// Rendered live in the Gemini debug panel.
    @Published private(set) var lastEvent: String = "—"
    /// Sticky — survives state transitions so you can still see what
    /// went wrong after a reconnect attempt.
    @Published private(set) var lastErrorMessage: String?
    /// Total successful connects since app launch (resets on stop).
    @Published private(set) var connectCount: Int = 0
    /// Reconnect budget consumed since the last successful connect.
    @Published private(set) var reconnectBudgetUsed: Int = 0
    /// Rough counters so you can tell audio is actually flowing.
    @Published private(set) var inputChunkCount: Int = 0
    @Published private(set) var outputBytesTotal: Int = 0
    /// Most recent user transcription (what Gemini heard).
    @Published private(set) var modelHeard: String?
    /// Most recent model transcription (what Gemini said).
    @Published private(set) var modelSaid: String?
    /// Last tool call name + argument summary + outcome wire value.
    @Published private(set) var lastToolCall: String?
    /// Number of times the UI has invoked `diagnose()`. Visible on
    /// the button so you can confirm a tap actually fired.
    @Published private(set) var diagnoseInvokeCount: Int = 0

    /// Synchronous ack that the diagnose button was tapped. Called
    /// on the main actor before the async `diagnose()` kicks off,
    /// so the UI redraws immediately even if the network call
    /// blocks for seconds.
    func markDiagnoseInvoked() {
        diagnoseInvokeCount += 1
        lastEvent = "diagnose button tapped (#\(diagnoseInvokeCount))"
        NSLog("[Gemini] markDiagnoseInvoked #\(diagnoseInvokeCount)")
    }

    private weak var actions: AssistantActions?
    private var session: LiveSession?
    private var pumpTask: Task<Void, Never>?
    private var reconnectAttempts: Int = 0
    private let transcriptAssembler = TranscriptAssembler()
    private let audio = GeminiAudioStreamer()

    /// Set by `AppStore` after construction so the provider can
    /// consult the shared output coordinator. When the app has
    /// claimed the voice (e.g. during `readScreen` reading), the
    /// provider drops incoming model-audio chunks instead of playing
    /// them back on top of the app's own TTS. Weak to avoid a
    /// retain cycle with `AppStore`.
    weak var audioCoordinator: AudioOutputCoordinator?

    // Note: Android has an `isClosed()`-polling watchdog
    // (`watchForClose`). The iOS `LiveSession` type doesn't expose
    // that method; instead the `for try await session.responses`
    // loop in `pumpEvents()` naturally yields nothing when the
    // server closes the socket, and the "if state != .stopped →
    // attemptReconnect" branch handles it. So a separate watchdog
    // is both unimplementable and unnecessary on iOS.

    /// Error patterns that will never be fixed by a retry.
    /// Reconnect loops on these just drain free-tier quota faster.
    static func isPermanentFailure(_ error: String) -> Bool {
        let lower = error.lowercased()
        return lower.contains("quota")
            || lower.contains("billing")
            || lower.contains("permission denied")
            || lower.contains("permission_denied")
            || lower.contains("api not enabled")
            || lower.contains("api is not enabled")
            || lower.contains("api has not been used")
            || lower.contains("model not found")
            || lower.contains("not supported")
            || lower.contains("app check")
            || lower.contains("unauthorized")
            || lower.contains("forbidden")
            // SDK parse errors — the server will keep sending the
            // same unparseable message types until the SDK is
            // updated. Retrying is a loop that cannot win.
            || lower.contains("unsupportedmessage")
            || lower.contains("invalidmessagetype")
    }

    /// Short, one-line human description of the current status.
    /// Mix of configuration + state so one Text() view tells the
    /// whole story at a glance.
    var statusLine: String {
        let fb = AppCheckSetup.isFirebaseConfigured ? "✓" : "✗"
        let s: String = {
            switch state {
            case .idle:          return "idle"
            case .connecting:    return "connecting…"
            case .listening:     return "listening"
            case .speaking:      return "speaking"
            case .reconnecting:  return
                "reconnecting (\(reconnectBudgetUsed)/\(AiConfig.maxReconnectAttempts))"
            case .stopped:       return "stopped"
            case let .error(m):  return "error: \(m)"
            }
        }()
        return "Firebase \(fb) · model \(AiConfig.liveModel) · \(s)"
    }

    func start(actions: AssistantActions) async {
        guard session == nil, state == .idle || state == .stopped else {
            NSLog("[Gemini] start ignored (state=\(state), session=\(session == nil ? "nil" : "live"))")
            lastEvent = "start ignored (state=\(state))"
            return
        }
        guard AppCheckSetup.isFirebaseConfigured else {
            let msg = "Firebase not configured — add GoogleService-Info.plist"
            state = .error(msg)
            lastErrorMessage = msg
            lastEvent = "aborted: no Firebase"
            NSLog("[Gemini] START BLOCKED: \(msg)")
            return
        }
        self.actions = actions
        state = .connecting
        reconnectAttempts = 0
        reconnectBudgetUsed = 0
        lastEvent = "connecting to \(AiConfig.liveModel)…"
        NSLog("[Gemini] start: model=\(AiConfig.liveModel) voice=\(AiConfig.voiceName) lang=\(AiConfig.spokenLanguage)")
        do {
            try await openSession()
            pumpTask?.cancel()
            pumpTask = Task { [weak self] in await self?.pumpEvents() }
        } catch {
            let full = String(describing: error)
            let typeName = String(describing: type(of: error))
            let msg = "\(typeName): \(error.localizedDescription)"
            state = .error(msg)
            lastErrorMessage = full
            lastEvent = "connect error"
            NSLog("[Gemini] START ERROR type=\(typeName)")
            NSLog("[Gemini] START ERROR msg=\(error.localizedDescription)")
            NSLog("[Gemini] START ERROR full=\(full)")
            // Auto-diagnose removed: it fires 1-2 additional text
            // requests on every start failure, chewing through
            // free-tier RPM when the Live socket is already failing.
            // Diagnose is manual-only now — tap the yellow button.
        }
    }

    /// Sends ONE ordinary (non-Live) text request to the same
    /// project + backend to surface a readable error the Live
    /// websocket never exposes. Mirrors Android `diagnose()`.
    /// Prints result to Console AND stores it in `lastEvent` /
    /// `lastErrorMessage` so you can read it on screen.
    func diagnose() async {
        NSLog("[Gemini] diagnose: starting text-model probe…")
        lastEvent = "diagnosing with \(AiConfig.textModel)…"
        let ai = FirebaseAI.firebaseAI(backend: .googleAI())

        // Leg 1 — plain text request. Isolates project / API-enablement
        // / auth / text-model-name problems from Live-protocol
        // problems. The Live socket closes without a readable reason;
        // a normal request returns a proper HTTP error naming the
        // real cause.
        let plain: String
        do {
            let text = ai.generativeModel(modelName: AiConfig.textModel)
            let reply = try await text.generateContent("Say OK")
            let out = (reply.text ?? "(empty)").prefix(30)
            plain = "text OK: \(out)"
        } catch {
            let typeName = String(describing: type(of: error))
            plain = "\(typeName): \(error.localizedDescription)"
        }

        // Leg 2 — same project + model, but with the Live tool
        // declarations attached. Mirrors Android `diagnose()`
        // lines 220-231. If leg 1 passes and leg 2 fails, the
        // problem is the Schema shape we're sending for the tools
        // (not the Live model id, not auth, not quota).
        let withTools: String
        do {
            let text = ai.generativeModel(
                modelName: AiConfig.textModel,
                tools: makeTools())
            _ = try await text.generateContent("Say OK")
            withTools = "tools OK"
        } catch {
            let typeName = String(describing: type(of: error))
            let m = "\(typeName): \(error.localizedDescription)"
            withTools = String(m.prefix(220))
        }

        let combined = "\(plain) | \(withTools)"
        NSLog("[Gemini] diagnose RESULT: \(combined)")
        lastEvent = combined

        // Only touch the sticky `lastErrorMessage` so a successful
        // probe clarifies a failed Live socket. A failed probe is
        // more diagnostic than the Live close reason — promote it.
        if plain.hasPrefix("text OK") && withTools == "tools OK"
           && lastErrorMessage?.contains("connect") == true
        {
            lastErrorMessage =
                "Live failed but text + tools both work. "
                + "Likely the Live model id is wrong "
                + "(\(AiConfig.liveModel)) or Live API not "
                + "enabled for this project."
        } else if !plain.hasPrefix("text OK") {
            lastErrorMessage = "diagnose: \(combined)"
        } else if withTools != "tools OK" {
            lastErrorMessage =
                "Live tools schema rejected: \(withTools)"
        }
    }

    func stop() async {
        pumpTask?.cancel()
        pumpTask = nil
        audio.stop()
        if let s = session {
            await s.close()
        }
        session = nil
        state = .stopped
        reconnectAttempts = 0
        reconnectBudgetUsed = 0
    }

    private func openSession() async throws {
        NSLog("[Gemini] openSession: start")
        lastEvent = "FirebaseAI.googleAI() init"
        let ai = FirebaseAI.firebaseAI(backend: .googleAI())
        let liveModel = ai.liveModel(
            modelName: AiConfig.liveModel,
            generationConfig: makeGenerationConfig(),
            tools: makeTools(),
            systemInstruction: ModelContent(
                parts: [TextPart(AiConfig.systemInstruction())]))
        lastEvent = "liveModel.connect()…"
        let opened = try await liveModel.connect()
        NSLog("[Gemini] openSession: connected")
        session = opened
        connectCount += 1
        lastEvent = "connected (#\(connectCount))"
        try startAudio(on: opened)
        NSLog("[Gemini] openSession: audio engine running")
        lastEvent = "audio engine running"
        state = .listening
        // No kick-off greeting. Android does not send one either —
        // the model listens continuously and will reply as soon as
        // the user speaks. Removing the greeting also saves one
        // free-tier RPM slot per session.
    }

    private func startAudio(on session: LiveSession) throws {
        audio.onInputChunk = { [weak session] data in
            guard let session else { return }
            Task { await session.sendAudioRealtime(data) }
        }
        try audio.start()
    }

    private func makeGenerationConfig() -> LiveGenerationConfig {
        let realtimeInput = AiConfig.enableInterruptions
            ? nil
            : RealtimeInputConfig(activityHandling: .noInterrupt)
        // Always request audio from the model — the only accepted
        // Live model on this project is native-audio-only and
        // rejects `[.text]`. In `AiConfig.singleVoice` mode the
        // pump loop drops the audio chunks unplayed and speaks the
        // `outputAudioTranscription` locally instead.
        return LiveGenerationConfig(
            responseModalities: [.audio],
            speech: SpeechConfig(
                voiceName: AiConfig.voiceName,
                languageCode: AiConfig.spokenLanguage),
            inputAudioTranscription: AudioTranscriptionConfig(),
            outputAudioTranscription: AudioTranscriptionConfig(),
            realtimeInputConfig: realtimeInput)
    }

    private func makeTools() -> [GenerativeModel.Tool] {
        let selectTarget = FunctionDeclaration(
            name: AssistantTools.selectTarget,
            description: AssistantTools.selectTargetDescription,
            parameters: [
                AssistantTools.queryArgument:
                    Schema.string(description:
                        AssistantTools.selectTargetQueryDescription)
            ],
            optionalParameters: [])
        let stopGuidance = FunctionDeclaration(
            name: AssistantTools.stopGuidance,
            description: AssistantTools.stopGuidanceDescription,
            parameters: [:],
            optionalParameters: [])
        let readScreen = FunctionDeclaration(
            name: AssistantTools.readScreen,
            description: AssistantTools.readScreenDescription,
            parameters: [:],
            optionalParameters: [])
        let confirmTarget = FunctionDeclaration(
            name: AssistantTools.confirmTarget,
            description: AssistantTools.confirmTargetDescription,
            parameters: [:],
            optionalParameters: [])
        let cancelTarget = FunctionDeclaration(
            name: AssistantTools.cancelTarget,
            description: AssistantTools.cancelTargetDescription,
            parameters: [:],
            optionalParameters: [])
        return [.functionDeclarations([
            selectTarget, stopGuidance, readScreen,
            confirmTarget, cancelTarget
        ])]
    }

    private func pumpEvents() async {
        guard let session else {
            NSLog("[Gemini] PUMP: no session, exiting")
            return
        }
        NSLog("[Gemini] PUMP: entering message loop")
        do {
            for try await message in session.responses {
                await handle(message: message, on: session)
            }
            if state != .stopped {
                let msg = "session stream ended by server (no exception)"
                lastErrorMessage = msg
                lastEvent = "stream ended"
                NSLog("[Gemini] STREAM-END: \(msg)")
                await attemptReconnect()
            } else {
                NSLog("[Gemini] PUMP: exited cleanly (stopped)")
            }
        } catch is CancellationError {
            NSLog("[Gemini] PUMP: cancelled")
        } catch {
            let full = String(describing: error)
            let typeName = String(describing: type(of: error))
            state = .error("\(typeName): \(error.localizedDescription)")
            lastErrorMessage = full
            lastEvent = "pump error"
            NSLog("[Gemini] PUMP ERROR type=\(typeName)")
            NSLog("[Gemini] PUMP ERROR msg=\(error.localizedDescription)")
            NSLog("[Gemini] PUMP ERROR full=\(full)")
            await attemptReconnect()
        }
    }

    private func handle(
        message: LiveServerMessage, on session: LiveSession
    ) async {
        switch message.payload {
        case let .content(content):
            NSLog("[Gemini] msg: content "
                  + "(turn=\(content.isTurnComplete), "
                  + "parts=\(content.modelTurn?.parts.count ?? 0))")
            handleContent(content)
        case let .toolCall(toolCall):
            NSLog("[Gemini] msg: toolCall "
                  + "(\(toolCall.functionCalls?.count ?? 0) calls)")
            await dispatchToolCalls(toolCall, on: session)
        case .toolCallCancellation:
            NSLog("[Gemini] msg: toolCallCancellation")
        case .sessionResumptionUpdate:
            NSLog("[Gemini] msg: sessionResumptionUpdate")
        case .goingAwayNotice:
            NSLog("[Gemini] msg: goingAwayNotice")
            await rollSession()
        }
    }

    private func handleContent(_ content: LiveServerContent) {
        if let text = content.inputAudioTranscription?.text, !text.isEmpty {
            NSLog("[Gemini] heard: \(text)")
            modelHeard = text
            lastEvent = "heard \(text.count) chars"
            transcriptAssembler.ingestInput(
                text, isFinal: content.isTurnComplete)
        }
        if let text = content.outputAudioTranscription?.text, !text.isEmpty {
            NSLog("[Gemini] said: \(text)")
            modelSaid = text
            lastEvent = "said \(text.count) chars"
            transcriptAssembler.ingestOutput(
                text, isFinal: content.isTurnComplete)
            // Single-voice: the model's audio is being generated and
            // streamed, but we're going to drop every byte of it in
            // the `modelTurn` loop below. The outputAudioTranscription
            // fragments arriving right here are the SAME words, just
            // as text — we buffer them and speak via local TTS when
            // the turn completes. This is how iOS silences Gemini's
            // own voice despite the model being native-audio-only
            // (which forbids `responseModalities: [.text]`).
            if AiConfig.singleVoice {
                pendingTurnText += text
            }
        }
        refreshTranscriptHistory()

        if let turn = content.modelTurn {
            state = .speaking
            // App-owns-voice window: when the app is reading the
            // screen aloud, drop Gemini's acknowledgement audio so
            // it can't cut off the reading mid-sentence. Mirrors
            // Android `LiveAssistViewModel.suppressAssistantUntilMs`.
            let suppress = audioCoordinator?.shouldSuppressModelAudio()
                ?? false
            var audioBytes = 0
            var suppressedBytes = 0
            for part in turn.parts {
                if let audioPart = part as? InlineDataPart,
                   audioPart.mimeType.hasPrefix("audio/") {
                    audioBytes += audioPart.data.count
                    // In single-voice mode we unconditionally drop
                    // the audio — the whole point is that Gemini
                    // has no speaker. In two-voice mode we only
                    // suppress during the readScreen window.
                    if AiConfig.singleVoice || suppress {
                        suppressedBytes += audioPart.data.count
                        continue
                    }
                    audio.enqueueOutput(audioPart.data)
                }
            }
            if audioBytes > 0 {
                outputBytesTotal += audioBytes
                if suppressedBytes > 0 {
                    let why = AiConfig.singleVoice
                        ? "single-voice"
                        : "app owns voice"
                    NSLog("[Gemini] audio out SUPPRESSED "
                          + "(\(suppressedBytes) bytes) — \(why)")
                    lastEvent = "model audio dropped (\(why))"
                } else {
                    NSLog("[Gemini] audio out: \(audioBytes) bytes")
                    lastEvent = "audio out \(audioBytes) B " +
                        "(total \(outputBytesTotal))"
                }
            }
        }
        if content.isTurnComplete {
            state = .listening
            if AiConfig.singleVoice {
                flushPendingTurnText()
            }
        }
    }

    /// Buffer for single-voice mode: `outputAudioTranscription`
    /// fragments across one model turn accumulate here, then get
    /// spoken as one utterance when `isTurnComplete` fires. Speaking
    /// each fragment as it arrives would be choppy half-sentences.
    private var pendingTurnText: String = ""

    private func flushPendingTurnText() {
        let trimmed = pendingTurnText.trimmingCharacters(
            in: .whitespacesAndNewlines)
        pendingTurnText = ""
        guard !trimmed.isEmpty else { return }
        NSLog("[Gemini] single-voice SAY: \(trimmed)")
        lastEvent = "spoken locally \(trimmed.count) chars"
        if let coordinator = audioCoordinator {
            // `shouldSuppressModelAudio` returning true means the app
            // is in the middle of its own reading — drop the model's
            // acknowledgement rather than queue it after.
            if coordinator.shouldSuppressModelAudio() {
                NSLog("[Gemini] text reply SUPPRESSED — "
                      + "app owns voice")
                return
            }
            _ = coordinator.speak(SpokenCue(
                id: "gemini.reply.\(Int(Date().timeIntervalSince1970 * 1000))",
                text: trimmed,
                priority: .high))
        }
    }

    private func dispatchToolCalls(
        _ toolCall: LiveServerToolCall, on session: LiveSession
    ) async {
        let calls = toolCall.functionCalls ?? []
        var responses: [FunctionResponsePart] = []
        for call in calls {
            let outcome: ToolOutcome
            let argSummary = stringArgs(call.args)
                .map { "\($0.key)=\($0.value ?? "nil")" }
                .joined(separator: ",")
            NSLog("[Gemini] tool call: \(call.name)(\(argSummary))")
            if let actions {
                outcome = await AssistantTools.dispatch(
                    name: call.name,
                    arguments: stringArgs(call.args),
                    actions: actions)
            } else {
                // No `AssistantActions` bound (session started
                // before AppStore wired itself in). Report the
                // honest outcome — the request did nothing — using
                // the same fallback Android `dispatch` uses for an
                // unknown function.
                outcome = .notOnScreen
            }
            let summary = "\(call.name)(\(argSummary)) → \(outcome.wireValue)"
            NSLog("[Gemini] tool outcome: \(summary)")
            lastToolCall = summary
            lastEvent = "tool: \(summary)"
            let payload = AssistantTools.responsePayload(for: outcome)
                .mapValues { JSONValue.string($0) }
            responses.append(FunctionResponsePart(
                name: call.name,
                response: payload,
                functionId: call.functionId))
        }
        if !responses.isEmpty {
            await session.sendFunctionResponses(responses)
            // DIAG F1: confirm the function-response round-trip
            // actually left the client. If this fires but Gemini
            // never speaks again the problem is server-side
            // (model stopped generating). If this fires and the
            // app crashes with a WebSocket close reason, the
            // response schema is being rejected.
            NSLog("[Gemini] response ack sent: "
                  + responses.map { $0.name }.joined(separator: ","))
        }
    }

    private func stringArgs(_ args: JSONObject) -> [String: String?] {
        var out: [String: String?] = [:]
        for (k, v) in args {
            switch v {
            case .null:          out[k] = nil
            case .string(let s): out[k] = s
            case .bool(let b):   out[k] = b ? "true" : "false"
            case .number(let d): out[k] = String(d)
            case .object, .array:
                out[k] = String(describing: v)
            }
        }
        return out
    }

    private func refreshTranscriptHistory() {
        let items = transcriptAssembler.recentExchanges(
            limit: AiConfig.transcriptHistoryLimit)
        transcriptHistory = items
    }

    /// Server-announced rollover. **Not** a failure — does not
    /// consume the retry budget. Matches Android
    /// `ReconnectPolicy.onPlannedRollover()`.
    private func rollSession() async {
        NSLog("[Gemini] rollSession: planned rollover in \(AiConfig.plannedRolloverDelayMs)ms")
        pumpTask?.cancel()
        if let s = session { await s.close() }
        session = nil
        state = .reconnecting
        lastEvent = "rollover (planned)…"
        try? await Task.sleep(
            nanoseconds: UInt64(AiConfig.plannedRolloverDelayMs)
                * 1_000_000)
        do {
            try await openSession()
            pumpTask = Task { [weak self] in await self?.pumpEvents() }
        } catch {
            let full = String(describing: error)
            let msg = "rollover failed: \(error.localizedDescription)"
            state = .error(msg)
            lastErrorMessage = full
            lastEvent = "rollover error"
            NSLog("[Gemini] ROLLOVER ERROR full=\(full)")
            // Fall through to normal failure path if the planned
            // reconnect itself failed.
            await attemptReconnect()
        }
    }

    private func attemptReconnect() async {
        // Never retry on quota / billing / permission errors — the
        // next attempt will fail the same way and burn more free-
        // tier quota. The user has to fix the project; code can't.
        if let err = lastErrorMessage,
           Self.isPermanentFailure(err)
        {
            NSLog("[Gemini] NOT RECONNECTING: permanent failure (\(err.prefix(80)))")
            state = .error("permanent: \(err)")
            lastEvent = "stopped — fix project settings"
            return
        }
        if reconnectAttempts >= AiConfig.maxReconnectAttempts {
            let msg = "reconnect attempts exhausted (\(reconnectAttempts))"
            state = .error(msg)
            lastErrorMessage = msg
            lastEvent = "giving up"
            NSLog("[Gemini] FATAL: \(msg) · lastError=\(lastErrorMessage ?? "nil")")
            return
        }
        reconnectAttempts += 1
        reconnectBudgetUsed = reconnectAttempts
        state = .reconnecting
        // Android `ReconnectPolicy.onFailure()`: exponential backoff
        // base * 2^(n-1), capped at the max. 1s → 2s → 4s → 8s → 15s.
        let delayMs = min(
            AiConfig.baseReconnectDelayMs
                << (reconnectAttempts - 1),
            AiConfig.maxReconnectDelayMs)
        lastEvent = "reconnect #\(reconnectAttempts) in \(delayMs)ms"
        NSLog("[Gemini] RECONNECT #\(reconnectAttempts) sleeping \(delayMs)ms")
        try? await Task.sleep(
            nanoseconds: UInt64(delayMs) * 1_000_000)
        do {
            try await openSession()
            reconnectAttempts = 0
            reconnectBudgetUsed = 0
            pumpTask = Task { [weak self] in await self?.pumpEvents() }
            NSLog("[Gemini] RECONNECT #\(reconnectAttempts) succeeded")
        } catch {
            let full = String(describing: error)
            let typeName = String(describing: type(of: error))
            lastErrorMessage = full
            lastEvent = "reconnect failed: " +
                "\(error.localizedDescription)"
            NSLog("[Gemini] RECONNECT ERROR type=\(typeName)")
            NSLog("[Gemini] RECONNECT ERROR full=\(full)")
            await attemptReconnect()
        }
    }
}
