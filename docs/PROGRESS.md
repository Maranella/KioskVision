# Progress

**Current phase.** Phase 0 — Initialization and research.

**Date of last update.** 2026-10-14.

## Completed

- Repository documentation structure established:
  `CLAUDE.md`, `docs/PRODUCT_SPEC.md`, `docs/MVP_AND_ACCEPTANCE_CRITERIA.md`,
  `docs/TECHNICAL_ARCHITECTURE.md`, `docs/ROADMAP.md`,
  `docs/SPIKES_AND_DECISIONS.md`, `docs/SAFETY_AND_PRIVACY.md`,
  `docs/TEST_STRATEGY.md`, this file.
- Initial research pass on the six risk areas. Findings and provisional
  ADRs recorded in `SPIKES_AND_DECISIONS.md` (R-01 through R-10;
  ADR-001 through ADR-007, all `proposed`).

## In progress

- Nothing. Awaiting user review and approval of Phase 1 (Spike-01).

## Verified results (what we actually know as fact right now)

- `ka-GE` is **not** in Apple's native STT / TTS / OCR support in current
  shipped iOS, based on authoritative public sources and prior knowledge.
  Must be re-verified in-code before locking (one-line check for each).
- Microsoft Azure Speech lists `ka-GE` for STT and two neural TTS voices.
  Source checked 2026-10-14:
  <https://learn.microsoft.com/en-us/azure/ai-services/speech-service/language-support>.
- Open-source Georgian stacks exist (Tesseract `kat.traineddata`, Piper
  `ka_GE natia-medium`, Whisper tokenizer includes `ka`). Subjective
  quality on our targets is **unverified**.

## Hypotheses to validate (not yet verified)

- Fingertip-landing error can be reduced below ~0.7 cm 1-sigma using
  LiDAR + screen-plane homography on iPhone Pro. Validate in Spike-01.
- Azure Speech delivers ≥ 90 % command recognition on a 30-command
  vocabulary in a quiet environment with native speakers. Validate in
  Spike-02.
- Piper `natia-medium` on-device is intelligible to blind native Georgian
  speakers for directional cues and numeric amounts. Validate in Spike-03.
- Tesseract 5 with `kat.traineddata` achieves ≤ 5 % CER on real kiosk
  frames after perspective rectification. Validate in Spike-04.

## Blockers

- **Access to a target kiosk.** Spike-04 and later milestones need
  physical access to a TBC Pay kiosk for capture and testing. Scheduling
  and (ideally) a non-public test environment are needed. Not an
  engineering blocker yet, but will become one before Phase 7.
- **Native Georgian testers.** Spikes 02 and 03 require 2–3 native
  Georgian speakers (blind participants preferred for Spike-03).

## Outstanding product questions

See `PRODUCT_SPEC.md §7`. The ones most likely to affect near-term work:

- U-06 — Confirming iPhone Pro / LiDAR as the baseline device (ADR-001
  proposed, awaiting Spike-01).
- U-08 — Which kiosk is the second supported model, if any, in V1.

## The single recommended next task

**Run Spike-01 (finger-guidance feasibility experiment).**

This is the highest-risk assumption in the entire product. All downstream
milestones that depend on finger guidance (M2, M5, M6) are gated by its
outcome. The experiment is cheap (2–4 engineer-days), scoped to a tablet-
as-fake-kiosk setup (no real kiosk access required), and produces a clear
go/no-go signal.

Approval required before starting. See `ROADMAP.md` Phase 1 for the full
deliverable and acceptance criteria.

## Exact steps to reproduce the Spike-01 setup when approved

1. Procure materials:
   - One iPhone 15 Pro or newer with LiDAR.
   - One tablet (iPad or Android, 10"+) to display a mock kiosk screen.
   - Printed sheet of 4 ArUco markers (6×6, ~3 cm) to tape to the four
     corners of the tablet.
   - A printed grid of 20 target dots (2 cm × 2 cm each) displayed on the
     tablet at known pixel coordinates (export as PNG at known DPI).
2. Create a new Xcode project. Minimum iOS 17.
3. Enable ARKit + camera entitlement. Request camera permission.
4. Implement a single-view SwiftUI app that:
   - Runs an ARKit session with `.sceneDepth` enabled.
   - Each frame: detects the four ArUco markers via a Core ML / OpenCV
     bridge (any suitable fiducial detector will do).
   - Computes the screen-plane homography from the four marker centers.
   - Runs `VNDetectHumanHandPoseRequest`; takes `.indexTip` and its
     confidence.
   - Method A: ray-plane intersection of the camera ray through
     `.indexTip` with the detected plane.
   - Method B: LiDAR `sceneDepth` sampled at the index-tip pixel,
     projected to 3D, then dropped perpendicular onto the plane.
   - Overlays the predicted landing points on screen for the tester to
     see, and logs them to a CSV along with the tablet's "ground truth"
     target coordinate.
5. Measurement protocol:
   - 3 testers (sighted; eyes may remain open for Spike-01 since we are
     measuring geometry, not UX).
   - Each tester touches each of the 20 targets twice (one slow, one
     quick).
   - Log each sample with: trial id, target id, method A prediction,
     method B prediction, hand-pose confidence, LiDAR depth sample,
     frame timestamp.
6. Analysis:
   - Per method, compute median and 95th-percentile error in
     screen-plane coordinates (converted to cm using the known tablet
     DPI).
   - Report in `SPIKES_AND_DECISIONS.md` under Spike-01, including raw
     CSV path in the repo (gitignored if large).
7. Decision:
   - If Method B achieves median ≤ 1 cm and 95th percentile ≤ 2 cm on
     the tablet, Spike-01 passes. Next: schedule a follow-on measurement
     on a real glossy kiosk screen before M2 completion.
   - If Method B fails to meet those thresholds on a tablet, Spike-01
     fails and we revisit the pivot options in `ROADMAP.md`.
