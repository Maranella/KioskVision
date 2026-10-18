import SwiftUI

/// Sighted-only Gemini Live diagnostics. One panel that shows every
/// signal that matters when verifying whether the voice assistant is
/// actually working:
///
/// - Firebase configured + consent granted (prerequisites)
/// - Model identifier and session state
/// - Last SDK event + sticky last error
/// - Reconnect budget + total connects + output audio bytes
/// - Most recent user utterance (what Gemini heard)
/// - Most recent model reply (what Gemini said)
/// - Last tool call name + arguments + outcome
/// - Last few assembled transcript exchanges
///
/// Hidden from VoiceOver. Rendered only when `ContentView`'s
/// `showDebugOverlay` is true (triple-tap the status dot).
struct GeminiDebugPanel: View {
    @ObservedObject var store: AppStore

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("GEMINI")
                .font(.caption).bold().monospaced()
                .foregroundStyle(.cyan)

            row("Firebase",
                AppCheckSetup.isFirebaseConfigured ? "configured ✓" : "missing ✗")
            row("Consent",
                store.voicePrefs.hasChosen
                    ? (store.voicePrefs.assistantEnabled
                        ? "accepted ✓" : "declined")
                    : "not asked")
            row("Model",      AiConfig.liveModel)
            row("Voice",      "\(AiConfig.voiceName) · \(AiConfig.spokenLanguage)")
            row("State",      stateText(store.voice.state))

            Divider().overlay(.gray)

            row("Connects",  "\(store.voice.connectCount)")
            row("Reconnect", "\(store.voice.reconnectBudgetUsed)/"
                + "\(AiConfig.maxReconnectAttempts)")
            row("Audio out", "\(store.voice.outputBytesTotal) B")
            row("Event",     store.voice.lastEvent)

            if let err = store.voice.lastErrorMessage {
                // Full-width multi-line error so the whole SDK
                // message is visible — not clipped to three
                // characters. Long-press to select and copy.
                VStack(alignment: .leading, spacing: 2) {
                    Text("ERROR")
                        .font(.caption2).bold().monospaced()
                        .foregroundStyle(.red)
                    Text(err)
                        .font(.caption).monospaced()
                        .foregroundStyle(.red)
                        .fixedSize(
                            horizontal: false, vertical: true)
                        .textSelection(.enabled)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            // Manual probe button. Big tap target because the panel
            // sits on top of a camera preview and small taps lose to
            // the ZStack below. Updates lastEvent synchronously so
            // you see immediate feedback.
            Button(action: {
                store.voice.markDiagnoseInvoked()
                Task { await store.voice.diagnose() }
            }) {
                HStack {
                    Spacer()
                    Text("▶  RUN DIAGNOSE  (\(store.voice.diagnoseInvokeCount))")
                        .font(.callout).bold().monospaced()
                        .foregroundStyle(.black)
                    Spacer()
                }
                .padding(.vertical, 12)
                .background(.yellow,
                            in: RoundedRectangle(cornerRadius: 8))
            }
            .buttonStyle(.borderless)
            .contentShape(Rectangle())

            Divider().overlay(.gray)

            if let heard = store.voice.modelHeard {
                row("Heard", heard, .yellow)
            }
            if let said = store.voice.modelSaid {
                row("Said", said, .white)
            }
            if let tool = store.voice.lastToolCall {
                row("Tool", tool, .green)
            }

            if !store.voice.transcriptHistory.isEmpty {
                Divider().overlay(.gray)
                Text("TRANSCRIPT")
                    .font(.caption).bold().monospaced()
                    .foregroundStyle(.cyan)
                ForEach(
                    Array(store.voice.transcriptHistory.suffix(4)
                        .enumerated()),
                    id: \.offset
                ) { _, ev in
                    if let u = ev.userUtterance, !u.isEmpty {
                        Text("you: \(u)")
                            .font(.caption2).monospaced()
                            .foregroundStyle(.yellow)
                            .lineLimit(2)
                    }
                    if let m = ev.modelReply, !m.isEmpty {
                        Text("bot: \(m)")
                            .font(.caption2).monospaced()
                            .foregroundStyle(.white)
                            .lineLimit(2)
                    }
                }
            }
        }
        .padding(10)
        .background(.black.opacity(0.78),
                    in: RoundedRectangle(cornerRadius: 10))
        .frame(maxWidth: 300, alignment: .leading)
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private func row(
        _ label: String, _ value: String,
        _ color: Color = .white
    ) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Text(label)
                .font(.caption2).bold().monospaced()
                .foregroundStyle(.gray)
                .frame(width: 70, alignment: .leading)
            Text(value)
                .font(.caption2).monospaced()
                .foregroundStyle(color)
                .lineLimit(3)
                .textSelection(.enabled)
        }
    }

    private func stateText(_ state: VoiceSessionState) -> String {
        switch state {
        case .idle:          return "idle"
        case .connecting:    return "connecting…"
        case .listening:     return "listening"
        case .speaking:      return "speaking"
        case .reconnecting:  return "reconnecting"
        case .stopped:       return "stopped"
        case let .error(m):  return "error: \(m)"
        }
    }
}
