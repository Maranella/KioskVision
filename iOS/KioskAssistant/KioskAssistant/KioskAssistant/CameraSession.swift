import Foundation
import AVFoundation
import Combine
import CoreVideo

/// Wraps `AVCaptureSession` for the rear camera. Exposes the latest
/// pixel buffer as a `@Published` property so SwiftUI views + the
/// perception coordinator can read it off the main actor.
///
/// Lifecycle is split: `init()` is cheap, nothing talks to AV until
/// `start()` is called. This mirrors how the voice/speech wrappers
/// behave and keeps `AppStore()` free of launch-time XPC races.
@MainActor
final class CameraSession: NSObject, ObservableObject,
                           AVCaptureVideoDataOutputSampleBufferDelegate {

    enum State: Equatable {
        case idle
        case starting
        case running
        case denied
        case failed(String)
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var latestPixelBuffer: CVPixelBuffer?

    let session = AVCaptureSession()
    private let output = AVCaptureVideoDataOutput()
    private let queue = DispatchQueue(
        label: "camera.sample",
        qos: .userInitiated)

    /// Caller must verify camera permission is `.granted` before
    /// invoking. `PermissionsCoordinator` owns the prompt flow; this
    /// class only configures hardware.
    func start() {
        guard state != .running, state != .starting else { return }
        guard AVCaptureDevice.authorizationStatus(for: .video)
                == .authorized else {
            state = .denied
            return
        }
        state = .starting
        configure()
    }

    func stop() {
        if session.isRunning {
            session.stopRunning()
        }
        state = .idle
    }

    /// Points autofocus at the kiosk-screen centre. Called by
    /// `PerceptionCoordinator` once `ScreenTracker` reports a stable
    /// quadrilateral. The POI is in Apple's normalized device space
    /// (0,0 = top-left of the landscape-oriented sensor, 1,1 =
    /// bottom-right); callers must already have mapped from
    /// top-left-origin image coords.
    func setFocusPOI(_ point: CGPoint) {
        guard let input = session.inputs
            .compactMap({ $0 as? AVCaptureDeviceInput }).first else {
            return
        }
        let device = input.device
        guard device.isFocusPointOfInterestSupported,
              device.isFocusModeSupported(.continuousAutoFocus) else {
            return
        }
        do {
            try device.lockForConfiguration()
            device.focusPointOfInterest = point
            device.focusMode = .continuousAutoFocus
            if device.isExposurePointOfInterestSupported,
               device.isExposureModeSupported(.continuousAutoExposure) {
                device.exposurePointOfInterest = point
                device.exposureMode = .continuousAutoExposure
            }
            device.unlockForConfiguration()
        } catch {
            // Lock failures are harmless: focus will continue from
            // its previous POI until the next tick.
        }
    }

    private func configure() {
        session.beginConfiguration()
        session.sessionPreset = .high

        guard let device = AVCaptureDevice.default(
            .builtInWideAngleCamera, for: .video, position: .back)
        else {
            session.commitConfiguration()
            state = .failed("no back camera")
            return
        }
        do {
            let input = try AVCaptureDeviceInput(device: device)
            if session.canAddInput(input) {
                session.addInput(input)
            } else {
                session.commitConfiguration()
                state = .failed("cannot add camera input")
                return
            }
        } catch {
            session.commitConfiguration()
            state = .failed(error.localizedDescription)
            return
        }

        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String:
                kCVPixelFormatType_32BGRA
        ]
        output.setSampleBufferDelegate(self, queue: queue)
        if session.canAddOutput(output) {
            session.addOutput(output)
        } else {
            session.commitConfiguration()
            state = .failed("cannot add video output")
            return
        }
        session.commitConfiguration()

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            self.session.startRunning()
            Task { @MainActor [weak self] in
                self?.state = .running
            }
        }
    }

    nonisolated func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            return
        }
        Task { @MainActor [weak self] in
            self?.latestPixelBuffer = pb
        }
    }
}
