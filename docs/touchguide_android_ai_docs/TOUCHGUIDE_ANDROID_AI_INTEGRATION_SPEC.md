# TouchGuide Android — AI Integration Specification

**Date:** 15 October 2026  
**Platform:** Android only  
**Language:** Kotlin  
**Primary user language:** Georgian (`ka`)  
**Initial kiosk target:** TBC Pay  
**Status:** Engineering plan; API/model compatibility and real-device quality must be measured before production claims.

## 1. Product goal

TouchGuide is an Android accessibility app for blind and visually impaired people using physical touchscreen kiosks. The user holds the Android phone in one hand and touches the kiosk screen with the other. The phone camera observes the kiosk and the user's fingertip; the assistant listens to Georgian speech and gives short Georgian voice instructions.

Example: the user says **„დენის გადახდა მინდა“** once. TouchGuide interprets this as an electricity-bill-payment goal, finds a matching control that is actually visible on the current kiosk screen, guides the user's finger toward it, observes the physical user's action and resulting screen transition, and continues with the same goal until success is independently verified or the assistant must stop and ask for clarification.

### Core product requirements

- Once the user opens TouchGuide, grants permissions, and has consented to cloud voice processing, the active foreground session should listen continuously. No microphone-button press is required before every utterance.
- The session must be visibly and audibly clear about whether it is listening, paused, reconnecting, or unavailable.
- The original goal survives screen transitions. Every new screen must be observed and evaluated again; never assume the expected next screen appeared.
- Georgian paraphrases and corrections must be understood, for example „დენის გადახდა მინდა“, „ელექტროენერგიის ქვითარი გადავიხადო“ and „არა, წყალი არა — დენი“.
- High-frequency camera, hand, geometry and direction calculations stay local on-device. Cloud AI is not part of the per-frame pointing loop.
- The app must never tap, press, submit or simulate interaction with the kiosk. The user performs every physical press.
- Before an irreversible payment step, read back the verified service/recipient, amount and visible fee, and require a clear affirmative confirmation. Silence, ambiguity, noise or partial speech are not consent.
- Never capture a PIN through speech recognition, send a PIN to an AI model, or read a PIN aloud. PIN handling is out of scope for this MVP.
- Never announce that a payment succeeded because the model predicted completion. Only report the observed screen state when reliable visible evidence supports the statement.
- If the screen, text, target or target geometry is uncertain, explain what is uncertain and recover safely rather than guessing.

## 2. Verified Android baseline and known blockers

Claude Code must verify these claims in the current repository rather than assume they remain unchanged:

1. The app is an Android/Kotlin project using CameraX, real Tesseract OCR (`kat.traineddata`) and MediaPipe hand tracking on a physical Samsung device.
2. The last reported source revision was `68388cc`, with changes deployed locally but not committed. Do not reset or overwrite existing user changes.
3. The current screen geometry was reported to treat the camera frame as the kiosk screen (`FrameGeometry` approximates the screen by the frame). A real kiosk screen detector/tracker was not found during the last audit. Verify this. This is a major blocker for robust screen-relative guidance.
4. A synthetic movement experiment regressed after a message/state-splitting change: guidance availability fell from the prior 77–86% range to approximately 57–65%, and target retention fell from 30/30 to 28/30. One assertion was left failing intentionally. Diagnose it; do not lower the test floor or hide the failure.
5. Synthetic OCR measurements (96 ground-truth labels per configuration, three rendered kiosk screens across six conditions) found conditional 2× upscaling to be the only clear win in that sweep: exact labels rose from 52.1% to 66.7%, with most benefit from small text. The corpus was rendered rather than photographed, so these figures are relative configuration rankings, not real-world accuracy.
6. The latest user report says the app sometimes detects a target and then says it cannot see the screen. The likely relevant distinctions are no target, unconfirmed target, OCR unreadable, and stale geometry. The app must not blame framing when OCR is the cause.

**Before substantial AI work:** run the current build and tests, reproduce/diagnose the known red motion regression, and document what the app actually does. A small isolated voice-provider proof of concept may proceed on a separate branch if that helps avoid mixing causes, but the failure must remain visible and tracked.

