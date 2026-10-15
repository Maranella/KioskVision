# Progress

**Current phase.** Android implementation. No milestone in
`MVP_AND_ACCEPTANCE_CRITERIA.md` has passed its acceptance criteria yet.

**Date of last update.** 2026-10-16.

**Scale.** ~5,800 lines of Kotlin under `android/`; 130 unit tests and 15
instrumented tests, all passing. Measurements recorded as R-11 through R-20.
Last commit `e509c1b`; the voice work below is in the working tree, uncommitted.

## Completed

- Repository documentation structure established:
  `CLAUDE.md`, `docs/PRODUCT_SPEC.md`, `docs/MVP_AND_ACCEPTANCE_CRITERIA.md`,
  `docs/TECHNICAL_ARCHITECTURE.md`, `docs/ROADMAP.md`,
  `docs/SPIKES_AND_DECISIONS.md`, `docs/SAFETY_AND_PRIVACY.md`,
  `docs/TEST_STRATEGY.md`, this file.
- Initial research pass on the six risk areas. Findings and provisional
  ADRs recorded in `SPIKES_AND_DECISIONS.md` (R-01 through R-10;
  ADR-001 through ADR-007, all `proposed`).

- **Android camera foundation** (`android/`, ADR-008):
  - Launch from the launcher or via the system assistant ("Hey Google,
    open KioskVision") — standard `MAIN`/`LAUNCHER` activity, no extra
    integration.
  - CameraX rear-camera live preview, lifecycle-bound (released in the
    background, single binding after resume and rotation).
  - Accessible camera-permission flow: explanation screen before the
    system dialog, denied / permanently-denied recovery via Settings,
    Georgian strings (`values-ka`), TalkBack semantics.

## In progress

- **Live-assist stabilization, OCR scheduling, and motion tolerance**
  (`android/`, 2026-10-14). Three rounds of work on the same pipeline:
  - the target is persistent state that only an explicit decision retires
    (new selection, confirmed screen transition, user rescan or stop) — an
    empty, blurred or hand-covered OCR run never discards it;
  - while a target is held, its position is refreshed by **region OCR**
    around it (~34 ms, 13x cheaper than a full run — R-11); full-frame OCR
    runs only on a suspected transition, an explicit rescan, or a defined
    stale-anchor fallback;
  - the hand is masked out of the screen-change comparison and the
    focus/sharpness check, so reaching for a button is not mistaken for a
    new screen or a lens going soft;
  - **a label may be accepted below the single-frame confidence gate when
    two or more recent runs read it the same way at the same place** (R-12).
    Tesseract returns the right Georgian string at 0.20 confidence under 4 px
    of motion blur, and the old gate of 0.60 discarded it, which is what made
    the app claim `გადახდა` was not there whenever the phone moved;
  - a request that cannot be confirmed is **held open for 6 s** while better
    frames are sought, and the user is asked to hold the phone still, instead
    of being told the button does not exist;
  - autofocus is pointed at the kiosk screen once and held.

  Tests: **130 unit** plus **15 instrumented** on a physical device
  (Samsung SM-S731B, Android 16, serial `R5GL75BE33T`).
  Measurements recorded as R-11 through R-17.

- **Georgian voice via Gemini Live** (`android/`, 2026-10-16, uncommitted).
  A debug-only spike (`VoiceSpikeActivity`, its own launcher icon in debug
  builds) measuring whether Gemini Live can carry a Georgian session:
  - `gemini-3.8-live` adopted after the 2.5-generation identifiers in the
    official sample were rejected by the server as retired, and
    `gemini-3.1-flash-live-preview` failed input language detection (R-18);
  - the model's own voice was re-entering the microphone and being
    transcribed as the user, which produced self-greeting loops; fixed with
    `VOICE_COMMUNICATION` capture and playback routing. **Barge-in is
    currently disabled** as a diagnostic measure and must be restored (R-18);
  - a Georgian kiosk vocabulary is supplied in the system instruction, the
    only lever the SDK exposes over input interpretation. It corrected
    "tenisi" to "deni" and did **not** cause false positives on an
    off-domain probe (R-19);
  - `inputAudioTranscription` is a separate, unreliable channel - it returns
    Latin transliteration and, at times, Spanish, German, Turkish or
    Devanagari, while the model's comprehension stays correct. **Evaluation
    must score responses, not transcripts** (R-19, R-20).

  **A safety defect was found and fixed here (R-20).** Asked for forty lari,
  the assistant announced that forty lari had been received, with no kiosk
  present. It had adopted the role of the machine and invented a transaction.
  Two rounds of prompt hardening stopped it and left the assistant reflecting
  the user's words back, useless. That trade-off is why **ADR-009** proposes
  the structural fix: deny the voice layer any screen data, keep every screen
  claim inside the closed `SpokenMessage` vocabulary, and arbitrate the two
  audio sources. ADR-009 is **proposed, not built**.

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

- Live assist — **the full scenario is verified on a physical device
  through rendered frames, not through the camera.** (Originally run on
  handset `A142`, which is no longer available; the current device is
  Samsung SM-S731B.)
  `LiveAssistScenarioTest` drives the real pipeline with real Tesseract and
  real MediaPipe and passes all eight steps: acquisition, selecting
  `გადახდა`, the target surviving its label being covered by a hand,
  fingertip tracking (within 7 px of the drawn tip at 0.99 confidence),
  `LEFT` / `UP` directions matching the finger's position, `ON_TARGET` on
  arrival, a confirmed transition retiring the old target, and reacquisition.

- Motion tolerance — **measured, not yet exercised by hand.** Recognition of
  `გადახდა` across 12 randomised handheld trials per band rose from 83% to
  100% under light movement and from 58% to 75% under heavy movement, with
  no change when steady (R-12).

- Real-camera behaviour — **partially observed.** Over a ~3 minute session
  with the rear camera on a text-dense real screen (80-114 OCR lines):
  autofocus reported `Locked` in 151 of 182 samples; the session held
  `ScreenReady` throughout; and across 14 samples where the gyroscope showed
  the phone being moved, 175 OCR runs reported `Unchanged` against only 2
  transient `ScreenChangeSuspected`, none confirmed. Small handheld movement
  did not invalidate the screen state. Full-frame OCR on that scene took
  **6.7 s**, which is the cost the region path exists to avoid.

  **Still unverified, and it needs a person holding the phone:** selecting a
  target by Georgian speech, a finger moving in the camera view producing
  spoken directions, and partially covering the label with a finger on the
  real device. Four recording windows were offered; the interaction was not
  performed in any of them. The rendered-frame harness covers the logic but
  has no lens, no depth and no microphone.

- Android foundation — originally verified on the emulator (2026-10-14);
  the pipeline, OCR and hand tracking have since been exercised on the
  physical Samsung SM-S731B.
- **Still not verified on Android:** TalkBack end to end (including whether a
  Georgian TTS voice is installed on the device), voice-assistant launch, real
  camera error paths. A blind user has to be able to open and operate *the
  app* before any of the kiosk work matters, and that path is untested.

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

- **Access to a target kiosk. This is now the active blocker, not a future
  one.** Every measurement in this file comes from rendered frames, synthetic
  harnesses or a desk-side camera session; the app has never been pointed at a
  kiosk. The capture tooling is written and idle. Scheduling and, ideally, a
  non-public test environment are needed.
- **A person to hold the phone.** The interaction at the centre of the product
  - a finger moving in the camera view while spoken Georgian directions guide
  it - has never been performed. Four recording windows were offered and none
  was used. No harness substitutes for this: it has no lens, no depth and no
  microphone.
- **Native Georgian testers.** Spikes 02 and 03 require 2–3 native
  Georgian speakers (blind participants preferred for Spike-03).

## Outstanding product questions

See `PRODUCT_SPEC.md §7`. The ones most likely to affect near-term work:

- U-06 — Confirming iPhone Pro / LiDAR as the baseline device (ADR-001
  proposed, awaiting Spike-01). **Pressing**: the code is Android-only and
  Android has no depth sensor, so ADR-001's premise does not hold for the
  platform actually being built.
- U-08 — Which kiosk is the second supported model, if any, in V1.
- **Privacy: the INTERNET permission.** It had been deliberately removed so
  that camera frames and screen text could not leave the device. The Gemini
  Live work restored it, and voice audio now streams to Google; camera frames,
  OCR text and geometry remain local. This trade-off has been raised with the
  product owner and **is not yet signed off**. See `SAFETY_AND_PRIVACY.md`.
- **`android/app/google-services.json`** is untracked and not in
  `.gitignore`. Whether it is committed needs a decision.

## What stands between here and a usable product

Grouped by what kind of problem each one is, because they are not
interchangeable and the third kind can invalidate work on the first.

### Engineering - known how, not done

- **Kiosk-screen detection.** `camera/FrameGeometry.kt` treats the whole
  camera frame as the screen: no quadrilateral detection, no homography, no
  perspective correction. Product M1's framing diagnoses (`OFF_FRAME`,
  `PARTIAL`, `TOO_FAR`, `OFF_ANGLE`, `GLARE`) do not exist, and every target
  coordinate downstream is frame-relative, so it is wrong whenever the phone
  is not square-on to the screen. Largest software gap.
