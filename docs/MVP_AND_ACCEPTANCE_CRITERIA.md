# MVP and Acceptance Criteria

The MVP is a sequence of six capability milestones. Each must meet its
acceptance criteria **by measurement**, not by plan. Thresholds marked
**(provisional)** are hypotheses to be validated during the relevant spike;
they are not performance guarantees.

Milestones are sequential. Later milestones depend on earlier ones.

---

## M1 — Detect and assess a known kiosk screen

**Goal.** Given a live camera feed pointed at one of our target kiosks, the
app can (a) decide whether the kiosk screen is adequately framed and (b)
emit a specific, actionable Georgian spoken instruction when it is not.

**Scope.** One kiosk model (TBC Pay, screen captured at a representative
indoor location). No OCR, no interaction, no hand tracking yet.

**Deliverables.**
- iOS target running a camera preview with Vision rectangle detection.
- A framing-assessment module producing one of: `OK`, `OFF_FRAME`,
  `PARTIAL`, `TOO_FAR`, `TOO_CLOSE`, `OFF_ANGLE`, `BLUR`, `GLARE`,
  `LOW_CONFIDENCE`.
- A small set of recorded short Georgian audio instructions (not generated
  yet — pre-rendered via chosen TTS) tied to each diagnosis.

**Acceptance criteria.**
- On 20 recorded video clips covering the above conditions, the module
  outputs the correct dominant diagnosis in ≥ 80 % of clips (provisional).
- Median diagnosis latency ≤ 200 ms on an iPhone Pro (provisional).
- The emitted instruction is specific — never "I cannot see the screen"
  without a reason.

**What counts as failure.**
- Any case where the app says "ok" while the screen rectangle is actually
  missing or truncated.
- Any case where the app loops on the same instruction while the user has
  obeyed it (requires state across frames).

---

## M2 — Recognize a known target and guide a finger to it

**Goal.** On a target kiosk screen whose layout is known, the app can
guide a user's finger to one named button using spoken directional cues.

**Scope.** One kiosk, one screen, one target button. On iPhone Pro with
LiDAR assumed available for the core experiment (ADR-001 provisional).

**Deliverables.**
- `Perception.handPose` + `Perception.fingertipToScreenProjector` from the
  architecture doc, wired to the state machine.
- Cue planner emitting Georgian spoken cues ("ზევით" up, "ქვევით" down,
  "მარცხნივ" left, "მარჯვნივ" right, "შეჩერდი" stop, "დააჭირე" press).
- A small lab setup (ArUco markers around a tablet acting as a mock kiosk)
  for calibration and measurement.

**Acceptance criteria.**
- Across 30 trials with 3 testers (sighted, eyes closed), ≥ 80 %
  (provisional) of trials result in the fingertip landing within the
  2 cm × 2 cm target when the app says "press".
- The app says "press" at least once per trial.
- The app **never** says "press" when the recorded landing was outside the
  target by more than the agreed tolerance (false-positive rate = 0 in the
  measured set — this is a safety criterion, not a performance criterion).
- End-to-end cue loop ≤ 150 ms on device (provisional).

**What counts as failure.**
- Any single trial where "press" led to a landing more than 2 cm from the
  target center.
- Oscillating/contradictory cues more than once per trial.

---

## M3 — Read and classify functional choices on selected screens

**Goal.** On recorded frames of our target kiosks, the app enumerates the
actionable functional choices and distinguishes them from decorative /
advertising / informational text.

**Scope.** 5–10 representative screens from one kiosk model.

**Deliverables.**
- Georgian OCR integration (ADR-004 — Tesseract `kat` provisional).
- Control candidate classifier (rectangle + text + heuristics).
- A ground-truth annotation set for the chosen screens (manually labeled
  actionable regions).

**Acceptance criteria.**
- Precision of "actionable" classification ≥ 0.9 (provisional) on the
  labeled set. (Precision matters more than recall: we must not list
  phantom buttons.)
- Recall ≥ 0.75 (provisional).
- Zero fabricated controls (controls listed that do not correspond to any
  real on-screen region).

**What counts as failure.**
- Any output that lists a label from an advertisement as a button.
- Precision < 0.9 on the labeled set.

---

## M4 — Georgian spoken commands and spoken instructions