## 3. Recommended architecture

Keep the architecture Android-native and separate semantic AI from authoritative perception and control.

```text
Microphone / voice session (foreground, user-visible)
              │
              ▼
Georgian voice adapter ───────► finalized utterance / interruption
              │                                  │
              │                                  ▼
              │                         AI Task Resolver
              │                   goal / clarification proposal
              │                                  │
              ▼                                  ▼
Audio Output Coordinator ◄──── Policy + typed decision validator
              │                                  ▲
              ▼                                  │
      Short Georgian cues            Local Perception / State Machine
                                                ▲
CameraX → screen detector/tracker → rectified screen → Tesseract OCR
                                                │
MediaPipe hand pose → fingertip/screen-plane mapping → CuePlanner
```

### Component responsibilities

- `VoiceSessionManager`: starts, stops, reconnects and reports the active voice session; enforces one active session and lifecycle handling.
- `VoiceProvider`: interface for the chosen streaming voice implementation. First candidate: Gemini Live API through Firebase AI Logic Android SDK. A provider spike must prove Georgian listening and useful Georgian speech quality before it is selected for the product path.
- `IntentResolver`: translates finalized Georgian utterances and compact, verified screen observations into task-goal/clarification proposals. It must not run for every partial transcript or camera frame.
- `TaskSession`: stores the persistent goal, current screen version, last verified workflow step, unresolved ambiguity, confirmation gate and session status.
- `ScreenPerception`: detects the actual physical kiosk screen, tracks its quadrilateral, rectifies it for OCR and maintains transforms to screen-relative coordinates.
- `KioskOcrEngine`: Tesseract using Georgian `kat.traineddata`, keeping the measured conditional 2× upscale behavior unless real photographed data demonstrates otherwise.
- `ControlCandidateBuilder`: converts OCR text, boxes and other local visual evidence into candidate interactive controls. OCR text alone does not prove an element is a button.
- `HandTracker` / `FingerProjection`: use the existing MediaPipe pipeline and validated geometry to estimate target-relative fingertip position.
- `CuePlanner`: deterministic local directional hints and aligned/press readiness; never lets generative output invent directions.
- `PolicyValidator`: rejects invalid candidate IDs, old screen versions, stale geometry, uncertain critical fields and any attempt to bypass confirmation.
- `AudioOutputCoordinator`: avoids overlapping model speech and local guidance, allows interruption, and cancels queued cues made stale by screen/target changes.
- `Diagnostics`: logs state transitions, durations, IDs, confidence/freshness metadata and usage counters—not raw audio, transcripts, OCR contents or camera images by default.

Use the project's current architectural style (MVVM/MVI/Compose, dependency injection and module boundaries) after inspecting the code. Do not force in a new architecture or add dependencies without a demonstrated need.

## 4. AI and speech-provider strategy

### First candidate: Gemini Live API via Firebase AI Logic for Android

Use the official Firebase AI Logic Kotlin/Android SDK as the first feasibility spike for real-time bidirectional Georgian audio. The official Live API documentation currently lists Georgian (`ka`) among the additional languages supported by audio-generating 3.x models, and provides an Android Kotlin path using a persistent `LiveSession` and `startAudioConversation()`.

Official references:
- Android Gemini Live API integration: https://developer.android.com/ai/gemini/live?hl=en
- Firebase AI Logic Live API quickstart: https://firebase.google.com/docs/ai-logic/live-api
- Current Live API supported languages and limits: https://firebase.google.com/docs/ai-logic/live-api/limits-and-specs?hl=en
- Firebase AI Logic security and production checklist: https://firebase.google.com/docs/ai-logic/production-checklist
- Firebase AI Logic FAQ/security changes: https://firebase.google.com/docs/ai-logic/faq-and-troubleshooting

**Do not assume Georgian quality based on a language-list entry.** Evaluate at least 30 natural Georgian requests across at least three native speakers if available, including kiosk noise, paraphrases, interruptions and corrections. Score what the model understood, not just whether it produced fluent-sounding speech.

