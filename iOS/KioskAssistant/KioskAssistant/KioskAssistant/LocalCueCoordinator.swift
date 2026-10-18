import Foundation
import Combine
import KioskVisionCore

/// Local TTS coordinator. Subscribes to state + framing and speaks
/// `CuePolicy` cues; preempts the "what would you like to do?"
/// prompt with a framing cue when framing degrades mid-listening;
/// re-prompts after 7 s of silence.
@MainActor
final class LocalCueCoordinator {

    private let model: AppModel
    private let perception: PerceptionCoordinator
    private let speech: SpeechSynthesizer
    private let audio: AudioOutputCoordinator
    private var cancellables: Set<AnyCancellable> = []
    private var ticker: Task<Void, Never>?

    private var lastPromptAt: Date?
    private let rePromptInterval: TimeInterval = 7

    weak var recognizer: LocalSpeechRecognizer?

    init(
        model: AppModel,
        perception: PerceptionCoordinator,
        speech: SpeechSynthesizer,
        audio: AudioOutputCoordinator
    ) {
        self.model = model
        self.perception = perception
        self.speech = speech
        self.audio = audio
    }

    func start() {
        model.$state
            .combineLatest(perception.$latestAssessment)
            .sink { [weak self] state, assessment in
                self?.consider(state: state, assessment: assessment)
            }
            .store(in: &cancellables)

        ticker?.cancel()
        ticker = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                self?.maybeRePromptListening()
            }
        }
    }

    func stop() {
        cancellables.removeAll()
        ticker?.cancel(); ticker = nil
        speech.cancel()
    }

    private func consider(
        state: SessionState,
        assessment: FramingAssessmentResult?
    ) {
        let problems = assessment?.problems ?? []

        if case .listeningGoal = state,
           let worst = CuePolicy.highestPriority(problems)
        {
            let cue = framingCue(for: worst)
            if cue.id != speech.lastSpokenCueId {
                audio.speak(cue)
            }
            lastPromptAt = nil
            return
        }

        guard let cue = CuePolicy.decide(
            state: state, framingProblems: problems)
        else { return }
        if case .listeningGoal = state, cue.id == "listening.prompt" {
            if lastPromptAt == nil {
                audio.speak(cue)
                lastPromptAt = Date()
            }
            return
        }
        if cue.id == speech.lastSpokenCueId { return }
        audio.speak(cue)
    }

    private func maybeRePromptListening() {
        guard case .listeningGoal = model.state else {
            lastPromptAt = nil; return
        }
        if speech.isSpeaking { return }
        if recognizer?.isListening != true { return }
        guard let last = lastPromptAt,
              Date().timeIntervalSince(last) >= rePromptInterval
        else { return }
        let cue = SpokenCue(
            id: "listening.reprompt.\(Int(Date().timeIntervalSinceReferenceDate))",
            text: String(localized: "status_listening"),
            priority: .normal)
        audio.speak(cue)
        lastPromptAt = Date()
    }

    private func framingCue(
        for problem: FramingProblem
    ) -> SpokenCue {
        if let cue = CuePolicy.decide(
            state: .acquiringScreen,
            framingProblems: [problem])
        {
            return cue
        }
        return SpokenCue(
            id: "framing.unknown",
            text: "Please steady the camera on the kiosk screen.",
            priority: .high)
    }
}
