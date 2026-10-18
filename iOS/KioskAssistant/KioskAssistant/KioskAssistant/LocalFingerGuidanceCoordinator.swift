import Foundation
import Combine
import CoreGraphics
import KioskVisionCore

/// Drives the local finger-guidance pipeline:
/// `PerceptionCoordinator` (hand pose) → `DirectionalGuidanceEngine`
/// → `GuidanceAnnouncer` → `SpeechSynthesizer`.
@MainActor
final class LocalFingerGuidanceCoordinator {

    private let model: AppModel
    private let perception: PerceptionCoordinator
    private let speech: SpeechSynthesizer
    private let audio: AudioOutputCoordinator
    private let engine = DirectionalGuidanceEngine()
    private let announcer = GuidanceAnnouncer()
    /// Smooths the fingertip in 0..1 screen coords before guidance.
    /// Mirrors Android `assist/OneEuroFilter.kt`: strong smoothing
    /// when the finger is still (removes landmark jitter), little
    /// smoothing when it moves fast (no lag in the direction cue).
    private let fingertipFilter = OneEuroFilter2D(
        minCutoffHz: 1.0, beta: 1.5, derivativeCutoffHz: 1.0)
    private var cancellables: Set<AnyCancellable> = []
    private var activeTargetId: TargetId?
    /// Last "why guidance is unavailable" cue id spoken, so we don't
    /// repeat the same reason every tick. Reset when the reason
    /// changes or guidance resumes.
    private var lastUncertaintyId: String?

    init(
        model: AppModel,
        perception: PerceptionCoordinator,
        speech: SpeechSynthesizer,
        audio: AudioOutputCoordinator
    ) {
        self.model = model
        self.perception = perception
        self.speech = speech
        self.audio = audio
    }

    func start() {
        model.$state
            .sink { [weak self] state in
                self?.onStateChange(state)
            }
            .store(in: &cancellables)
        perception.$latestAssessment
            .sink { [weak self] result in
                self?.onAssessment(result)
            }
            .store(in: &cancellables)
    }

    func stop() {
        cancellables.removeAll()
        engine.reset()
        announcer.reset()
    }

    private func onStateChange(_ state: SessionState) {
        if case let .guidingToTarget(id) = state {
            activeTargetId = id
            engine.reset()
            announcer.reset()
            fingertipFilter.reset()
            lastUncertaintyId = nil
            if let control = perception.latestAssessment?
                .controls.first(where: { $0.id == id })
            {
                audio.speak(CuePolicy.guidingCue(for: control))
            }
        } else {
            activeTargetId = nil
            engine.reset()
            announcer.reset()
            fingertipFilter.reset()
            lastUncertaintyId = nil
        }
    }

