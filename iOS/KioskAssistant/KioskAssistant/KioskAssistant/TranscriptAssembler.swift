import Foundation

/// Assembles streaming input/output transcription fragments from
/// Gemini Live into finalized `TranscriptEvent`s.
@MainActor
final class TranscriptAssembler {

    private var inputBuffer = ""
    private var outputBuffer = ""
    private var exchanges: [TranscriptEvent] = []

    func ingestInput(_ fragment: String, isFinal: Bool) {
        inputBuffer += fragment
        guard isFinal else { return }
        let user = inputBuffer.trimmingCharacters(
            in: .whitespacesAndNewlines)
        inputBuffer = ""
        if !user.isEmpty {
            exchanges.append(
                TranscriptEvent(userUtterance: user, modelReply: nil))
        }
    }

    func ingestOutput(_ fragment: String, isFinal: Bool) {
        outputBuffer += fragment
        guard isFinal else { return }
        let reply = outputBuffer.trimmingCharacters(
            in: .whitespacesAndNewlines)
        outputBuffer = ""
        if reply.isEmpty { return }
        if var last = exchanges.last, last.modelReply == nil {
            last = TranscriptEvent(
                userUtterance: last.userUtterance, modelReply: reply)
            exchanges[exchanges.count - 1] = last
        } else {
            exchanges.append(
                TranscriptEvent(userUtterance: nil, modelReply: reply))
        }
    }

    func recentExchanges(limit: Int) -> [TranscriptEvent] {
        if exchanges.count <= limit { return exchanges }
        return Array(exchanges.suffix(limit))
    }
}