The Firebase AI Logic Gemini Live integration is currently documented as Preview/developer preview, so APIs/model identifiers can change and it has no SLA. The exact currently available model identifier must be checked during implementation and kept in one configuration location. Do not blindly copy model IDs from old examples or spread a model name throughout the codebase.

Known limits documented for Firebase AI Logic Live API include:
- It does **not** support constrained structured JSON output.
- Audio-only sessions have an approximate maximum length of 15 minutes and a connection limit of about 10 minutes; the connection may send a “going away” signal about 60 seconds before termination. Implement session resumption/rollover and preserve the user's goal across reconnects.
- Video is sent as discrete frames at 1 FPS. This is not suitable for fingertip/coordinate tracking. Do not upload a live video stream for the high-frequency guidance loop.
- The documented audio input format is 16-bit PCM at 16 kHz little-endian; output audio is 16-bit PCM at 24 kHz. Prefer the SDK-supported recording path and samples rather than hand-rolling audio framing unless a measured need requires it.

The Live API supports function calling, but the app must validate every call and its arguments locally. If current SDK/API limitations make reliable tool calling awkward, keep the live connection for speech/dialogue and use a normal Gemini text request with structured output for the intent decision. This request should happen only after finalized speech or a meaningful screen/workflow event, never per frame.

### Fallback candidate: Azure Speech + Gemini text intent resolver

If Gemini Live fails Georgian streaming/latency/reliability tests, run a separate, measured fallback spike using Azure Speech continuous STT for `ka-GE` and a Georgian TTS voice, with a regular Gemini text model for task semantics. Microsoft lists Georgian language support, but exact streaming accuracy, voice naturalness, cost and terms must be tested and verified.

Reference: https://learn.microsoft.com/en-us/azure/ai-services/speech-service/language-support

Do not ship two continuously streaming voice providers by default. Select one active implementation behind `VoiceProvider` via centralized configuration. Avoid duplicating audio costs and complexity.

### Audio policy

- The assistant should speak concise Georgian. Let a natural voice model handle ordinary dialogue only if physical-device tests show intelligible Georgian. Prefer deterministic, predictable local cues for geometric directions.
- Do not let the model and local TTS speak over one another. Directional safety cues may preempt conversational audio. Drop queued guidance if the target/screen changes.
- Support interruption and immediate stop/cancel. While testing, inspect what the chosen SDK actually exposes for partial/final input transcription, end-of-utterance detection, barge-in and audio-loop handling. Do not assume the SDK implements all these behaviors automatically.
- If the assistant's own speech is picked up by the microphone, use platform audio controls/echo cancellation where available or a tested half-duplex policy. A user must still be able to interrupt naturally; test this explicitly.

## 5. Hands-free session behavior on Android

“Hands-free” means no microphone-button press before each utterance during an active TouchGuide session. It does not mean secret always-on background recording.

1. On first use, explain in Georgian what cloud voice processing is used for and what data is transmitted. Obtain any product consent required before streaming.
2. Request runtime `RECORD_AUDIO` and `CAMERA` permissions when needed. Do not start capture before permission is granted.
3. After the user opens the app and required permissions/consent exist, automatically start or clearly start the foreground session once. Subsequent phrases should not require more taps.
4. Keep a reliable accessible stop/exit control and support spoken Georgian commands: „გაჩერდი“, „გააუქმე“, „გაიმეორე“, „ეკრანი წამიკითხე“, „უკან დაბრუნება მინდა“.
5. If the activity is backgrounded, device is locked, permission is revoked, the microphone is occupied, or the app is interrupted, pause safely and inform the user. Do not promise a custom always-on wake phrase or unrestricted background listening.
6. Start a microphone/camera foreground service only if there is a real product need. If one is needed, inspect the targeted Android API level and comply with current foreground-service types, permissions and while-in-use restrictions. For Android 14+ this includes validating microphone/camera service prerequisites and starting from a permitted visible state. Do not add a foreground service simply to bypass platform rules.
7. Represent session lifecycle explicitly: `IDLE`, `REQUESTING_PERMISSIONS`, `CONNECTING`, `LISTENING`, `THINKING`, `SPEAKING`, `PAUSED`, `RECONNECTING`, `STOPPED`, `ERROR`.
8. Debounce partial transcripts. Commit an intent only after final/end-of-utterance or a tested correction boundary. Don't send each partial word to the model.

