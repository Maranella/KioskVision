# Spikes and Decisions

A living record of research questions, experiments, results, and
architecture decisions. Follow the lightweight ADR format below. Mark each
decision `proposed`, `accepted`, `rejected`, or `superseded`. When
superseded, link forward to the replacement.

## Legend

- **Documented fact**: cited from an authoritative source with URL + date.
- **Experimental result**: measured by us on our hardware.
- **Estimate**: informed guess, labeled as such.
- **Assumption**: not yet verified; must be converted into one of the above.

---

## Research findings (initial sweep, 2026-10-14)

The following were produced during Phase 0 initialization by targeted
research. Where web fetches could not be completed in-session, we fall back
to prior knowledge and label the item as unverified-this-session. All
should be re-verified before being used in a decision that locks the
architecture.

### R-01 — Apple SFSpeechRecognizer does not support Georgian

**Finding.** `SFSpeechRecognizer.supportedLocales()` has, historically and
through current public documentation, not included `ka-GE`. Siri's
supported-languages list likewise does not include Georgian.

**Evidence.** Siri's public language list is well known and has never
included Georgian; Apple's `supportedLocales()` page was reachable but body
not retrievable in this session.

**Confidence.** High that Georgian is not supported; verify in-code on an
iOS 18/26 device before locking (one-line check:
`SFSpeechRecognizer.supportedLocales().filter { $0.identifier.hasPrefix("ka") }`).

**Implication.** We cannot rely on Apple's native STT. See ADR-002.

### R-02 — Apple AVSpeechSynthesizer does not include a Georgian voice

**Finding.** `AVSpeechSynthesisVoice.speechVoices()` returns no `ka-GE`
voice. Falling back to another locale's voice produces unintelligible
output for Mkhedruli text.

**Evidence.** No shipped iOS version has added Georgian; verify in-code
with `AVSpeechSynthesisVoice.speechVoices().filter { $0.language.hasPrefix("ka") }`.

**Implication.** We cannot rely on native TTS. See ADR-003.

### R-03 — Apple Vision OCR does not support Georgian

**Finding.** `VNRecognizeTextRequest.supportedRecognitionLanguages()` has
not included `ka` through revision 3 (iOS 16–current). Live Text likewise.

**Evidence.** Apple's documented language set covers Latin-script
languages, Chinese, Japanese, Korean, Russian, Ukrainian, Thai, Vietnamese,
Arabic — not Georgian. Verify in-code on target OS.

**Implication.** We cannot rely on native OCR. See ADR-004.

### R-04 — Azure Speech supports Georgian for both STT and TTS

**Finding.** Microsoft's language-support page lists `ka-GE` for Speech-to-
Text (real-time / fast / batch; custom speech with plain text) and lists
two neural TTS voices (`ka-GE-EkaNeural`, `ka-GE-GiorgiNeural`).

**Evidence.** Verified during research pass:
<https://learn.microsoft.com/en-us/azure/ai-services/speech-service/language-support> (2026-10-14).

**Implication.** Primary cloud candidate for both STT and TTS. See ADR-002
and ADR-003.

### R-05 — Google Cloud OCR, ML Kit, Azure Vision OCR Georgian coverage

**Finding.**
- Azure AI Vision Read API: Georgian **not** in printed or handwritten OCR
  tables (verified in-session).
- Google ML Kit v2 on-device: Georgian script **not** in supported packs
  (Latin, Chinese, Devanagari, Japanese, Korean only). High confidence.
- Google Cloud Vision OCR: historically multilingual and anecdotally
  recognizes Georgian; **page unverified in-session**. Must be checked
  before relying on it.

**Implication.** On-device commercial OCR is a dead end for Georgian. See
ADR-004.

### R-06 — Open-source Georgian stacks exist

**Finding.**
- Tesseract 5 has `kat.traineddata` for Mkhedruli and `kat_old.traineddata`
  for Asomtavruli / Nuskhuri.
- Piper community voice: `rhasspy/piper-voices/ka/ka_GE/natia/medium` —
  ONNX, MIT license, single female voice, "medium" quality tier.
  Mirror: `speaches-ai/piper-ka_GE-natia-medium` (updated Mar 2025).
- Whisper tokenizer includes `ka`, but no cited WER number for Georgian
  on `large-v3` was located.

**Confidence.** Tesseract and Piper paths are verified by their existence
in canonical repos; subjective quality on real kiosk frames and for blind-
user listening is **unverified**.

**Implication.** On-device fallback paths exist for OCR and TTS; viability
is a measurement task (Spike-03, Spike-04).

### R-07 — Finger guidance accuracy is parallax-limited without depth

