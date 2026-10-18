import Foundation
import Combine
import CoreGraphics
import KioskVisionCore

/// Owns every long-lived object the UI needs.
@MainActor
final class AppStore: ObservableObject {

    let model: AppModel
    let permissions: PermissionsCoordinator
    let voicePrefs: VoicePreferences
    let camera: CameraSession
    let perception: PerceptionCoordinator
    let voice: GeminiLiveVoiceProvider
    let speech: SpeechSynthesizer
    let audioCoordinator: AudioOutputCoordinator
    let recognizer: LocalSpeechRecognizer
    let cues: LocalCueCoordinator
    let guidance: LocalFingerGuidanceCoordinator
    let log: EventLog

    /// Label last offered via a `needsConfirmation` outcome. Cleared
    /// on confirm/cancel or screen transition.
    @Published fileprivate(set) var pendingTarget: String?

    /// Most recent finalized utterance from `LocalSpeechRecognizer`,
    /// surfaced for the on-screen "heard" overlay. Also written to
    /// the `EventLog` for the sighted debug panel + console.
    @Published private(set) var lastHeard: String?

    /// What the parser decided the last utterance was: `stop`,
    /// `read screen`, `select target`, etc. Lets the overlay show
    /// whether the command landed where expected.
    @Published private(set) var lastHeardCommand: String?

    /// Short status line written by action handlers so you can see
    /// in the overlay what actually happened (e.g. "readScreen → 0
    /// labels"). Updated every tool/command invocation.
    @Published private(set) var lastActionTrace: String?

    private var cancellables: Set<AnyCancellable> = []

    init() {
        let log = EventLog()
        let model = AppModel()
        let permissions = PermissionsCoordinator()
        let voicePrefs = VoicePreferences()
        let camera = CameraSession()
        let perception = PerceptionCoordinator(
            camera: camera, appModel: model, log: log)
        let voice = GeminiLiveVoiceProvider()
        let speech = SpeechSynthesizer()
        let audioCoordinator = AudioOutputCoordinator(speech: speech)
        let recognizer = LocalSpeechRecognizer()
        let cues = LocalCueCoordinator(
            model: model, perception: perception,
            speech: speech, audio: audioCoordinator)
        let guidance = LocalFingerGuidanceCoordinator(
            model: model, perception: perception,
            speech: speech, audio: audioCoordinator)

        self.log = log
        self.model = model
        self.permissions = permissions
        self.voicePrefs = voicePrefs
        self.camera = camera
        self.perception = perception
        self.voice = voice
        self.speech = speech
        self.audioCoordinator = audioCoordinator
        self.recognizer = recognizer
        self.cues = cues
        self.guidance = guidance
        cues.recognizer = recognizer
        voice.audioCoordinator = audioCoordinator
        permissions.refresh()

        [model.objectWillChange,
         permissions.objectWillChange,
         voicePrefs.objectWillChange,
         camera.objectWillChange,
         voice.objectWillChange,
         speech.objectWillChange,
         recognizer.objectWillChange]
            .forEach { publisher in
                publisher
                    .sink { [weak self] in
                        self?.objectWillChange.send()
                    }
                    .store(in: &cancellables)
            }

        model.$state
            .sink { [weak self] s in
                self?.log.log("state → \(s)")
            }
            .store(in: &cancellables)

        recognizer.utterances
            .sink { [weak self] text in
                self?.handleUtterance(text)
            }
            .store(in: &cancellables)

        // Route model speech into the output coordinator so local
        // cues cannot talk over Gemini. ONLY needed in audio mode:
        // in `AiConfig.singleVoice` mode the model has no speaker
        // of its own — we TTS its text through the same
        // `SpeechSynthesizer` the local cues use, so the engine's
        // own priority/interrupt handling already serialises
        // everything. Keeping the gate active in single-voice would
        // silently drop framing cues during the brief
        // text-accumulation window where `voice.state == .speaking`
        // but nothing has actually been spoken yet.
        if !AiConfig.singleVoice {
            voice.$state
                .map { state -> Bool in
                    if case .speaking = state { return true }
                    return false
                }
                .removeDuplicates()
                .sink { [weak self] speaking in
                    self?.audioCoordinator.voiceSpeakingChanged(speaking)
                }
                .store(in: &cancellables)
        }
    }

