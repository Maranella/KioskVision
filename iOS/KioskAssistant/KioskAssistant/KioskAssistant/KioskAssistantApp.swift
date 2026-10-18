import SwiftUI

@main
struct KioskAssistantApp: App {
    var body: some Scene {
        WindowGroup {
            RootHost()
        }
    }
}

/// Lazy host for the entire app. `AppStore()` instantiates camera,
/// audio engine, perception, and the local voice coordinators —
/// several of which XPC to system daemons. Doing all of that during
/// `KioskAssistantApp.init` was racing with the OS launch sequence
/// and producing a first-launch `OS_dispatch_mach_msg _setContext:`
/// crash. We defer that work to a `Task` fired from the view's
/// `.task` modifier after SwiftUI has finished its launch work.
///
/// `AppCheckSetup.install()` must run before `FirebaseApp.configure()`
/// — it does both.
struct RootHost: View {
    @State private var store: AppStore?

    var body: some View {
        Group {
            if let store {
                ContentView()
                    .environmentObject(store)
            } else {
                Color.black.ignoresSafeArea()
            }
        }
        .task {
            try? await Task.sleep(nanoseconds: 400_000_000)
            await MainActor.run {
                AppCheckSetup.install()
                self.store = AppStore()
            }
        }
    }
}
