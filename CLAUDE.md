# CLAUDE.md

Persistent instructions for Claude Code sessions on this repository.

## Product summary

A native iOS application (working title: **Voice-Guided Kiosk Assistant**) for
blind and visually impaired users in Georgia who need to operate physical
touchscreen kiosks and payment terminals (TBC Pay and selected others).

The app uses the iPhone camera to understand the kiosk screen, listens to the
user's spoken Georgian requests, and provides spoken Georgian instructions that
help the user physically press the intended control. The user always performs
the final press themselves. The app never autonomously executes a transaction.

## Current development phase

**Phase 0 — Initialization and research.**
No application code exists yet. The repository currently contains only
documentation and the results of initial technical investigation.

The project will not move to Phase 1 (first spike) until the user approves it.

## Primary documents (read before acting)

1. `docs/PRODUCT_SPEC.md` — product vision, confirmed requirements, open decisions.
2. `docs/MVP_AND_ACCEPTANCE_CRITERIA.md` — milestones with measurable criteria.
3. `docs/TECHNICAL_ARCHITECTURE.md` — proposed system components and trade-offs.
4. `docs/ROADMAP.md` — ordered development plan.
5. `docs/SPIKES_AND_DECISIONS.md` — ADR log, open questions, experiment results.
6. `docs/PROGRESS.md` — current status and the single recommended next task.
7. `docs/SAFETY_AND_PRIVACY.md` — safety invariants.
8. `docs/TEST_STRATEGY.md` — test and validation plan.

## Repository layout and platforms

The product is built natively for both platforms (ADR-008):

- `android/` — Android app: Kotlin, Jetpack Compose, CameraX, Gradle.
  Package `com.maranella.kioskvision`.
- `ios/` — iOS app: Swift, Xcode project (not created yet).

Documentation in `docs/` is shared. Where a doc names an Apple framework,
check whether an Android equivalent has been measured before assuming it.

## Coding and architecture principles

- Native code per platform: Kotlin + Compose on Android, Swift on iOS.
- Prefer platform frameworks (Apple: AVFoundation, Vision, ARKit, AVFAudio;
  Android: CameraX, ARCore, platform TTS) when measurement shows they meet
  requirements. Apple's frameworks explicitly do **not** cover Georgian for
  STT/TTS/OCR, and ML Kit OCR does not either — see `SPIKES_AND_DECISIONS.md`.
- Keep safety-critical behavior deterministic. A language model may help
  interpret user intent; it must not independently authorize a target press or
  transaction confirmation.
- Separate concerns strictly: camera capture, screen recognition, hand tracking,
  speech I/O, interpretation, and navigation state. Each must be independently
  testable with recorded inputs.
- Keep the activation mechanism decoupled from the core navigation system so
  activation can be changed later without rewriting navigation.
- Do not introduce every framework at once. Do not pick a backend, database, DI
  container, or third-party AI provider until there is a demonstrated need.

## Safety requirements (non-negotiable)

See `docs/SAFETY_AND_PRIVACY.md` for the full list. Highlights:

1. Never fabricate a button, amount, recipient, or screen state.
2. Never confuse an OCR text region with a confirmed interactive control.
3. Never direct the user to press when target alignment is uncertain.
4. Never proceed with a critical transaction when relevant details cannot be
   read reliably.
5. Never autonomously execute a financial transaction.
6. Never interpret silence or ambiguity as confirmation.
7. Provide a clear stop/cancel interaction throughout any session.
8. Treat all confidence thresholds as hypotheses, not guarantees.

## Milestone-based workflow

Each milestone follows this cycle. **Do not skip steps and do not run ahead.**

1. Read `CLAUDE.md` and the relevant docs before acting.
2. Confirm the current milestone and its acceptance criteria with the user.
3. Explain the intended scope and important technical choices before making
   substantial changes.
4. Implement only the approved milestone — no unrelated refactoring, no
   speculative features.
5. Run the relevant build, tests, or experiment.
6. Report exactly what was verified and what was not.
7. Update `docs/PROGRESS.md` and `docs/SPIKES_AND_DECISIONS.md`.
8. Identify blockers and recommend one next task.
9. Stop and wait for user approval before beginning a new milestone.

## Rules for updating progress and decisions

- Never mark a milestone complete merely because a plan or code exists. Mark it
  complete only when its acceptance criteria have been verified.
- When new evidence invalidates an existing decision, record the evidence in
  `SPIKES_AND_DECISIONS.md` and propose a revision rather than silently
  changing the architecture.
- Distinguish, in writing, between: documented fact, experimental result,
  estimate, assumption.
- Cite sources (URL + date checked) for external claims.
- Do not claim to have run code, inspected a physical device, accessed a kiosk,
  or verified a service unless you actually did so this session.

## Prohibition on unapproved work

Do **not** attempt to implement:

- Milestones beyond the one currently approved.
- Universal kiosk support.
- Automated transaction execution.
- Always-on custom wake-word listening.
- Account systems or personalization.
- Backend services, databases, DI containers, or third-party AI providers that
  are not required by the current milestone.
- A full test suite before there is production code to test.

When in doubt, ask. The goal of this project is not maximum code output. The
goal is to determine whether the core interaction can work reliably, and then
to build a safe product around verified capabilities.
