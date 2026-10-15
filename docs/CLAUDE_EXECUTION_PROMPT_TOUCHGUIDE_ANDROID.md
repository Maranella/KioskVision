# Claude Code task — TouchGuide Android-only AI integration

This project is **Android only**. Do not create, inspect, plan, or modify an iOS/Swift app. Read `TOUCHGUIDE_ANDROID_AI_INTEGRATION_SPEC.md` completely before editing code. Also read the project's `CLAUDE.md` and existing architecture, safety/privacy, OCR, test, and progress docs if present.

## Work order — do not skip the gates

### M0 — inspect and establish the real baseline
1. Identify the actual Android repository, branch, worktree state and project structure. Preserve uncommitted changes.
2. Run the current build and all available unit/instrumented tests without editing code first.
3. Investigate the reported synthetic-motion regression: guidance availability fell to about 57–65%, retention to 28/30, with one failing assertion. Do not lower the floor, suppress the assertion or bury the regression.
4. Verify whether actual kiosk-screen rectangle detection exists. The last audit found that `FrameGeometry` treated the camera frame as the kiosk screen. Confirm this from code.
5. Report findings and baseline before proceeding.

### M1 — Android-only Gemini Live feasibility spike
1. Use the official Firebase AI Logic Kotlin SDK / Gemini Live API candidate, after verifying the current SDK version and supported model identifier in the official docs.
2. Configure Firebase AI Logic and App Check properly. Never embed an unrestricted Gemini Developer API key in the APK, source code or committed local configuration. Use the debug App Check provider only for local development.
3. Create only a small start/stop voice test screen or adapter. Prove whether Georgian audio can be understood and answered in Georgian on the actual Samsung device.
4. Test session connect/disconnect, partial/final input behavior if exposed, interruption/barge-in, echo/self-triggering, reconnect, limits and spoken response quality.
5. Do not upload kiosk camera images or integrate kiosk control decisions in this spike. Do not claim production readiness because the connection works.
6. Since Firebase AI Logic Live API is Preview and does not support constrained JSON output, keep the provider behind an interface and use locally validated function calls or a separate structured intent request. If Georgian quality/latency is inadequate, run a separate Azure Speech `ka-GE` fallback spike rather than shipping two streaming providers simultaneously.

### M2 — task intent and session state
Add typed Kotlin domain models for the persistent goal, current screen version, last verified workflow step, unresolved ambiguity and confirmation gate. The model may propose an observed control ID, but the app must validate that the ID belongs to the currently observed screen. Test Georgian paraphrases, corrections, stop/cancel/repeat/back and ambiguous requests.

### M3 — hands-free active foreground voice session
Once the user has opened the app and granted permissions/consent, support continuous listening for the active session, with no microphone-button press before each utterance. Handle audio focus, interruption, cancel, TTS/assistant echo, network loss and session rollover. Do not promise hidden or unrestricted background listening. Only add a microphone/camera foreground service if needed and comply with the target Android API's current requirements.

### M4 — screen/OCR/geometry prerequisites
Implement and measure actual kiosk screen detection/tracking if absent; the camera frame must not simply be treated as the kiosk. Preserve local Tesseract and MediaPipe work. Keep high-frequency fingertip guidance local and deterministic. Distinguish screen tracking loss from unreadable OCR, missing target, unconfirmed target and stale geometry. Do not relax safety/freshness thresholds.

### M5 — one complete safe flow
Implement one TBC Pay electricity-bill path in a safe/test environment only. The user says the goal once; TouchGuide keeps the goal across screens, uses verified visible controls, handles corrections, reads back validated critical fields, gets explicit spoken confirmation before an irreversible action, lets the user press every physical kiosk button, and verifies completion from actual screen evidence. If no safe test environment exists, stop before a real irreversible payment.

## Non-negotiable rules

- Read the complete Android spec first.
- Android only. Do not refer to iOS repositories or add Swift requirements.
- No generated coordinates or model-invented controls; no model-triggered tap, PIN entry, payment or authoritative completion.
- No false “target reached” or payment-success announcement.
- No stale geometry; no relaxed thresholds to make tests green.
- Do not send every camera frame, OCR pass, fingertip update or partial transcript to Gemini.
- Do not log raw audio, transcripts, kiosk text, financial fields or camera frames by default.
- Verify current SDK, model, Firebase/App Check security, Android permission and foreground-service rules in official documentation before using them. Keep model/dependency configuration centralized.
- Build and test after each small change. Do not commit unless explicitly asked. Never claim tests or physical-device trials that were not actually run.

## Required report after each milestone

State the files changed, verified root cause/implementation, exact commands and actual test results, model/dependency versions, physical tests completed, remaining risks and next required manual action. Start with M0 and do not jump ahead.
