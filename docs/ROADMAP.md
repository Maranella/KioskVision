# Roadmap

Ordered development plan. Highest-risk experiments come first. Nothing on
this roadmap is approved for implementation until the user approves it
explicitly, milestone by milestone.

**Estimates** are rough ranges in engineer-days, assuming one experienced
iOS developer with intermittent CV help. They assume access to a real
target kiosk for at least a few test sessions.

---

## Phase 0 — Initialization (current)

**Goal.** Documentation and a shared understanding of risks.

**Deliverables.**
- `CLAUDE.md`, `docs/*.md` (this set).
- Initial research findings on the six technical risks (done, in
  `SPIKES_AND_DECISIONS.md`).

**Verification.** User reviews documents, approves next phase.

**Risks.** None material at this stage.

**Estimate.** 0–1 d (done this session).

---

## Phase 1 — Spike-01: Finger-guidance feasibility

**Goal.** Decide whether iPhone-camera-based finger-to-target guidance is
accurate enough to safely proceed, and whether LiDAR is required.

**Dependencies.** Access to one iPhone Pro (with LiDAR), one tablet (iPad
or Android) acting as a mock kiosk, printable fiducial markers.

**Deliverables.**
- A single-screen SwiftUI + ARKit app that: detects fiducial markers,
  builds a screen plane, runs `VNDetectHumanHandPoseRequest`, projects the
  index tip onto the plane using (a) ray-plane intersection and (b)
  LiDAR-assisted perpendicular foot.
- A recorded measurement run across 30+ trials, logged to CSV.
- A short written report with the median error for each method and a
  recommendation on whether to require LiDAR.

**Acceptance criteria (for Spike-01 itself).**
- Measurement data is reproducible from the included dataset.
- The report commits to a go/no-go decision on finger guidance, or clearly
  identifies the next experiment needed before a decision.

**Verification method.** Physical measurement with ground-truth target
positions printed on paper under the tablet.

**Risks.**
- Vision hand pose foreshortening at near-screen poses may be worse than
  expected.
- Target kiosks have glossy surfaces that may defeat LiDAR; this spike
  uses a tablet instead and so may be optimistic. Follow-on measurement
  on a real kiosk is still required before M2 completion.

**Estimate.** 2–4 d.

---

## Phase 2 — Spike-02: Georgian STT real-world measurement

**Goal.** Validate that one of our provider candidates (Azure Speech
primary) delivers acceptable Georgian command recognition in a noisy
realistic environment, and quantify the gap vs. a Whisper on-device
baseline.

**Dependencies.** Azure Speech trial account, 3 native Georgian speakers
willing to record a 30-command set.

**Deliverables.**
- A small iOS test harness that records a fixed vocabulary via push-to-talk
  and sends audio to each candidate STT engine.
- A labeled test set of 30 commands × 3 speakers × 2 environments
  (quiet / café-level noise).
- WER + per-command accuracy report.

**Acceptance criteria.**
- Decision locked in `SPIKES_AND_DECISIONS.md` on the primary STT provider
  with measured evidence.

**Risks.** Georgian accents and code-switching with English loanwords may
degrade all engines more than expected. Pre-register "no engine is
acceptable" as a possible outcome.

**Estimate.** 2–3 d (excluding scheduling testers).

---

## Phase 3 — Spike-03: Georgian TTS listening evaluation

**Goal.** Confirm that Piper `natia-medium` on-device and Azure neural
voices are intelligible to blind native Georgian speakers for both short
cues and long readouts.

**Dependencies.** 2–3 native Georgian speakers (blind participants
preferred) willing to listen and rate.

**Deliverables.**
- 20 reference utterances covering directional cues, confirmation
  prompts, and numeric amounts.
- A short listening evaluation protocol with pass/fail per utterance.
- A written recommendation.

**Acceptance criteria.** ≥ 95 % pass rate for critical utterances
(amounts, recipient names) on the chosen voice.

**Risks.** On-device Piper is a single speaker, medium tier; it may be
borderline. Have the Azure stack as fallback.

**Estimate.** 2 d.

---

## Phase 4 — Spike-04: Georgian OCR on real kiosk frames

**Goal.** Validate OCR accuracy on real-world kiosk photos under glare,
oblique angles, and typical fonts. Decide between Tesseract-only vs
hybrid with cloud fallback.

**Dependencies.** Permission and physical access to at least one TBC Pay
kiosk; iPhone capture of 30+ screens across 5+ environmental conditions.

**Deliverables.**
- A labeled dataset of real kiosk frames.
- A measurement harness running Tesseract (`kat.traineddata`) with and
  without perspective correction, scored against ground truth.
- Optional: comparison with Google Cloud Vision on a small subset
  (requires acknowledgement that frames leave the device for this test
  only).
- A decision on the OCR stack.

**Acceptance criteria.** CER (character error rate) on actionable button
labels ≤ 5 % (provisional) on the chosen path.

**Risks.** Tesseract on Mkhedruli has not been benchmarked for kiosk
fonts specifically. If quality is too low, fine-tuning becomes a Phase 5
task.

**Estimate.** 3–5 d.

---

## Phase 5 — Milestone M1 implementation

Implements and verifies M1 as defined in `MVP_AND_ACCEPTANCE_CRITERIA.md`.
Depends on Spikes 01 and 04 being resolved.

**Estimate.** 3–5 d.

---

## Phase 6 — Milestone M2 implementation

Finger guidance to a single known target. Depends on Spike-01 being
resolved favorably. If Spike-01 fails to meet accuracy criteria on real
kiosks, this phase is suspended and we pivot (see "Pivot options" below).

**Estimate.** 5–8 d.

---

## Phase 7 — Milestone M3 implementation

Full control enumeration and classification on target screens. Depends on
Spike-04.

**Estimate.** 5–8 d.

---

## Phase 8 — Milestone M4 implementation

Georgian STT/TTS wired into the full session loop. Depends on Spikes 02
and 03.

**Estimate.** 4–6 d.

---

## Phase 9 — Milestone M5 implementation

Multi-screen navigation with retained goal. Depends on M1–M4.

**Estimate.** 5–8 d.

---

## Phase 10 — Milestone M6 implementation

Critical-field confirmation. Depends on M1–M5. Must include an
accessibility-user usability test before being called complete.

**Estimate.** 5–8 d.

---

## Phase 11 — Field pilot

A supervised pilot with real blind users at a small number of real kiosks.
Not a public release. The outcome informs the next product cycle.

**Estimate.** 2–3 weeks elapsed, with external coordination driving the
range.

---

## Pivot options if a critical spike fails

- **If Spike-01 fails on real kiosks even with LiDAR:** consider a
  stylus-with-fiducial accessory, or constrain phone-hold geometry with
  spoken coaching, or scope V1 down to kiosks with physical buttons /
  tactile overlays only. All of these require re-planning.
- **If Spike-02 fails for all candidates:** evaluate a Georgian-specific
  wav2vec2 fine-tune (research-grade, open-weights). If no engine is
  usable, descope STT and use a minimal spoken command set with
  earcon-driven interaction. (This is a major product change.)
- **If Spike-03 fails:** evaluate a licensed Georgian voice-over actor
  recording a canned-phrase library and sacrifice dynamic amounts /
  recipient readouts in V1 (large product impact — probably means
  descoping M6).
- **If Spike-04 fails:** invest in fine-tuning or move to a cloud OCR
  with the privacy trade-off, with explicit user consent.

All pivots require user approval before being executed.
