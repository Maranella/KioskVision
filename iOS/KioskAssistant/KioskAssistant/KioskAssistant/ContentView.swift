import SwiftUI
import Combine
import KioskVisionCore

/// Blind-first UI: full-screen camera + status dot once permissions
/// are granted; before that, high-contrast permission explanations
/// gate access. Launch flow:
///
/// 1. Camera `.unknown` or `.notGranted` → explain, then prompt.
/// 2. Camera `.blocked` → explain, then Open Settings.
/// 3. Camera `.granted` + mic `.notGranted` → mic explain, prompt.
/// 4. Mic `.blocked` → explain, Open Settings. Camera keeps running.
/// 5. Both granted → active session (camera + STT + guidance).
struct ContentView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.scenePhase) private var scenePhase

    /// Sighted-only diagnostic overlay. Hidden by default; a sighted
    /// developer unhides it by tapping the status dot three times.
    @State private var showDebugOverlay = false
    @State private var statusDotTapCount = 0

    var body: some View {
        Group {
            switch store.permissions.camera {
            case .unknown, .notGranted:
                PermissionExplanationView(
                    title: String(localized: "permission_needed_title"),
                    message: String(localized: "permission_needed_message"),
                    primaryActionLabel: String(localized: "action_continue"),
                    primaryAction: { Task { await requestCamera() } })
            case .blocked:
                PermissionExplanationView(
                    title: String(localized: "permission_blocked_title"),
                    message: String(localized: "permission_blocked_message"),
                    primaryActionLabel: String(localized: "action_open_settings"),
                    primaryAction: { store.permissions.openSettings() })
            case .granted:
                sessionContent
            }
        }
        .onChange(of: scenePhase) { _, new in
            handleScenePhase(new)
        }
        .onChange(of: store.model.state) { _, newState in
            syncRecognizer(for: newState)
        }
        .onChange(of: store.voice.state) { _, _ in
            // Gemini owning the mic ↔ Apple STT must be off. React
            // to voice-session transitions so Apple STT comes back
            // on if Gemini stops / errors.
            syncRecognizer(for: store.model.state)
        }
    }

    /// STT lifecycle. iOS can only grant one audio-input client at
    /// a time: the moment Gemini Live opens the mic, our
    /// SFSpeechRecognizer start fails with `!pri` (insufficient
    /// priority), the audio-queue error spam begins, and
    /// `openSession` is retried in a loop. So Apple STT only runs
    /// when Gemini is NOT holding the audio session (idle / stopped
    /// / error) AND the session is active (not .idle / .aborted).
    private func syncRecognizer(for state: SessionState) {
        guard store.permissions.microphoneReady else { return }

        let geminiOwnsMic: Bool = {
            switch store.voice.state {
            case .connecting, .listening, .speaking, .reconnecting:
                return true
            case .idle, .stopped, .error:
                return false
            }
        }()
        if geminiOwnsMic {
            if store.recognizer.isListening {
                store.recognizer.stop()
            }
            return
        }

        switch state {
        case .idle, .aborted:
            if store.recognizer.isListening {
                store.recognizer.stop()
            }
        default:
            if !store.recognizer.isListening {
                store.recognizer.start()
            }
        }
    }

    @ViewBuilder
    private var sessionContent: some View {
        ZStack {
            cameraPane
            if showDebugOverlay,
               let assessment = store.perception.latestAssessment {
                DebugOverlay(
                    controls: assessment.controls,
                    hand: assessment.handPose,
                    trackedScreen: assessment.trackedScreen)
            }
            VStack {
                HStack(alignment: .top) {
                    debugPanel
                    Spacer()
                    VStack(alignment: .trailing, spacing: 8) {
                        statusDot
                        if showDebugOverlay {
                            GeminiDebugPanel(store: store)
                        }
                    }
                    .padding(.trailing, 20)
                    .padding(.top, 20)
                }
                Spacer()
                heardOverlay
                micOverlay
            }
            // Voice-assistant consent sheet. Appears exactly once
            // per device after mic is granted and before the user
            // has made a choice. Camera + local STT continue even
            // if they decline.
            if store.permissions.microphoneReady,
               !store.voicePrefs.hasChosen {
                PermissionExplanationView(
                    title: String(localized: "assistant_consent_title"),
                    message: String(localized: "assistant_consent_body"),
                    primaryActionLabel: String(
                        localized: "assistant_consent_accept"),
                    primaryAction: {
                        store.voicePrefs.accept()
                        store.startVoiceAssistantIfConsented()
                    },
                    secondaryActionLabel: String(
                        localized: "assistant_consent_decline"),
                    secondaryAction: { store.voicePrefs.decline() })
            }
        }
        .task {
            await startSession()
        }
    }

    /// All three transcription paths at once, so you can see where
    /// a word is getting lost. From top to bottom:
    ///
    /// - LISTENING / SPEAKING / silent badge
    /// - Apple on-device STT, live partial transcript (yellow)
    /// - Apple on-device STT, finalized utterance + CommandParser
    ///   classification (white + cyan)
    /// - Gemini input transcription — what the Live model heard
    ///   from the mic stream (orange)
    /// - Gemini output transcription — what the model said back
    ///   (teal)
    /// - Last tool invocation summary (magenta)
    /// - Last action-trace line (green)
    ///
    /// Always visible when any of these have data; hidden from
    /// VoiceOver.
    @ViewBuilder
    private var heardOverlay: some View {
        let partial = store.recognizer.transcript
        let last = store.lastHeard
        let intent = store.lastHeardCommand
        let listening = store.recognizer.isListening
        let speaking = store.speech.isSpeaking
        let geminiHeard = store.voice.modelHeard
        let geminiSaid = store.voice.modelSaid
        let geminiTool = store.voice.lastToolCall
        let anyData =
            listening || speaking
            || !partial.isEmpty || last != nil
            || geminiHeard != nil || geminiSaid != nil
            || geminiTool != nil
        if anyData {
            VStack(alignment: .leading, spacing: 6) {
                if listening {
                    HStack(spacing: 8) {
                        Circle().fill(.red)
                            .frame(width: 12, height: 12)
                            .modifier(PulseModifier())
                        Text("LISTENING — speak now")
                            .font(.headline).bold()
                            .foregroundStyle(.red)
                    }
                } else if speaking {
                    HStack(spacing: 8) {
                        Image(systemName: "speaker.wave.2.fill")
                            .foregroundStyle(.blue)
                        Text("SPEAKING — wait")
                            .font(.headline).bold()
                            .foregroundStyle(.blue)
                    }
                } else {
                    HStack(spacing: 8) {
                        Circle().fill(.gray.opacity(0.5))
                            .frame(width: 12, height: 12)
                        Text("silent")
                            .font(.headline)
                            .foregroundStyle(.gray)
                    }
                }
                // --- Apple on-device STT ---------------------------
                if !partial.isEmpty {
                    labelled("Hearing (iOS)", partial, .yellow)
                }
                if let last {
                    labelled("You said", last, .white, bold: true)
                }
                if let intent {
                    labelled("→ parsed", intent, .cyan)
                }
                // --- Gemini audio path -----------------------------
                if let geminiHeard {
                    labelled("Gemini heard", geminiHeard, .orange)
                }
                if let geminiSaid {
                    labelled("Gemini said", geminiSaid, .teal)
                }
                if let geminiTool {
                    labelled("Gemini tool", geminiTool, .pink)
                }
                // --- Local app trace -------------------------------
                if let trace = store.lastActionTrace {
                    labelled("action", trace, .green)
                }
                // --- Gemini error (full, selectable) ---------------
                if let err = store.voice.lastErrorMessage {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("GEMINI ERROR")
                            .font(.caption).bold().monospaced()
                            .foregroundStyle(.red)
                        Text(err)
                            .font(.callout).monospaced()
                            .foregroundStyle(.red)
                            .fixedSize(
                                horizontal: false,
                                vertical: true)
                            .textSelection(.enabled)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(.black.opacity(0.75),
                        in: RoundedRectangle(cornerRadius: 10))
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
            .accessibilityHidden(true)
        }
    }

    /// Compact label + value row used by `heardOverlay`.
    @ViewBuilder
    private func labelled(
        _ label: String, _ value: String,
        _ color: Color, bold: Bool = false
    ) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Text(label)
                .font(.caption).bold().monospaced()
                .foregroundStyle(.gray)
                .frame(width: 100, alignment: .leading)
            Text(value)
                .font(bold ? .callout.bold() : .callout)
                .monospaced()
                .foregroundStyle(color)
                .lineLimit(3)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// Mic-specific overlay. Covers only the bottom portion so the
    /// camera stays visible while the user resolves the mic prompt.
    @ViewBuilder
    private var micOverlay: some View {
        switch store.permissions.microphone {
        case .notGranted, .unknown:
            PermissionExplanationView(
                title: String(localized: "mic_permission_title"),
                message: String(localized: "mic_permission_message"),
                primaryActionLabel: String(localized: "action_continue"),
                primaryAction: { Task { await requestMic() } },
                secondaryActionLabel: String(localized: "action_not_now"),
                secondaryAction: { /* dismiss — session runs without STT */ })
                .frame(maxHeight: 420)
        case .blocked:
            PermissionExplanationView(
                title: String(localized: "mic_blocked_title"),
                message: String(localized: "mic_blocked_message"),
                primaryActionLabel: String(localized: "action_open_settings"),
                primaryAction: { store.permissions.openSettings() })
                .frame(maxHeight: 420)
        case .granted:
            EmptyView()
        }
    }

    @ViewBuilder
    private var cameraPane: some View {
        switch store.camera.state {
        case .running:
            CameraPreviewView(session: store.camera.session)
                .ignoresSafeArea()
                .accessibilityLabel(
                    String(localized: "camera_preview_description"))
        case .denied:
            Color.black.ignoresSafeArea().overlay(
                Text(String(localized: "permission_denied_title"))
                    .foregroundStyle(.white).padding())
        case let .failed(reason):
            Color.black.ignoresSafeArea().overlay(
                VStack(spacing: 8) {
                    Text(String(localized: "status_camera_failed"))
                    Text(reason).font(.caption)
                }
                .foregroundStyle(.white).padding())
        case .idle, .starting:
            Color.black.ignoresSafeArea().overlay(
                ProgressView().tint(.white).scaleEffect(1.6))
        }
    }

    /// Red = listening to the user. Blue = speaking a cue.
    /// Gray = idle. Three quick taps toggle the sighted-dev
    /// overlay; the gesture is deliberately not advertised to
    /// VoiceOver.
    @ViewBuilder
    private var statusDot: some View {
        let color: Color = {
            if store.speech.isSpeaking { return .blue }
            if store.recognizer.isListening { return .red }
            return .gray.opacity(0.5)
        }()
        Circle()
            .fill(color)
            .frame(width: 24, height: 24)
            .contentShape(Rectangle())
            .onTapGesture {
                statusDotTapCount += 1
                if statusDotTapCount >= 3 {
                    showDebugOverlay.toggle()
                    statusDotTapCount = 0
                }
            }
            .accessibilityHidden(true)
    }

    @ViewBuilder
    private var debugPanel: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("state: \(stateLabel(store.model.state))")
            Text("stt: "
                 + (store.recognizer.isListening ? "listening" : "idle")
                 + "  speak: \(store.speech.isSpeaking ? "yes" : "no")")
            if !store.recognizer.transcript.isEmpty {
                Text("heard: \(store.recognizer.transcript)")
                    .lineLimit(2)
            }
            if let err = store.recognizer.lastError {
                Text("err: \(err)")
                    .foregroundStyle(.red)
                    .lineLimit(2)
            }
        }
        .font(.caption2).monospaced()
        .foregroundStyle(.white)
        .padding(8)
        .background(.black.opacity(0.6),
                    in: RoundedRectangle(cornerRadius: 8))
        .padding(.leading, 12).padding(.top, 12)
        .frame(maxWidth: 260, alignment: .leading)
        .accessibilityHidden(true)
    }

    // MARK: - Lifecycle

    private func startSession() async {
        store.permissions.refresh()
        guard store.permissions.cameraReady else { return }
        store.grantedCamera()
        try? await Task.sleep(nanoseconds: 200_000_000)
        if store.permissions.microphoneReady {
            store.grantedMicrophone()
        }
    }

    private func requestCamera() async {
        await store.permissions.requestCamera()
        if store.permissions.cameraReady {
            store.grantedCamera()
        }
    }

    private func requestMic() async {
        await store.permissions.requestMicrophone()
        if store.permissions.microphoneReady {
            store.grantedMicrophone()
        }
    }

    private func handleScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .active:
            store.permissions.refresh()
            if store.permissions.cameraReady,
               store.camera.state != .running,
               store.camera.state != .starting {
                store.grantedCamera()
                if store.permissions.microphoneReady {
                    store.grantedMicrophone()
                }
            }
        case .background, .inactive:
            store.pauseAll()
        @unknown default:
            store.pauseAll()
        }
    }

    private func stateLabel(_ s: SessionState) -> String {
        switch s {
        case .idle: return "idle"
        case .activating: return "activating"
        case .listeningGoal: return "listeningGoal"
        case .confirmingGoal: return "confirmingGoal"
        case .acquiringScreen: return "acquiringScreen"
        case .exploringScreen: return "exploringScreen"
        case .guidingToTarget: return "guidingToTarget"
        case .confirmingCriticalFields: return "confirmingCriticalFields"
        case .recoveringFromTransition: return "recoveringFromTransition"
        case .aborted: return "aborted"
        case .completedLeg: return "completedLeg"
        }
    }
}

/// Simple 1-second pulse. Used to animate the red "LISTENING" dot
/// so the sighted dev can see the mic is actually live.
private struct PulseModifier: ViewModifier {
    @State private var scale: CGFloat = 1
    func body(content: Content) -> some View {
        content
            .scaleEffect(scale)
            .onAppear {
                withAnimation(
                    .easeInOut(duration: 0.6)
                        .repeatForever(autoreverses: true)
                ) {
                    scale = 1.5
                }
            }
    }
}

@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var state: SessionState = .idle

    /// The user utterance that was confirmed as the session's goal.
    @Published private(set) var retainedGoalUtterance: String?

    func send(_ event: SessionEvent) {
        if case let .confirmingGoal(goal) = state,
           case .affirmation(.affirmative) = event {
            retainedGoalUtterance = goal.userUtterance
        }
        state = SessionTransition.reduce(state, event)
        switch state {
        case .idle, .aborted:
            retainedGoalUtterance = nil
        default:
            break
        }
    }

    func reset() {
        state = .idle
        retainedGoalUtterance = nil
    }
}
