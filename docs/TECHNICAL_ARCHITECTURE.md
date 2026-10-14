# Technical Architecture (Proposed)

Status: **proposed**. Nothing in this document is implemented. Every
component listed here is a candidate pending experimental validation. See
`SPIKES_AND_DECISIONS.md` for open ADRs.

The goal of this document is to describe the shape of the system clearly
enough to run experiments against it, not to lock in a final design.

## 1. System overview

The system is an iOS app running on a user's iPhone. It has no required
backend for the MVP. It integrates several external SaaS endpoints for
Georgian speech, with on-device alternatives where available.

```
┌───────────────────────────────────────────────────────────────────┐
│                           iOS Application                         │
│                                                                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐ │
│  │   Capture    │  │  Perception  │  │     Interaction          │ │
│  │              │  │              │  │                          │ │
│  │ AVFoundation │─▶│ Vision+ARKit │─▶│  Session State Machine   │ │
│  │ (camera,     │  │ Hand pose,   │  │                          │ │
│  │  LiDAR,      │  │ screen rect, │  │  Intent interpreter      │ │
│  │  audio in)   │  │ OCR (ka),    │  │  Target selector         │ │
│  │              │  │ icon/UI det. │  │  Directional cue planner │ │
│  └──────────────┘  └──────────────┘  └──────────────────────────┘ │
│         │                 │                       │               │
│         └─────────────────┴───────────────────────┘               │
│                           │                                       │
│                 ┌─────────▼────────┐                              │
│                 │  Speech I/O      │                              │
│                 │  STT: Azure/     │                              │
│                 │       Whisper    │                              │
│                 │  TTS: Piper +    │                              │
│                 │       Azure      │                              │
│                 └──────────────────┘                              │
└───────────────────────────────────────────────────────────────────┘
          │                                     │
          ▼                                     ▼
   On-device models                  Azure Speech (opt. STT/TTS)
   (Tesseract kat,
    Piper natia, Whisper
    quantized, custom YOLO)
```

Component boundaries are strict and tested with recorded inputs so each
layer can be improved independently.

## 2. Components and responsibilities

### 2.1 Capture

Responsible for raw sensor data only.

- **AVCaptureSession** at the highest practical resolution and frame rate
  the perception loop can keep up with (likely 1080p @ 30 fps for Vision,
  with 4K bracketed stills on demand).
- Continuous autofocus restricted to near range.
- On iPhone Pro: **ARKit** session providing `sceneDepth` (LiDAR) at 30 Hz
  aligned with the color frame.
- **AVAudioSession** push-to-talk capture during command windows. No
  continuous mic streaming while idle.

Boundary: emits timestamped frames (RGB + optional depth) and audio buffers.
Does not interpret.

### 2.2 Perception

Responsible for turning frames into structured observations.

- **Screen rectangle detector**: `VNDetectRectanglesRequest` tuned for
  kiosk-screen size and aspect, with per-frame tracking.
- **Homography builder**: four screen-corner points → screen-plane
  transform for rectifying OCR regions and projecting the fingertip.
- **Georgian OCR**: Tesseract 5 with `kat.traineddata` (ADR-004,
  provisional). Future: a fine-tuned recognizer on captured kiosk frames.
  Runs on rectified crops, not raw frames.
- **UI control classifier**: hybrid of rectangle detection + OCR +
  kiosk-specific heuristics (and later a small Core ML detector trained on
  captured kiosk frames). Produces candidate "actionable control" records
  with confidence.
- **Hand pose**: `VNDetectHumanHandPoseRequest`, focused on the index tip
  keypoint and the knuckles that let us judge finger-pointing geometry.
- **Fingertip-to-screen projector**: given fingertip pixel, screen-plane
  homography, and (on Pro) LiDAR depth at the fingertip pixel, compute the
  perpendicular foot of the finger on the screen plane as the predicted
  landing point.
- **Screen-change detector**: perceptual hash + rectified-region diff of
  rectangle contents to notice kiosk transitions.

Boundary: emits a per-frame **ScreenObservation**:
`{ screenPresent, framingProblems[], screenPolygon, perspectiveTransform,
   controls[{bbox, label, confidence, isActionableConfidence}],
   hand[{indexTipPx, indexTipPlanePoint, projectionConfidence}],
   screenHash, timestamp }`

### 2.3 Session state machine

Deterministic finite state machine. States include:

- `idle`
- `activating` (user has signaled start)
- `listening_goal` (STT window)
- `confirming_goal`
- `acquiring_screen`
- `exploring_screen`
- `guiding_to_target`
- `confirming_critical_fields`
- `recovering_from_transition`
- `aborted`
- `completed_leg`

Transitions are triggered by typed events: `UserCommand`, `ScreenObservation`,
`PerceptionTimeout`, `UnrecoverableError`. All transition logic is
deterministic and unit-testable without the camera.

### 2.4 Intent interpreter

Takes a transcribed Georgian utterance and classifies it into an intent
record. Candidates:

- Rule-based + small vocabulary for the MVP (payment categories, provider
  names, numeric amounts, control commands "stop", "repeat").
- Optional LLM assistance **only** for free-form intent interpretation and
  **never** for authorizing a press. The planner always re-grounds the
  LLM's suggested target in a real detected control.

### 2.5 Target selector