Official Android foreground-service references:
- https://developer.android.com/develop/background-work/services/fgs/service-types#microphone
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

## 6. AI task contract and state persistence

### User goal and workflow state

The app needs explicit domain state, not a vague conversation history alone:

```kotlin
data class TaskSession(
    val goal: TaskGoal?,
    val currentScreenVersion: Long,
    val lastVerifiedStep: VerifiedStep?,
    val unresolvedQuestion: String?,
    val state: WorkflowState,
    val awaitingCriticalConfirmation: Boolean,
)
```

Treat this as an illustrative contract; adapt names/types to the actual project, use sealed types/enums where appropriate, and add serialization only if needed.

The session must preserve a normalized goal such as `ELECTRICITY_BILL_PAYMENT` across every screen transition, voice reconnection and Live session rollover. Current screen details must not be reused after the screen version changes.

### Compact observation sent to the model

Send minimal structured text when the user goal is finalized, a meaningful screen transition is detected, or clarification is needed. Example:

```json
{
  "locale": "ka",
  "userUtterance": "დენის გადახდა მინდა",
  "currentGoal": null,
  "workflowState": "SCREEN_OBSERVED",
  "screenVersion": 4,
  "observedControls": [
    {
      "candidateId": "control_12",
      "labelRaw": "კომუნალური გადახდები",
      "labelNormalized": "კომუნალური გადახდები",
      "ocrConfidence": 0.94,
      "screenRelativeBounds": {"left": 0.1, "top": 0.35, "right": 0.8, "bottom": 0.44},
      "actionabilityEvidence": ["button_rectangle", "stable_label"],
      "observationAgeMs": 120
    }
  ]
}
```

Only include controls actually observed by the local vision pipeline. No camera frames, raw audio buffers, or raw fingertip coordinates need to be sent to the LLM by default.

### Allowed proposals from the AI

- `SET_GOAL` — create or update normalized task goal.
- `SELECT_OBSERVED_CONTROL` — propose a `candidateId` from the current observed-control list.
- `ASK_CLARIFICATION` — ask one concise question in Georgian.
- `READ_OBSERVED_SCREEN` — request a spoken summary of current verified visible content.
- `CANCEL_TASK` — cancel the task/session.
- `REQUEST_RESCAN` — request local reacquisition/re-reading.
- `REQUEST_CRITICAL_CONFIRMATION` — allowed only after local extraction and deterministic validation of all required critical fields.
- `PROPOSE_COMPLETE` — a proposal only; the local app must independently verify visible completion evidence.

The model may never return authoritative coordinates, directly move the finger, simulate a tap, submit a payment, bypass confirmation, process PINs, or mark a transaction as completed by itself. Model-supplied confidence is advisory only; it does not replace OCR confidence, control-actionability evidence, geometry freshness or policy validation.

### Required validation

- Decode into Kotlin data/sealed types and validate every field. Reject malformed values, unknown actions, missing fields and unexpected IDs.
- `candidateId` must belong to the current `screenVersion`. Reject IDs from any previous screen, even when labels match.
- OCR text alone is not proof that text is an actionable button. Use UI/rectangle evidence and measured kiosk-specific heuristics.
- A proposed target is not actionable until local policy verifies its existence, current geometry and freshness.
- Any ambiguous/invalid output becomes a safe clarification or rescan, never a guess.
- If using the Live API directly, do not expect schema-constrained JSON generation; use carefully validated function calling for narrow proposals, or a separate non-Live structured request.

### Georgian conversation policy

System instructions should require:
- Speak Georgian (`ka`) clearly and concisely by default.
- Understand Georgian paraphrases, case endings, colloquial speech, corrections and common loanwords.
- Keep the current goal unless the user corrects or cancels it.
- Treat „კი“ as confirmation only when an explicit critical-confirmation question was just asked and the utterance is unambiguous.
- Never request or speak a PIN.
- Ask one short question when provider/service/amount/recipient is ambiguous.
- Do not claim that an action occurred merely because the next expected screen was predicted.

