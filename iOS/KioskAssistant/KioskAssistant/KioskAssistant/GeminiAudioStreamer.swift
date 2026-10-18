import Foundation
import AVFoundation

/// Captures microphone audio for Gemini Live and plays the model's
/// audio response back out through the device speaker/earpiece.
///
/// - **Mic → Gemini**: Live API expects **16 kHz, 16-bit, mono PCM**.
/// - **Gemini → speaker**: 24 kHz, 16-bit, mono PCM, received as
///   `InlineDataPart`s on `LiveServerContent.modelTurn`.
@MainActor
final class GeminiAudioStreamer {

    var onInputChunk: (@MainActor (Data) -> Void)?

    private let engine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private var inputConverter: AVAudioConverter?
    private var outputConverter: AVAudioConverter?
    private var running = false

    func start() throws {
        guard !running else { return }
        do {
            try configureAudioSession()
            NSLog("[Audio] session configured: "
                  + "\(AVAudioSession.sharedInstance().category.rawValue) / "
                  + "\(AVAudioSession.sharedInstance().mode.rawValue)")
            try installPlayback()
            try installCapture()
            try engine.start()
            NSLog("[Audio] engine started, running=\(engine.isRunning)")
            playerNode.play()
            running = true
        } catch {
            NSLog("[Audio] start FAILED: \(error.localizedDescription)")
            throw error
        }
    }

    func stop() {
        guard running else { return }
        playerNode.stop()
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        running = false
    }

    func enqueueOutput(_ pcm24kHz: Data) {
        guard let converter = outputConverter,
              let outputFormat = playerNode.outputFormat(forBus: 0) as AVAudioFormat?
        else { return }
        guard let inputBuffer = makeBuffer(
            from: pcm24kHz, format: converter.inputFormat)
        else { return }
        let ratio =
            outputFormat.sampleRate / converter.inputFormat.sampleRate
        let outCapacity = AVAudioFrameCount(
            Double(inputBuffer.frameLength) * ratio + 1024)
        guard let outBuffer = AVAudioPCMBuffer(
            pcmFormat: outputFormat,
            frameCapacity: outCapacity)
        else { return }
        var error: NSError?
        let status = converter.convert(
            to: outBuffer, error: &error
        ) { _, inputStatus in
            inputStatus.pointee = .haveData
            return inputBuffer
        }
        if status == .error || error != nil { return }
        playerNode.scheduleBuffer(outBuffer, completionHandler: nil)
    }

    private func configureAudioSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(
            .playAndRecord,
            mode: .voiceChat,
            options: [.defaultToSpeaker, .allowBluetooth])
        try session.setPreferredIOBufferDuration(0.02)
        try session.setActive(true, options: [])
    }

    private func installPlayback() throws {
        engine.attach(playerNode)
        guard let geminiOutputFormat = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: 24_000,
            channels: 1,
            interleaved: true)
        else { throw AudioError.formatUnsupported("gemini-24k mono int16") }
        let deviceFormat = engine.outputNode.outputFormat(forBus: 0)
        outputConverter = AVAudioConverter(
            from: geminiOutputFormat, to: deviceFormat)
        engine.connect(playerNode, to: engine.mainMixerNode, format: deviceFormat)
    }

    private func installCapture() throws {
        let input = engine.inputNode
        let hardwareFormat = input.outputFormat(forBus: 0)
        guard hardwareFormat.sampleRate > 0 else {
            throw AudioError.formatUnsupported(
                "input node reports zero sample rate")
        }
        guard let geminiInputFormat = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: 16_000,
            channels: 1,
            interleaved: true)
        else { throw AudioError.formatUnsupported("gemini-16k mono int16") }

        inputConverter = AVAudioConverter(
            from: hardwareFormat, to: geminiInputFormat)
        guard let inputConverter else {
            throw AudioError.formatUnsupported("input converter")
        }

        input.removeTap(onBus: 0)
        input.installTap(
            onBus: 0,
            bufferSize: AVAudioFrameCount(hardwareFormat.sampleRate / 25),
            format: hardwareFormat
        ) { [weak self] buffer, _ in
            guard let self else { return }
            let ratio = geminiInputFormat.sampleRate / hardwareFormat.sampleRate
            let outCapacity = AVAudioFrameCount(
                Double(buffer.frameLength) * ratio + 1024)
            guard let outBuffer = AVAudioPCMBuffer(
                pcmFormat: geminiInputFormat,
                frameCapacity: outCapacity)
            else { return }
            var error: NSError?
            let status = inputConverter.convert(
                to: outBuffer, error: &error
            ) { _, inputStatus in
                inputStatus.pointee = .haveData
                return buffer
            }
            if status == .error || error != nil { return }
            guard let data = Self.data(from: outBuffer) else { return }
            Task { @MainActor [weak self] in
                self?.onInputChunk?(data)
            }
        }
    }

    private func makeBuffer(
        from pcm: Data, format: AVAudioFormat
    ) -> AVAudioPCMBuffer? {
        let bytesPerFrame = Int(format.streamDescription.pointee.mBytesPerFrame)
        if bytesPerFrame == 0 { return nil }
        let frames = AVAudioFrameCount(pcm.count / bytesPerFrame)
        guard frames > 0 else { return nil }
        guard let buffer = AVAudioPCMBuffer(
            pcmFormat: format, frameCapacity: frames)
        else { return nil }
        buffer.frameLength = frames
        if let dst = buffer.int16ChannelData?.pointee {
            pcm.withUnsafeBytes { raw in
                guard let src = raw.baseAddress else { return }
                memcpy(dst, src, pcm.count)
            }
        }
        return buffer
    }

    private static func data(from buffer: AVAudioPCMBuffer) -> Data? {
        guard let channel = buffer.int16ChannelData?.pointee else { return nil }
        let byteCount = Int(buffer.frameLength) * MemoryLayout<Int16>.size
        return Data(bytes: channel, count: byteCount)
    }

    enum AudioError: Error, LocalizedError {
        case formatUnsupported(String)
        var errorDescription: String? {
            switch self {
            case .formatUnsupported(let what):
                return "unsupported audio format: \(what)"
            }
        }
    }
}
