# Working-distance test protocol

Measures how far from a kiosk screen TouchGuide actually works, and separates the
failure modes it can hit. Needs a person with the phone and a kiosk; nothing here
can be measured from the simulator or from rendered frames.

## Before you start

- Install the current debug build. It logs one state line per second plus a line on
  every state transition, which is what makes the results diagnosable.
- Measure distance with a tape or a ruler. **Do not estimate it from the image** —
  the app has no depth sensor and pixel width alone cannot give centimetres without
  knowing the physical screen size and camera geometry.
- Start the capture before each trial:

```
adb logcat -c
adb logcat -s LiveAssistPipeline > trial-40cm-frontal.log
```

## What to run

At each distance — **20, 30, 40, 50, 60, 80 cm** (skip any that is not physically
possible at your kiosk, and write down which) — do these four trials:

| # | Angle | Movement | Lighting |
|---|---|---|---|
| 1 | Frontal | Phone braced | Normal |
| 2 | Frontal | Normal handheld movement | Normal |
| 3 | Oblique, ~30 deg | Normal handheld movement | Normal |
| 4 | Frontal | Normal handheld movement | Glare or reflection on the screen |

For each trial:

1. Point the camera at the kiosk screen and wait up to 15 s.
2. Say a button name you can see, e.g. `გადახდა`.
3. If a target is found, hold position for **10 seconds**.
4. Note what the app said and stop the capture.

## What to record

Per trial, in a table:

- Measured distance (cm) and angle.
- Did it acquire the screen at all? (phase reached `ScreenReady`)
- Did it read the Georgian labels correctly? (compare the log's `lines` and the
  `SELECT` candidate list against what you can see)
- Was the right control selected?
- Did guidance stay available for the full 10 s?
- **Which message did it say** when it failed — this is the point of the exercise:

| Message | Means |
|---|---|
| `ტექსტს ვერ ვკითხულობ. ცოტა მიუახლოვდი ეკრანს.` | OCR cannot read the text — usually too far |
| `ღილაკის ადგილი დავკარგე. ეკრანი კადრში დატოვე.` | The button is still selected; its position went stale |
| `ეკრანი ბუნდოვანია, ოდნავ შეცვალე კამერის კუთხე.` | Image too soft to read |
| `ეკრანი შეიცვალა.` | A screen transition was confirmed |
| `„X“ ეკრანზე ვერ ვიპოვე.` | The screen was read, but has no such button |

## Reading the log

The transition lines carry the evidence:

```
PHASE AcquiringScreen -> ScreenReady  lines=18  textPx=23  target=-  lastOcr=Unchanged  sharp=0.051/0.055  focus=Locked
GUIDANCE available -> GeometryStale  anchoredAgoMs=1380  confirmations=3  lines=18  unreadableRuns=0  anchorJobs=42  ocrJobs=6
```

`textPx` is the median label height in the analysis frame. Measured separately
(R-16), recognition degrades below roughly 19 px and is unreliable below 12 px, so
`textPx` at each distance is the number that predicts whether any of this can work
there.

## Limits of this protocol

One kiosk is one kiosk. Results from a single screen, font and panel do **not**
generalise to other terminals — record which kiosk and which screen was used, and
treat the numbers as applying to that one until a second is tested.