## 7. Android-local perception and OCR

### Screen detection is a prerequisite

The current reported implementation approximates the kiosk as the camera frame. Verify this. If still true, implement actual physical screen detection/tracking before claiming the app can reliably navigate kiosk UI:

1. Detect the screen rectangle/quadrilateral in the CameraX frame using a measured vision approach.
2. Track it across normal hand motion and reacquire after actual tracking loss.
3. Produce screen-relative coordinates and a reversible transform between original camera coordinates and rectified screen coordinates.
4. Keep a freshness timestamp and confidence/validity status for every target and geometry estimate.
5. Do not continue directional guidance with stale or invalid geometry.
6. Distinguish `NO_SCREEN`, `SCREEN_TRACKING_LOST`, `OCR_UNREADABLE`, `NO_TARGET`, `UNCONFIRMED_TARGET`, and `GEOMETRY_STALE`. User-facing messages must correctly identify the problem.

Use whatever Android-native libraries already exist in the project where sensible. Do not assume the screen detector exists because docs describe it; verify from code and measurement.

### OCR requirements

- Continue using real Tesseract and Georgian `kat.traineddata` unless benchmarking supports a justified change.
- Preserve `PSM_SPARSE_TEXT` and conditional 2× upscale unless real-photo evaluation shows another configuration is better.
- Do not adopt `tessdata_best`, PSM_AUTO, grayscale preprocessing or other changes simply because they sound sophisticated; previous synthetic sweeps did not show meaningful wins for these options.
- Build a real-photo evaluation workflow: collect about 30 initial real kiosk photographs across distance/angle/glare/lighting, manually transcribe visible labels, annotate clickable control bounds and measure CER, WER, exact button-label match, critical amount accuracy, correct-target precision and p50/p95 latency.
- Keep rendered/synthetic scores separate from real-photo results. Never report the synthetic 66.7% exact label rate as field accuracy.
- When OCR is weak, say the text cannot be read and request a useful recovery. Do not blame framing unless the frame really is the issue.

### Finger guidance

- Keep MediaPipe hand/fingertip tracking local and independent of LLM request cadence.
- Verify how fingertip pixels map to the physical screen plane. A 2D fingertip point alone is not proof of a correct landing point when the phone is held offset from the user's tapping finger.
- Do not say “press now” or “aligned” unless the measured landing uncertainty and current target geometry satisfy empirically validated thresholds.
- Keep the previous known target only while its validity and geometry freshness can be demonstrated. Never increase freshness timeouts just to improve availability.

## 8. Safety and privacy invariants

These are non-negotiable for the MVP:

- No fabricated labels, control candidates, amounts, recipients, screen transitions or success states.
- Never ask the user to speak a PIN; do not pass PIN/audio digits to Gemini or speech logs.
- No model action can tap or submit anything on the kiosk.
- Before irreversible confirmation, read out the verified service/recipient, normalized amount and visible fee, then ask for explicit affirmative confirmation. If a critical field is not reliably readable, stop and offer rescan/cancel.
- No implicit confirmation from silence, unrelated speech, background audio or partial results.
- Keep stop/cancel available during normal operation, ambiguous state and network failure.
- Detect screen changes and invalidate all targets from earlier screen versions.
- Never log raw audio, full transcripts, camera frames, OCR screen text, payment details or identifying data by default. Structured diagnostics should contain durations, state names, error categories and IDs only.
- Do not upload kiosk images by default. A separate image-based AI experiment requires explicit approval, a masked/synthetic dataset, privacy review and a measured reason it is worth the exposure/cost.
- Make cloud streaming clear during onboarding. Offer a clear stop option. Do not represent a cloud feature as on-device/private.

### Firebase AI Logic security

- Use Firebase AI Logic's Android client SDK and configure Firebase App Check for development and production. Use the development debug provider only for local testing, never as the release setup.
- Do not embed a raw unrestricted Gemini Developer API key in the APK, source code or local configuration committed to version control. Follow the currently documented Firebase AI Logic setup, which uses its own gateway and security controls.
- Restrict Firebase API keys to the app's package/SHA-1 as appropriate and required APIs; enable/enforce App Check before real user testing/release. Google documentation says App Check enforcement is scheduled to be required for Firebase AI Logic starting 2 November 2026, so configure it now.
- Do not put permanent Azure credentials in the APK if Azure fallback is selected; use a server-issued short-lived token or secure broker.
- Add quotas/budget alerts and per-session request limits. Avoid sending repeated requests for the same final utterance or unchanged screen.

