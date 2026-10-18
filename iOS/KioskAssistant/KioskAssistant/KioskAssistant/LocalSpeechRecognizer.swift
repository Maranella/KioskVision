import Foundation
import Combine
import Speech
import AVFAudio

/// On-device-first speech recognizer. Pinned to `en-US` per product
/// directive. Kiosk-screen OCR remains Georgian; user speech is
/// English.
@MainActor
final class LocalSpeechRecognizer: NSObject, ObservableObject,
                                   SFSpeechRecognizerDelegate {

    @Published private(set) var isListening: Bool = false
    @Published private(set) var transcript: String = ""
    @Published private(set) var lastError: String?
    @Published private(set) var authorizationGranted: Bool = false

    /// Finalized transcript each time the user stops speaking for
    /// ~1.5 s.
    let utterances = PassthroughSubject<String, Never>()

    private let recognizer: SFSpeechRecognizer?
    private let audioEngine = AVAudioEngine()
    private var task: SFSpeechRecognitionTask?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var endOfSpeechWork: DispatchWorkItem?
    private let endOfSpeechDelay: TimeInterval = 1.5

    override init() {
        recognizer = SFSpeechRecognizer(
            locale: Locale(identifier: "en-US"))
        super.init()
        recognizer?.delegate = self
    }

    /// Snapshots the current system permission state. Prompting is
    /// `PermissionsCoordinator`'s job; this just reads the result so
    /// `start()` can refuse without crashing.
    func refreshAuthorization() {
        let speech = SFSpeechRecognizer.authorizationStatus() == .authorized
        let mic = AVAudioApplication.shared.recordPermission == .granted
        authorizationGranted = speech && mic
    }

    func start() {
        guard !isListening else { return }
        guard authorizationGranted else {
            lastError = "not authorized"; return
        }
        guard let recognizer, recognizer.isAvailable else {
            lastError = "recognizer unavailable"; return
        }
        do {
            try configureAudioSession()
            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            request.requiresOnDeviceRecognition =
                recognizer.supportsOnDeviceRecognition
            self.request = request

            let input = audioEngine.inputNode
            let format = input.outputFormat(forBus: 0)
            input.removeTap(onBus: 0)
            input.installTap(
                onBus: 0, bufferSize: 1024, format: format
            ) { [weak self] buffer, _ in
                self?.request?.append(buffer)
            }
            audioEngine.prepare()
            try audioEngine.start()
            task = recognizer.recognitionTask(with: request) {
                [weak self] result, error in
                guard let self else { return }
                Task { @MainActor [weak self] in
                    self?.handleResult(result: result, error: error)
                }
            }
            isListening = true
            lastError = nil
            transcript = ""
        } catch {
            lastError = error.localizedDescription
            stop()
        }
    }

    func stop() {
        endOfSpeechWork?.cancel(); endOfSpeechWork = nil
        task?.finish(); task = nil
        request?.endAudio(); request = nil
        if audioEngine.isRunning {
            audioEngine.inputNode.removeTap(onBus: 0)
            audioEngine.stop()
        }
        isListening = false
    }

    private func configureAudioSession() throws {
        let session = AVAudioSession.sharedInstance()
        // `.measurement` mode disables the loudspeaker on most
        // iPhones, which made TTS fire silently. `.default` keeps
        // STT accuracy acceptable in a kiosk environment and lets
        // the speech synthesizer play through the main speaker.
        try session.setCategory(
            .playAndRecord, mode: .default,
            options: [.defaultToSpeaker, .allowBluetooth,
                      .duckOthers, .mixWithOthers])
        try session.setActive(
            true, options: .notifyOthersOnDeactivation)
        // Belt-and-braces: force the main speaker regardless of
        // what route the OS picked by default.
        try? session.overrideOutputAudioPort(.speaker)
    }

    private func handleResult(
        result: SFSpeechRecognitionResult?, error: Error?
    ) {
        if let result {
            transcript = result.bestTranscription.formattedString
            scheduleEndOfSpeech(final: result.isFinal)
            if result.isFinal { emit(transcript) }
        }
        if let error = error as NSError? {
            let benign: Set<Int> = [203, 216, 1101, 1110]
            if !benign.contains(error.code) {
                lastError = "\(error.code): \(error.localizedDescription)"
            }
            stop()
        }
    }

    private func scheduleEndOfSpeech(final: Bool) {
        endOfSpeechWork?.cancel()
        if final { return }
        let work = DispatchWorkItem { [weak self] in
            Task { @MainActor [weak self] in
                guard let self, !self.transcript.isEmpty else { return }
                self.emit(self.transcript)
                self.stop()
            }
        }
        endOfSpeechWork = work
        DispatchQueue.main.asyncAfter(
            deadline: .now() + endOfSpeechDelay, execute: work)
    }

    private func emit(_ text: String) {
        let trimmed = text.trimmingCharacters(
            in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        utterances.send(trimmed)
        transcript = ""
    }

    nonisolated func speechRecognizer(
        _ speechRecognizer: SFSpeechRecognizer,
        availabilityDidChange available: Bool
    ) {
        Task { @MainActor [weak self] in
            if !available { self?.stop() }
        }
    }
}