    /// Opt-in start of the Gemini Live voice session. Called by the
    /// UI after the user accepts the consent sheet, or on launch if
    /// consent already granted + Firebase configured. Stops the
    /// local Apple STT first so the audio session is clear when
    /// Gemini's streamer tries to tap the microphone.
    func startVoiceAssistantIfConsented() {
        guard voicePrefs.assistantEnabled else { return }
        guard AppCheckSetup.isFirebaseConfigured else {
            log.log("voice: Firebase not configured; skipping")
            return
        }
        if recognizer.isListening {
            recognizer.stop()
        }
        Task { [weak self] in
            guard let self else { return }
            await self.voice.start(actions: self)
        }
    }

    func stopVoiceAssistant() {
        Task { [weak self] in
            await self?.voice.stop()
        }
    }

    /// Called from the UI when the user accepts the camera prompt.
    /// Flips state and starts the hardware.
    func grantedCamera() {
        permissions.refresh()
        if permissions.cameraReady {
            camera.start()
            perception.startIfNeeded()
            cues.start()
            guidance.start()
            model.send(.userCommand(.start))
        }
    }

    /// Called from the UI after the mic prompt resolves; snapshots
    /// recognizer auth and starts the active mic consumer. Only one
    /// client can hold the iOS mic at a time, so if Gemini is going
    /// to be used we must NOT also start Apple STT — the local STT
    /// would grab the audio session first and the Live SDK would
    /// then fail with `AVAudioSessionErrorCodeInsufficientPriority`
    /// ("!pri") in a tight retry loop.
    func grantedMicrophone() {
        permissions.refresh()
        recognizer.refreshAuthorization()
        guard permissions.microphoneReady else { return }

        let willUseGemini =
            voicePrefs.assistantEnabled
            && AppCheckSetup.isFirebaseConfigured
        if willUseGemini {
            // Gemini owns the mic. `syncRecognizer` in ContentView
            // will bring Apple STT back online if Gemini errors or
            // stops (fallback path).
            startVoiceAssistantIfConsented()
        } else {
            recognizer.start()
        }
    }

    /// Pause all hardware when the scene backgrounds. Reverse via
    /// `grantedCamera()` + `grantedMicrophone()` on return.
    func pauseAll() {
        stopVoiceAssistant()
        recognizer.stop()
        cues.stop()
        guidance.stop()
        perception.stop()
        camera.stop()
    }

    func resetSession() {
        model.reset()
        log.log("session reset")
    }

    // MARK: - Utterance → state machine