    private func onAssessment(
        _ result: FramingAssessmentResult?
    ) {
        guard let result, let id = activeTargetId,
              let control = result.controls.first(where: {
                  $0.id == id
              })
        else { return }
        guard case .guidingToTarget = model.state else { return }

        let now = Int64(Date().timeIntervalSince1970 * 1000)

        // Both the target bbox and the fingertip are mapped through
        // the tracked screen's homography into **canonical screen
        // space** (unit square over the kiosk screen) before being
        // fed to the engine. This matches Android's
        // `DirectionalGuidanceEngine` contract: the engine is
        // space-agnostic as long as its inputs share a space, and
        // screen space is what makes "left of the button" mean the
        // same thing regardless of phone angle. Frame-space inputs
        // only match when the phone faces the screen squarely.
        let frameBox = TextBox(
            left: Float(control.normalizedBBox.minX),
            top: Float(control.normalizedBBox.minY),
            right: Float(control.normalizedBBox.maxX),
            bottom: Float(control.normalizedBBox.maxY))
        let targetBox: TextBox = {
            guard let screen = result.trackedScreen,
                  let mapped = ScreenSpace.toScreen(
                    screen: screen, box: frameBox)
            else { return frameBox }
            return mapped
        }()
        let target = TrackedTarget(
            label: control.label,
            normalizedLabel: control.label.lowercased(),
            box: targetBox,
            isCritical: false,
            confirmations: TargetTracker.requiredConfirmations,
            missedRuns: 0,
            isEstimated: false,
            anchoredAtMs: now)

        let fingertip: FingertipPoint? = {
            guard let tip = result.handPose.indexTipNormalized,
                  result.handPose.confidence
                    >= Double(HandPose.minUsableConfidence)
            else { return nil }
            // Pre-smooth with OneEuroFilter2D. Without this, Vision's
            // raw per-frame fingertip jitters by a few pixels and the
            // guidance engine fires conflicting direction cues when
            // the finger is actually still.
            let (sx, sy) = fingertipFilter.filter(
                px: Float(tip.x), py: Float(tip.y),
                timestampMs: now)
            // Map through the same screen homography so fingertip
            // and target are in matching coord space.
            let framePoint = ScreenPoint(x: sx, y: sy)
            let screenPoint: ScreenPoint = {
                guard let screen = result.trackedScreen,
                      let mapped = ScreenSpace.toScreen(
                        screen: screen, point: framePoint)
                else { return framePoint }
                return mapped
            }()
            return FingertipPoint(
                x: screenPoint.x, y: screenPoint.y,
                confidence: Float(result.handPose.confidence))
        }()
        if fingertip == nil {
            fingertipFilter.reset()
        }

        // Honest geometry/motion flags: directional guidance must
        // stop when either the phone is swinging or the screen quad
        // is unknown/stale. Guessing in those windows walks the
        // user's finger toward where the screen used to be.
        let out = engine.update(GuidanceInput(
            timestampMs: now,
            target: target,
            fingertip: fingertip,
            space: .kioskScreen,
            deviceSteady: perception.isDeviceSteady,
            screenTracked: result.trackedScreen?.stable == true))

        guard let spoken = announcer.onDirection(
            out.direction, nowMs: now)
        else { return }

        if spoken != .targetUncertain { lastUncertaintyId = nil }

        if let cue = fingerCue(for: spoken,
                               unavailable: out.unavailable)
        {
            // Suppress duplicate uncertainty reason within one spell
            // so the user doesn't hear the same explanation every
            // few seconds while nothing has changed.
            if spoken == .targetUncertain {
                if cue.id == lastUncertaintyId { return }
                lastUncertaintyId = cue.id
            }
            audio.speak(cue)
        }
    }

    /// Maps a direction + the engine's "why can't I guide" reason to
    /// a specific localized cue. Previously `.targetUncertain`
    /// returned nil and the user heard nothing while the geometry
    /// was stale or the screen was out of view — they had no idea
    /// what to fix.
    private func fingerCue(
        for direction: GuidanceDirection,
        unavailable: GuidanceUnavailable?
    ) -> SpokenCue? {
        switch direction {
        case .left:
            return SpokenCue(
                id: "finger.left",
                text: String(localized: "speak_direction_left"),
                priority: .navigation)
        case .right:
            return SpokenCue(
                id: "finger.right",
                text: String(localized: "speak_direction_right"),
                priority: .navigation)
        case .up:
            return SpokenCue(
                id: "finger.up",
                text: String(localized: "speak_direction_up"),
                priority: .navigation)
        case .down:
            return SpokenCue(
                id: "finger.down",
                text: String(localized: "speak_direction_down"),
                priority: .navigation)
        case .nearTarget:
            return SpokenCue(
                id: "finger.near",
                text: String(localized: "speak_direction_near"),
                priority: .navigation)
        case .onTarget:
            return SpokenCue(
                id: "finger.on",
                text: String(localized: "speak_direction_on_target"),
                priority: .high)
        case .handNotDetected:
            return SpokenCue(
                id: "finger.hand",
                text: String(localized: "speak_hand_not_detected"),
                priority: .high)
        case .targetUncertain:
            // Pick the message that matches the actual reason the
            // engine refused to give a direction. Mirrors Android's
            // `messageForUncertainty(output.unavailable)`.
            switch unavailable {
            case .screenNotTracked:
                return SpokenCue(
                    id: "finger.uncertain.screen",
                    text: String(localized: "speak_screen_not_tracked"),
                    priority: .high)
            case .geometryStale:
                return SpokenCue(
                    id: "finger.uncertain.stale",
                    text: String(localized: "speak_screen_out_of_focus"),
                    priority: .high)
            case .unconfirmed:
                return SpokenCue(
                    id: "finger.uncertain.unconfirmed",
                    text: String(localized: "speak_target_uncertain"),
                    priority: .normal)
            case .noTarget:
                return SpokenCue(
                    id: "finger.uncertain.lost",
                    text: String(localized: "speak_target_position_lost"),
                    priority: .high)
            case .none:
                return nil
            }
        }
    }
}