**Finding.** A 2D fingertip pixel plus a 2D screen-plane homography yields
the finger's projected point on the screen plane **only** if the fingertip
is already touching the glass. If the finger hovers at distance `d` above
the glass and the camera axis is off-normal by angle `θ`, the projected
landing error is approximately `d · tan(θ)`. For `d = 3 cm, θ = 20°`:
~1.1 cm error. For `d = 5 cm, θ = 30°`: ~2.9 cm — a full button width on
typical kiosks.

**Confidence.** This is a geometric fact (not an experimental claim).

**Implication.** iPhone LiDAR is probably required to compute the
perpendicular foot of the fingertip onto the screen plane with
sub-centimeter error. See ADR-001.

### R-08 — Required finger-landing accuracy

**Finding (estimate).** For a 2 cm target, a 1-sigma error of ≈ 0.7 cm in
landing estimate is a reasonable working budget to give useful L/R/U/D
cues without oscillation. For 4 cm targets, ≈ 1.5 cm is tolerable.

**Confidence.** Estimate from first-principles pointing/HCI; label as
provisional until Spike-01 measurements adjust.

### R-09 — Vision hand pose at near-screen poses

**Finding.** `VNDetectHumanHandPoseRequest` returns a 21-keypoint hand
with `.indexTip`. Apple does not publish quantitative accuracy. Known
weakness: foreshortened fingers pointing toward the camera (which is
exactly the pose when approaching a screen), hands at frame edges, and
occlusion by bezel.

**Confidence.** Known qualitatively; must be measured for our geometry in
Spike-01.

### R-10 — Closest shipping UX precedent

**Finding.** Apple's **Point and Speak** in the Magnifier app (iOS 17+)
uses LiDAR + Vision to read labels under a user's finger. Different task
(labels under finger, not "go to target"), but strong precedent for the
sensor stack. No competitor product offering camera-guided fingertip-to-
target navigation for blind users was identified in the research pass.

---

## Architecture Decision Records

### ADR-001 — iPhone Pro / LiDAR as the baseline device for V1

**Status.** Proposed.

**Context.** Spike research (R-07, R-09) suggests a pure 2D camera +
homography cannot reliably hit 2-4 cm targets due to parallax when the
finger hovers above the glass. LiDAR gives a per-pixel depth that lets us
compute the perpendicular foot of the finger onto the screen plane.

**Decision (proposed).** Require an iPhone Pro / Pro Max with LiDAR as
the V1 device baseline. Non-Pro models are out of scope for V1 unless
Spike-01 unexpectedly shows acceptable accuracy without LiDAR.

**Consequences.**
- Smaller addressable market in V1.
- Development, test and support fleet converges on one hardware class.
- Must validate the decision in Spike-01 before locking.

**Alternatives considered.** Pure-2D with aggressive user coaching;
rejected pending measurement because the geometric error is deterministic,
not a tuning problem.

---

### ADR-002 — Primary STT provider: Microsoft Azure Speech (ka-GE)

**Status.** Proposed.

**Context.** Apple Speech does not support Georgian (R-01). Azure has
verified `ka-GE` support (R-04). Google / Deepgram / AssemblyAI / ElevenLabs
Georgian support was not verified in-session and historically has been
absent.

**Decision (proposed).** Use Azure Speech `ka-GE` as the primary STT.
Explore Whisper on-device as a secondary (offline) path in Spike-02.
Prohibit using voice for PIN entry regardless of provider.

**Consequences.**
- Cloud dependency on the critical path for intent interpretation.
- Needs user-visible transparency about audio leaving the device.
- Must measure WER on real Georgian commands in Spike-02 before locking.

**Alternatives considered.** Native Apple Speech (not available); Whisper
on-device as primary (no cited Georgian WER; latency on large-v3
multi-second on iPhone).

---

### ADR-003 — Dual-stack TTS: Piper on-device + Azure neural

**Status.** Proposed.

**Context.** Apple AVSpeechSynthesizer lacks Georgian (R-02). Azure
neural has verified `ka-GE` voices (R-04). Piper has a community
`ka_GE natia-medium` voice (R-06). Interrupt latency matters for safety-
critical cues.

**Decision (proposed).** Use Piper on-device for latency-critical cues
(directional nudges, "stop", "aligned") and Azure neural for longer
narration (critical-field readouts, confirmations). Expose a user
preference for voice selection.

**Consequences.**
- Audio pipeline must support interleaved sources with deterministic
  interrupt.
- Model-shipping cost (~20–30 MB) acceptable.
- Measure subjective intelligibility in Spike-03 before locking; Piper's
  medium tier may be insufficient for critical readouts.

