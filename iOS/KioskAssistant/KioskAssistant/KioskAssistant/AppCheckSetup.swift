import Foundation
import FirebaseCore
import FirebaseAppCheck

/// Firebase App Check initialization.
///
/// Guard: if `GoogleService-Info.plist` is not bundled, we skip
/// Firebase init entirely so the app can still boot and the camera /
/// perception stack can run. The voice provider will refuse to start
/// in this state. Drop the plist into the project folder and
/// Firebase lights up on next launch — no code change needed.
enum AppCheckSetup {

    /// `true` once `install()` has successfully called
    /// `FirebaseApp.configure()`. The voice provider gates on this.
    private(set) static var isFirebaseConfigured = false

    static func install() {
        guard Bundle.main.path(
            forResource: "GoogleService-Info",
            ofType: "plist") != nil
        else {
            print("""
                [KioskAssistant] GoogleService-Info.plist not found. \
                Firebase init skipped — voice assistant will remain \
                dormant. Drop the plist into \
                KioskAssistant/KioskAssistant/ and relaunch.
                """)
            return
        }

        #if DEBUG
        let providerFactory = AppCheckDebugProviderFactory()
        #else
        let providerFactory = AppAttestProviderFactory()
        #endif
        AppCheck.setAppCheckProviderFactory(providerFactory)
        FirebaseApp.configure()
        isFirebaseConfigured = true
    }
}
