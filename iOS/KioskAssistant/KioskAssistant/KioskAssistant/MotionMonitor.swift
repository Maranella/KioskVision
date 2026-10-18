import Foundation
import Combine
import CoreMotion

/// Device-motion gate. Mirrors Android
/// `sensors/DeviceMotionMonitor.kt`: samples the gyroscope and
/// reports whether the phone has rotated faster than
/// `motionThresholdRadPerSec` within the last `settleIntervalMs`.
///
/// Guidance uses `isSteady` to decide whether a target anchor is
/// still trustworthy: swinging the phone invalidates frame-pixel
/// coordinates, so cues computed from them would point at the past.
@MainActor
final class MotionMonitor: ObservableObject {

    /// True when gyro samples have stayed below the motion threshold
    /// for at least `settleInterval`.
    @Published private(set) var isSteady: Bool = true

    /// Whether the device actually exposes a gyroscope. On devices
    /// without one (iPod, simulator) we treat the device as always
    /// steady — matching Android's fallback.
    @Published private(set) var gyroAvailable: Bool = false

    private let manager = CMMotionManager()
    private var lastMotionAt: Date = .distantPast

    /// Radians/second. 0.3 rad/s ≈ 17°/s — a hand sweeping, not a
    /// hand-held breath. Same constant as Android.
    private let motionThresholdRadPerSec: Double = 0.3
    private let settleInterval: TimeInterval = 0.4

    func start() {
        guard manager.isGyroAvailable else {
            gyroAvailable = false
            isSteady = true
            return
        }
        gyroAvailable = true
        manager.gyroUpdateInterval = 1.0 / 50.0
        manager.startGyroUpdates(to: .main) { [weak self] data, _ in
            guard let self, let data else { return }
            self.ingest(data.rotationRate)
        }
    }

    func stop() {
        if manager.isGyroActive { manager.stopGyroUpdates() }
    }

    private func ingest(_ rate: CMRotationRate) {
        let magnitude = sqrt(
            rate.x * rate.x + rate.y * rate.y + rate.z * rate.z)
        if magnitude >= motionThresholdRadPerSec {
            lastMotionAt = Date()
            if isSteady { isSteady = false }
        } else {
            let elapsed = Date().timeIntervalSince(lastMotionAt)
            if !isSteady, elapsed >= settleInterval {
                isSteady = true
            }
        }
    }
}
