import Foundation
import Combine
import CoreGraphics
import CoreVideo
import KioskVisionCore

/// Runs `FramingAssessor` at ~4 Hz on frames from `CameraSession`,
/// publishes the latest assessment, feeds OCR results through a
/// `TargetTracker`, and emits state-machine events (framing,
/// screen-change, critical-fields) to `AppModel`.
@MainActor
final class PerceptionCoordinator: ObservableObject {

    @Published private(set) var latestAssessment: FramingAssessmentResult?

    private let camera: CameraSession
    private let appModel: AppModel
    private let log: EventLog
    private var ticker: Task<Void, Never>?
    private let thresholds: FramingThresholds = .provisional
    private let assessmentIntervalMs: UInt64 = 250

    // Screen-change detection: two signals, cheapest first.
    // 1. 16-cell luminance signature flips immediately when the
    //    kiosk advances (no OCR needed).
    // 2. OCR-token `ScreenHash` corroborates once the next frame
    //    is OCR'd, so a bright flash alone doesn't trigger.
    private var lastSignature: [Double] = []
    /// Mean absolute per-cell delta above which we call a screen
    /// change. Values roughly track Android's measured ~15/255.
    private let signatureChangeThreshold: Double = 15.0
    private var lastScreenHash: ScreenHash?

    // OCR throttling.
    private let ocrIntervalSec: TimeInterval = 1.0
    /// Longer gap during guidance — the heavy full-frame OCR is
    /// swapped for a smaller anchor-region check that only
    /// corroborates the target still reads correctly.
    private let anchorOcrIntervalSec: TimeInterval = 0.4
    private var lastOCRStartedAt: Date?
    private var lastAnchorOCRAt: Date?
    private var isOCRInFlight = false
    private var cachedControls: [ActionableControl] = []

    private var isAssessing = false

    // Target tracker. Collects corroborated OCR labels across runs
    // so blurred-but-correct reads aren't thrown away.
    private let tracker = TargetTracker()
    /// Everything downstream (labels, screen quad, fingertip) is in
    /// 0..1 normalized frame coords, so the frame "size" the tracker
    /// + scene builder work against is the unit square. A width of
    /// 1 makes `KioskScene.normalisedInFrame` an identity so the
    /// fallback (screen untracked) path doesn't shrink boxes.
    /// Mirrors Android's "frame pixels" convention by substituting
    /// "frame unit" — the math is identical as long as labels and
    /// screen quad share the space, and this way iOS doesn't need
    /// to plumb the real camera resolution through.
    private let nominalFrame = FrameSize(width: 1, height: 1)

    /// Smooths the Vision rectangle across frames, versions the
    /// screen, and flags stale/lost geometry so guidance doesn't
    /// chase ghosts.
    private let screenTracker = ScreenTracker()
    private let motion = MotionMonitor()

    /// Last time we moved the autofocus POI. Prevents churning the
    /// device by re-aiming every tick.
    private var lastFocusPOIAt: Date?
    private let focusPOIInterval: TimeInterval = 1.5

    init(
        camera: CameraSession,
        appModel: AppModel,
        log: EventLog
    ) {
        self.camera = camera
        self.appModel = appModel
        self.log = log
        motion.start()
    }

    deinit {
        // Can't await main actor in deinit; MotionMonitor cleans up
        // its own CM handle when the object is released.
    }

    /// Exposed so UI/debug can read whether the device is steady
    /// (for a status readout) without diving into perception.
    var isDeviceSteady: Bool { motion.isSteady }