    private func handleUtterance(_ raw: String) {
        log.log("heard: \(raw)")
        lastHeard = raw

        // Global commands first — stop/back/repeat/read must preempt
        // any pending goal match. Only `.selectTarget` falls through
        // to the per-state handling.
        let parsed = CommandParser.parse(raw)
        lastHeardCommand = Self.describe(parsed)
        switch parsed {
        case .stop:
            audioCoordinator.cancelAll()
            pendingTarget = nil
            model.send(.userCommand(.cancel))
            return
        case .goBack:
            audioCoordinator.cancelAll()
            model.send(.userCommand(.cancel))
            return
        case .repeatLast:
            // Re-speak the most recent non-direction cue. If nothing
            // is stored (e.g. right after launch) tell the user so
            // they don't wait silently.
            if let repeated = audioCoordinator.repeatLast() {
                lastActionTrace = "repeat → \"\(repeated.text)\""
            } else {
                audioCoordinator.speak(SpokenCue(
                    id: "repeat.nothing",
                    text: String(localized: "speak_not_understood"),
                    priority: .high))
                lastActionTrace = "repeat → nothing to repeat"
            }
            return
        case .readScreen:
            Task { [weak self] in
                _ = await self?.readScreen()
            }
            return
        case .unknown, .selectTarget:
            break
        }

        switch model.state {
        case .listeningGoal:
            let controls = perception.latestAssessment?.controls ?? []
            // Prefer a concrete control match when we already have
            // one; otherwise still treat the utterance as a goal and
            // confirm it verbatim. Previously an empty-control case
            // sent `interpretedGoalId: nil`, which the reducer
            // treats as "stay in listeningGoal" — so nothing was
            // audible and the user had no feedback at all.
            if !controls.isEmpty,
               let match = GoalMatcher.match(
                utterance: raw, controls: controls)
            {
                model.send(.speechTranscript(
                    utterance: raw,
                    interpretedGoalId: match.control.id.rawValue,
                    readbackPhrase: "Did you want \(match.control.label)?"))
            } else {
                // No match yet — carry the raw utterance into
                // `.confirmingGoal` so the user hears a readback and
                // can confirm. After yes, we land in
                // `.exploringScreen` where the perception loop keeps
                // trying to match as new OCR arrives.
                let safe = raw.trimmingCharacters(
                    in: .whitespacesAndNewlines)
                model.send(.speechTranscript(
                    utterance: raw,
                    interpretedGoalId: safe.isEmpty ? "unknown" : safe,
                    readbackPhrase: "I heard: \(safe). Is that right?"))
            }
        case .confirmingGoal, .confirmingCriticalFields:
            model.send(.affirmation(parseAffirmation(raw)))
        default:
            break
        }
    }

    /// Short, human-readable description of a parsed command.
    /// Shown in the on-screen "heard" overlay so you can see
    /// whether the intent landed where you expected.
    private static func describe(_ cmd: AssistCommand) -> String {
        switch cmd {
        case .stop:                      return "stop"
        case .goBack:                    return "go back"
        case .repeatLast:                return "repeat"
        case .readScreen:                return "read screen"
        case let .selectTarget(query):   return "select → \(query)"
        case .unknown:                   return "unknown"
        }
    }

    private func parseAffirmation(
        _ raw: String
    ) -> AffirmationResponse {
        let t = raw.lowercased()
        let yes = [
            "yes", "yeah", "yep", "yup",
            "okay", "ok", "sure", "correct",
            "that's right", "that is right",
            "please", "do it"
        ]
        let no = [
            "no", "nope", "nah",
            "stop", "cancel", "quit",
            "not that", "wrong", "different"
        ]
        if yes.contains(where: { t.contains($0) }) { return .affirmative }
        if no.contains(where: { t.contains($0) }) { return .negative }
        return .ambiguous(utterance: raw)
    }
}

extension AppStore: AssistantActions {

    /// Target the user last offered (via a `needsConfirmation`
    /// outcome). `confirmTarget` / `cancelTarget` act on this.
    /// Reset on screen transition.
    private var pendingTargetIdKey: String { "pendingTargetId" }

