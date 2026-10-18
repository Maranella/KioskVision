import SwiftUI
import AVFoundation
import Speech
import UIKit
import Combine

/// Camera and microphone permission state + full-screen explanation
/// UI. Mirrors Android `ui/main/MainContract.kt` + `PermissionExplanation.kt`
/// behaviour: user is shown a titled explanation before the first
/// system prompt, after a denial (asking again), and after a blocked
/// state (offering Open Settings).
///
/// iOS collapses Android's `Denied` vs `PermanentlyDenied`: once the
/// user taps "Don't Allow", iOS never re-prompts. We model that as
/// `.blocked`. The pre-prompt case is `.notGranted`.
enum PermissionStatus: Equatable {
    /// Not read yet in this session.
    case unknown
    /// Can still show the system prompt (`.notDetermined`).
    case notGranted
    /// Blocked at the system level; only Settings can grant.
    case blocked
    case granted
}

@MainActor
final class PermissionsCoordinator: ObservableObject {

    @Published private(set) var camera: PermissionStatus = .unknown
    @Published private(set) var microphone: PermissionStatus = .unknown

    /// Both permissions required for a full session. Mic can be
    /// deferred; the camera is a hard prerequisite.
    var cameraReady: Bool { camera == .granted }
    var microphoneReady: Bool { microphone == .granted }

    func refresh() {
        camera = Self.readCamera()
        microphone = Self.readMicrophone()
    }

    /// Prompts if `.notGranted`, otherwise refreshes state.
    func requestCamera() async {
        switch Self.readCamera() {
        case .notGranted:
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            camera = granted ? .granted : .blocked
        default:
            refresh()
        }
    }

    /// Mic request is two-step on iOS: speech recognition auth AND
    /// microphone record permission. Both must be granted.
    func requestMicrophone() async {
        let micGranted: Bool
        switch AVAudioApplication.shared.recordPermission {
        case .undetermined:
            micGranted = await withCheckedContinuation { c in
                AVAudioApplication.requestRecordPermission {
                    c.resume(returning: $0)
                }
            }
        case .granted:
            micGranted = true
        case .denied:
            micGranted = false
        @unknown default:
            micGranted = false
        }

        let speechAuth: SFSpeechRecognizerAuthorizationStatus
        switch SFSpeechRecognizer.authorizationStatus() {
        case .notDetermined:
            speechAuth = await withCheckedContinuation { c in
                SFSpeechRecognizer.requestAuthorization {
                    c.resume(returning: $0)
                }
            }
        case let other:
            speechAuth = other
        }

        let both = micGranted && speechAuth == .authorized
        microphone = both ? .granted : .blocked
    }

    func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString),
              UIApplication.shared.canOpenURL(url) else { return }
        UIApplication.shared.open(url)
    }

    private static func readCamera() -> PermissionStatus {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .notDetermined: return .notGranted
        case .authorized:    return .granted
        case .denied, .restricted: return .blocked
        @unknown default:    return .blocked
        }
    }

    private static func readMicrophone() -> PermissionStatus {
        let mic: PermissionStatus = {
            switch AVAudioApplication.shared.recordPermission {
            case .undetermined: return .notGranted
            case .granted:      return .granted
            case .denied:       return .blocked
            @unknown default:   return .blocked
            }
        }()
        let speech: PermissionStatus = {
            switch SFSpeechRecognizer.authorizationStatus() {
            case .notDetermined: return .notGranted
            case .authorized:    return .granted
            case .denied, .restricted: return .blocked
            @unknown default:    return .blocked
            }
        }()
        // Collapse: both granted → granted; either blocked → blocked;
        // otherwise notGranted (iOS will still prompt).
        if mic == .granted && speech == .granted { return .granted }
        if mic == .blocked || speech == .blocked { return .blocked }
        return .notGranted
    }
}

/// Full-screen high-contrast explanation. Mirrors Android
/// `PermissionExplanation.kt`. Shown before the first system prompt
/// and after a block; VoiceOver reads title → body → actions.
struct PermissionExplanationView: View {
    let title: String
    let message: String
    let primaryActionLabel: String
    let primaryAction: () -> Void
    let secondaryActionLabel: String?
    let secondaryAction: (() -> Void)?

    init(
        title: String,
        message: String,
        primaryActionLabel: String,
        primaryAction: @escaping () -> Void,
        secondaryActionLabel: String? = nil,
        secondaryAction: (() -> Void)? = nil
    ) {
        self.title = title
        self.message = message
        self.primaryActionLabel = primaryActionLabel
        self.primaryAction = primaryAction
        self.secondaryActionLabel = secondaryActionLabel
        self.secondaryAction = secondaryAction
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(title)
                        .font(.system(size: 30, weight: .bold))
                        .foregroundStyle(.white)
                        .accessibilityAddTraits(.isHeader)
                    Text(message)
                        .font(.system(size: 22))
                        .foregroundStyle(.white)
                        .lineSpacing(6)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(24)
            }
            VStack(spacing: 12) {
                Button(action: primaryAction) {
                    Text(primaryActionLabel)
                        .font(.system(size: 22, weight: .bold))
                        .frame(maxWidth: .infinity, minHeight: 72)
                        .foregroundStyle(.black)
                        .background(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                if let label = secondaryActionLabel,
                   let action = secondaryAction {
                    Button(action: action) {
                        Text(label)
                            .font(.system(size: 22, weight: .bold))
                            .frame(maxWidth: .infinity, minHeight: 72)
                            .foregroundStyle(.white)
                            .background(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(.white, lineWidth: 2))
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.ignoresSafeArea())
    }
}
