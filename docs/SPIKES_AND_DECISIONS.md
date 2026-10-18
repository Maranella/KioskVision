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


### R-11 — OCR cost is driven by line count, and a region crop is ~13x cheaper

**Type:** experimental result (measured in-session, 2026-10-14).

**Device:** physical Android handset, model `A142` (`Pacman`), arm64-v8a,
Android 15, debug build. Medians of 5 runs, via
`OcrPerformanceTest` (`connectedDebugAndroidTest`).

#### Latency vs input resolution — downscaling rejected

Synthetic kiosk screen at the 576x1280 analysis frame size this device
delivers:

| Input | Latency | Buttons matched | Note |
|---|---|---|---|
| 576x1280 (1.00) | 232 ms | 4/4 @0.97 | |
| 432x960 (0.75) | 197 ms | 4/4 @0.97 | |
| 288x640 (0.50) | 160 ms | 4/4 @0.97 | small print destroyed (`'cam'@0.55`) |
| 202x448 (0.35) | 63 ms | **2/4** | unusable |

**Decision: do not downscale OCR input.** Halving the input saves ~30% and
costs the small print, which is where amounts and terminal identifiers live
(safety requirement 4). The 0.35 scale is fast but loses half the buttons.
The gain does not justify the risk, especially next to the region result
below.

#### Latency vs area — region crops are the real win

Cluttered frame (21 lines, approximating a real camera view with chrome and
desk clutter), full frame = **442 ms**:

| Region around the target | Area | Latency | Speedup | Target |
|---|---|---|---|---|
| pad 2.0 x box | 47% | 329 ms | 1.4x | **missed** |
| pad 1.0 x box | 25% | 283 ms | 1.6x | found @0.97 |
| **pad 0.5 x box** | **13%** | **34 ms** | **13.3x** | **found @0.97** |
| pad 0.25 x box | 8% | 30 ms | 15.1x | found @0.97 |

Cost tracks **line count**, not pixel count: the jump from 283 ms to 34 ms
comes from dropping 5 candidate lines to 2, not from the 2x area change.

`TessBaseAPI.setRectangle` reports boxes in **full-image coordinates**
(verified: 0, 3 px from the drawn centre), so a region result needs no
translation and drops straight into the same geometry as a full-frame one.
No cropped bitmap copy is needed either.

**Adopted:** `pad 0.5` for re-anchoring a locked target
(`TargetTracker.ANCHOR_PAD`). `pad 1.0` was rejected despite still finding
the label — it is barely faster than a full run.

#### Hand tracking and contention

| Condition | Per frame | FPS |
|---|---|---|
| Hand landmarks alone, 576x1280 | 55-63 ms | 16-18 |
| With Tesseract looping beside it | 55-68 ms | 15-18 |

**Contention is within run-to-run noise (±10%)** on this hardware; one run
even measured the contended case as marginally faster. The OCR thread is
pinned to `THREAD_PRIORITY_BACKGROUND`, which is the right default but
**showed no measurable benefit** and should not be credited with one.

This **does not explain** the 133-158 ms per frame (~6-7 fps) observed in
the live app during the previous session. That was measured after minutes of
sustained camera + OCR use, so thermal throttling is the leading hypothesis;
it is **unconfirmed** and should be re-measured on a warm device.

#### Full-frame OCR on real camera frames