    func selectTarget(query: String) async -> ToolOutcome {
        log.log("tool: selectTarget(\(query))")
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let scene = perception.currentScene(nowMs: now)
        let resolution = TargetResolver().resolve(
            intent: UserIntent(query: query),
            scene: scene,
            nowMs: now)
        switch resolution {
        case let .resolved(target):
            log.log("resolved → \(target.label)")
            // Non-critical path: start guidance immediately.
            // Critical path: ask the user before moving.
            if target.isCritical {
                pendingTarget = target.label
                return .needsConfirmation
            }
            // The reducer's target id must match an
            // `ActionableControl.id` so `LocalFingerGuidanceCoordinator`
            // can find the control in `perception.latestAssessment`.
            // `ControlClassifier` builds those ids as
            // `"<label_slug>@<x>,<y>"` (ControlClassifier.swift:85-90),
            // not as the raw label — so we must resolve the real id
            // instead of fabricating one from `target.label`.
            guard let controlId = resolveActionableControlId(
                for: target)
            else {
                log.log("resolved label \(target.label) has no "
                        + "matching ActionableControl")
                return .noScreen
            }
            model.send(.targetLocked(controlId))
            // Only claim `.guiding` if the reducer accepted the
            // transition AND locked onto *this* control. There is no
            // `(.guidingToTarget, .targetLocked)` case in the
            // reducer, so a re-selection while a previous target is
            // still being guided to is a no-op; checking just
            // `case .guidingToTarget` would read TRUE on the stale
            // state and misreport success.
            guard case let .guidingToTarget(current) = model.state,
                  current == controlId
            else {
                log.log("targetLocked ignored or stale in state "
                        + "\(model.state) (wanted \(controlId.rawValue))")
                return .noScreen
            }
            return .guiding
        case let .ambiguous(options):
            log.log("ambiguous → \(options.map { $0.label })")
            return .ambiguous
        case let .unresolved(reason):
            log.log("unresolved → \(reason)")
            switch reason {
            case .noScreen:          return .noScreen
            case .sceneUnreadable:   return .screenNotReadable
            case .sceneStale,
                 .geometryUnusable:  return .screenNotReadable
            case .unconfirmedLabel:  return .unconfirmed
            case .noSimilarLabel:    return .notOnScreen
            }
        }
    }

    func stopGuidance() async -> ToolOutcome {
        log.log("tool: stopGuidance")
        pendingTarget = nil
        model.send(.userCommand(.cancel))
        return .stopped
    }

    func readScreen() async -> ToolOutcome {
        log.log("tool: readScreen")
        // Claim the voice for the reading window up-front: Gemini's
        // acknowledgement audio was arriving mid-reading with its
        // own TTS playing on top and cutting the labels off. Mirrors
        // Android commit 53bfaa0 (LiveAssistViewModel.readScreen).
        audioCoordinator.beginAppSpeakingWindow()
        let scene = perception.currentScene(
            nowMs: Int64(Date().timeIntervalSince1970 * 1000))
        let trustworthy = scene.elements.filter { $0.isTrustworthy }
        let allCount = scene.elements.count
        log.log("read → \(trustworthy.count)/\(allCount) labels")
        lastActionTrace =
            "readScreen → \(trustworthy.count)/\(allCount) labels"

        guard !trustworthy.isEmpty else {
            let emptyCue = SpokenCue(
                id: "readback.empty",
                text: String(localized: "speak_no_text_visible"),
                priority: .high)
            let accepted = audioCoordinator.speak(emptyCue)
            lastActionTrace =
                "readScreen empty (spoken=\(accepted))"
            return .reading
        }

        // Build a readback line locally; TTS speaks it via the
        // output coordinator so it preempts low-priority cues and
        // pauses while Gemini is speaking.
        //
        // ADR-009 forbids sending OCR text (Georgian or English)
        // to the cloud. When the OCR text is Georgian, the right
        // follow-up is Apple's on-device `Translation` framework
        // (iOS 17.4+) which never leaves the device. Deliberately
        // not routing through Gemini here. For now we speak the
        // labels as-is; English labels play correctly and Georgian
        // labels get mispronounced by the English voice until the
        // on-device translator is wired.
        let labels = trustworthy.prefix(8).map { $0.label }
        let joined = labels.joined(separator: ", ")
        let format = String(localized: "speak_screen_contents")
        let line = String(format: format, joined)
        let accepted = audioCoordinator.speak(SpokenCue(
            id: "readback.lines",
            text: line,
            priority: .high))
        lastActionTrace =
            "readScreen → spoke \(trustworthy.count) "
            + "label(s) (accepted=\(accepted))"
        if scene.elements.contains(where: { !$0.isTrustworthy }) {
            audioCoordinator.speak(SpokenCue(
                id: "readback.partial",
                text: String(localized: "speak_screen_some_unreadable"),
                priority: .normal))
        }
        return .reading
    }

