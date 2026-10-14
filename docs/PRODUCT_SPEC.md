# Product Specification

Working title: **Voice-Guided Kiosk Assistant**. The product has no official
name yet.

## 1. Vision

A native iOS application that helps blind and visually impaired users in
Georgia independently and safely operate physical touchscreen kiosks and
payment terminals. The app perceives the kiosk screen through the iPhone
camera, listens to the user's Georgian spoken requests, and guides the user's
finger to the intended control with spoken Georgian instructions.

The app augments the user; it does not act on their behalf. The user always
performs the final press.

## 2. Target users and initial market

- Primary user: blind and visually impaired adults in Georgia.
- Secondary user: sighted assistant giving the device to a blind user for
  independent use.
- Initial market: Georgia.
- Initial language: Georgian (Mkhedruli script). Code-switching with English
  loanwords (Visa, PIN, provider names) is expected.
- Initial kiosk coverage: a small set of well-known kiosk models starting with
  TBC Pay. Universal kiosk support is explicitly out of scope for V1.

## 3. Confirmed requirements

- Platform: native iOS. No Android, no web.
- Language: Swift.
- Testing: on physical iPhone and real touchscreen hardware. Simulator and
  mocked images are supplementary.
- Camera arrangement: user holds the iPhone in one hand and touches the kiosk
  with the other.
- Interaction: spoken instructions and spoken commands. No reliance on visual
  on-screen UI by the end user.
- Navigation: both screen exploration (list what is on screen) and
  goal-directed (find the "pay electricity" path).
- Final confirmation: the user physically presses the final button on the
  kiosk. The app does not touch the kiosk.
- The app must never autonomously submit a payment.

## 4. Intended interaction flow

Stages A through H as described in the product brief. Summarized here; detailed
behavior and failure handling are tracked in
`MVP_AND_ACCEPTANCE_CRITERIA.md`.

### A. Start and activation (unresolved)

Options under investigation (ranked by current feasibility):

1. Launch the app and press a prominent VoiceOver-friendly start button. The
   session begins immediately. Lowest-risk MVP activation.
2. Siri + App Intents shortcut: "Hey Siri, start kiosk helper." Standard iOS,
   no custom wake word required.
3. Back-tap / Action Button shortcut on supported hardware.
4. Custom wake-phrase listening while the app is in the foreground only.
5. Always-on background wake-word listening: not realistic on iOS due to
   microphone and background-execution restrictions. Not promised.

Activation will be kept decoupled from the navigation system so it can evolve
later without rewrites.

### B. Understand the user's request

User initiates by speaking a short Georgian instruction, e.g. "დამეხმარე
კიოსკის გამოყენებაში" ("help me use the kiosk") or more specifically
"ელექტროენერგიის გადახდა" ("pay the electricity bill").

The app:
- Confirms it heard the user and restates the intent.
- Guides the user to position the camera.
- Does not act on an ambiguous request without clarification.

### C. Acquire and assess the screen

Before interpreting the screen, the app verifies image quality and framing.
Specific measurable conditions (see `TECHNICAL_ARCHITECTURE.md` and
`SPIKES_AND_DECISIONS.md` ADR-005) include:
- Screen rectangle not detected or outside the frame.
- Only part of the rectangle visible.
- Distance too great (text too small to OCR reliably).
- Viewing angle too oblique.
- Motion blur, insufficient lighting, strong reflections or glare.
- Low confidence in screen-boundary detection.

Spoken feedback must be **specific and actionable**, not vague. Examples:
"move the phone a little to the left", "move farther from the screen", "tilt
the phone downward", "hold still for a moment".

### D. Explore the current screen

The app enumerates the actionable functional choices on the current screen and
reads them aloud in Georgian in a stable order (e.g. top-left to bottom-right
in the screen's own coordinate space, after perspective correction).

The app must distinguish actionable controls from decorative text,
advertisements, instructions, headings, and legal fine print. OCR text alone
is not an actionable control.

### E. Goal-directed navigation

Given a user goal, the app:
- Selects the single most relevant choice on the current screen, or
- Asks a clarifying question if the choice is ambiguous.

The app retains the goal across screen transitions but **re-perceives and
re-assesses** every new screen rather than assuming the expected next screen
appeared.

### F. Finger guidance

Once a target is selected, the app continuously estimates where the user's
finger would land on the physical screen and gives short spoken directional
cues ("up", "slightly right", "closer", "stop, aligned", "press now").

The app must distinguish between:
- Detecting a hand in frame.
- Estimating a 2D fingertip pixel position.
- Estimating the 3D fingertip position relative to the screen plane.
- Estimating the point on the screen plane where the finger will actually
  land if it moves straight in.
- Judging whether the estimated landing point is close enough to a target with
  sufficient confidence to invite a press.

The app must not say "press" when the estimated landing point is uncertain.
See `SAFETY_AND_PRIVACY.md` for the full stop rules.

### G. Screen transition and recovery

After a physical press, the kiosk may advance, show an error, open a modal, or
time out. The app detects meaningful screen changes, reassesses the new screen,
and continues the current goal when appropriate.

The user may at any time say the Georgian equivalent of:
- Stop
- Repeat
- Read the screen again
- Go back
- Cancel

These commands take precedence over the current directional loop.

### H. Critical details and final confirmation

Before an irreversible action, the app reads out the critical fields it has
extracted (recipient, amount, visible fee) and asks for explicit confirmation.

Example (Georgian): "აპირებ გადაიხადო 45 ლარი ელექტროენერგიისთვის. გინდა
გაგრძელება?" ("You are about to pay 45 GEL for electricity. Continue?").

If any critical field cannot be read reliably, the app **stops**, explains
what it could not read, and offers to re-scan or cancel.

The user must say an explicit affirmative in Georgian. Silence, ambiguity, or
noise is **not** confirmation.

After confirmation, the app guides the user to the physical confirmation
button on the kiosk. The user presses it themselves.

## 5. Explore and goal-directed modes

Both modes share the same perception stack but differ in output:

- **Explore mode**: enumerate every actionable control; wait for the user's
  choice.
- **Goal-directed mode**: select the single best next step given the retained
  goal; ask only when the choice is ambiguous.

Users can switch modes mid-session with a spoken command.

## 6. Safety and recovery

Expanded in `SAFETY_AND_PRIVACY.md`. Short version: the app must prefer
stopping and asking the user over acting on uncertain perception.

## 7. Explicitly unresolved product decisions

| # | Question | Status |
|---|---|---|
| U-01 | Final activation mechanism | Open; MVP will use a launch button. |
| U-02 | Custom wake phrase, if any | Deferred; not required for MVP. |
| U-03 | STT provider for Georgian | Candidate: Azure Speech. See ADR-002. |
| U-04 | TTS provider for Georgian | Candidate: Piper on-device + Azure cloud. See ADR-003. |
| U-05 | OCR engine for Georgian | Candidate: Tesseract with `kat.traineddata`. See ADR-004. |
| U-06 | Minimum iPhone model | Likely iPhone Pro with LiDAR; see ADR-001. |
| U-07 | Minimum iOS version | Decide after fixing framework stack. |
| U-08 | Which kiosks to support in V1 | TBC Pay first. Second kiosk TBD. |
| U-09 | Business model | Out of scope for Phase 0. |
| U-10 | Any backend service | Deferred; not required for MVP. |
| U-11 | Account/personalization | Deferred. |
| U-12 | Data retention policy | Default: no retention of camera frames or audio. See `SAFETY_AND_PRIVACY.md`. |
