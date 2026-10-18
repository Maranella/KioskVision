import Foundation
import Combine

/// Persistent consent for the Gemini Live voice assistant. Mirrors
/// Android `settings/VoicePreferences.kt`: one durable boolean plus
/// a flag that remembers whether the user has been asked at all,
/// so the onboarding sheet appears exactly once per device.
///
/// Default: **off**. Nothing leaves the device until the user
/// explicitly accepts the consent screen.
@MainActor
final class VoicePreferences: ObservableObject {

    private let defaults: UserDefaults
    private static let keyEnabled = "assistant.voice.enabled"
    private static let keyChosen = "assistant.voice.hasChosen"

    @Published private(set) var assistantEnabled: Bool
    @Published private(set) var hasChosen: Bool

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.assistantEnabled = defaults.bool(forKey: Self.keyEnabled)
        self.hasChosen = defaults.bool(forKey: Self.keyChosen)
    }

    func accept() {
        assistantEnabled = true
        hasChosen = true
        defaults.set(true, forKey: Self.keyEnabled)
        defaults.set(true, forKey: Self.keyChosen)
    }

    func decline() {
        assistantEnabled = false
        hasChosen = true
        defaults.set(false, forKey: Self.keyEnabled)
        defaults.set(true, forKey: Self.keyChosen)
    }

    /// Lets the user revoke consent from a settings UI (reserved
    /// for a later milestone — exposed now so the data layer is
    /// not retrofitted).
    func revoke() {
        assistantEnabled = false
        defaults.set(false, forKey: Self.keyEnabled)
    }
}