References:
- https://firebase.google.com/docs/ai-logic/app-check
- https://firebase.google.com/docs/ai-logic/production-checklist
- https://firebase.google.com/docs/ai-logic/faq-and-troubleshooting

## 9. Session duration, latency and cost control

- A Live session is not infinite; use session rollover/resumption, and persist only the minimal task state needed to continue. A new Live connection must receive the current goal and last verified workflow step, but never an assumed next screen.
- Keep `VoiceProvider` and model IDs centrally configurable.
- Instrument session duration, audio seconds/token usage where supported, number of reasoning calls, latency, reconnections, errors and rough session cost. Do not log user content.
- Do not send a request for every video frame, MediaPipe output, cue update, OCR pass or interim token. Send compact events for finalized user intent, a meaningful screen transition, a critical-field screen or a true ambiguity.
- The AI's response latency target should be measured on the physical Samsung. Provisional goal: p95 first conversational response within 1.5 s after utterance finalization on a stable connection. This is a target to validate, not a claimed result.
- Local directional feedback must not wait for a network call.

## 10. Implementation milestones (do not skip gates)

### M0 — Android repository baseline and regression audit

- Locate and inspect the real Android repository and project instructions.
- Read the existing `CLAUDE.md`, architecture, OCR, safety, testing and progress docs if present.
- Run the current build, unit tests and instrumented tests without making changes first.
- Reproduce and diagnose the 57–65% synthetic motion guidance availability / 28-of-30 retention regression. Do not lower its floor or hide the red assertion.
- Verify the “no real screen detector” finding and prove it with code references.
- Report the exact baseline, current branch/worktree status, and blockers. Preserve all uncommitted work.

### M1 — Voice-provider spike (not a full agent)

Implement a minimal Android-only screen/adapter to test:
- Firebase AI Logic dependency compiles and initializes.
- App Check works in local debug mode through the intended debug provider and the production configuration is documented.
- Current supported Gemini Live model connects and closes cleanly.
- Georgian speech can be understood and answered in Georgian in actual phone tests.
- Inspect the SDK's actual behavior for continuous audio, partial/final transcript visibility, interruptions/barge-in, microphone echo, reconnect and session limit events.
- Record model identifier, dependency version, test prompts/speakers, network state and measured outcomes.
- No camera frames and no kiosk control decisions in this spike.

**Gate:** If Gemini Live Georgian speech/interrupt behavior is unreliable or the Preview API is too limiting, run the Azure Speech `ka-GE` fallback spike. Do not silently adopt two providers at once.

### M2 — Typed task/intent contract

- Add explicit session/goal state.
- Add a provider-neutral `IntentResolver` interface and typed action results.
- Test Georgian paraphrases, service corrections, stop/repeat/back/cancel and ambiguity cases.
- Reject stale screen IDs and malformed/unobserved candidate IDs.

### M3 — Continuous active-session voice

- Start listening automatically in the active foreground session after permission/consent conditions are met.
- Support user interruption, cancel/repeat, audio focus, echo avoidance, reconnect and session rollover.
- No microphone tap per utterance; no secret always-on background listening.
- Ensure assistant speech doesn't cause self-trigger loops.

### M4 — Fix screen perception and reliable target selection

- Implement and measure physical kiosk screen rectangle detection/tracking.
- Preserve the updated OCR upscale behavior as baseline pending real-photo tests.
- Separate screen loss, OCR failure, no target, unconfirmed target and stale geometry in logs and spoken feedback.
- Validate target candidates against a real photographed set and physical Samsung trials.
- Keep fingertip geometry and direction cues local.

### M5 — One end-to-end electricity-payment path

