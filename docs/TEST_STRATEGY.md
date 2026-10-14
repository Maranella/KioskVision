# Test Strategy

Testing plan for a safety-sensitive accessibility app with a perception
pipeline. The strategy is layered: deterministic logic is unit-tested,
perception is tested against recorded inputs, and the full system is
tested on real hardware with real users. We **do not** build a complete
test suite until there is production code to test.

## 1. Layers

| Layer | What it tests | Where it runs |
|---|---|---|
| Unit | Pure logic: state machine, cue planner, intent parser, number normalization, scoring functions. | CI, Xcode local. |
| Coordinate | Homography math, ray-plane intersection, perpendicular-foot projection, finger-landing confidence. | CI with recorded inputs. |
| Perception replay | OCR, screen detection, hand pose, control classification on recorded frames with ground-truth labels. | CI (large datasets excluded from the repo). |
| Integration | Camera + perception + planner + TTS on-device, driven by recorded screens or a tablet simulator. | On device. |
| Device & kiosk | Full stack on a physical kiosk. | Field. |
| Performance | Latency and frame-rate budgets. | On device. |
| Speech evaluation | Native-speaker listening and command evaluation. | Human testers. |
| Accessibility usability | Blind native-speaker usability. | Human testers, real kiosks. |
| Safety & recovery | Scripted failure injection; abstention behavior. | On device, with mocked bad inputs. |

Each layer has well-defined inputs and outputs. Nothing should require all
layers to be up to validate anything other than full-session behavior.

## 2. Unit tests

Scope:

- State machine transitions, including every path to `aborted` and
  `completed_leg`.
- Cue planner: given a sequence of synthetic observations, the planner's
  output must satisfy the "no press under uncertainty" and "no whiplash"
  invariants.
- Intent parser: Georgian command strings → intent records.
- Number normalization: vigesimal Georgian → digits.
- Confidence-threshold rules: parameterized tests that cover each
  abstention case explicitly.

Each safety invariant from `SAFETY_AND_PRIVACY.md` must have at least one
property or example test.

## 3. Coordinate-math tests

Scope:

- Homography from 4 corner points produces identity when corners are
  coplanar and oriented identically to the camera plane (sanity).
- Ray-plane intersection and perpendicular-foot projection reproduce
  known positions on synthetic input.
- Property test: for a random fingertip 3D position within a bounded
  volume, Method B (perpendicular foot) is closer to the "ground truth"
  foot than Method A (ray-plane intersection) when the finger is above
  the plane and camera axis is off-normal.

These tests do not require a camera and should run in CI.

## 4. Recorded-frame perception tests

Scope:

- A small curated dataset of kiosk frames (initially synthetic / tablet-
  rendered until we have real captures).
- Each frame has a JSON ground-truth file listing: screen polygon,
  actionable controls (bbox + Georgian label), decorative text (bbox +
  text), the correct framing diagnosis.
- The harness runs perception against each frame and compares outputs
  to ground truth. Reports precision/recall for actionable
  classification, CER for OCR, and false-positive rate for framing.

Dataset lives outside the repo (gitignored path) or in Git LFS once we
have scale. The harness runs locally and in CI with a small checked-in
subset for smoke testing.

## 5. Integration tests on device

Scope:

- A tablet acting as a mock kiosk showing a recorded screen sequence.
- Finger-landing tests: scripted operator moves finger over marked
  targets; the app's "press" assertions are logged and compared against
  marked positions.
- End-to-end session replays: operator speaks a scripted goal, follows
  cues, and the full session result is logged. A session passes if the
  user actually hit the target button on the mock kiosk.

These tests cannot run in CI. They are run by the engineer at the end of
each milestone and the raw logs become part of the acceptance evidence.

## 6. Device and kiosk field tests

Scope:

- Real target kiosks (TBC Pay) in real environments (indoor, mixed
  lighting, actual physical geometry).
- No real-money transactions unless explicitly authorized by the tester
  for a specific amount.
- Must involve blind users, not sighted testers with eyes closed, before
  any claim that a milestone is complete at the "field" level.
- Protocol: pre-registered script, consent, note-taking on every point
  where the app refused to proceed vs. proceeded confidently; every
  unexpected transition noted.

## 7. Performance and latency

Scope:

- Measure the end-to-end cue loop on an iPhone Pro: capture → perception
  → cue → first audio sample.
- Measure OCR time per frame at target resolution with and without
  perspective correction.
- Measure STT time-to-interpretation on a cellular/Wi-Fi connection vs.
  a trusted lab Wi-Fi.
- Measure TTS time-to-first-audio for Piper on-device vs. Azure
  streaming.
- Budget targets live in `TECHNICAL_ARCHITECTURE.md §3`. Any budget miss
  by > 20 % is a milestone failure.

## 8. Georgian speech evaluation

STT:
- Fixed 30-command vocabulary (control verbs, payment categories, common
  provider names, numeric amounts).
- 3 native Georgian speakers × 5 trials/command × 2 environments
  (quiet / noisy).
- Report per-command accuracy, overall WER, and common confusions
  (expect code-switch confusions on English loanwords).

TTS:
- 20 reference utterances covering directional cues, confirmation
  prompts, numeric amounts, and recipient names.
- 2–3 native Georgian listeners rate each utterance pass/fail for
  intelligibility and binary-rating for naturalness.
- Report pass rate per utterance class.

## 9. Accessibility usability testing

- At least one blind participant for M4 onward; two for M6.
- Pre-registered tasks with success criteria defined before the session.
- Think-aloud protocol in Georgian.
- Observer logs every experimenter intervention (voice coaching, physical
  nudge). An observer-corrected success is not a product success.
- Debrief captures (a) perceived safety, (b) perceived trust, (c)
  frustration points.

## 10. Safety and recovery scenarios

Scripted failure cases that must be reproducible on a mock kiosk:

- OCR injected low-confidence on a critical field → app must abstain.
- Screen hash change without the app knowing why → app re-acquires and
  does not continue the prior leg's directional loop.
- Hand pose confidence drops mid-guidance → app stops saying "press".
- Multiple kiosks visible in the frame → app asks the user to pick one.
- Loud background chatter not addressed to the app → app does not react
  (push-to-talk discipline).
- User says silence / "I don't know" at a confirmation prompt → abort.

Each case is a reproducible test added to the integration suite.

## 11. What we are explicitly not doing yet

- A continuous end-to-end integration test running in CI. We will add it
  if milestones M1–M3 are approved for implementation.
- Automated snapshot comparison of TTS audio. Natural voices vary too
  much; we rely on human evaluation.
- Automated screenshot-based testing of the (minimal) iOS UI. The UI is
  not the user interface; speech and camera are.

## 12. Reporting

Every milestone's acceptance measurement must produce:

- A short Markdown report in `docs/experiments/` (to be created when the
  first measurement runs). Linked from `PROGRESS.md`.
- Raw data as CSV or small JSON. Checked in if small; otherwise linked
  with a one-line summary in the report.
- A pass/fail verdict against the milestone's acceptance criteria.

Reports are what update `PROGRESS.md`. Code changes alone never do.
