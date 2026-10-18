import Foundation
import Combine

/// Minimal in-memory + console log for debugging. Keep the ring
/// buffer small — the UI shows at most a dozen lines.
@MainActor
final class EventLog: ObservableObject {

    struct Entry: Identifiable, Equatable {
        let id = UUID()
        let timestamp: Date
        let text: String
    }

    @Published private(set) var entries: [Entry] = []
    private let maxEntries = 200

    func log(_ text: String) {
        let entry = Entry(timestamp: Date(), text: text)
        entries.append(entry)
        if entries.count > maxEntries {
            entries.removeFirst(entries.count - maxEntries)
        }
        NSLog("[KA] \(text)")
    }
}