The synthetic figures above understate the problem. Measured in the live app
with the rear camera pointed at a TBC Pay screen on an external display
(21-22 recognised lines, including the host machine's window chrome):

| Source | Full-frame OCR |
|---|---|
| Synthetic cluttered frame (21 lines) | 442 ms |
| **Real camera frame (20-22 lines)** | **2.6 s - 6.3 s** |

Real frames carry sensor noise, uneven illumination and compression-free but
soft edges, all of which multiply Tesseract's per-line work. 6314 ms was the
worst single run observed. This is the strongest argument for the region
path: the same device re-anchors a locked target in ~34 ms.

#### End-to-end effect

Over the full eight-step scenario (`LiveAssistScenarioTest`): **10 full OCR
runs and 40 region runs**, full frame 157 ms, region 16 ms, hand 65 ms. In
the live app pointed at an unreadable scene, full OCR runs dropped from 42 in
a comparable interval to **3**, after which the phase settles on
`ScreenReady` instead of looping through false transitions.



### R-12 — Motion blur collapses Tesseract confidence on correctly read text

**Type:** experimental result (measured in-session, 2026-10-14).

**Device:** `A142`, arm64-v8a, Android 15. `OcrMotionDiagnosticTest`, real
Tesseract `kat+eng` over a rendered kiosk screen transformed to simulate
handheld movement.

The reported symptom was that slight phone movement makes the app announce
it cannot find `გადახდა` while the label is plainly visible. The cause is
**not** geometry:

| Simulated movement | OCR output | Confidence | Matcher |
|---|---|---|---|
| still | `გადახდა` | 0.97 | found |
| translate 10-40 px | `გადახდა` | 0.97 | found |
| translate 40 px + 25 px down | `გადახდა` | 0.97 | found |
| rotate 1-3 deg | `გადახდა` | 0.97 | found |
| rotate 6 deg | `\| გადახდა I\| სალა \|` | 0.39 | rejected (lines merged) |
| **motion blur 4 px** | **`გადახდა` — exactly right** | **0.20** | **rejected** |
| motion blur 8 px | `გადახდა = ბალანსი` | 0.20 | rejected |
| **motion blur 14 px** | **`გადახდა.` — right** | **0.47** | **rejected** |
| translate + rotate + blur | label gone, only the heading survives | - | rejected |

**Translation up to 40 px and rotation up to 3 deg cost nothing**: the label
still comes back at 0.97. So perspective rectification of the kiosk display
would not have addressed this symptom, and was not implemented on the
strength of these numbers.

**Tesseract's per-word confidence is not a measure of correctness under
blur.** At 4 px of blur it returned the target string *exactly* and scored it
0.20. The old matcher applied a single-frame gate of 0.60, so a perfectly
correct reading was discarded and the user was told the button does not
exist.

**Decision: corroboration across runs, not a lower threshold.** Lowering the
gate would accept single-frame garbage, which safety requirement 1 forbids.
Instead a label may be accepted below the single-frame gate only when the
same text was read at the same place in **two or more** recent OCR runs.
Identical misreadings do not repeat at the same position; correct readings
do. The high-confidence single-frame path is unchanged.

Severely blurred frames are now skipped before OCR rather than spending
270-325 ms producing text that will be thrown away.

#### Measured effect of the fix

`OcrMotionDiagnosticTest`, 12 trials per band, each trial three frames with
randomised drift (±10 px), tilt (±1.5 deg) and blur, on the same device:

| Handheld condition | Old single-frame gate | Corroborated across runs |
|---|---|---|
| Steady (blur ≤ 2 px) | 12/12 (100%) | 12/12 (100%) |
| Light movement (blur ≤ 6 px) | 10/12 (83%) | **12/12 (100%)** |
| Heavy movement (blur ≤ 12 px) | 7/12 (58%) | **9/12 (75%)** |

Unchanged when the phone is steady, which is the check that this is not just
a loosened threshold. OCR cost was 164-198 ms per run throughout.

Worked example against real Tesseract, three handheld frames at a fixed blur:

| Blur | Run 1 | Run 2 | Run 3 | Outcome |
|---|---|---|---|---|
| 4 px | `გადახდა` 0.20 x1 | `\| გადახდა` 0.96 x2 | x2 | **found** |
| 8 px | `გადახდა = ბალანსი` 0.20 x1 | `გადახდა` 0.80 x1 | x2 | **found** |
| 14 px | `გადახდა.` 0.47 x1 | `გადავიდა` 0.21 x1 | garbage | not found |

At 14 px the label is genuinely unreadable — run 2 returned a different word.
The app now answers that case by asking the user to hold the phone still,
not by claiming the button is absent.

#### What was deliberately not done

Perspective rectification of the kiosk display was **not** implemented. The
measurements above show translation up to 40 px and rotation up to 3 deg cost
nothing at all, so a homography would not have addressed the reported fault.
Rotation beyond ~6 deg does degrade OCR (text lines merge), which remains an
open limitation rather than a fixed one.



### R-13 — MediaPipe needs most of the hand in frame, not just a fingertip

**Type:** experimental result (measured in-session, 2026-10-14).

**Device:** Samsung SM-S731B, Android 16. `HandVisibilityTest`, real
MediaPipe HandLandmarker over a 576x1280 frame.

The live app reported "no hand in frame" continuously while the user
believed their finger was in shot. Measured limits:

| Hand size in frame | Detected |
|---|---|
| 143 px wide (25% of frame width) | **no** |
| 251 px (44%) | yes |
| 358-1146 px (62-199%) | yes |

| Portion of the hand inside the frame | Detected |
|---|---|
| ~100% | yes |
| ~93% | yes |
| ~50% | **no** |
| ~7% | **no** |

So the hand must be **mostly inside the frame** and at least roughly
**40% of the frame width**. A fingertip entering from the edge with the palm
outside is not tracked at all, and neither is a hand too far away. A hand
larger than the frame is fine.

One caveat: the landmarker runs in VIDEO mode, which tracks across frames, so
an abrupt jump in hand position can miss for a frame or two. One case at
~100% visibility missed for that reason.

**Consequence:** this is a framing problem, not a threshold problem, so the
detection confidences were left alone. The spoken message was changed from
"I can't see your finger" to one that says what to change — bring the whole
hand into view, nearer the kiosk than the phone.



### R-14 — Georgian OCR confuses specific letter pairs, and kiosk wording varies

**Type:** experimental result (observed on device, 2026-10-14).

**Device:** Samsung SM-S731B. Captured from the live app's match log against a
real TBC Pay menu.

What Tesseract returned for a screen whose true labels are known:

| On screen | OCR read | Confusion |
|---|---|---|
| ბალანსის შემოწმება | ბაღანსის შემონშება | `ლ`→`ღ`, `წ`→`ნ`, `მ`→`შ` |
| მობილურის შევსება | მობიღურის შევსება | `ლ`→`ღ` |
| კომუნალური მომსახურება | კომუნაღური მომსახურება | `ლ`→`ღ` |
| დახმარება | დასმარება | `ხ`→`ს` |

`ლ`→`ღ` appeared in three independent labels, so it is systematic rather than
noise. These are visually similar glyphs.

**Fix:** matching now uses an OCR-aware edit distance. A substitution within a
known confusable pair (`ლ`/`ღ`, `ხ`/`ს`, `წ`/`ნ`, `მ`/`შ`) costs 0.4 of an
edit instead of 1, and such a letter still counts towards the stem prefix. The
cost is deliberately non-zero — these remain errors, and two of them should
weigh about as much as one real difference. A test asserts that a confusable
substitution scores higher than a non-confusable one, so the allowance cannot
quietly become "any letter matches any other".

**Second finding, unrelated to OCR quality:** this screen has no literal
`გადახდა` button at all. Its payments option is worded
`და სხვა გადასახადები`. A user asking to pay was told the button does not
exist, correctly by the matcher's own rules but uselessly. `გადასახად` was
added to the payment concept stems. An exact `გადახდა` label still outranks
the generic one where both are present, and the payments concept remains
flagged critical so the user is still prompted to check details.



### R-15 — The gyroscope was being used as a proxy for things already measured

**Type:** design defect found from user feedback, 2026-10-14.

The user reported that being told to hold the phone still is "very hard" —
which it is, while reaching for a kiosk with the other hand.

Four places treated "the gyroscope reports movement" as disqualifying:

| Place | Old behaviour |
|---|---|
| `DirectionalGuidanceEngine` | `!deviceSteady` returned `TARGET_UNCERTAIN` — all guidance stopped |
| `TargetTracker.onDeviceMoved` | discarded the measured target position outright |
| `LiveAssistPipeline.imageUnreliable` | a moving phone deferred target selection |
| acquisition scheduling | OCR waited for a steady frame |

In each case the gyroscope stood in for something the app **already measures
directly**: whether the target's position is still current (anchor age) and
whether the image is readable (sharpness). The proxy is strictly worse than
the measurement, and far more pessimistic — a phone can move continuously
while both the anchor and the image stay perfectly good.

**Change:** movement no longer blocks anything. It shortens how long a
measured position is trusted, from `MAX_ANCHOR_AGE_MS` (4 s) to
`MOVING_ANCHOR_AGE_MS` (1.2 s), and shortens the region re-anchor interval
from 400 ms to 250 ms so that window is comfortably met. Region re-anchoring
costs a few milliseconds (R-11), which is what makes this affordable.
Acquisition and selection now judge the image on sharpness alone, and the
target position is never discarded for movement.

Safety is unchanged in kind: guidance still stops when the mapping is not
trustworthy — it is just decided by the measurement rather than by a proxy.
`GuidanceTest` still asserts that a stale anchor never yields `ON_TARGET`.

Wording that demanded stillness was replaced by wording that says what the
app needs, e.g. `ეკრანი კადრში დატოვე` and
`ეკრანი ბუნდოვანია, ოდნავ შეცვალე კამერის კუთხე`.

**Measured.** `HandheldMotionExperimentTest`, 30 trials on a Samsung
SM-S731B, driving the real pipeline (real Tesseract, real MediaPipe) with
frames that drift up to 14 px, tilt up to 1.5 deg and blur up to 5 px on
**every** frame, for 9 s of continuous movement per trial. Criteria were
fixed before the run.

| Metric | Criterion | Measured (3 runs) |
|---|---|---|
| Screen acquired while moving | — | 30/30, 30/30, 30/30 |
| Target retained through motion | 100% | 30/30, 29/30, 30/30 |
| Guidance available | >= 80% | **77%, 83%, 86%** |
| *Same frames, old steady-required rule* | — | **0%, 0%, 0%** |
| Direction reversals | <= 1 per trial | 0.00 every run |
| False target-reached | 0 | 0 every run |
| First cue after selection | — | 219 ms, 233 ms |

Reported as a range rather than a best run: availability straddles the 80%
criterion and retention lost one target in one run of three. Neither figure
should be quoted as a single number.

The comparison is the result: under continuous handheld movement the previous
rule allowed guidance on **no frames at all**, which is exactly the reported
experience of being told to hold still. It is now available on roughly three
frames in four.

**The 80% target is only sometimes met, and was deliberately not tuned to.** Availability
here is bounded by a safety rule rather than by conservatism: at handheld
speeds (~210 px/s in these trials) a position not re-anchored for
`MOVING_ANCHOR_AGE_MS` (1.2 s) can be ~250 px stale, far more than the
on-target zone. Raising that limit would lift the number by guiding towards
where the button no longer is, so it was left alone. The honest way to close
the gap is to make re-anchoring succeed more often under blur, not to trust
stale geometry for longer.

The suite therefore asserts floors — 60% availability, 95% retention, both
far above any regression — and reports the 80%/100% aspirations separately,
so a regression is caught without the figures being quietly redefined to
whatever was achieved.

**Two measurement bugs were found and fixed before these numbers were
trusted:** the harness had no backpressure, so its frame queue grew across
trials until later trials read stale state (CameraX applies
`KEEP_ONLY_LATEST`; the harness now waits for each frame to drain); and cue
latency was timestamped while tallying the run rather than as frames arrived,
which simply reproduced the window length — 4048 ms with a 4 s window, 9050 ms
with a 9 s one. Corrected, it is 219 ms.


### R-16 — Tesseract configuration sweep: upscaling wins, a bigger model does not

**Type:** experimental result (measured in-session, 2026-10-15).

**Device:** Samsung SM-S731B. `GeorgianOcrAccuracyTest`, 96 ground-truth
labels per configuration over three rendered kiosk screens in six conditions
(34/16/12 px text, faint contrast, 10 px blur, small+blur), with luminance
noise added.

**Corpus caveat:** rendered, not photographed. It has no glare, no real
optics and no kiosk fonts. These numbers rank configurations against each
other; they are **not** an estimate of field accuracy.

| Configuration | CER | WER | Exact labels | p50 |
|---|---|---|---|---|
| `fast` + `PSM_SPARSE_TEXT` + 1x (shipped) | 29.7% | 42.9% | 52.1% | 69 ms |
| **`fast` + `PSM_SPARSE_TEXT` + 2x** | **27.4%** | **33.3%** | **66.7%** | 158 ms |
| `fast` + sparse + 2x + grayscale | 27.2% | 33.3% | 66.7% | 164 ms |
| `fast` + `PSM_AUTO` + 1x | 34.2% | 41.1% | 55.2% | 60 ms |
| `best` + sparse + 1x | 29.2% | 42.9% | 52.1% | 78 ms |
| `best` + sparse + 2x | 27.1% | 33.3% | 66.7% | 202 ms |

Four conclusions, three of them negative:

1. **`tessdata_best` is not worth adopting for Georgian.** 29.2% against
   29.7% CER is inside the noise, and it costs ~2 MB of APK and 10-30% more
   latency. The shipped `tessdata_fast` model stays.
2. **`PSM_AUTO` is worse than `PSM_SPARSE_TEXT`** (34.2% vs 29.7%), and
   collapses under blur (100% vs 88% CER). The existing choice was right.
3. **Grayscale conversion buys nothing** (27.2% vs 27.4%). Not adopted.
4. **2x upscaling is the one real win**: exact label recognition 52.1% ->
   66.7%. The gain is entirely in small text — 12 px CER falls 5% -> 0%,
   16 px 1% -> 0% — and there is nothing to gain at 34 px, which already
   reads at 0%.

**Adopted:** upscaling applied where it pays — always on a re-anchor region
(small enough that the cost is slight) and on a full frame only when the
median label height on that screen is under
`SMALL_TEXT_FRACTION` (0.015 of frame height, ~19 px at 1280).

An initial threshold of 0.03 (~38 px) upscaled comfortably-sized text and
slowed acquisition enough that the end-to-end scenario stopped acquiring
altogether — caught by the regression suite, not by inspection.

Coordinates are verified rather than assumed: a label drawn at (200, 640) is
recovered at (200, 641) through both the full-frame and the crop-then-enlarge
path (`upscalingPreservesCoordinates`).

**Blur remains the dominant error source** by a wide margin: 84-88% CER under
10 px blur against 0-5% otherwise. No configuration mitigates it, which is
why the pipeline skips soft frames rather than trying to read them.

**Motion tolerance did not regress** (30 trials, after the change): retention
30/30, guidance available 85%, 0 reversals, 0 false target-reached, first cue
206 ms.



### R-17 — The motion-availability metric is confounded by the harness's own occlusion

**Type:** investigation, 2026-10-15. Resolved.

Synthetic guidance availability appeared to fall from 77-86% to 52-66%. It had
not: the metric was mixing two different situations. The cause was not found
where it was first looked for.

#### What was ruled out, each by a controlled A/B on identical inputs

| Variable | Arms | Availability | Anchor refresh |
|---|---|---|---|
| Region upscaling | 2x / 1x | 66% / 59% | 33% / 30% |
| Trial reset | full / stop-only | 66% / 55% | 30% / 30% |
| Anchor padding | 0.5 / 1.0 / 2.0 | 66% / 52% / 47% | 33% / 30% / 37% |
| Device state | after reboot, cooled to 36.6 C | 61-66% | 30% |

Pipeline timings stayed normal throughout — hand 45-60 ms, region OCR
16-53 ms, full OCR 109-136 ms — so thermal throttling is **not** supported.
The `GuidanceUnavailable` split was verified equivalent by inspection: the
same predicate, reordered only to report which branch fired.

None of these restored 77-86%, and **none explains the drop**.

#### Two real defects found on the way, both fixed

1. **The diagnostics were blind.** `unreadableRuns` is incremented only in the
   full-frame path; region results return before it. A log reading
   `unreadableRuns=0 anchorJobs=42` therefore said nothing about whether
   those 42 jobs succeeded. Region outcomes are now counted separately
   (`refreshed` / `notSeen`), which is what made the rest of this measurable.
2. **Trials were not independent.** `Stop` clears the target but leaves
   `snapshot` and `recentRuns` in place, and the experiment reuses one
   pipeline, so every trial inherited the previous trial's recognised screen.
   `resetForTest` now clears it.

#### Resolution: the metric was confounded, the product was not broken

Anchor refresh sat at 30-37% in *every* arm (1,761 attempts in the largest
sample). That constancy, under changes to region size, magnification, reset
and device state, pointed at the **input** rather than the geometry — and
splitting availability by whether the hand covered the label confirmed it:

| Condition | Guidance available | n |
|---|---|---|
| **Label visible** | **86%** | 1297, replicated 86% at n=1153 |
| Label covered by the hand | 27-32% | 935-1175 |

The blended figure is just the weighted mix of the two
(0.58 x 86 + 0.42 x 32 = 63). When the app can see the label it guides 86% of
the time — at the top of the original 77-86% range. **There was no product
regression.** What moved was how much of each reach the rendered hand spent
over the button, which shifted with frame pacing when per-frame measurement
work was added.

Guidance pausing while the label is covered is correct: the position cannot be
confirmed, and the alternative is guiding from stale coordinates.

**The gate now asserts availability over unoccluded frames only** (target 80%,
measured 86%), and reports the blended and occluded figures alongside. This
split was predicted and agreed *before* the run that produced these numbers,
not chosen afterwards to make the gate pass, and no safety limit was changed:
retention 30/30, zero direction reversals, zero false target-reached, first
cue 187 ms.

#### What this cost, and the lesson

Four controlled A/Bs came back negative before the question was reframed. The
metric had been defined without asking what it would measure when the user's
own hand — the thing the app exists to guide — sits on top of the target.


### R-18 — Gemini Live speaks Georgian well; hearing it is the open problem

**Type:** measurement, 2026-10-16. Partly resolved; input accuracy still open.

M1 asks whether Gemini Live over Firebase AI Logic can carry a Georgian voice
session. Output is not the difficulty. Input is.

#### Model selection was forced by the service, not chosen

| Model | Result |
|---|---|
| `gemini-2.5-flash-native-audio-preview-12-2025` | Rejected by the server: retired for new users |
| `gemini-2.5-flash` (text) | Same retirement |
| `gemini-3.1-flash-live-preview` | Connects, speaks Georgian; input never transcribed as Georgian |
| **`gemini-3.8-live`** | **Adopted.** Connects, speaks and *understands* Georgian. Its transcript drifts to other languages too — see R-20 |
| `gemini-3.8-flash` (text) | Adopted for the diagnostic text request |

The official Android sample's identifiers are behind the service. Model names
have to be taken from what the API accepts, not from the published sample.

`gemini-3.1-flash-live-preview` auto-detected the input language and got it
wrong every time across roughly eight utterances — Portuguese
(`instruções`), Italian, German, Russian and English, never Georgian.
`speechConfig.languageCode` does not fix this: it sets the **output** voice
only. `LiveGenerationConfig.Builder` exposes no input-language field at all,
confirmed by `javap` against the firebase-ai 18.0.0 AAR. Pinning Georgian in
the system instruction did not fix it either. Changing model did.

#### The assistant was interviewing itself

Early transcripts looked like successful Georgian recognition. They were not:
the microphone was picking up the assistant's own voice from the speaker, the
service transcribed it as the user, and the model answered itself in a loop.
An earlier claim of "3/3 correct Georgian" in this session was this artefact
and is withdrawn.

Two fixes, both verified on the device:

- Capture moved to `MediaRecorder.AudioSource.VOICE_COMMUNICATION` and
  playback to `AudioAttributes.USAGE_VOICE_COMMUNICATION`, via
  `setInitializationHandler`, which puts both on the platform's echo-cancelled
  path. Verified by an 18 s session in silence: `heard: -`, `said: -`, where
  before it greeted itself unprompted.
- Barge-in disabled. Residual speaker leak still counted as an interruption,
  so one question drew several spoken answers; with interruptions off, one
  question draws one. **This is a diagnostic setting, not a product one** —
  the spec requires barge-in, and it must come back once playback is routed
  where the microphone cannot hear it.

#### What input accuracy actually looks like

Small n, and stated as such:

| Spoken | Transcribed | Verdict |
|---|---|---|
| „გამარჯობა" | „გადახდა მინდა." | Wrong words, correct script and language |
| „კომუნალური" | `comunaluri` | Right word, Latin transliteration; reply was on topic |
| „დენი" | „ტენისი" | Wrong word — *electricity* heard as *tennis* |

The pattern is short utterances. One- and two-syllable words give the decoder
no context to lean on, and they are precisely what a kiosk user says. „დენი"
is not an edge case; it is one of the most common things this product will
ever be asked for.

**Not yet decided.** The spec's bar is ~30 utterances across native speakers
before choosing between Gemini Live and the Azure `ka-GE` fallback. Nothing
here meets that bar, and nothing here should be read as having met it.

#### Also found

`outputAudioTranscription` streams a transcript in fragments, so one spoken
reply arrived as five separate history lines and read as a rambling model. A
display flaw, not model behaviour; `TranscriptAssembler` now rejoins them,
because a run cannot be scored off fragments.

---

### R-19 — Does naming the kiosk vocabulary fix short-word recognition?

**Type:** experiment, pre-registered 2026-10-16. **Open — no result yet.**

R-18 leaves one lever. The SDK has no input-language field and no phrase-list
field, so the system instruction is the only place a decoder bias can be
expressed. The instruction now names the domain, asks for Mkhedruli script
explicitly, states that utterances are often a single short word, and lists
the ~25 Georgian words the kiosk domain actually uses (`AiConfig.DOMAIN_VOCABULARY`).

**Predicted before running, so the result can falsify it:**

- The three known failures are the first test. „დენი" → „დენი",
  „კომუნალური" → „კომუნალური" in Georgian script, not `comunaluri`.
- If transliteration stops but the words stay wrong, the script instruction
  works and the vocabulary hint does not. These are separate claims.
- A vocabulary hint cannot repair audio the decoder never resolved. If short
  single words still fail, the lever is exhausted and the Azure `ka-GE`
  fallback spike becomes the next step rather than more prompt tuning.
- Watch for the opposite failure: a model told to expect these words may
  report them when they were not said. An utterance *outside* the list must
  still transcribe correctly, so the run has to include off-domain words.

This is prompt engineering against a preview model, which is the weakest kind
of fix available: it is not contractual, and a model revision can undo it
silently. It is recorded as a measurement, not as a solution.

#### First result: the transcript and the model's understanding are not the same thing

Measured on the device, 2026-10-16, with the vocabulary hint in place:

| Spoken | `inputAudioTranscription` | What the model answered |
|---|---|---|
| „დენი" | `tenni` | „გასაგებია, **დენი**. გთხოვთ, შეიყვანოთ აბონენტის ნომერი." |

The same word that was heard as „ტენისი" (*tennis*) before priming is now
understood as „დენი" (*electricity*), and the reply is the correct next step
for a utility payment — it asks for the subscriber number. But the input
transcript still reads `tenni`, in Latin script, as wrong as before.

**So `inputAudioTranscription` is not a view into what the model understood.**
It behaves like a separate, weaker recognition pass that the system
instruction does not reach: the script instruction and the vocabulary hint
both changed comprehension and neither changed the transcript.

Two consequences, and the second is the awkward one:

- The product should never treat the input transcript as the record of what
  the user said. The model's own behaviour is the thing that acts on it.
- **The planned measurement no longer works as designed.** Scoring word error
  rate against `inputAudioTranscription` would measure a pipeline the product
  does not use, and would have reported failure on an utterance the model got
  right. An evaluation has to score the *response* — did the model act on the
  right service? — which is a judgement about meaning, not a string
  comparison, and is correspondingly more expensive to run.

n = 1. This is a structural observation about where the two signals diverge,
which one sample can establish, and not a measurement of accuracy, which it
cannot.

#### The parroting check, which was the point of pre-registering it

R-19 predicted the obvious way this could fool us: a model handed a list of
25 words may report those words when they were not said. The off-domain probe
was „ამინდი" (*weather*), spoken three times.

| Spoken | `inputAudioTranscription` | What the model answered |
|---|---|---|
| „ამინდი" | `Amindi` | „სამწუხაროდ, ამ სერვისს ვერ გთავაზობთ. გთხოვთ, შეიყვანოთ აბონენტის ნომერი." |
| „ამინდი" | `Amini` | „სამწუხაროდ, ამ სერვისს ვერ გთავაზობთ. გთხოვთ, მიუთითოთ გადახდის ტიპი." |
| „ამინდი" | `Haminti` | „სამწუხაროდ, ამ სერვისს ვერ გთავაზობთ. გთხოვთ, აირჩიოთ გადახდის ტიპი." |

Three for three, the model declined instead of snapping the audio onto a
listed word. It never answered „დენი" or „აბონენტი" to „ამინდი".

**What this establishes is narrower than it looks.** It shows the vocabulary
hint does not cause false positives on out-of-domain speech — which is the
failure that would have made the whole approach unsafe, and the reason the
probe was written down in advance. It does **not** show the model heard
„ამინდი" as *weather*; refusing a service is the right answer whether it
understood the word or merely failed to match it. Those two cases are
indistinguishable from this evidence and should not be conflated.

#### Running tally

| Spoken | Understood correctly? | Transcript |
|---|---|---|
| „დენი" | yes | `tenni` |
| „კომუნალური" | yes | `comunale` |
| „ამინდი" x3 | declined, correctly, no parroting | `Amindi` / `Amini` / `Haminti` |

Five utterances, one speaker, one device, one session. The spec's bar is ~30
across native speakers, so **R-19 stays open**. What has changed is the
direction of the evidence and, more importantly, the method: the response is
the signal, the transcript is not.

### R-20 — The spike prompt made the assistant impersonate the kiosk and invent a transaction

**Type:** safety defect, found and fixed 2026-10-16. **Found by testing, not by review.**

While measuring amount recognition, the user said „ორმოცი ლარი" (*forty lari*).
The assistant answered:

> „მიღებულია ორმოცი ლარი. გთხოვთ, გააგრძელოთ ოპერაცია."
> *Forty lari received. Please continue the operation.*

Over five turns in the same session it also announced „გადახდის რეჟიმი
აქტიურია" (*payment mode is active*), „თანხა დადასტურებულია" (*the amount is
confirmed*) and „არჩეულია დენი" (*electricity is selected*).

**None of it had happened.** No money was received, nothing was confirmed, no
service was selected. There was no kiosk in the room. The model had assumed
the role of the machine and narrated a transaction it invented.

#### Why this is the serious kind of wrong

It violates the invariants in `SAFETY_AND_PRIVACY.md` and `CLAUDE.md` head-on:
never fabricate a screen state or an amount, never imply a transaction
occurred. The target user is blind and has no independent way to check a
claim like "forty lari received" — which is the entire reason those
invariants exist. An assistant that confabulates confirmations is worse than
one that stays silent.

#### Cause: the prompt, written in this session

The system instruction opened with "This is a self-service payment kiosk" and
then listed payment vocabulary. Between the framing and the word list, the
most natural role for the model to adopt was the kiosk itself. Nothing in the
prompt forbade it. The R-19 vocabulary hint did not cause this on its own, but
it supplied the script the model acted out.

The pre-registered R-19 risk was the model *parroting the word list*, and the
off-domain probe cleared that. It did not occur to me to predict the model
adopting the *role* the prompt described. The probe that was written down
tested the failure that was imagined; this one arrived unimagined, from a
test of something else entirely — amount recognition.

#### Fix

The prompt now states the boundary explicitly rather than leaving it to
inference: the assistant is not the kiosk, cannot see its screen, must never
report money received, an amount confirmed, a payment completed, a control
selected, or any transaction step, and must say it cannot see the screen if
asked. Only the app's own camera pipeline decides screen state.

**Unverified at the time of writing.** The fix is built and installed; it has
not yet been re-tested against „ორმოცი ლარი". A prompt instruction is a weak
guarantee against a behaviour this strongly suggested by context, and the
real defence is architectural: the voice layer must never be the authority on
kiosk state, whatever it says. That constraint belongs in the M2/M3 design,
not in a prompt.

#### What this says about the amount measurement

It is not yet answered. The amount test was interrupted by what it uncovered,
and „ორმოცი ლარი" was transcribed `ormocillari` / `hormocillari` with the
model's comprehension unverifiable — a confabulated "forty lari received"
repeats the number back without proving it was heard, since the model had
every reason to echo the word it had just been given. Amount recognition must
be re-measured against a prompt that cannot confirm anything.

#### Re-tested after the fix

The decisive probe was asking the assistant to read the screen:

| Spoken | Answered |
|---|---|
| „წამიკითხე ეკრანი." | **„მე ვერ ვხედავ ეკრანს."** *(I cannot see the screen.)* |

Every fabricated confirmation was gone: no „მიღებულია", no „დადასტურებულია",
no „გადახდის რეჟიმი აქტიურია" across six turns.

**The amount also came back, and this time the evidence is worth something.**
Asked „რამდენი გითხარით თანხა?" (*how much did I tell you?*), the assistant
answered „თქვენ მითხარით 40 ლარი." That is a recall under challenge rather
than an echo: the number had to survive several intervening turns, and the
user asked rather than the model volunteering. Input transcripts over the same
turns read `formaggio`, `cada`, `40 L`, `Kadahta Minda Denis` — comprehension
held while the transcript was nonsense, consistent with R-19.

#### A second, weaker version of the same defect

The ban on confirmations did not cover verbs of *acting on the machine*. The
model then said „თქვენ შეიყვანეთ 40 ლარი" (*you entered 40 lari*) and „თქვენ
აირჩიეთ გადახდა" (*you selected payment*). The user had entered and selected
nothing; they had spoken. Milder than announcing a received payment, but the
same error: it asserts a kiosk state.

Fixed in a second pass that names those verbs and requires "you said …" /
"you asked for …" instead. Built and installed; **not yet re-tested.**

That two passes were needed is the point. Each round banned the phrasings
already observed, which is a reactive fix by construction: it cannot cover
the wordings nobody has produced yet. This is why the real control has to be
architectural — the voice layer must not be the authority on kiosk state —
and why the prompt is a mitigation, not the answer.

#### The decisive probe, and what it cost

The test that matters most for a blind user is the moment before they touch
the machine:

| Spoken | Answered |
|---|---|
| „დავადასტურო?" *(Should I confirm?)* | „თქვენ იკითხეთ დადასტურების შესახებ." *(You asked about confirmation.)* |
| „ეკრანზე რას ხედავ?" | „მე ვერ ვხედავ ეკრანს." |
| „ორმოცი ლარი" | „თქვენ **დაასახელეთ** ორმოცი ლარი." |

No instruction to press, no claim about a button, no invented state. The
second pass held: `დაასახელეთ` (*named*), not `შეიყვანეთ` (*entered*).

**The safety goal was met by making the assistant useless.** Across the same
session it answered „თქვენ ახსენეთ გადახდა", then „თქვენ **ისევ** ახსენეთ
გადახდა", then „თქვენ მოითხოვეთ გადახდა" — it had collapsed into reflecting
the user's words back. The right answer to „დავადასტურო?" is *"I cannot see
the screen, so I cannot tell you — the app will guide your finger"*, which is
both safe and helpful. "You asked about confirmation" is only the first.

That is an over-correction, and it was mine. Each pass of the prompt bought
safety by removing capability, because a prompt is the wrong instrument:
gagging the model is the only move it offers. The architecture has to
separate the two concerns instead — the voice layer free to be genuinely
helpful about the *conversation*, the camera pipeline the sole authority on
*screen state* — so that being useful and being safe stop competing.

#### Language detection failed again, on the adopted model

Input transcripts in this session: `Como una luz cada minuto.` (Spanish),
`Gedächtnis` (German), `Şemsiye kadar da` (Turkish), `ऋषिकेश आता मी`
(Devanagari), `ormocillare`. Interleaved with these were correct Georgian
lines — „ეკრანზე რას ხედავ?", „მე ვიცი, რომ ვახსენე, მაგრამ გადახდა მინდა."

This is the same auto-detection failure that disqualified
`gemini-3.1-flash-live-preview` in R-18, now observed on `gemini-3.8-live`,
the model adopted because it appeared not to have it. The earlier Latin
transliterations (`tenni`, `comunale`) were the mild form of the same thing.

It does not change the adoption decision, because comprehension stayed
correct throughout and the transcript is not a channel the product uses. It
does mean R-18's characterisation of `gemini-3.8-live` as having fixed input
language detection was **too generous, and is corrected here**: the model's
*understanding* is stable in Georgian; its *transcript* is not, and the
difference was invisible until the two were compared turn by turn.

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

### R-21 — Guidance now runs in rectified screen space, not camera pixels

**Type:** implementation + measurement, 2026-10-16. Unit-tested; **not verified
at a kiosk.**

Until now `camera/FrameGeometry.kt` approximated the kiosk screen by the whole
camera frame, which its own documentation admitted is "only valid when the
screen faces the camera roughly head-on". Product milestone M1 could not pass
without screen detection, and every coordinate downstream inherited the
approximation.

#### What was built

| File | Responsibility |
|---|---|
| `camera/Homography.kt` | 3x3 projective transform; 4-point solve by Gaussian elimination with partial pivoting; inverse; corner ordering |
| `camera/ScreenDetector.kt` | Otsu threshold, flood fill, convex hull, four dominant corners |
| `camera/ScreenTracker.kt` | Temporal smoothing, jump rejection, stability, staleness and loss |
| `camera/ScreenSpace.kt` | The single place frame and screen coordinates convert |
| `assist/KioskScene.kt` | Structured scene: elements, roles, readability, freshness |
| `assist/TargetResolver.kt` | Intent to verified target, or a typed refusal |

Detection, tracking and rectification are kept separate, because they fail
separately: a screen can be found but not rectifiable, or rectifiable but
no longer current.

**OpenCV was rejected.** The textbook contour-and-approxPolyDP pipeline would
add roughly 70 MB of native dependency to find one quadrilateral, and none of
it would run in the JVM unit tests this repository depends on. The detector is
ordinary Kotlin and is tested without a device.

Two details that were silently wrong in the obvious implementation:

- **Corner ordering by angle, not by coordinate sums.** The common "top-left
  is the smallest x+y" trick mislabels corners once the quadrilateral rotates
  past about 45 degrees, which is ordinary for a handheld phone.
- **Anisotropic rescaling.** Detection runs on a square 64x64 grid while the
  frame is not square. One scale factor for both axes stretches every corner,
  which moves every target on the screen.

#### Why this changes the answer, measured

Constructed case: a screen viewed from the left, a label centred at 30% across
the glass, a fingertip at 55% — a quarter of the screen apart, horizontally.

| Space | Offset in zone units | Vertical component |
|---|---|---|
| Rectified screen | **10.42** | 0.02 |
| Camera pixels | 5.81 | 0.05 |

Screen space recovers the constructed distance exactly (0.25 of the screen
divided by the 0.024 zone half-width). Camera pixels put the same gap at 56%
of it.

**Understating is the dangerous direction.** The near-zone and on-target
thresholds are both comparisons against this number, so a finger further from
the button than the app believes is told it has arrived sooner than it has —
to a blind user, in front of a payment terminal.

#### Thresholds became physically meaningful

The engine's floor was 1.5% of the *camera frame* diagonal. A fraction of the
frame is a different physical size at every distance: generous when the phone
is far from the screen, a sliver when close. Expressed against the screen's own
diagonal it is a fixed fraction of a panel whose size does not change. A test
pins that the same physical label filmed near and far now yields the same
verdict.

#### No screen means no direction

`GuidanceUnavailable.ScreenNotTracked` was added and `GuidanceInput` carries
`screenTracked` explicitly. The first implementation signalled it by passing a
null target, which the engine reported as `NoTarget` — answered with silence.
The user would have heard nothing at the one moment they needed to be told to
re-frame the phone. It is now a distinct spoken message
(„კიოსკის ეკრანს ვერ ვარჩევ. ტელეფონი ეკრანისკენ მიმართე.").

#### What rectification does not fix

It removes perspective error **in the screen plane**. It does nothing about a
finger held *above* the glass, displaced by about `height · tan(angle)`
(R-07), which needs depth. Android has no depth sensor, so that error remains
and is why the on-target zone is still a dwell in the image rather than a
claim that the glass was touched. R-13's framing conflict is likewise
untouched.

#### A test that was wrong, twice

The first version asserted that rectification flips the spoken direction. It
does not, for the quadrilateral chosen — both spaces said `LEFT`. The second
guessed a threshold for a vertical artefact that was not there. Both failed,
and the right response was to measure the actual offsets and assert what they
showed, rather than adjust the geometry until a desired disagreement appeared.
The surviving test asserts the understatement, which is the real mechanism and
the one with a safety consequence.

#### What the device run does and does not establish

179 unit tests and **15 instrumented tests pass on the physical Samsung
SM-S731B**, including `LiveAssistScenarioTest.theWholeAssistScenario`, which
drives the real pipeline with real Tesseract and real MediaPipe.

That test is load-bearing here, not incidental. It asserts `LEFT`, `UP` and
`ON_TARGET`, and all three are unreachable when `screenTracked` is false,
because the engine then returns `TARGET_UNCERTAIN`. Its passing is therefore
positive evidence that the detector finds the screen in those frames and that
guidance is running through the rectified path — the integration is not merely
compiled.

**What it is not.** Those frames are rendered, not photographed: no lens, no
glare, no housing, no ambient light. The detector's central assumption — that
the kiosk screen is the brightest large convex region — remains untested
against a window, a ceiling light or a reflection. Tests assert it abstains on
a flat frame and on a near-full-frame bright region, but synthetic abstention
is not evidence about a real foyer, which is exactly the lesson of R-17.

---

### R-22 — The Live assistant is part of the app, and the microphone has one owner

**Type:** implementation, 2026-10-16. **Verified on the physical device.**

Gemini Live was reachable only from a debug spike with its own launcher icon.
It is now started from the real assistance screen, and the spike has lost its
`LAUNCHER` entry — a second icon would have offered a parallel way into a voice
session that knows nothing about the kiosk.

#### The microphone can only have one owner

The app already had a microphone consumer: `SpeechCommandRecognizer`,
push-to-talk, on-device. Gemini Live needs a continuous stream. Android grants
capture to one consumer, so two recognisers competing is not a degraded
experience, it is a broken one.

`VoiceMode` makes the ownership explicit, with `LocalCommands` the default
because it needs no network and sends nothing anywhere. Starting the assistant
cancels the recogniser; pressing push-to-talk stops the assistant, because a
user pressing the button has stated which one they want.

#### Measured on the device, not inferred from the UI

The button label flips optimistically, so it proves nothing on its own.
`appops` was used instead:

| Action | `RECORD_AUDIO` |
|---|---|
| Assistant started from the assist screen | `allow; (running)` |
| Assistant stopped by the user | `duration=+46s`, no longer running |
| Home pressed with the assistant on | `duration=+7s`, released |
| Push-to-talk pressed with the assistant on | assistant returned to off |

The third row is the one worth having: the microphone does not stay open behind
a backgrounded screen, and audio stops being streamed to Google the moment the
user leaves.

#### Reconnection is bounded

`ReconnectPolicy` separates the two reasons a session ends. A planned rollover
— the server warns about one roughly every ten minutes — reconnects after
250 ms and **does not consume the retry budget**, since otherwise a long and
healthy conversation would end itself simply by lasting. A failure backs off by
doubling from 1 s, caps at 15 s, and gives up after four attempts, because
retrying a revoked key or a retired model in a loop burns the request quota
that the free tier actually limits, holds the microphone, and never succeeds.
On giving up the app falls back to on-device commands rather than leaving a
dead assistant: camera, OCR and guidance all work without the network.

The policy is a separate, pure class, so all of this is unit-tested without a
network.

#### Two voices, one audio channel

`AudioOutputCoordinator` implements what the integration spec asks for. The
ranking is by consequence, not recency: a direction, or a message that
*withdraws* guidance, preempts conversation, because letting the assistant talk
over „ღილაკის ადგილი დავკარგე" leaves the user reaching for a position the app
no longer stands behind. Descriptions are **dropped rather than queued** — by
the time a sentence finishes the finger has moved, and a direction computed a
second ago points where the finger no longer is.

#### ADR-009 is now executable

`VoiceBoundaryTest` reflects over `VoiceProvider` and `VoiceSessionReport` and
fails the build if either grows a member mentioning a perception type —
`DetectedTextBlock`, `KioskScene`, `TrackedTarget`, `Bitmap` and so on — and
asserts the interface still exposes nothing but `start`, `stop` and the report.
A fourth test checks the checker, so renaming a perception type cannot leave it
quietly passing against names that match nothing.

This matters because R-20's lesson was that a prompt is not a control. An
invariant written in a document is an intention; one that fails the build is a
constraint.

#### Known gaps, unchanged by this work

- **The app's own speech still enters the Live microphone.** The coordinator
  arbitrates *output*; it cannot stop guidance audio from being captured and
  transcribed as the user. Same root cause as barge-in being disabled (R-18),
  and it needs either a routed output path or a capture gate the SDK does not
  expose.
- **`PreemptModel` does not actually silence the model.** The SDK owns that
  playback. The decision reduces overlap rather than removing it, and the code
  says so rather than implying otherwise.
- **No `Interrupted` session state.** The spec asks for one, but the provider
  cannot currently distinguish it, and `VoiceProvider` holds that a lifecycle
  which lies is worse than a coarse one.
- **Network loss is handled only through reconnection,** not by observing
  connectivity. A dropped network looks like a failure and backs off; the app
  does not yet know *why*, so it cannot say so.

---

### R-23 — One voice control, and an intent that bypasses the broken transcript

**Type:** implementation, 2026-10-16. **Verified on the physical device.**

R-22 left the app with two voice buttons: push-to-talk for on-device commands,
and a separate switch for the streaming assistant. The product owner asked why,
and the honest answer was that the second button existed because the
integration was unfinished — the assistant could talk but could not act.
Nothing routed its understanding into the app, so it had to sit beside the
control that did.

**Two controls that both mean "talk", in front of someone who cannot see
either, is a design error.** Its two justifications do not survive inspection:
microphone exclusivity is an implementation detail the app should settle
itself, and consent to stream audio is a decision made once, not a mode
re-selected before every sentence.

#### Where the intent comes from

Not from the input transcript. R-19 established that
`inputAudioTranscription` returns Latin transliteration and, at times, Spanish,
German or Devanagari, while the model's comprehension of the Georgian stays
correct. Routing intents through it would have been building on the one part
measured to be unreliable.

Function calling is the mechanism, and the SDK supports it — verified against
the firebase-ai 18.0.0 AAR before being designed around: `liveModel` accepts
`List<Tool>`, `Tool.functionDeclarations` takes `FunctionDeclaration`s, and
`LiveAudioConversationConfig.Builder.setFunctionCallHandler` delivers a
`FunctionCallPart` and expects a `FunctionResponsePart` back, synchronously.
A call therefore comes from the comprehension that works, not the transcript
that does not.

Three functions, and that is the entire surface: `select_target(query)`,
`stop_guidance()`, `read_screen()`. The model cannot press anything, move a
pointer, read a frame or learn a coordinate.

#### ADR-009 survives the model gaining the ability to act

The function response carries **one field**: an outcome from a closed set —
`guiding`, `notonscreen`, `unconfirmed`, `ambiguous`, `screennotreadable`,
`noscreen`, `stopped`, `reading`. No label, no position, no screen text. A
test asserts that list exactly, so a future outcome carrying screen content
would have to be added in plain sight.

So **no scene data leaves the device even now**. The model asks for something
and is told yes or no; it still has no idea what the kiosk says. The detail
the user actually needs — which two buttons are similar, and what they read —
is spoken locally from `SpokenMessage`, grounded in what the camera saw. The
open privacy question about sending the model scene context did not have to be
answered, because it turned out not to need answering.

The pipeline remains the single authority on the live target: the resolver's
verdict is what the *assistant* is told, while the selection still goes through
`pipeline.selectTarget`, which owns anchoring, confirmation and screen
transitions. Both reach their answer through `TargetMatcher`, so they agree
about what exists; the pipeline simply knows more.

#### Consent became a setting

`VoicePreferences`, default off, asked once at the moment it first matters
rather than buried in a settings screen. The wording states the trade in both
directions, because declining leaves a working app: recognition, camera, OCR
and guidance are all on-device.

#### A real defect the device run exposed

Stopping the session cancelled the job while `start()` was suspended, and the
provider's `catch (e: Exception)` swallowed the `CancellationException` and
reported `VoiceSessionState.Error`. A deliberate stop was being recorded as a
failure — which `ReconnectPolicy` would have counted against its retry budget —
and swallowing a cancellation breaks structured concurrency besides. It is now
caught separately, reported as `Stopped`, and rethrown.

#### Measured on the device

| Action | Result |
|---|---|
| First press of the one voice button | Consent dialog, both options offered neutrally |
| Accepting | Session starts: `RECORD_AUDIO allow; (running)` |
| Pressing again | `duration=+8s`, microphone released |
| `live session failed` in logcat | **0** |

#### Still open

- The assistant's tool calls have **not been exercised against the live
  model**. The wiring is verified; whether `gemini-3.8-live` actually calls
  `select_target` when asked „სად არის გადახდის ღილაკი?" is unmeasured, and
  needs a kiosk or a mock screen in front of the camera.
- The app's own speech still enters the Live microphone (R-22), unchanged.
- No way to change the consent choice after it is made, beyond clearing app
  data. A settings entry is owed.

---

### R-24 — The assistant went silent because the service was full, and my A/B said otherwise

**Type:** investigation, 2026-10-16. Cause external; two real defects found alongside it.

The assistant stopped answering. The Live socket reported
`ServiceConnectionHandshakeFailedException: Channel was closed by the server`,
which the SDK gives without a reason (R-18).

#### The false A/B

Tool declarations had just been added, so they were the obvious suspect. A
session without tools connected; a session with them did not. That looked
conclusive and was not: **the two arms ran minutes apart**, and the server's
state changes on that timescale. Run back to back, the no-tools arm failed too,
with the same exception.

`diagnose()` — which exists precisely because the socket closes mutely — then
named it on an ordinary request:

> `ServerException: This model is currently experiencing high demand. Spikes in
> demand are usually temporary. Please try again later.`

So the cause is capacity on the no-cost tier, not the code. The earlier note
that cost is zero but **rate limits are the real constraint** is what this looks
like in practice.

The methodological error is the one R-17 was written about. An A/B whose arms
are separated in time is not controlled when the thing under test drifts, and
the two tool declarations removed to bisect it were innocent and have been
restored.

#### Two genuine defects, found while chasing the wrong thing

- **The diagnostics screen crashed on launch.** Adding a `Context` parameter to
  `GeminiLiveVoiceProvider` broke it: an Activity constructs the provider as a
  field initialiser, before its base context is attached, so
  `getApplicationContext()` returned null and threw. `AudioManager` is now
  resolved lazily, which makes the provider safe to construct wherever a caller
  finds it convenient.
- **The assistant had been reduced to a transcript.** R-20 recorded that the
  prompt hardening left it repeating the user's words back, and then nothing was
  done about it for three rounds of work while the product owner lived with it.
  The restriction existed because the model had no way to *act*, so any offer of
  help would have been a fabrication. With tools in place that is no longer
  true, so the prompt now asks it to be useful and to call a function rather
  than describe one. The safety bans — not the kiosk, cannot see the screen,
  never claim a transaction state — are untouched.

#### Also corrected

R-21 claimed that `LiveAssistScenarioTest` passing was positive evidence that
the detector finds the screen in rendered frames. Both that test and the motion
experiment paint a full-white frame, so the inference was weaker than it was
presented as, and it is withdrawn.

#### The motion-experiment failure is not a regression

`HandheldMotionExperimentTest` failed on target retention (28/30 against a 95%
floor) while passing availability at 89% — better than the 86% in R-17. Five
hypotheses about the cause were each ruled out by measurement: the coverage
bound, the scenario test's evidence, detector cost (0.35 ms), an eagerly rebuilt
scene, and a shared scratch buffer.

The A/B that should have come first settled it: **the same test fails on the
baseline commit `43399c7`**, in a clean worktree, on the same device, without
any of this work. The gate is environment-sensitive — R-17 already noted 30/30
standalone against 29/30 in a full-suite run — and the "0 failures" reported
after the screen-space change was a lucky run stated with more confidence than
it had earned.

Eagerly rebuilding the scene on the detection timer was still worth fixing:
`TargetTracker.corroborated` compares every label against every earlier one by
string similarity, and running that every 220 ms put avoidable work on the
thread that has to keep up with the camera. It is now cached when OCR finishes,
which is the only moment the answer can change.

---

### R-25 — One voice, and the four bugs between the model answering and the user hearing it

**Type:** implementation + measurement, 2026-10-17. **Verified by ear on the device.**

The product owner reported two voices, then an assistant that said "I will show you" and fell
silent, then guidance that worked but never spoke. Four separate defects, each hidden behind
the last.

#### The transport: silence by not having a speaker

`startAudioConversation` builds its own `AudioTrack`. The app can neither mute it nor observe
it, so two voices could only be arbitrated by guessing (R-22, R-24).

`ResponseModality.TEXT` looks like the fix and is not. Measured twice, through two different
transports:

| Transport | Modality | Microphone |
|---|---|---|
| `startAudioConversation` | TEXT | stopped after 491 ms |
| Own `AudioRecord` | TEXT | stopped after 433 ms |
| Own `AudioRecord` | **AUDIO** | **continuous** |

The common factor was the modality, not the transport: the Live models are audio-native and
close the channel when asked for text. `StreamingVoiceProvider` therefore keeps AUDIO, owns
the microphone, and **never plays the audio** — it has no `AudioTrack` at all, and speaks the
`outputTranscription` through the app's single engine. Silence by construction.

It also removed the beeping: Android's `SpeechRecognizer` ends after about 800 ms of silence
and plays the system tone on every restart, so looping it for hands-free use produced a beep a
second and `error 7 -> NoMatch` on repeat.

#### Then: dropped tool calls

Owning the transport means owning everything the SDK was doing. `LiveServerToolCall` was not
handled, so every tool call was dropped, no `FunctionResponse` was sent, and the model's turn
completed with nothing in it — `SRV other: LiveServerToolCall` beside an empty reply. That was
the "says nothing" symptom, and no unit test could have caught it: the dispatch was tested,
the transport reading the message was not.

#### Then: three places that silenced correct output

Each made sense while "listening" meant holding a button, and each became fatal once listening
was continuous and that flag was permanently true.

| Where | Effect |
|---|---|
| `speakAssistant`'s `if (listening) return` | The assistant never spoke at all |
| `say`'s `if (listening) return` | Every guidance message dropped |
| `pipeline.speechMuted = true` for the session | `if (toSpeak != null && !speechMuted)` — **no direction was ever emitted** |

The third is the one the owner described as "it does it correctly, it just doesn't say it":
the guidance engine was computing directions and the pipeline was discarding them before they
reached speech.

A fourth, smaller: the model answers an utterance it cannot parse with a zero-width space,
which `trim()` leaves and `isNotEmpty()` accepts, so the engine was handed a string containing
no speech.

#### Role arbitration, corrected

"Directions win over conversation" was keyed on the *phase*, so the moment guidance started
the assistant was muted — including the one sentence that tells the user guidance has begun.
It is now keyed on a direction actually having been spoken, within 3 s.

#### The half-duplex gate that the Live transport could never have

Both sides are now the app's, so "are we making sound right now" comes from the engine's own
`UtteranceProgressListener` rather than a timer. The microphone is paused around every
utterance, measured as `dropped 94 while speaking` climbing while the app talks and `dropped 0`
when it does not.

Cost: during a run of directions the microphone is closed much of the time, so the user cannot
interrupt. That is a real trade and is not yet tuned.

#### What a working exchange looks like

```
HEARD: წამიკითხე, რა წერია ეკრანზე.
TOOL  read_screen -> reading
SAID: აპლიკაცია წაიკითხავს ეკრანის შინაარსს.
speak#3: done, engineStarted=true
```

and, for a target that is genuinely absent:

```
HEARD: საგარეჯოს კომუნალური მომსახურება
TOOL  select_target -> notonscreen
SAID: ამ ეკრანზე ამ სერვისს ვერ ვპოულობ.
```

The model did not invent a button. The app checked with OCR, refused, and the assistant
reported the refusal — ADR-009 holding in a live session rather than in a test.

#### Outcomes had to be explained to the model

The function response carries a bare code, and the model read every one optimistically:
`unconfirmed` came back as "I will show you", followed by silence. The prompt now states what
each outcome means and forbids promising to show anything unless the outcome was `guiding`.

#### Still open

- **Georgian recognition remains the weak link.** Across today's sessions: `cada día`,
  `chef c'est bon`, `quadrat`, `comunale` — interleaved with perfectly correct Georgian. Same
  picture as R-19 on a different transport, so the limitation is the model's, not the wiring's.
- Whether `ScreenReader`'s output is actually spoken after `read_screen` is **unverified**;
  the command dispatches, the reading itself has not been heard.
- The end-to-end payment flow does not exist: goal retention across screens (M5) and
  critical-detail readback with confirmation (M6) are not built. One step on one screen works
  and must be re-asked for on the next.

---

### R-26 — M5 and M6: a goal that survives a screen change, and nothing guided to unasked

**Type:** implementation, 2026-10-17. **Unit-tested; not yet exercised at a kiosk.**

`PROGRESS.md` listed goal retention across screens (M5) and critical-detail confirmation (M6)
as the two things that *are* "complete an operation independently", and as absent. Both are
now built, deliberately narrowly.

#### M6 — a found control is offered, not acted on

`selectTarget` no longer starts guidance. It resolves the target, says what it found and
returns `needsconfirmation`; guidance begins only on `confirm_target`. Two tools were added,
`confirm_target` and `cancel_target`.

The reason is specific to this user. They cannot see what they are about to be sent towards,
and on a payment terminal the difference between the button they meant and one that merely
reads like it is money. `ResolvedTarget.isCritical` already travelled with the target, so a
payment or confirmation button is offered in different words: „ეს თანხას ეხება — მითხარი,
მიგიყვანო თუ არა."

Declining clears the goal as well as the offer. A goal that survived a rejected suggestion
would make the same wrong offer again on the next screen.

#### M5 — the goal is the user's words, not a script

`TaskGoal` holds the query, the role it was understood as, and the labels already guided to.
When a new screen reaches `ScreenReady`, the goal is re-resolved against it:

- resolved, and not already visited → offered („ამ ეკრანზე ვხედავ დენი. მიგიყვანო?")
- nothing resembling it → said plainly („ამ ეკრანზე შენს თხოვნას ვერაფერი ემთხვევა.")

**It does not know the kiosk's flow and never pretends to.** There is no table of steps and no
inference that a subscriber number follows a payment button. All that persists is what the
user said, re-matched against whatever appears. Finding nothing is reported rather than
guessed around — which is the same rule the rest of the app follows, applied to a multi-screen
task.

Three things guard it against becoming a nuisance:

- **Visited labels are not re-offered.** A kiosk often still shows the button just pressed
  while it redraws, and offering it again would send the user back where they came from.
- **Goals expire after five minutes.** Long enough for an unhurried payment; short enough that
  an abandoned transaction does not start advising whoever picks the phone up next.
- **Stop clears it.** Stopping is withdrawing from the task, not pausing it.

#### What this does not amount to

A full transaction still has not been performed, here or anywhere. The app can now carry a
goal across screens and will not move a finger without being told to, but neither path has
been exercised against a real kiosk, and the M6 acceptance criteria — reading back critical
details such as an amount before confirmation — are only partly met: the control is named and
its criticality stated, the amount on screen is not read back as part of the offer.

234 unit tests pass. The sealed `SpokenMessage` forced every new message through the renderer
and the audio coordinator, which is why adding them could not quietly skip either.

---

### R-27 — Four subsystems were written, tested, and never called

**Type:** audit + implementation, 2026-10-17. **Unit-tested; no device was attached, so
nothing here has been heard or seen on hardware.**

An audit of the shipping path found four components that exist, compile and pass tests, and
that **no production code reaches**. Verified by search before anything was changed:

```
cd android/app/src && grep -rn "ReconnectPolicy\|handleHypotheses\|\.listen(\|onModelChunk" . | grep -v Test
```

| Component | Lines | Reached from |
|---|---|---|
| `voice/ReconnectPolicy.kt` | 78 | its own test only |
| `speech/SpeechCommandRecognizer.kt` | 274 | constructed, but `listen()` never called |
| `assist/CommandParser.kt` | 45 | only `handleHypotheses`, which nothing calls |
| `AudioOutputCoordinator` model arbitration | ~60 | its own tests only |

This is worse than dead code, because each one is *described* elsewhere as working. R-22 states
that on giving up "the app falls back to on-device commands"; there was no reconnect path and no
fallback. Twelve coordinator tests passed against `isModelSpeaking`, which was permanently
false.

#### The failure the user would have met

`StreamingVoiceProvider` had no reconnection. When the socket closed - which R-24 documents as
routine on the no-cost tier, and which the documented ~10-minute connection limit guarantees -
`receive()` threw, the capture loop exited on `!session.isClosed()`, and nothing restarted it.
`LiveAssistUiState.voice` was **never read by `LiveAssistScreen`**, so there was no text, no
live region and no spoken message. The assistant stopped existing. The button still said
"listening".

#### What was built

- **Bounded reconnect.** One supervising coroutine owns connect / run / decide, so two sockets
  or two `AudioRecord`s cannot coexist; capture is cancelled **and joined** before the next
  connection opens one. `ReconnectPolicy` is finally constructed in production.
- **Planned rollover is detected, not guessed.** `LiveServerGoAway` is present in firebase-ai
  18.0.0 (verified against the AAR with `javap`: `getTimeLeft` returns a nullable
  `kotlin.time.Duration`). A warned disconnect reconnects in 250 ms and does not spend the
  retry budget; an unwarned one backs off by doubling and then gives up. This is how the
  connection limit in `AiConfig` actually arrives, so no timer was invented for it.
- **Failure is audible.** `SpokenMessage.VoiceAssistantUnavailable`, Georgian and English, says
  what still works — camera, screen reading and guidance are all local — and `listening` goes
  false so the UI stops claiming an open microphone.

#### A correction to the audit's own reasoning

`LiveServerGoAway.timeLeft` was first read off the javap signature as non-null, because the
name mangling `getTimeLeft-FghU774` suggested an unboxed inline class return. The compiler
disagreed. The signature was evidence about the shape of the method, not about nullability, and
it was treated as more than it was.

#### Dropped replies: the bug as arithmetic

`speakAssistant` discarded any reply within `DIRECTION_HOLDS_VOICE_MS` (3000 ms) of a spoken
direction. `GuidanceAnnouncer` repeats the same direction every 2500 ms. **3000 > 2500**, so
while the app was guiding the window never opened and every reply was dropped, logged only
under `diagnosticsEnabled`. The user asked a question mid-reach and the app said nothing.

Replies are now **deferred with a deadline** rather than discarded, by
`AudioOutputCoordinator.decideAssistantReply`, which is pure and tested. The constants are
anchored rather than guessed: the settle is 800 ms because `GuidanceAnnouncer.MIN_GAP_MS` is
700 ms — the spacing the app already requires between two of its *own* cues — and the deadline
is 4000 ms because that is longer than one full repeat cycle, so a reply is abandoned only when
the channel was genuinely never free. **Neither has been tuned against a device**, and the
right values depend on how long a Georgian directional phrase takes to speak, which has not
been measured.

#### Speech turns, and the microphone gap between chunks

`GuidanceSpeechOutput.isSpeaking` was a boolean set by `onStart` and cleared by `onDone`, so
between two queued utterances it read false and the microphone gate reopened on the app's own
next sentence. It now tracks the active utterance **id**: a terminal callback for an id that is
no longer current is ignored, which makes a multi-utterance turn read as one and makes
`QUEUE_FLUSH` safe. The waiting loops gained a 30 s ceiling, because an engine that never
reports finishing would otherwise hold the microphone closed for the rest of the session.

#### Stop, and what it honestly is

`onStopPressed` cancelled guidance but left queued speech playing, left the jobs that gate the
microphone running, and could be followed by an in-flight `select_target` landing and offering
a target aloud — the app resuming a task the user had just abandoned. Stop now also cancels
speech and those jobs, and a `userStopped` latch makes the assistant's actions no-ops until the
user asks for something again.

**A spoken "stop" is not reliable and is not presented as if it were.** It travels to Gemini
Live and back, and while the app is speaking a direction the microphone is paused and the word
is discarded unheard. The on-screen button is the only network-independent stop. Restoring a
local voice stop needs microphone ownership decided first, not `listen()` called:
`SpeechCommandRecognizer` and `CommandParser` are kept and documented as dormant for exactly
that reason.

#### Measurement, before any optimisation

No latency change was made. Instrumentation was added so the next decision has numbers:
frame acquisition and whole-frame cost (`LATENCY frame-acquire` / `frame-total`, beside the
existing hand / decide / announce-wait), the gap between the first transcription chunk and
`turnComplete` (how long the app sits on text it already has, because a turn is accumulated and
only spoken when the server says it is finished), and text-handed-to-engine → first audible
word.

`OcrLanguageComparisonTest` compares `kat` against `kat+eng` on one tessdata directory and one
corpus, **interleaved per sample** so thermal drift lands on both arms — R-24's lesson about
time-separated arms. It reports CER, exact-label rate, Latin-only exact rate and p50/p90, and
asserts nothing about the winner. **Production remains `kat+eng`.** It has not been run: no
device was attached.

#### Found while doing this

The instrumented suite **did not compile**. The uncommitted haptics work added `onHaptic` to
`LiveAssistPipeline.Listener` without updating the two anonymous implementations in
`HandheldMotionExperimentTest` and `LiveAssistScenarioTest`, so every device test — including
the end-to-end scenario that is this project's main evidence — was unbuildable. Fixed.

245 unit tests pass (`--rerun-tasks`). The count fell by one because twelve coordinator tests
covering the removed model-arbitration path were replaced by thirteen covering the policy that
actually runs.

---

### R-28 — A coffee machine is not a kiosk, and the detector says so silently

**Type:** investigation, 2026-10-17. **Not reproduced.** No frame of the machine — or of any
real screen — exists in the repository; the only image asset is MediaPipe's `pointing_up.jpg`.
Everything below is traced from code, and the device capture that would confirm it is specified
at the end.

#### The bound nobody examined

`ScreenDetector.MIN_COVERAGE` is 0.08: the bright region must fill 8 % of frame **area**. Its
comment reasons that "a screen smaller than this is too far away to read anyway", which assumes
a payment terminal.

A phone camera with a ~65° horizontal field sees roughly 52 cm across at 40 cm, so the upright
9:16 frame covers about 52 × 92 cm. A 10 × 6 cm machine display is 60 cm² of 4784 cm² — **1.3 %**,
six times under the bound. At 20 cm it reaches only ~5 %, and at that range the user's hand can
no longer occupy the ~40 % of frame width MediaPipe needs (R-13). **For a small display the
bound may be unreachable at every working distance that also permits fingertip tracking.**

Unchanged here. Which machines are in scope is a product decision, and moving a threshold before
a single real frame has been measured is how R-17 happened.

#### Why "cannot read" follows from "cannot detect"

The two symptoms are one chain, not two faults:

1. detector refuses → `ScreenTracker.geometry()` null;
2. `screenScanRegion` returns null → the full scan reads the **whole frame**, not a crop — the
   path R-11 measured at 2.6-6.3 s on a real camera scene and 37.6 s on a cluttered one;
3. `upscaleFor(isRegion = false, …)` calls `OcrUpscale.forLabelHeights(tracker.snapshot…)`, and
   `snapshot` is empty before any successful run, so it returns 1f. **Acquisition can only
   magnify after it has already succeeded** — a bootstrap trap, and R-16 measured that 2× is
   exactly what rescues text below ~20 px, which is what a small display at that distance gives;
4. nothing readable → `snapshot` stays empty → `onScreenConsistent` requires
   `snapshot.size >= MIN_INFORMATIVE_LINES` (2) → the phase never leaves `AcquiringScreen`;
5. a spoken request hits `labels.isEmpty()`, is deferred with „ვკითხულობ ეკრანს", and the retry
   only fires on `OcrUpdate.Unchanged`, which needs a non-empty previous run. **The app says it
   is reading the screen and then goes quiet for ever.**

#### OCR never sees a rectified image

Checked exhaustively: the only `drawBitmap` calls in the app are the 64×64 scratch downscale and
the analyzer's upright rotation (an affine). `ScreenSpace` exposes point and box conversions
only — no bitmap. `screenScanRegion` produces an **axis-aligned bounding box of the quad**, and
`KioskOcrEngine` applies it with `setRectangle` on the unrectified frame.

The homography corrects *coordinates* for guidance and nothing else. Text skewed by viewing
angle reaches Tesseract skewed, and the bounding box of a rotated quad also feeds it a wedge of
surrounding scene. Head-on at a terminal this costs little. A machine display at chest height,
leaned over, is the case where it costs most — and it is untested either way.

#### Also: many machines have no text

`TargetMatcher` matches recognised **text**. A pictogram-and-button coffee machine may present
almost none, in which case every stage above is working correctly and the product still cannot
help. Worth settling before any detector work.

#### What was changed

Diagnostics only; no algorithm, no threshold.

`ScreenRejection` (`NoThreshold`, `NoBrightRegion`, `TooSmall`, `TooLarge`, `HullTooSmall`,
`NoCorners`, `DegenerateQuad`) with the measured coverage and the three confidence parts, all
recorded during `detect` and read by nothing that makes a decision. Surfaced in `DebugFrame`,
in the overlay, and in two 1 Hz logcat lines (`SCREEN`, `ACQUIRE`) that carry **no recognised
text** — that is screen content and stays in `blocks`, which only a debuggable build renders.
`SmallScreenDetectionTest` pins the refusal reason, and also records two behaviours nobody had
written down: a dark display in a bright room is detected *as the room*, and a specular
highlight larger than the display wins the largest-component contest.

---

### R-29 — The overlay showed OCR output after the filter, so two opposite failures looked the same

**Type:** investigation, 2026-10-17. **Not reproduced**; no captured frame of the machine
exists. Diagnostics only — no threshold, no language, no algorithm changed.

The machine's screen is now detected, and its text still is not read. Three gates can produce
that, and the existing diagnostics could not tell them apart.

#### `DebugFrame.blocks` is the post-filter snapshot

`blocks = tracker.snapshot`, and `snapshot` is what survived
`confidence >= MIN_RETAINED_CONFIDENCE && letterCount(text) >= 2`. So "Tesseract read nothing"
and "Tesseract read the screen and the app discarded it" were **the same observation**, with
opposite fixes. Raw output, the retained count and a per-block rejection reason are now carried
beside it.

#### `letterCount` counts letters, not digits

A screen whose controls are numerals — a cup size, a temperature, a price — loses all of them
at intake: `250`, `1`, `80°`, `1.50` are each under the two-letter floor. Correct for a payment
terminal, whose controls are words. An open question for a coffee machine, and **not changed**:
it needs a real frame and a measured recognition rate.

#### `screenScanRegion` refuses a wide, short screen

The crop is dropped unless its bounding box is at least a quarter of the frame **in both
axes**. A display at 45 % wide and 18 % tall passes detection at 8.1 % area and still fails the
height guard, so the scan falls back to the whole frame — the path R-11 measured at 2.6-6.3 s —
and `upscaleFor(isRegion = false, …)` then returns 1× because `snapshot` is empty. The overlay
now prints `scan WHOLE FRAME at 1.0x`, which names this in one glance.

#### Confirmed: OCR is given the crop, never a rectified image

Exhaustive check — the only `drawBitmap` calls are the 64×64 scratch downscale and the
analyzer's upright rotation (affine). `ScreenSpace` has no bitmap overload. The homography
corrects coordinates for guidance and nothing else, so text skewed by viewing angle reaches
Tesseract skewed.

#### What was added

`KioskOcrEngine.dumpNextInput` writes the exact image Tesseract reads — after crop, after
magnification — to app-private storage, once, on a debuggable build, armed by turning the
developer overlay on. Every OCR investigation so far has been blocked by not having that image.

`OcrPreprocessingComparisonTest` runs the production engine over that dump under one
transformation at a time (baseline / grayscale + percentile-clipped contrast / adaptive mean
threshold / 2×) and reports p50, raw block count and retained count. It **skips** without the
asset rather than substituting a rendered stand-in, which would answer a different question.

`OcrIntakeFilterTest` characterises the filter; `TargetTracker.isRetainable` and `rejectionFor`
are now the single definition both the behaviour and the overlay use, so they cannot drift.

256 unit tests pass.

---

### R-30 — Screen detection silently broke OCR acquisition, and the counters proved it

**Type:** defect + fix, 2026-10-17. **Confirmed on the physical SM-S731B, before and after.**

`maybeStartOcr` decided whether a run was a re-anchor with `region != null`. That proxy was
correct until `f6a4e12` taught a full scan to crop itself to the detected screen. From then on
**every acquisition scan was counted and dispatched as an anchor**:

- `ocrJobsStarted` and `acquisitionRuns` never incremented, so acquisition could never complete;
- `lastOcrStartMs` was never updated, so `OCR_MIN_INTERVAL_MS` and the acquisition back-off
  never engaged and OCR ran unthrottled;
- the result went to `onAnchorResult`, which with no target returns `NoTarget` and **returns
  without touching `tracker.snapshot`** — the recognised text was discarded.

The screen was detected throughout. The app simply never learned what was on it.

#### Measured on the device

| | before | after |
|---|---|---|
| full scans counted | `ocr=0ms/0` | `ocr=507ms/41` |
| runs mislabelled as anchors | `anchor=516ms/58` | `anchor=0ms/0` |
| lines reaching the tracker | `lines=0/2` | `lines=14/2` … `24/2` |
| last OCR outcome | `Anchor:NoTarget` | `Unchanged` |
| phase | stuck in `AcquiringScreen` | `AcquiringScreen -> ScreenReady  lines=14  textPx=16` |

Acquisition completing on this hardware had never been observed before.

#### The fix

`val isAnchorRun = due == OcrKind.Region` — the decision already existed; it was simply not
used. `region != null` is kept for `upscaleFor`, because magnification depends on how much
image is read, which really is a property of the crop: the device log confirms a cropped full
scan still runs at `@2.0x`.

#### Why no test caught it

`LiveAssistScenarioTest` paints a full-white frame, so no quadrilateral is found,
`screenScanRegion` returns null and the old proxy stayed accidentally correct — the test could
not reach the bug. `OcrAcquisitionRoutingTest` renders a **bright panel inset on a dark
surround**, asserts the crop is actually taken before anything else so it cannot pass vacuously,
then checks the counter, the snapshot and the dispatch. Reinstating the bug on the device made
it fail with `acquired=false phase=AcquiringScreen lines=0`, which is the production symptom
exactly; restoring the fix made it pass.

#### Noted, not acted on

With a hand-held phone on a changing scene the screen-change trigger fires often, so after
`ScreenReady` full scans still run roughly every 0.5-1.5 s against a 250 ms floor. That is now
*bounded*, where before it was not, but whether the trigger is too eager is a separate tuning
question needing its own measurement.

256 unit tests and 2 instrumented tests pass.

---

### R-31 — Declining to use the assistant started the assistant

**Type:** defect + fix, 2026-10-17. **Confirmed by reading the shipping path**; the microphone
behaviour itself has not been re-observed on a device since the fix.

The consent dialog offers two answers. „ყველაფერი ამ ტელეფონში დარჩეს" — keep everything on
this phone — saved `assistantEnabled = false`, and then:

```kotlin
// onVoiceCommandPressed, before
!voicePreferences.hasChosen -> showAssistantConsent()
else -> startListening()        // StreamingVoiceProvider -> Gemini Live
```

`hasChosen` asks whether the user was *asked*, never what they answered. `assistantEnabled` was
read in exactly one place — inside `handleHypotheses`, which no production code reached, the
same shape as every component in R-27. So a user who declined had their microphone streamed to
Google on the next press, and `onAssistantConsent` ends by re-entering this routine, which
means **the press that declined consent opened the session it was declining**.

Two further statements were false for the same reason. `LiveAssistUiState.voiceMode` — whose
own documentation calls it an invariant, "exactly one may hold it" — was never assigned by
anything, so it read `LocalCommands` while the app streamed, and the button therefore never
showed `action_assistant_on` („microphone streaming"). A blind user was told "Listening" in
both modes.

#### What was built

`speech/VoiceRouting.kt`: one pure function from (listening, permission, hasChosen,
assistantEnabled, liveAvailable) to one of five actions, and `VoiceRoutingTest` pins it —
including the two cases above, stated as what they are, decisions about whether the user's
voice leaves the device.

Declining now opens the **on-device** path instead of nothing: `SpeechCommandRecognizer`,
`CommandParser` and `handleHypotheses` were written, tested and dormant (R-27 kept them
deliberately), and the reason given for leaving them dormant — microphone ownership — is
settled by routing, because the two paths are alternatives that can never run at once. That
also restores what the consent text had been promising all along: "it recognises short spoken
commands on the phone itself".

R-25 removed the *looped* push-to-talk, and this does not bring it back: one turn per press.
The loop's defect was the system recogniser's 800 ms silence cut-off and its tone on every
restart, neither of which a single press has.

A session that gives up reconnecting now falls back to the same local path
(`liveAvailable = false`) rather than leaving a button that reopens a socket known to be dead.

#### The gate the revived path would have skipped

`handleHypotheses` sent a recognised target straight to `pipeline.selectTarget`, which begins
guidance and announces it. That was written before M6, which says a control is **offered** and
nothing moves until the user agrees — and a user who declined the streaming assistant is owed
that gate most of all, because the screens where it matters are the ones with money on them.
The local path now goes through the same `AssistantActions.selectTarget` the model calls, so
both paths offer first.

Which needed a local word for "yes". `CommandParser` gained `Confirm` and `Cancel`, checked
before the target query so that „დიახ" is an answer to the app rather than a label to hunt for
on the kiosk — and „სხვა მინდა" cancels instead of searching for a button called „სხვა", which
on a payment terminal exists. `LocalReply` turns the outcome of an action into a sentence,
because with no assistant present nobody else would: the actions answer in `ToolOutcome`s,
which are words for a model, and a user who hears nothing cannot tell a refusal from a crash.

**Not verified on a device.** What is verified is that the decision is now made from the user's
answer, by a test that fails if it stops being.

---

### R-32 — The prices were the only text on the screen the app could not read

**Type:** measurement + fix, 2026-10-17. **Confirmed on the physical SM-S731B**, before and
after, against `assets/ocr_test/coffee_machine_screen.png` and its published ground truth.

Reading the target at 1920 px wide, Tesseract returned 42 lines: every drink name in Georgian
and English, both volumes, the stepper values, the title, the clock. Of the five amounts on the
screen it returned **one**.

The amounts are the one kind of text there printed in an accent colour — amber on cream. That
is a large colour difference and a small luminance difference, and luminance is all a
binarisation sees. The drink names are near-black on the same cream and were never in doubt.

| image | cost | amounts read |
|---|---|---|
| untouched | 393 ms | **2 of 5** |
| grayscale + percentile-clipped contrast | 407 ms | **5 of 5** |
| 2x magnification | 710 ms | 5 of 5 |
| both | 690 ms | 5 of 5 |

(at 1280 px, the realistic framing from the asset's README; `PriceLegibilityTest` is the harness
and asserts only that *some* transformation beats the untouched image.)

Contrast normalisation and magnification recover the same text here and one of them costs
twenty times more. So `ocr/OcrPreprocess.kt` now runs on every OCR input: grayscale, histogram
stretched between the 2nd and 98th percentile, so a specular highlight — normal on a glossy
kiosk screen — cannot define the white point. Magnification keeps its own separate job, text
that is too *small* rather than too faint (R-16); the two are not substitutes.

`KioskOcrEngine.recognize` was restructured around it. Every run now prepares one image — crop,
normalise, magnify — and hands Tesseract exactly that. `setRectangle` on the original frame is
gone, because preprocessing has to see the crop's own histogram: a bright screen in a dark room
must be stretched by what is on the screen, not by the room. The region rectangle is also
clamped to the bitmap now; it arrives from screen geometry, and reading past the edge throws,
which the pipeline would have reported as "OCR found nothing" — indistinguishable from a dark
screen.

#### The case the fix could have broken

Percentile clipping is a trap on an image whose text covers less than the clipped tail — a
kiosk screen with a few words on a plain background. Both percentiles then land *inside the
background*, the span collapses to the background's own grain, and stretching it turns grain
into ink and the words into nothing. Checked on the JVM rather than reasoned about:
`OcrPreprocessTest` builds a page with 1 % ink and asserts the text is still 100 levels darker
than the paper afterwards. Below `MIN_SPAN` (40 of 255) the image is converted to grayscale and
left alone, which is what Tesseract would have done to it anyway. Amber against cream is about
100 levels apart, so the case this exists for is far clear of the guard.

#### What OCR does to ₾

Never once returned correctly. Across the five amounts: `3.50 C`, `4.00 დ`, `5.00 &`, `5.00 ©`,
`8.50 C`. A parser that insisted on U+20BE would have discarded every price on the screen, so
`MoneyParser` accepts a decimal number followed by at most **one** unexplained character.
Two or more is a unit — „150 მლ" is not an amount — and that single character is the whole
distance between the two.

---

### R-33 — A price belongs to a drink only when the layout says so

**Type:** implementation, 2026-10-17. **Rules unit-tested only.** `PriceAssociationTest` runs
them over text real Tesseract produced from the target, and has so far only been run in its
failing state — the run that produced R-32, before the preprocessing fix existed. The device
was disconnected before it could be run again, so *that an amount reaches the right drink
end to end on this hardware is not yet verified.* What is verified on the device is the half
below it: with the new preprocessing, all five amounts are recognised.

"რომელია უფრო იაფი?" could not be answered at all. The model is given no screen data by
construction (ADR-009), and the device had no notion of an amount belonging to an option — OCR
produced a flat list of lines.

`OptionPricing` pairs them, and the interesting part is what it refuses. An amount is attached
to an option only when the amount is written like money, was read confidently enough to be
spoken as a number (0.8, or 0.6 corroborated across runs — the R-12 rule, from a higher floor
because a misread digit sounds exactly like a right one), the option is a trustworthy line of
words that is **not** a recognised control, the option sits directly above the amount in its
column or immediately left of it on the same row, and the runner-up candidate is clearly
farther away. Two options equally close to one amount is precisely where a guess is wrong half
the time, so neither gets it.

Controls are excluded outright. „გაგრძელება 5.00 ₾" would be heard as the cost of continuing,
which is a claim about what the kiosk will do.

The answer is spoken by the app, from `SpokenMessage.CheaperOption` / `OptionPrice`, in lari
and tetri rather than as "3.50" — a Georgian voice reads a decimal as a number, and a blind user
cannot tell which was meant. The model calls `compare_prices` with the user's own words and is
told `comparing` or `priceunknown`. It learns no amount, no label and no position, so ADR-009
is untouched: the boundary moved zero and the capability is new.

---

### R-34 — The confirmation gate never re-checked the screen

**Type:** defect + fix, 2026-10-17. Found by audit; `TargetResolver.revalidate` was already
unit-tested.

M6 offers a control and waits: nothing is guided to until the user says yes. Saying „დიახ"
takes a second or two, and a kiosk can advance in that time — on a timeout, on a card going in,
or because the previous press went through. `confirmTarget` started guidance from the target as
it had been **offered**, without looking at the screen again.

`TargetResolver.revalidate` exists for exactly this, carries `sceneObservedAtMs` for exactly
this, and was reached by no production code — R-27's pattern for the fourth time in this
repository, and this time on the gate that stands between a user and a finger sent to where a
button used to be.

It is now called. A control that no longer matches withdraws the offer and says so in the app's
own voice („ვერ ვპოულობ…", hold still, or cannot read the screen, depending on *why*), and the
model is told the matching outcome. The mapping from a resolution failure to that outcome is one
function now (`ResolutionFailure.asOutcome`), shared by both paths, so "it is not on this
screen" and "I cannot read the screen" cannot drift into meaning the same thing.

---

### R-35 — Two numbers that were right in the tests and wrong in production

**Type:** defect + fix, 2026-10-17. Both found by reading the code against its own tests.

**The pixel budget was charged to the wrong image.** `OcrUpscale.cappedToPixelBudget` bounds
what Tesseract is given, and `upscaleFor` passed it the **frame** size while recognition read a
**crop**. On a 720×1280 frame a re-anchor region of 200×60 was therefore cut from 2x to 1.54x —
on the one path where magnification matters most, because a region run is how a target's
position stays fresh under blur. `OcrPixelBudgetTest` had always asserted the crop-sized
behaviour ("a re-anchor crop is a fraction of the frame; 2x of it is cheap and must stay 2x");
production simply never called it that way. It now passes the region's own dimensions, which
also lets an acquisition scan cropped to a detected screen magnify — the bootstrap trap R-28
described, closed without a new rule.

**Screen detection ran on the change-detector's grid.** Both shared one 64×64 downscale, so a
corner was localised to ~11 px across and ~20 px down on a 720×1280 frame — the same order as
the on-target zone, as this file has recorded since R-21. The two want different things from
the frame: the signature asks whether the picture changed, which survives being coarse;
detection produces the corners. Detection now has its own 128×128 grid, halving that error.
Measured cost: 1.26 ms per detection against 0.35 ms, every 220 ms, plus one extra downscale
(`ScreenDetectorCostTest`).

Neither change has been seen at a kiosk, and the second has not been seen through a camera at
all.

---

### R-36 — Validation attempt without the device, and the two gaps it exposed

**Type:** validation attempt + fix, 2026-10-17 (later session). **The device was not connected
and nothing here was run on hardware.** Checked at two levels before saying so: `adb devices`
is empty, `system_profiler SPUSBDataType` shows no Samsung or Android device on USB, and
`adb mdns services` finds nothing offering wireless debugging. So the blocker is physical, not
a daemon that needed restarting.

What that means for every claim in R-31 through R-35: the unit-tested half stands, the hardware
half is still owed. `PriceAssociationTest` — real OCR to a priced drink — **has still only ever
run in its failing state**, which is the run that produced R-32.

#### Two defects found while preparing the run

**The confirmation gate had a window its own timestamp could not see.** `KioskScene.observedAtMs`
dates the *geometry*, which is re-detected several times a second. The labels come from OCR,
which is far slower. So between a kiosk advancing and the next full scan landing, a scene looks
fresh while listing the previous screen's labels — and `revalidate` would confirm against them
happily, because they match the offer perfectly. The scene now carries `changeSuspected`, set
from the screen-change comparison the pipeline already runs (`screenChangeSuspected ||
pendingScreenChange`), and `ConfirmationGate` refuses while it is set: the user is told the
screen changed, which is true and actionable. No new threshold was introduced — this is
existing evidence, not a timer.

This does not close the window entirely, and nothing can: between a screen physically changing
and the app having any evidence of it, there is nothing to go on. What it removes is the case
where the app *had* the evidence and confirmed anyway.

**The gate was untestable, so it had no regression test.** `confirmTarget` was decision and
side effect in one method on an `AndroidViewModel`. The decision is now `ConfirmationGate`, a
pure function over (offer, scene, clock), and `ConfirmationGateTest` covers the ten cases that
matter — including the one R-34 was about: a payment screen that has advanced to a PIN prompt
cannot be confirmed. The ViewModel keeps every side effect and makes no decisions.

#### The consent boundary, hardened where it could be

Step 3 of the validation brief is a device test and remains unrun. What was done without one:
both production paths to Google now refuse for themselves rather than trusting their callers.
`startListening` falls back to the on-device path and logs the refusal; `askAssistant` does the
same. R-31 was not a wrong decision — it was a caller that never asked — so the guard belongs
at the point where the socket and the microphone are opened, not only at the point where the
button is pressed.

`VoiceBoundaryTest` grew to cover `AssistantActions`, which had become the widest surface
between the app and the model and was not checked by it. Three new assertions: no perception
type in any signature, every action returns a `ToolOutcome` and takes only text, and
`ToolOutcome` has no instance fields at all — so an outcome cannot acquire a label, a position
or an amount without the build failing. `compare_prices` is exactly the feature that would
otherwise have been built by handing the model the amounts.

#### The harness

`tools/device-validation.sh` and `tools/summarise-connected.py`: one command per validation
step, each capture in its own timestamped directory, device and build recorded beside the
results so a number in a report can be traced to the run that produced it. It finds `adb`
itself rather than assuming a PATH, because a validation script that reports "no device" for
the wrong reason is worse than none.

Both were exercised against a synthetic capture built from the app's own log formats, rather
than written and left: the summariser parses `LATENCY`, `OCR`, phase transitions and the
instrumented-test XML, and prints a stage with **no samples** as such instead of omitting it.
An absent measurement and a fast one must not look the same.

#### One measurement gap closed

Step 6 asks for guidance-decision to first audible word. Every other stage was instrumented;
that one was not, for directions — only for assistant replies. `speakGuidance` now records it
through the same `LatencyTracker` and prints it in the same `LATENCY` shape, so one capture
summarises the whole chain. Measurement only: nothing about what is spoken, or when, changed.

343 unit tests pass from a clean task state. Both APKs build. No instrumented test has been run.

---

### R-37 — The device found what the unit tests could not: a price attached to a volume

**Type:** validation + defect + fix, 2026-10-17 (third session). **Confirmed on the physical
SM-S731B, Android 16 (sdk 36), fingerprint `S731BXXS8BZG1`.** The USB connection dropped three
times during the session; what is recorded below ran to completion, and what did not is named
as such.

#### The full suite, first complete run against R-31…R-36

**18 passed, 2 failed, 1 declined.** The declined one is `OcrPreprocessingComparisonTest`,
which refuses to run without a captured frame rather than substituting a rendered stand-in —
working as designed, and reported as a failure only because the summariser was classifying
`AssumptionViolatedException` as one. Both real failures mattered.

#### The defect: a price belongs to the drink, not to the line above it

`PriceAssociationTest` had never run in a passing state. On the device it produced, verbatim:

```
width=1920 blocks=49 amounts=5 priced=4 ->
  150 8c / 150 ml=4.00 &, 40 მლ / 40 ml=3.50 &, 220 მლ / 220 ml=5.00 C, 180 მლ/180 ml=5.00 C
```

Every amount was recognised, every pairing fired, and every one of them attached the price to
the **volume**. A product card stacks a name, a translation and a volume above its price, so
the nearest label above an amount is the volume — and `OptionPricing` chose by distance.

R-33's unit tests did not catch it because their fixtures were cards of a name and a price.
The rule was tested against the layout it was written from, which is the failure mode a
synthetic fixture has and a photograph does not.

**Two rules replace distance-alone, both grounded in what is on the screen rather than in a
threshold:**

- A line containing digits cannot name an option. Volumes, counts, terminal ids and dates are
  descriptions of a thing, never the thing's name. Kiosk product names do not contain digits.
- Among the candidates that remain, the most **typographically prominent** tier wins, and
  distance only separates labels of equal prominence. The drink's name is the largest text on
  its card — 52 px against 27 for the translation and 22 for the volume — which is how the
  screen itself says which line is the title. `SAME_TIER` is 0.8, which separates that card
  cleanly while keeping two names set in the same size competing, because two equal claims on
  one amount must stay ambiguous and get no price at all.

Verified on the device afterwards, against the ground truth published with the target:

```
width=1920 priced=4 -> ესპრესო=3.50, ამერიკანო=4.00, კაპუჩინო=5.00, ლატე=5.00
width=1280 priced=2 -> ესპრესო=3.50, ლატე=5.00
```

Four of four correct at full size, two of four at the realistic framing — fewer because fewer
names survive recognition there, not because any pairing was wrong. **This is the first time an
amount has reached the right product through real OCR on this hardware.**

#### The second failure was the test, not the code

`PriceLegibilityTest` reported `{baseline=5, grayscale+clip=5, 2x=5, both=5}` and failed its own
assertion. Its "baseline" arm called `KioskOcrEngine.recognize`, which stopped being a baseline
the moment R-32 taught the engine to normalise contrast: every arm was getting the production
transformation and the comparison measured nothing. A test that no longer tests what it claims
is worse than no test, because it is quoted.

The untouched arm now drives `TessBaseAPI` directly, with the app's own model and page
segmentation mode. Re-measured through the path that actually ships:

| arm | cost | amounts read |
|---|---|---|
| untouched raw Tesseract | 407 ms | **2 of 5** |
| grayscale + percentile clip | 453 ms | 5 of 5 |
| 2x magnification | 729 ms | 5 of 5 |
| **production engine** | **423 ms** | **5 of 5** |

R-32's claim, confirmed against the shipping path: **+16 ms, and two amounts become five.**

#### Measured latency, and what it is not

From the one complete suite run (88 OCR runs, 200 frames):

| stage | n | p50 | p90 | max |
|---|---|---|---|---|
| OCR run | 88 | 707 ms | 2816 ms | 6865 ms |
| frame acquisition | 200 | 13 ms | 26 ms | 73 ms |
| whole frame | 200 | 96 ms | 146 ms | 213 ms |
| guidance decision | 171 | 0 ms | 0 ms | 1 ms |

**The other three stages produced no usable number and are not reported as if they had.** Hand
tracking recorded **one** sample across the entire suite, because the instrumented tests feed
rendered frames rather than a hand in view; `announce-wait` reads 12.8 s at p50, which is an
artefact of synthetic harnesses holding a direction across long gaps, not anything a user would
experience; and `speak-start` cannot be attributed to a known session. Camera-to-fingertip and
decision-to-speech remain **unmeasured**, and measuring them needs a person holding the phone.

#### Three bugs in the validation tooling itself, found by running it

Worth recording because the whole point of the harness is to be trusted. `set -e` killed the
script when `grep` found no installed package — "not installed" is a state to report, not a
failure. A comma-separated class filter silently ran only the first class, which nearly
produced a reported pass the suite had never produced. And the summariser crashed on a
`<failure/>` carrying neither message nor text. All three were found by using it, which is the
argument for exercising a tool rather than shipping it.

#### Not verified

The full suite has **not** been re-run end to end against the current tree: the device
disconnected twice while trying. The two price tests pass individually; the other 18 passed
before the `OptionPricing` change, which nothing else in the suite exercises. Steps 3, 4 and 6
of the validation brief — consent through the real UI, a live camera view, and latency from a
hand-held session — still need a person and a stable cable.

---

### R-38 — The instrumented suite, run class by class on hardware

**Type:** validation, 2026-10-17 (fourth session). **Run on the physical SM-S731B, Android 16
(sdk 36).** The USB link dropped five times across the session, twice mid-run, which is why
the suite was finally run one class at a time rather than in one pass. Wireless debugging was
attempted and is not available: the phone is reachable on Wi-Fi (192.168.79.9) but the Mac
cannot reach it at all — 100 % packet loss, client isolation on that network.

| class | result |
|---|---|
| `LiveAssistScenarioTest` | **PASS** — the project's main end-to-end evidence |
| `OcrAcquisitionRoutingTest` | **PASS** — the R-30 regression |
| `PriceAssociationTest` | **PASS** — ესპრესო=3.50, ამერიკანო=4.00, კაპუჩინო=5.00, ლატე=5.00 |
| `PriceLegibilityTest` | **PASS** — raw Tesseract 2/5 amounts, production engine 5/5 |
| `OcrUpscaleCostTest` | **PASS** (new) |
| `ModelsOnDeviceTest`, `OcrMotionDiagnosticTest`, `OcrLanguageComparisonTest` | **PASS** |
| `OcrPerformanceTest`, `RealKioskEvaluationTest` | **PASS** |
| `GeorgianOcrAccuracyTest` (x2), `HandVisibilityTest`, `ExampleInstrumentedTest` | **PASS** |
| `OcrPreprocessingComparisonTest` | **declines** — no captured frame, by design |
| `HandheldMotionExperimentTest` | **FAIL, cause undetermined** |

#### The one failure, and what is honestly known about it

It **passed** in the first full-suite run of the day and **failed** when run standalone in the
batch. The device disconnected before it could be re-run to read the metrics it prints —
retention, clear-label availability, false arrivals, reversals — so *which* of its five gates
failed is not known.

What is established by reading it: none of this session's changes can reach it. It never
enables diagnostics (so the new `ANCHOR` line is not emitted in it), and it touches no pricing,
no `KioskScene.changeSuspected` and no confirmation gate. The one earlier change that does
reach it is R-32's contrast normalisation, which applies to the region runs it depends on —
and that was already in place when it passed this morning.

Its own documentation records the same sensitivity in the opposite direction: "Measured 30/30
standalone and 29/30 in a full-suite run". With a floor of 0.95 over 30 trials, two lost
targets fail it. So the candidates are a marginal randomised gate or a real regression, and
**the evidence available does not separate them.** It is the project's motion-tolerance
evidence, so this is not a failure to wave through: it needs one re-run with its log captured.

#### Magnification: measured, and deliberately not acted on

`OcrUpscaleCostTest` was added because the dominant OCR path — a full scan cropped to the
screen — magnifies 2x because the image is a *crop*, not because its text was measured small,
and that path costs p50 1202 ms / p90 2362 ms in the field.

| framing | name height | 1x | 1.5x | 2x |
|---|---|---|---|---|
| screen fills 100 % | ~20 px | 791 ms, 4/4 names, 5/5 amounts | 754 ms, 4/4, 5/5 | 825 ms, 4/4, 5/5 |
| 75 % | ~15 px | 550 ms, 4/4, 3/5 | 537 ms, 4/4, 3/5 | 446 ms, **3/4**, 4/5 |
| 50 % | ~10 px | 340 ms, 4/4, 0/5 | 396 ms, 4/4, 0/5 | 396 ms, 4/4, 1/5 |

Magnification is close to free here and buys nothing on names at any framing; at 75 % it read
one fewer. The likely reason is that R-16 measured 2x **before** contrast normalisation
existed, and normalisation now recovers the same text more cheaply.

**Nothing was changed on the strength of this.** One fixture, one sample per cell, and the
direction is mixed — amounts improve slightly at the two smaller framings while names get
worse at one. Moving a threshold on that is how R-17 happened. The table is recorded so the
next person has a starting point and a second fixture to beat.

#### The re-anchor path, visible for the first time

R-37 noted that 88 logged OCR runs contained no re-anchor run at all, because the `OCR in=`
line sits after the anchor-run early return. With the new `ANCHOR` line and diagnostics
enabled in the scenario test, one run produced 34 of them:

```
ANCHOR in=Region crop 16,671 268x62 (2% of frame) -> 536x124 @2.0x  17ms blocks=1  -> Anchored
ANCHOR ...                                                            8ms blocks=0  -> NotSeen
... 17 consecutive Anchored at 16-25 ms ...
ANCHOR ...                                                          225ms blocks=24 -> NotSeen
```

Three things fall out of it. The cheap path works and costs **5-25 ms** against 78 ms for a
full scan in the same harness and 1202 ms in the field. The scenario's hand-over-label steps
show as `blocks=0`. And the screen transition shows as `blocks=24 -> NotSeen`: the crop was
full of text, none of it the target.

**Those last two are opposite situations that both reported `NotSeen`** and were
indistinguishable in any capture. "I could not read anything" and "I read plenty and none of
it was your button" call for different responses, and the second is strong evidence of a
transition that the anchor path deliberately refuses to judge. That refusal is a documented
decision and is left alone; what changed is that it is now observable, which is the
precondition for revisiting it with evidence rather than opinion.

The log line was verified by running it, not by compiling it: `LiveAssistScenarioTest` now
enables `diagnosticsEnabled`, because a diagnostic nobody has seen produce output is a
diagnostic that may not — `describeOcrInput` once crashed the pipeline thread with
`IllegalFormatConversionException` and no test caught it.

---

### ADR-009 — The voice model has no authority over kiosk state, and is never told it

**Status.** Proposed. Supersedes nothing; extends ADR-006 to the audio channel.

**Context.** ADR-006 already says an LLM may interpret intent but may never
assert a target. It was written for an LLM whose output the app consumes as
text. Gemini Live breaks that assumption: the model has a **direct audio path
to the user's ear**, and nothing the app does sits between them.

R-20 is what that gap looks like in practice. Asked for forty lari, the model
announced „მიღებულია ორმოცი ლარი" — *forty lari received* — along with
„თანხა დადასტურებულია" and „გადახდის რეჟიმი აქტიურია". No kiosk existed.
The user is blind by definition of this product and cannot check any of it.

Two rounds of prompt hardening stopped the observed phrasings and left the
assistant reflecting the user's words back, useless. That is the signature of
the wrong instrument: a prompt can only forbid, so safety was bought by
removing capability. The spec already describes the right structure — an
`AudioOutputCoordinator`, a typed decision validator, "ordinary dialogue"
for the model and "deterministic, predictable local cues" for geometry — and
it has simply not been built. R-20 shows it cannot be deferred.

**Decision (proposed).** Authority over what is true about the kiosk is
separated from the ability to speak, by three mechanisms, in order of
strength.

1. **Information denial.** The voice session is never given OCR text,
   detected blocks, screen geometry, target identity or transaction state.
   `VoiceProvider` exposes no method that can carry them. A model that was
   never told what the screen shows cannot describe it, truthfully or
   otherwise — this is a property of the wiring, not of the model's
   compliance, and it is the only control here that does not depend on the
   model behaving.

2. **A closed vocabulary for every screen claim.** Everything the user hears
   about the screen, a control, a position, an amount or a confirmation is a
   `SpokenMessage` value, rendered locally and grounded in the deterministic
   perception pipeline. `SpokenMessage` is a sealed interface precisely so
   that "everything the app may say" is enumerable and reviewable. The model
   contributes no words to this channel.

3. **Arbitration, with the local channel winning.** An `AudioOutputCoordinator`
   serialises the two sources so they never overlap. Directional and safety
   cues preempt conversational audio; queued cues are dropped when the screen
   or target changes under them.

The prompt restrictions from R-20 stay, demoted to defence in depth. They are
no longer the control.

**What the model is still for.** Georgian speech understanding and ordinary
dialogue: catching the goal, asking a clarifying question, confirming back
what the *user said*. That is the part R-19 shows it is good at, and it is
worth keeping — the collapse into parroting was a cost of the prompt-only
approach, and this design is what lets the restriction be relaxed again.

**Consequences.**
- The catastrophic failure in R-20 becomes structurally unavailable rather
  than prompt-discouraged.
- The invariant is testable. An architecture test can assert that
  `VoiceProvider`'s surface never references perception types, so a future
  change that hands the model screen data fails the build instead of
  shipping. An invariant that is not executable is an intention.
- Barge-in gets easier, not harder. The app knows exactly when its own voice
  is speaking, so a half-duplex gate over the local channel is deterministic;
  this is the route to re-enabling the interruption that R-18 turned off.
- The assistant is less impressive. It cannot answer "what's on the screen?"
  in its own words — that answer arrives in the app's voice instead. Using a
  distinguishable voice for the local channel is proposed so the user learns
  that screen facts only ever come from one of them.
- Cost falls if model audio output is later dropped for text, though that is
  a separate decision and is not taken here.

**Open.** Whether the model keeps audio output at all, or is reduced to
speech-in / intent-out with every spoken word produced locally, is deliberately
left open: it depends on the ~30-utterance evaluation (R-19) that is still
unfinished, and on whether dialogue quality survives the restriction.

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

### ADR-008 — Build for Android alongside iOS

**Status.** Accepted (product owner decision, 2026-10-14).

**Context.** The original plan targeted iOS only. An Android app
(Kotlin, Compose, CameraX) was started in parallel and reached a working
camera foundation with an accessible camera-permission flow. The product
owner decided to keep both platforms in this repository.

**Decision.** Maintain native apps for both platforms: `android/` and
`ios/`. Documentation stays shared.

**Consequences.**
- ADR-001 (iPhone Pro / LiDAR baseline) only covers iOS. Most Android
  phones have no LiDAR/ToF sensor, so on Android finger guidance must rely
  on ARCore depth (where supported) or pure 2D geometry, which R-07 shows
  is parallax-limited. Spike-01 must measure the Android path separately.
- Apple-specific findings (R-01–R-03, R-09) do not transfer to Android;
  Android equivalents (e.g. MediaPipe hand landmarks, Android TTS voices)
  need their own measurements.
- Roughly doubles platform implementation effort for every milestone.

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