**Alternatives considered.** Azure-only (fails on sub-500 ms interrupt
budget over 4G); Piper-only (likely too robotic for long narration and
numeric accuracy).

---

### ADR-004 — Primary OCR: Tesseract 5 with `kat.traineddata`

**Status.** Proposed.

**Context.** No commercial on-device OCR supports Georgian (R-05).
Tesseract has Georgian training data (R-06). We should prefer on-device
to minimize camera-frame egress (see `SAFETY_AND_PRIVACY.md`).

**Decision (proposed).** Use Tesseract 5 (via a maintained Swift / C++
bridge, to be chosen in Spike-04) with `kat.traineddata`. Add a cloud
fallback **only** if Spike-04 shows Tesseract CER unacceptable; cloud
usage requires explicit consent and will be scoped to rectified labels,
not whole frames.

**Consequences.**
- Image preprocessing (perspective rectification, glare mitigation,
  contrast) matters more than raw OCR tuning.
- Keep a path to a fine-tuned recognizer on captured kiosk fonts if
  Spike-04 requires it.

**Alternatives considered.** Google Cloud Vision (privacy cost);
PaddleOCR (no Georgian in default models); building a custom OCR from
scratch (not justified without evidence Tesseract fails).

---

### ADR-005 — Framing diagnostics are rules over Vision outputs, not an ML model

**Status.** Proposed.

**Context.** Stage C needs specific, actionable diagnostics ("too far",
"off angle", "glare"). We could train a model, or we could compute each
diagnosis from measurable image statistics.

**Decision (proposed).** Compute framing diagnostics from rules over
Vision outputs: screen rectangle presence/area, aspect vs expected,
rectangle-corner stability over N frames, Laplacian variance for blur,
highlight histograms for glare. Keep rules in one module. No ML model
for framing in V1.

**Consequences.**
- Easier to debug, easier to tune per-kiosk.
- May be verbose; migrate to a lightweight classifier later if rules
  become brittle.

---

### ADR-006 — Target selection is not performed by an LLM

**Status.** Proposed.

**Context.** It is tempting to send a kiosk-screen image to a vision LLM
and ask "which button advances goal X?". This conflicts with safety
invariants (no fabrication, auditable abstention).

**Decision (proposed).** Target selection is a scoring function over
structured observations produced by deterministic perception. An LLM may
assist **intent interpretation** (free-form Georgian → structured goal)
but the planner always re-grounds the LLM output to a detected control
and never trusts an LLM-asserted target as authoritative.

**Consequences.**
- Reduces flexibility.
- Increases confidence that failures are reproducible.
- Keeps the critical-path auditable.

---

### ADR-007 — No backend service in V1

**Status.** Proposed.

**Context.** The MVP milestones do not require account state, analytics,
or shared storage. Adding a backend adds attack surface and development
cost.

**Decision (proposed).** V1 ships without a backend service. Any
telemetry must be strictly opt-in and content-free. Cloud STT/TTS calls
go direct from the device to the vendor over TLS with device-held keys.

**Consequences.**
- No per-user analytics in V1.
- Secret management is on-device (keychain) with the known risks; mitigate
  with scoped Azure keys and server-side revocation.

---

## Open spikes (planned, not yet executed)

| ID | Title | Goal | Owner | Status |
|---|---|---|---|---|
| Spike-01 | Finger-guidance feasibility (homography vs LiDAR) | Decide if we can hit 2 cm targets reliably | TBD | planned |
| Spike-02 | Georgian STT real-world measurement | Lock STT provider with measured WER | TBD | planned |
| Spike-03 | Georgian TTS listening evaluation | Confirm intelligibility with blind native speakers | TBD | planned |
| Spike-04 | Georgian OCR on real kiosk frames | Lock OCR stack with measured CER | TBD | planned |

Each spike has a dedicated section in `ROADMAP.md` with deliverables,
acceptance criteria, and estimates.

## Rejected approaches

- **GPT-4V / vision-LLM as the primary perception engine.** Rejected per
  ADR-006 safety constraints and because it introduces mandatory
  cloud dependence for camera frames (`SAFETY_AND_PRIVACY.md` §4).
- **Always-on background wake-word listening.** Rejected due to iOS
  background microphone restrictions and the user-brief guidance to not
  promise it.
- **Direct kiosk-provider API integration as a baseline.** Rejected
  because such integrations are not assumed to exist. Could be added
  later as an opt-in acceleration path.
- **Simulating input on the kiosk.** Rejected by product spec and safety
  requirements. The user always performs the physical press.