Given the current observation and the retained goal, selects one of:
- A specific candidate control to guide the user to.
- A clarifying question to ask (if ambiguity above threshold).
- A "no match on this screen" signal that advances the state machine.

Target selection is **not** done by the LLM; it is a scoring function
over structured observations.

### 2.6 Directional cue planner

Given the current fingertip landing estimate and the target bbox on the
screen plane:

- Emits short directional cues ("up", "slightly right", "closer").
- Suppresses whiplash by requiring stability over N frames before changing
  the active cue.
- Promotes to "aligned" / "press" only when the landing estimate is inside
  the target bbox with acceptable uncertainty.
- Falls back to "hold still" and silence when confidence drops.

### 2.7 Speech I/O

- **STT (Georgian)**: Azure Speech `ka-GE` (confirmed support, see ADR-002)
  as primary. Push-to-talk, short command window. Potential on-device
  fallback: Whisper quantized (ka is in the token set but WER on Georgian
  is unverified).
- **TTS (Georgian)**: on-device Piper `natia-medium` (community ONNX, MIT)
  for latency-critical cues; Azure `ka-GE-GiorgiNeural`/`EkaNeural` for
  longer narration. See ADR-003.
- All playback goes through a single `AVAudioPlayerNode` with a short
  buffer queue so interrupts are deterministic.

## 3. Data flow

A representative cycle during Stage F (finger guidance):

1. Capture: 30 fps RGB + LiDAR depth.
2. Perception runs on each frame:
   - Update screen polygon + homography (tracked, not re-detected every frame).
   - Run hand pose on the frame.
   - If both present: compute fingertip plane point + confidence.
3. State machine receives `ScreenObservation` and asks the cue planner for
   the current directional instruction.
4. Cue planner compares current and previously-emitted cue; if changed and
   stable, enqueue a new TTS utterance.
5. Speech I/O preempts any currently playing cue and plays the new one.
6. On "press", the state machine moves to `recovering_from_transition` and
   watches the screen hash for a change.

Latency budget (target, to validate in Spike-01):

| Stage | Target |
|---|---|
| Frame capture → perception result | ≤ 35 ms |
| Perception result → cue decision | ≤ 10 ms |
| Cue decision → first audio sample | ≤ 100 ms (on-device Piper) |
| End-to-end cue loop | ≤ 150 ms |

## 4. State machine concept

A session is a sequence of `legs`. Each leg is one kiosk screen.
A leg has sub-phases:

1. **Acquire** — detect screen, verify framing.
2. **Interpret** — enumerate controls, classify, cross-reference with goal.
3. **Guide** — directional cues until aligned or user abort.
4. **Confirm (optional)** — only on legs that look like critical-field
   screens; read fields and ask for affirmative.
5. **Advance** — detect screen change; initialize next leg.

The retained goal lives at the session level, not the leg level. Each new
leg re-evaluates "which control advances the goal" from scratch; it does
not assume a predicted path.

## 5. On-device vs cloud

### Must stay on-device
- Camera frames (never leave by default).
- Hand pose, screen detection, homography.
- Fingertip projection.
- Session state machine.
- Final confirmation logic.

### Can be on-device or cloud, with trade-offs
- **OCR**: Tesseract on-device is viable; Google Cloud Vision could be a
  high-confidence fallback **if** we accept the privacy cost of sending
  rectified screen crops that may contain amounts and recipient names.
- **STT**: cloud (Azure) is the realistic path for V1; on-device Whisper
  is the aspiration.
- **TTS**: dual-stack as above.

### Should remain off the critical path
- LLM intent interpretation. Keep rule-based first; LLM optional and never
  authoritative.

## 6. Testable boundaries

- `Perception` can be driven by recorded frames + depth maps. No camera
  required. Enables regression tests on known kiosks.
- `SessionStateMachine` is pure logic. Fully unit-testable.
- `CuePlanner` is pure logic given observations. Property tests for "never
  says press when confidence low" are feasible.
- `SpeechIO` is behind a protocol so Azure/Piper/mocked backends are
  interchangeable in tests.

## 7. Architecture alternatives considered

| Alternative | Why not now |
|---|---|
| Server-driven architecture with heavy backend | Adds latency, privacy exposure, and complexity. No V1 feature requires it. |
| LLM-first ("show kiosk screen to GPT-4V, ask for next action") | Not auditable. Does not meet safety invariants (no fabrication, deterministic finger guidance). |
| Multiple small ML models trained from scratch | Premature. Start with Vision + Tesseract baseline, measure, then invest where measurement shows need. |
| Universal kiosk support via generic UI understanding | Out of scope. The product targets a small kiosk set first. |
| Native kiosk-provider API integration | Not assumed to exist. If a provider offers an SDK later, that becomes an opt-in acceleration path, not the default. |

## 8. Decisions that must wait for experiments

- Target iPhone model and minimum iOS version. Depends on whether LiDAR is
  required (Spike-01). If so, iPhone Pro only.
- OCR provider final selection. Depends on Tesseract-vs-cloud quality on
  real kiosk frames (Spike-04).
- Whether to ship on-device Whisper for Georgian STT. Depends on measured
  WER and latency (Spike-02).
- Confidence thresholds for every "do not press" rule. Depends on
  Spike-01 and Spike-04 results.
- Whether to use any LLM for intent interpretation. Depends on how much
  rule-based coverage leaves gaps during user testing.