In a safe/test setting, support a single specific TBC Pay electricity-payment path. The user speaks the goal once; the app keeps context through all screens, guides the user to visible controls, handles corrections, reads back verified critical details, requests explicit voice confirmation, lets the user perform all presses, and independently verifies the resulting screen. If no safe test mode is available, stop before any real irreversible payment.

### M6 — Real-world evaluation and accessibility gate

- Use at least ~30 real photos for an initial OCR baseline, then an independent set before generalizing claims.
- Run 30 Georgian spoken-goal cases with native speakers if possible.
- Test ambient noise, distance/angle/glare, natural phone movement, tracking loss, poor OCR, screen changes and network dropouts.
- Measure intent accuracy, OCR metrics, correct-target precision, false target-reached, false completion, session latency, provider cost and successful task completion without experimenter intervention.
- Include blind users in accessibility/usability tests before describing the app as ready for real payment use.

## 11. Test requirements

### Georgian voice/intent set

Include variants of:
- „დენის გადახდა მინდა“
- „დენი გადავიხადო“
- „ელექტროენერგიის ქვითარი უნდა გადავიხადო“
- „კომუნალურების გადახდა მინდა“
- „წყლის გადახდა მინდა“
- „არა, წყალი არა — დენი“
- „გაიმეორე“
- „უკან დაბრუნება მინდა“
- „გაჩერდი“ / „გააუქმე“
- ambiguous service/provider choices
- „კი“ outside an explicit confirmation question

Measure actual intent accuracy, correction handling, false confirmation rate, p50/p95 latency and behavior in noise/network failure. Separate STT errors from semantic resolver errors.

### Screen and control tests

- Keep synthetic and real-photo test results separate.
- Maintain ground-truth transcriptions and action-control bounding boxes.
- Measure CER, WER, exact label accuracy, amount/recipient exact match, actionability precision/recall, correct-target precision, target freshness, OCR rejection count and p50/p95 runtime.
- Distinguish wrong text, unreadable text, right text assigned to the wrong control, stale geometry and true screen-tracking loss.

### Safety/regression tests

- Zero false “target reached” announcements in test cases.
- Zero false payment completion announcements.
- No press cue with uncertain/stale geometry.
- No PIN in STT/LLM payloads or normal logs.
- Current target IDs from previous screen versions are rejected.
- A screen change invalidates old target/control data.
- Any ambiguous critical amount/recipient blocks confirmation.
- Silence or background “yes” is not confirmation.
- Spoken stop/cancel preempts normal narration/directional cue playback.
- Network disconnection never results in pretending the user was heard or the task completed.

## 12. What Claude Code must report

After each milestone, report:
1. Files changed and why.
2. The root cause or feature implemented, with code references.
3. Exact build/test commands and actual results.
4. Actual physical-device tests performed, with sample count and conditions.
5. Provider/model/dependency versions and current official docs checked.
6. Privacy, security, session limit and cost implications.
7. Remaining uncertainty/blockers and the next manual action needed.

Do not claim “AI integrated” just because a connection was established. Do not fabricate benchmark numbers, real-world distance claims, OCR accuracy, Georgian quality or physical trials. Use small auditable changes. Do not commit unless explicitly asked. Preserve the user's uncommitted work.

## 13. Official reference links

- Android Gemini Live API: https://developer.android.com/ai/gemini/live?hl=en
- Firebase AI Logic Live API quickstart: https://firebase.google.com/docs/ai-logic/live-api
- Firebase AI Logic Live API limits and languages: https://firebase.google.com/docs/ai-logic/live-api/limits-and-specs?hl=en
- Firebase AI Logic overview: https://firebase.google.com/docs/ai-logic
- Firebase AI Logic App Check: https://firebase.google.com/docs/ai-logic/app-check
- Firebase AI Logic production checklist: https://firebase.google.com/docs/ai-logic/production-checklist
- Firebase AI Logic security FAQ: https://firebase.google.com/docs/ai-logic/faq-and-troubleshooting
- Android microphone foreground-service types: https://developer.android.com/develop/background-work/services/fgs/service-types#microphone
- Android background foreground-service restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Azure Speech language support (fallback): https://learn.microsoft.com/en-us/azure/ai-services/speech-service/language-support
