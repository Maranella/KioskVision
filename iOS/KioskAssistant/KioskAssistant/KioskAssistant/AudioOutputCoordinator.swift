import Foundation
import Combine
import KioskVisionCore

/// Serializes speech requests from the local coordinators and the
/// Gemini Live model so the two voices never overlap. Mirrors
/// Android `speech/AudioOutputCoordinator.kt`.
///
/// Behaviour:
/// - A new cue preempts the current one only if its priority is
///   higher. Equal-priority cues queue (local TTS) or are dropped
///   as duplicates by `SpeechSynthesizer`.
/// - `voiceSpeakingChanged(_:)` ties Gemini audio playback into the
///   same gate: while the model is speaking, local cues of priority
///   below `.critical` are dropped. Critical cues (stop/cancel,
///   safety aborts) always preempt.
/// - On a target or screen change, all queued local cues are
///   cancelled — their referent no longer exists.
@MainActor
final class AudioOutputCoordinator {

    private let speech: SpeechSynthesizer
    private var cancellables: Set<AnyCancellable> = []
    private var modelSpeaking: Bool = false
    private var currentPriority: SpokenCue.Priority?

    /// Deadline (ms since boot) up to which the app owns the voice
    /// and Gemini's incoming audio chunks must be silenced. Mirrors
    /// Android `LiveAssistViewModel.suppressAssistantUntilMs` /
    /// `APP_SPEAKS_WINDOW_MS = 20_000L` — long enough to cover
    /// reading a screen of labels aloud.
    private var appSpeakingUntilMs: Int64 = Int64.min / 4
    static let appSpeakingWindowMs: Int64 = 20_000

    /// Most recently accepted repeatable cue. Direction / short-
    /// lived navigation cues are excluded because repeating "a bit
    /// left" after the finger has moved is harmful. Only prompts,
    /// readbacks and safety-level cues are stored.
    private var lastRepeatableCue: SpokenCue?

    init(speech: SpeechSynthesizer) {
        self.speech = speech
        speech.$isSpeaking
            .sink { [weak self] speaking in
                if !speaking { self?.currentPriority = nil }
            }
            .store(in: &cancellables)
    }

    /// Called by `GeminiLiveVoiceProvider` whenever its session
    /// enters/exits `.speaking`.
    func voiceSpeakingChanged(_ speaking: Bool) {
        modelSpeaking = speaking
        if speaking {
            // The model is talking: silence low-priority local
            // chatter immediately. Finger-direction cues and
            // critical safety cues still get through below.
            if let p = currentPriority, p < .high {
                speech.cancel()
                currentPriority = nil
            }
        }
    }

    /// Request to speak a cue. Returns true if the cue was accepted.
    @discardableResult
    func speak(_ cue: SpokenCue) -> Bool {
        if modelSpeaking && cue.priority < .critical {
            return false
        }
        let shouldInterrupt: Bool = {
            guard let current = currentPriority else { return false }
            return cue.priority > current
        }()
        speech.speak(cue, interrupt: shouldInterrupt)
        currentPriority = cue.priority
        if cue.priority != .navigation {
            // Keep for `.repeatLast` — but never remember direction
            // cues: they describe the finger's position a moment ago
            // and are useless when replayed.
            lastRepeatableCue = cue
        }
        return true
    }

    /// User asked the app to repeat itself. Re-speaks the most
    /// recent non-direction cue, if any, bypassing dedup — the user
    /// asking twice means they didn't hear it the first time.
    /// Returns the cue that was re-spoken, or nil if nothing is
    /// available.
    @discardableResult
    func repeatLast() -> SpokenCue? {
        guard let cue = lastRepeatableCue else { return nil }
        speech.cancel()
        currentPriority = nil
        // Direct `speech.speak` with interrupt true, bypassing the
        // `modelSpeaking` gate: the user explicitly asked for this.
        speech.speak(cue, interrupt: true)
        currentPriority = cue.priority
        return cue
    }

    /// Called when a screen transition or target change makes any
    /// still-queued cues stale.
    func cancelAll() {
        speech.cancel()
        currentPriority = nil
    }

    /// Reserve the voice for the app for `ms` milliseconds. Called
    /// by handlers whose answer the app itself speaks (e.g.
    /// `readScreen`). While this window is open, `GeminiAudioStreamer`
    /// drops incoming model-audio chunks so the assistant can't talk
    /// over the reading. Mirrors Android `suppressAssistantUntilMs`.
    func beginAppSpeakingWindow(
        ms: Int64 = AudioOutputCoordinator.appSpeakingWindowMs
    ) {
        appSpeakingUntilMs = nowMs() + ms
    }

    /// True while the app owns the voice. `GeminiLiveVoiceProvider`
    /// checks this before playing a model-audio chunk.
    func shouldSuppressModelAudio() -> Bool {
        nowMs() < appSpeakingUntilMs
    }

    private func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}
