# Safety and Privacy

This document defines the safety invariants that must hold at every point in
the product, the behavior under uncertainty, and the privacy posture. Treat
all requirements here as non-negotiable until the user explicitly amends them.

## 1. Threat model

The user cannot visually verify what the app says. If the app is wrong, the
user may:
- Press the wrong button.
- Authorize the wrong amount or recipient.
- Lose money through a mis-pressed transfer.
- Fail to notice a kiosk error state and walk away mid-transaction.

Therefore the dominant failure modes we design against are:
- **Hallucinated control**: the app claims a button exists that does not.
- **Mis-identified control**: the app reads label A but the physical button
  will trigger action B.
- **Mis-aimed finger guidance**: the app says "aligned, press" while the
  finger is above a different control.
- **Stale state**: the kiosk screen changed and the app did not notice.
- **Wrong critical field**: the app reads out an amount or recipient that
  does not match what the kiosk will actually submit.

## 2. Safety invariants

1. **No fabrication.** Never invent a button, amount, recipient, screen
   state, or option. If a label cannot be read, say so.
2. **OCR is not interaction.** An OCR text region is a candidate label, not a
   confirmed interactive control. A control is only "actionable" when
   evidence beyond text supports it (rectangle geometry, kiosk profile,
   positional heuristics — see `TECHNICAL_ARCHITECTURE.md`).
3. **No press under uncertainty.** The app must not say "press" or "aligned"
   unless the estimated finger-landing point is within the agreed target
   tolerance **with** acceptable confidence. Both thresholds will be
   validated empirically in Spike-01 before being locked.
4. **Critical fields must be read before confirmation.** For irreversible
   actions (payment submit, transfer confirm), the app must read recipient,
   amount, and any visible fee aloud, and obtain explicit affirmative
   confirmation.
5. **No autonomous execution.** The app never presses, taps, submits, or
   simulates input to the kiosk. The user performs every physical press.
6. **No implicit confirmation.** Silence, ambiguity, background noise, or
   partial speech must not be interpreted as "yes".
7. **Always-available stop.** The user can say the Georgian equivalent of
   "stop", "cancel", or "repeat" at any point. These commands preempt any
   active directional loop or utterance.
8. **No contradictions.** Do not emit directional instructions that
   immediately contradict a prior one within the same perception window. If
   the perception window changes direction, prefer silence briefly over
   whiplash.
9. **Graceful recovery.** After an unexpected screen transition or
   recognition failure, the app re-acquires the screen and asks the user
   rather than guessing.
10. **Thresholds are hypotheses.** Any confidence or tolerance number in the
    code is a hypothesis to be validated by measurement, not an authority.

Additional invariants identified during initialization:

11. **PIN entry must never go through STT.** Voice is a side channel visible
    to anyone within earshot. PIN entry, if ever supported, requires a
    different modality (secure numeric keypad with haptic feedback) and is
    out of scope for V1.
12. **Amounts must be re-stated in normalized form.** Georgian uses
    vigesimal counting ("ოცდაათი" = 30 as "20+10"). The confirmation prompt
    must restate the amount both as digits-equivalent and natural phrase so
    the user hears the actual numeric value.
13. **Kiosk screen changes must be detected.** The app must not continue
    directional guidance into a screen the kiosk has already left.
14. **The app must not promise what the kiosk will do.** The app is a
    reader-aloud, not a transaction system. Any phrase like "you have paid"
    is forbidden; the app may only describe what the kiosk displayed.
15. **Human-in-the-loop for ambiguity.** If more than one choice could plausibly
    match the user's goal, ask the user — do not guess.

## 3. Behavior under uncertainty (abstention rules)

In every subsystem, define an explicit "I am not confident enough" state and
the exact user-facing behavior:

- **Screen acquisition**: if screen rectangle not detected with high
  confidence after N frames (N to be measured), announce the specific framing
  problem and wait.
- **OCR**: if a critical field (amount, recipient) OCR confidence is below a
  validated threshold, do not read it; ask the user to re-position.
- **Control classification**: if a candidate control's "is-actionable"
  confidence is below threshold, do not include it in the exploration list.
- **Finger guidance**: if the estimated landing point has >X cm uncertainty
  (X to be measured in Spike-01), emit directional cues but not "press".
- **Intent interpretation**: if the user's spoken goal is interpreted with
  low confidence, read back the interpreted intent and ask for confirmation.

All thresholds live in a single configuration file so they can be revised
based on measurement, not scattered through code.

## 4. Privacy

### Data classification

- **Camera frames** may contain: account numbers, bank card numbers, PINs
  being entered, recipient names, phone numbers, and the user's hand.
- **Audio** may contain the user's spoken commands including provider names
  and amounts.
- Both are highly sensitive.

### Default posture

- No camera frame or audio buffer is persisted to disk by default.
- No camera frame or audio buffer is transmitted to any third-party service
  by default.
- Any cloud call (Azure STT/TTS candidates) must be an explicit, opt-in
  product decision with the user informed before it is made.

### If cloud STT/TTS is used

- Send only what is required. For STT: send audio for the active command
  window, not continuous streaming. For TTS: send the text to be spoken; do
  not include surrounding context or user identifiers.
- Prefer providers that document no-training-on-customer-data terms (Azure
  Speech standard tier does; verify current terms at purchase).
- Never send a camera frame to a cloud OCR or multimodal model as a default
  path for V1.
- Log only metadata (timing, success flag), not content.

### If cloud multimodal models are evaluated

Treat as a separate decision requiring explicit sign-off. Experiments on
cloud multimodal models must use synthetic kiosk screens or clearly-masked
captures, not live payment flows.

### User transparency

- An onboarding step must explain, in Georgian audio, exactly which services
  are contacted during a session and what data leaves the device.
- A privacy mode may be offered that forces on-device-only operation, with
  degraded functionality clearly communicated.

## 5. Failure scenarios we must handle without harm

| Scenario | Required behavior |
|---|---|
| Kiosk screen glare, OCR unreliable | Stop interpretation, instruct re-positioning. Do not guess amounts. |
| Kiosk unexpectedly logs out mid-session | Detect, announce, restart from start. |
| Network loss mid-session | Degrade to on-device path if available; otherwise stop and explain. |
| User walks away mid-session | Session times out and announces. No pending action persists. |
| Multiple kiosks visible in camera | Ask the user to point at a single screen. |
| Someone else approaches / background voice | Only react to the user's push-to-talk / explicit activation; do not react to overheard speech. |
| App detects what looks like a confirmation screen it did not expect | Stop, read the screen aloud, and ask the user how to proceed. |

## 6. Accessibility testing requirements

- Usability testing must involve blind participants, not sighted testers
  with eyes closed, before any claim of "ready for use."
- Testers must include at least one who is proficient with VoiceOver and at
  least one who is not.
- Test scenarios must include real kiosks in real environments (not a lab
  mock-up) before any field release.
- Any experimenter guiding a tester must document whether assistance was
  given mid-task; a task "completed with experimenter nudge" is not success.
- Payment scenarios in testing must use test accounts or minimal amounts
  authorized by the tester, never surprise real-money flows.

## 7. What this document is not

- Not a legal privacy policy. The legal policy is a separate document owned
  by whoever ships the app.
- Not a security audit. A separate security review is required before
  anything approaches a public release.
- Not a complete list of edge cases. New failure modes discovered in
  testing must be added here.