**Goal.** The user can speak short Georgian commands and the app
understands them with acceptable latency; the app speaks Georgian
instructions back with acceptable quality.

**Scope.** A fixed vocabulary of ~30 commands (control verbs, payment
categories, numeric amounts, common provider names).

**Deliverables.**
- STT integration (ADR-002 — Azure Speech `ka-GE` provisional).
- TTS integration (ADR-003 — Piper on-device + Azure cloud, provisional).
- Push-to-talk UI with VoiceOver-friendly controls.
- Georgian number normalization (vigesimal → digits).

**Acceptance criteria.**
- Command recognition accuracy ≥ 90 % (provisional) in a quiet room with
  3 native Georgian speakers across 5 trials per command.
- Command recognition accuracy ≥ 75 % (provisional) in a noisy public
  recording (café-level background).
- Median time from end-of-utterance to interpreted intent ≤ 1.5 s with
  cloud STT on 4G (provisional).
- Georgian TTS output is intelligible to native speakers in a listening
  test (binary pass/fail per utterance, ≥ 95 % pass rate on 20
  instructions).

**What counts as failure.**
- Any command misinterpretation that could cause a wrong-action press
  without a confirmation step catching it.
- TTS output that native speakers mark as unintelligible for critical
  numeric or recipient readouts.

---

## M5 — Navigate between screens while retaining a user-defined goal

**Goal.** The app can carry a goal across kiosk screen transitions,
re-assessing each screen rather than assuming the predicted next screen
appeared.

**Scope.** A 3-screen flow on the target kiosk (home → category →
provider list, for example).

**Deliverables.**
- Screen-change detector.
- Session state machine with `recovering_from_transition`.
- Target re-selection on every new leg.

**Acceptance criteria.**
- On recorded transitions (hand triggers press on a tablet simulating
  kiosk), the app detects every transition within 1.5 s (provisional).
- The retained goal correctly selects a target on each of the 3 screens in
  ≥ 90 % of 20 runs.
- The app detects a simulated "unexpected screen" (we substitute a
  different screen) and asks the user rather than guessing, in 100 % of
  trials.

**What counts as failure.**
- Any case where the app guides the user as if the previous screen were
  still showing after a transition.
- Any case where the app proceeds confidently on an unexpected screen.

---

## M6 — Read and verify critical details before user-controlled confirmation

**Goal.** Before any irreversible action, the app reads out the critical
fields (amount, recipient, visible fee) in Georgian, obtains explicit
affirmative, and only then guides the user to the physical confirm
button.

**Scope.** A simulated final-confirmation screen with a known amount and
recipient.

**Deliverables.**
- Critical-field extractor on a confirmation screen (amount + recipient
  OCR with validated confidence).
- Normalized spoken number readout.
- Affirmative-only confirmation flow (silence / ambiguity → abort).

**Acceptance criteria.**
- On 20 simulated confirmation screens, the app correctly extracts amount
  and recipient in ≥ 95 % of trials.
- The app refuses to proceed on all cases where either OCR confidence
  falls below the validated threshold (100 % of injected failure cases).
- The app accepts only explicit Georgian affirmatives; silence and the
  injected-ambiguity test case ("I don't know, maybe") result in abort,
  100 % of trials.
- The user's physical press of the confirm button is still the authoring
  action — the app does not simulate input.

**What counts as failure.**
- Any trial where the app announces an amount or recipient different from
  what the simulated screen shows.
- Any trial where the app accepts a non-affirmative as confirmation.

---

## Cross-cutting criteria

Applies to all milestones.

- **No fabrication.** No milestone passes if its output includes any
  hallucinated control, label, amount, or recipient at any point in
  the measurement run.
- **Observable abstention.** Every subsystem must have at least one
  measured case in which it correctly abstained (did not emit "press",
  did not list a control, did not accept a confirmation) when its
  confidence was below threshold.
- **Reproducibility.** Each acceptance measurement must be re-runnable
  from recorded inputs. The command and dataset should be referenced
  in `PROGRESS.md`.
- **Threshold revision.** When measurement shows a provisional threshold
  was wrong, revise it in `SPIKES_AND_DECISIONS.md` and re-run the
  milestone before claiming it passes.