    func startIfNeeded() {
        guard ticker == nil else { return }
        ticker = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(
                    nanoseconds: self?.assessmentIntervalMs ?? 250
                        * 1_000_000)
                guard !Task.isCancelled else { return }
                self?.tick()
            }
        }
    }

    func stop() {
        ticker?.cancel(); ticker = nil
    }

    /// Scene snapshot built from the latest corroborated OCR run.
    /// Includes the tracked screen so downstream consumers (critical
    /// field extractor, scene reader, voice-assistant tool outcomes)
    /// can honestly report whether the geometry is still trustworthy.
    func currentScene(nowMs: Int64) -> KioskScene {
        KioskScene.from(
            labels: tracker.corroborated,
            screen: screenTracker.geometry(nowMs: nowMs),
            frameWidth: nominalFrame.width,
            frameHeight: nominalFrame.height,
            nowMs: nowMs)
    }

    private func tick() {
        guard let pb = camera.latestPixelBuffer else { return }
        guard !isAssessing else { return }

        let runHand: Bool
        switch appModel.state {
        case .guidingToTarget: runHand = true
        default:               runHand = false
        }

        let thresholds = self.thresholds
        isAssessing = true
        Task.detached(priority: .userInitiated) { [weak self] in
            let result = FramingAssessor.assess(
                pixelBuffer: pb,
                thresholds: thresholds,
                runTextRecognition: false,
                runHandPose: runHand)
            await MainActor.run { [weak self] in
                self?.consumeAssessment(result, from: pb)
            }
        }
    }

    @MainActor
    private func consumeAssessment(
        _ result: FramingAssessmentResult,
        from pb: CVPixelBuffer
    ) {
        defer { isAssessing = false }

        // Feed the frame's rectangle candidate into the screen
        // tracker and read back the smoothed, versioned geometry.
        let now = Date()
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        screenTracker.onDetection(result.candidate, nowMs: nowMs)
        let tracked = screenTracker.geometry(nowMs: nowMs)

        if let tracked, tracked.stable,
           lastFocusPOIAt.map({ now.timeIntervalSince($0) >= focusPOIInterval })
            ?? true
        {
            let cx = Float(tracked.quad.corners.reduce(0) { $0 + $1.x }) / 4
            let cy = Float(tracked.quad.corners.reduce(0) { $0 + $1.y }) / 4
            camera.setFocusPOI(CGPoint(
                x: CGFloat(cx), y: CGFloat(cy)))
            lastFocusPOIAt = now
        }

        let wantOCR: Bool = {
            switch appModel.state {
            // Keep OCR running while the user composes their goal.
            // Previously stopped at `.listeningGoal`, which left
            // `cachedControls` empty and made both goal matching
            // and `read_screen` silently useless.
            case .acquiringScreen, .listeningGoal,
                 .confirmingGoal, .exploringScreen: return true
            default: return false
            }
        }()
        /// Guiding re-anchors the current target without re-doing
        /// the whole-screen OCR. Mirrors Android's "light OCR" path
        /// in `LiveAssistPipeline` — enough to notice the target
        /// text has drifted out of view, without burning the CPU on
        /// labels that haven't changed.
        let wantAnchorOCR: Bool = {
            if case .guidingToTarget = appModel.state { return true }
            return false
        }()
        let framingOK: Bool = {
            switch result.signals.rectangle {
            case .present: return true
            default: return false
            }
        }()
        // Blur gate: skip OCR when the frame is clearly unsharp.
        // Threshold is empirical and matches the lower bound used
        // by the framing diagnostics; we gate again here because
        // problems may be empty even when the Laplacian is only
        // marginal.
        let sharpEnough: Bool = {
            guard let lv = result.signals.laplacianVariance else {
                return true
            }
            return lv >= thresholds.minLaplacianVariance
        }()
        let readyForOCR =
            wantOCR
            && !isOCRInFlight
            && result.problems.isEmpty
            && framingOK
            && sharpEnough
            && (lastOCRStartedAt
                .map { Date().timeIntervalSince($0) >= ocrIntervalSec }
                ?? true)
        if readyForOCR { kickOffOCR(pixelBuffer: pb) }

        let readyForAnchorOCR =
            wantAnchorOCR
            && !isOCRInFlight
            && framingOK
            && sharpEnough
            && (lastAnchorOCRAt
                .map { Date().timeIntervalSince($0) >= anchorOcrIntervalSec }
                ?? true)
        if readyForAnchorOCR {
            kickOffAnchorOCR(pixelBuffer: pb)
        }

        let published = FramingAssessmentResult(
            signals: result.signals,
            problems: result.problems,
            controls: cachedControls,
            handPose: result.handPose,
            trackedScreen: tracked,
            candidate: result.candidate,
            signature16: result.signature16)
        latestAssessment = published

        if case .acquiringScreen = appModel.state {
            appModel.send(.screenObservation(ScreenObservation(
                screenPresent: framingOK,
                framingProblems: result.problems,
                actionableControls: cachedControls,
                timestamp: Date().timeIntervalSinceReferenceDate)))
        }

        if case .exploringScreen = appModel.state,
           let utterance = appModel.retainedGoalUtterance,
           !cachedControls.isEmpty,
           let match = GoalMatcher.match(
                utterance: utterance, controls: cachedControls)
        {
            log.log("goal matched → \(match.control.label)")
            appModel.send(.targetLocked(match.control.id))
        }

        let watchStates: Bool
        switch appModel.state {
        case .exploringScreen, .guidingToTarget: watchStates = true
        default: watchStates = false
        }

        // Fast path: 16-cell luminance signature. Flips the instant
        // the kiosk advances; works without waiting for a fresh OCR
        // run. Hand mask excludes cells covered by the fingertip so
        // reaching for a button does not look like a screen change.
        if watchStates {
            let current = result.signature16
            if !current.isEmpty, !lastSignature.isEmpty {
                let handCenter = result.handPose.indexTipNormalized
                let distance = ImageStatistics.signatureDistance(
                    current, lastSignature,
                    handCenter: handCenter)
                if distance >= signatureChangeThreshold {
                    log.log("screen transition (lum sig Δ=\(distance))")
                    appModel.send(.screenTransitionDetected)
                }
            }
            if !current.isEmpty { lastSignature = current }
        }

        // Corroborating signal: OCR-token hash. Slower (needs a
        // fresh OCR run) but catches cases where luminance is
        // similar yet the labels have changed (menu → sub-menu).
        if watchStates, !cachedControls.isEmpty {
            let currentHash = ScreenHash.of(controls: cachedControls)
            if let prior = lastScreenHash,
               currentHash.significantlyDifferent(from: prior)
            {
                log.log("screen transition (OCR hash)")
                appModel.send(.screenTransitionDetected)
            }
            lastScreenHash = currentHash
        }
        if case .idle = appModel.state {
            lastScreenHash = nil
            lastSignature = []
        }

        if case .guidingToTarget = appModel.state,
           let readout = CriticalFieldExtractor.extract(
            from: cachedControls)
        {
            log.log("critical fields → "
                    + "\(readout.amountNormalized) / "
                    + "\(readout.recipient)")
            appModel.send(.criticalFieldsReady(readout))
        }
    }

    private func feedTracker(controls: [ActionableControl]) {
        // Store label bboxes in 0..1 normalized frame coords — the
        // same space the screen quad lives in — so the homography
        // in `KioskScene.from(screen:)` maps labels to screen space
        // correctly. The previous version multiplied by 1000 to
        // mimic pixel coords, but the screen quad was in 0..1, so
        // the two spaces didn't match and screen-relative bounds
        // came out garbled.
        let w: Float = 1
        let h: Float = 1
        let blocks = controls.map { ctrl in
            DetectedTextBlock(
                text: ctrl.label,
                boundingBox: TextBox(
                    left: Float(ctrl.normalizedBBox.minX) * w,
                    top: Float(ctrl.normalizedBBox.minY) * h,
                    right: Float(ctrl.normalizedBBox.maxX) * w,
                    bottom: Float(ctrl.normalizedBBox.maxY) * h),
                confidence: Float(ctrl.isActionableConfidence))
        }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        _ = tracker.onOcrResult(
            blocks: blocks,
            frame: nominalFrame,
            nowMs: now,
            jobStartedAtMs: now)
    }

    private func kickOffOCR(pixelBuffer: CVPixelBuffer) {
        isOCRInFlight = true
        lastOCRStartedAt = Date()
        Task.detached(priority: .utility) { [weak self] in
            let controls: [ActionableControl] = {
                do {
                    let observations = try TextRecognizer.detect(
                        in: pixelBuffer)
                    return ControlClassifier.classify(
                        textObservations: observations)
                } catch {
                    return []
                }
            }()
            await MainActor.run { [weak self] in
                guard let self else { return }
                self.cachedControls = controls
                self.feedTracker(controls: controls)
                self.isOCRInFlight = false
            }
        }
    }

    /// Lightweight re-OCR run while guiding: same engine as the
    /// full-screen path, but we only feed the result into the
    /// `TargetTracker` so the target anchor stays fresh. We do
    /// **not** replace `cachedControls`, which would wipe non-target
    /// labels that may still be needed for `read_screen` and
    /// critical-field extraction. Mirrors Android's "light OCR"
    /// mode in `LiveAssistPipeline`.
    private func kickOffAnchorOCR(pixelBuffer: CVPixelBuffer) {
        isOCRInFlight = true
        lastAnchorOCRAt = Date()
        Task.detached(priority: .utility) { [weak self] in
            let controls: [ActionableControl] = {
                do {
                    let observations = try TextRecognizer.detect(
                        in: pixelBuffer)
                    return ControlClassifier.classify(
                        textObservations: observations)
                } catch {
                    return []
                }
            }()
            await MainActor.run { [weak self] in
                guard let self else { return }
                self.feedTracker(controls: controls)
                self.isOCRInFlight = false
            }
        }
    }
}