- **ADR-009** - proposed, unbuilt. Barge-in stays off until it is.
- **Session rollover.** Live sessions end at ~10 min of connection and ~15 min
  total; a real transaction can outlast that. Not implemented.
- **Goal retention across screens** (M5) and **critical-detail readback with
  explicit confirmation** (M6). These *are* "complete an operation
  independently", and neither exists.

### Unknown risk - needs measurement before it can be designed around

- **No real kiosk data at all.** Acceptance criteria call for 20 recorded
  clips and 30 spoken cases. Current totals: 0 clips, ~13 utterances from one
  speaker. `tools/ocr-eval.sh`, `RealKioskEvaluationTest` and
  `docs/DISTANCE_PROTOCOL.md` are built and waiting on kiosk access.
- **Abstention rate.** The safety invariants require abstaining under
  uncertainty, and nobody knows how often that fires at a real kiosk. An app
  that abstains every third attempt is safe and unusable, and no measurement
  distinguishes those two outcomes today.
- **The app's own accessibility.** TalkBack end to end is untested, including
  whether a Georgian TTS voice is present on the device.

### Possibly not solvable on this hardware

- **R-13 and R-07 together.** MediaPipe needs most of the hand in frame and
  roughly 40% of frame width; a fingertip alone is never detected. Reading a
  2 cm label needs the phone close. Seeing the whole hand needs it far. And
  with no depth sensor on Android, finger-to-button distance is not
  observable when the two are at different depths. "Your finger is 3 cm left
  of the button" may not be computable on this device at all.

  No amount of code closes this. The honest resolutions are a hardware change
  or a product change - for example guiding by screen region ("lower right
  quarter") rather than by fingertip.

## The single recommended next task

**Capture the real-kiosk dataset: ~30 annotated photos and short clips at a
TBC Pay kiosk, following `docs/DISTANCE_PROTOCOL.md`.**

Not because it is the biggest piece of work, but because it is the cheapest
thing that can invalidate the others. The third category above is unmeasured,
and every week spent on kiosk-screen detection or ADR-009 is a week that may
be spent building on a geometry the hardware cannot support. The tooling is
already written; this task needs physical kiosk access, not engineering.

Spike-01 (finger-guidance feasibility, `ROADMAP.md` Phase 1) remains the
formal gate for that question and is still unexecuted. Its reproduction steps
below were written for iOS and LiDAR; there is no `ios/` directory, so on
Android the depth method (Method B) has no equivalent and the spike needs
rewriting before it can be run here.

Approval required before starting.

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
