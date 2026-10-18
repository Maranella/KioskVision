import AVFAudio
import Combine
import KioskVisionCore

/// Thin wrapper around `AVSpeechSynthesizer`.
///
/// Lazy init on first `speak(_:)` — building the engine at app
/// launch was racing with camera XPC startup.
@MainActor
final class SpeechSynthesizer: NSObject, ObservableObject,
                               AVSpeechSynthesizerDelegate {

    @Published private(set) var isSpeaking: Bool = false
    @Published private(set) var lastSpokenCueId: String?

    private var _synth: AVSpeechSynthesizer?
    private var _voice: AVSpeechSynthesisVoice?

    private var synth: AVSpeechSynthesizer {
        if let s = _synth { return s }
        let s = AVSpeechSynthesizer()
        s.delegate = self
        _synth = s
        if _voice == nil {
            if let enhanced = AVSpeechSynthesisVoice.speechVoices()
                .first(where: {
                    $0.language.hasPrefix("en")
                        && $0.quality == .enhanced
                })
            {
                _voice = enhanced
            } else {
                _voice = AVSpeechSynthesisVoice(language: "en-US")
            }
        }
        return s
    }

    override init() {
        super.init()
    }

    func speak(_ cue: SpokenCue, interrupt: Bool = false) {
        let s = synth
        if s.isSpeaking {
            guard interrupt else { return }
            s.stopSpeaking(at: .immediate)
        }
        let utterance = AVSpeechUtterance(string: cue.text)
        utterance.voice = _voice
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * 0.85
        utterance.pitchMultiplier = 1.0
        utterance.volume = 1.0
        utterance.postUtteranceDelay = 0.15
        lastSpokenCueId = cue.id
        s.speak(utterance)
    }

    func cancel() {
        _synth?.stopSpeaking(at: .immediate)
    }

    nonisolated func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer,
        didStart utterance: AVSpeechUtterance
    ) {
        Task { @MainActor [weak self] in self?.isSpeaking = true }
    }

    nonisolated func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer,
        didFinish utterance: AVSpeechUtterance
    ) {
        Task { @MainActor [weak self] in self?.isSpeaking = false }
    }

    nonisolated func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer,
        didCancel utterance: AVSpeechUtterance
    ) {
        Task { @MainActor [weak self] in self?.isSpeaking = false }
    }
}