    func confirmTarget() async -> ToolOutcome {
        log.log("tool: confirmTarget")
        guard let label = pendingTarget else {
            return .nothingPending
        }
        // The pending target was offered via `selectTarget` and the
        // user has now agreed through Gemini's conversation. Lock it
        // as the target directly — sending `.affirmation(.affirmative)`
        // would be a no-op when the local state machine is at
        // `.listeningGoal` (where it stays while Gemini owns the
        // mic), causing `confirmed` to be reported without any state
        // transition. Same `ActionableControl.id` lookup rule as in
        // `selectTarget` — see the comment there.
        pendingTarget = nil
        guard let controlId = resolveActionableControlId(label: label)
        else {
            log.log("confirmTarget: no matching ActionableControl "
                    + "for \(label)")
            return .noScreen
        }
        model.send(.targetLocked(controlId))
        // Same strictness as `selectTarget` — compare the actual
        // locked id so a stale `.guidingToTarget(oldId)` can't look
        // like a successful confirmation.
        guard case let .guidingToTarget(current) = model.state,
              current == controlId
        else {
            log.log("confirmTarget: targetLocked ignored or stale in "
                    + "state \(model.state) "
                    + "(wanted \(controlId.rawValue))")
            return .noScreen
        }
        return .confirmed
    }

    /// Finds the `ActionableControl.id` for a resolved target by
    /// matching the normalized label against the current
    /// `latestAssessment.controls`. When several controls share the
    /// same label (rare — `TargetResolver` would have returned
    /// `.ambiguous`), picks the one whose centre is closest to the
    /// resolver's screen-bounds centre.
    private func resolveActionableControlId(
        for target: ResolvedTarget
    ) -> TargetId? {
        let candidates = labelMatchedControls(label: target.label)
        if candidates.isEmpty { return nil }
        if candidates.count == 1 { return candidates[0].id }
        let targetCx = (target.screenBounds.left
                        + target.screenBounds.right) / 2
        let targetCy = (target.screenBounds.top
                        + target.screenBounds.bottom) / 2
        let closest = candidates.min(by: { a, b in
            Self.sqDist(cx: Float(a.normalizedBBox.midX),
                        cy: Float(a.normalizedBBox.midY),
                        tx: targetCx, ty: targetCy)
            < Self.sqDist(cx: Float(b.normalizedBBox.midX),
                          cy: Float(b.normalizedBBox.midY),
                          tx: targetCx, ty: targetCy)
        })
        return closest?.id
    }

    /// Overload used by `confirmTarget` which only has the pending
    /// label (no `ResolvedTarget`). Picks the single matching
    /// control if exactly one is present, otherwise `nil` to force
    /// an honest refusal rather than guessing.
    private func resolveActionableControlId(
        label: String
    ) -> TargetId? {
        let candidates = labelMatchedControls(label: label)
        return candidates.count == 1 ? candidates[0].id : nil
    }

    private func labelMatchedControls(
        label: String
    ) -> [ActionableControl] {
        let needle = label.lowercased().trimmingCharacters(
            in: .whitespacesAndNewlines)
        guard let controls = perception.latestAssessment?.controls
        else { return [] }
        return controls.filter {
            $0.label.lowercased().trimmingCharacters(
                in: .whitespacesAndNewlines) == needle
        }
    }

    private static func sqDist(
        cx: Float, cy: Float, tx: Float, ty: Float
    ) -> Float {
        let dx = cx - tx
        let dy = cy - ty
        return dx * dx + dy * dy
    }

    func cancelTarget() async -> ToolOutcome {
        log.log("tool: cancelTarget")
        guard pendingTarget != nil else {
            return .nothingPending
        }
        pendingTarget = nil
        model.send(.affirmation(.negative))
        return .stopped
    }
}
