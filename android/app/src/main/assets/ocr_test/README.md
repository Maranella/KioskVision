# OCR test target — coffee machine touchscreen

`coffee_machine_screen.png` — 1920 × 1200 PNG, flat and head-on.

Display it on a second phone, tablet or monitor and point KioskVision at it. It exists to
separate the four things that can make the app read nothing at a real machine: **input quality,
text size, language recognition, and downstream filtering.** Those have different fixes, and
until now one device session could not tell them apart.

**No perspective is baked in.** Photograph it at an angle if you want to test rectification —
distortion applied twice would measure neither honestly.

## What is on it, and at what size

Ground truth, so accuracy can be scored rather than eyeballed. Sizes are **source pixels**;
see the arithmetic below for what they become in the camera frame.

| Element | Georgian | English / value | Source px | Contrast |
|---|---|---|---|---|
| Title | ყავის აპარატი | COFFEE MACHINE · ტერმინალი №14 | 50 / 23 | light on dark |
| Clock | — | 09:42 · Terminal ID: TB-4417 | 44 / 20 | light on dark |
| Heading | აირჩიეთ სასურველი სასმელი | Please select your drink | 38 / 25 | dark on light |
| Drink 1 | ესპრესო | Espresso · 40 მლ / 40 ml · **3.50 ₾** | 52 / 27 / 22 / 46 | dark on light |
| Drink 2 | ამერიკანო | Americano · 150 მლ / 150 ml · **4.00 ₾** | 52 / 27 / 22 / 46 | dark on light |
| Drink 3 | კაპუჩინო | Cappuccino · 180 მლ / 180 ml · **5.00 ₾** | 52 / 27 / 22 / 46 | **light on dark** |
| Drink 3 badge | არჩეული | — | 21 | white on green |
| Drink 4 | ლატე | Latte · 220 მლ / 220 ml · **5.00 ₾** | 52 / 27 / 22 / 46 | dark on light |
| Settings | პარამეტრები | Settings | 34 / 21 | dark on light |
| Stepper 1 | სიძლიერე | Strength · − **3** + | 29 / 19 / 38 | dark on light |
| Stepper 2 | შაქარი | Sugar · − **2** + | 29 / 19 / 38 | dark on light |
| Stepper 3 | ტემპერატურა | Temperature · − **80°** + | 29 / 19 / 38 | dark on light |
| Fine print | ფასი მოიცავს დღგ-ს | Price includes VAT 18% | 20 / 18 | low contrast |
| Fine print | მომსახურების საკომისიო 0.20 ₾ | — | 18 | low contrast |
| Total | ჯამი / Total | **8.50 ₾** | 23 / 50 | dark on light |
| Status | რძე / წყალი / ჭიქები / ბოლო სერვისი | Milk · Water · Cups · Last service | 25 / 17 | dark on light |
| Status values | საკმარისი | 82% · 140 · 12.10.2026 | 30 | accent on light |
| Cancel | გაუქმება | Cancel | 38 / 20 | dark on light |
| **Continue** | **გაგრძელება** | Continue | 46 / 20 | white on green |
| Footer | დახმარება 2 55 55 55 | support@kiosk.ge · v1.0.4 · 2026 | 16 | **lowest contrast** |

Size ladder: **16, 17, 18, 19, 20, 21, 22, 23, 25, 27, 29, 30, 32, 34, 38, 44, 46, 50, 52**.
Deliberately down to 16 px, which is past the point of readability at normal distance — a
target everything reads is a target that ranks nothing.

Note what the values are made of: `3`, `2`, `80°`, `82%`, `140`, `12.10.2026`, `3.50 ₾` carry
**fewer than two letters**, and `TargetTracker.isRetainable` discards anything under that floor
before the overlay ever shows it. If those vanish while the drink names survive, the failure is
the intake filter, not recognition. See `OcrIntakeFilterTest`.

## The arithmetic that decides how close to stand

The analysis frame is 720 px wide (upright). If the displayed screen spans **P** pixels of that
frame's width, a glyph of **s** source pixels arrives as `s × P / 1920` frame pixels.

R-16 measured Georgian reading at ~0 % CER around 34 frame px, with 2× upscaling recovering
text below about 20 px. So:

| To get this text to 20 frame px | screen must fill | at 1× | with 2× upscale |
|---|---|---|---|
| Drink names (52 px) | 738 px | ~100 % of frame width | ~51 % |
| Prices (46 px) | 835 px | not reachable | ~58 % |
| Stepper labels (29 px) | 1324 px | not reachable | ~92 % |
| Fine print (16–18 px) | 2133+ px | not reachable | not reachable |

**The fine print is not meant to be readable.** It marks where recognition gives out, which is
the number you actually need. If the drink names do not read when the screen fills the frame,
the problem is not size.

## Running a repeatable test

1. Display the PNG **at 100 %, no scaling, full brightness**. Turn off auto-brightness and
   night shift on the display — a warm cast changes the contrast you are measuring.
2. Debug build, open live assist, turn the **developer overlay on**. That also arms a one-shot
   dump of the exact image Tesseract reads.
3. Hold the phone square-on, with the whole screen in frame. Note the distance.
4. Capture:

```sh
adb logcat -s LiveAssistPipeline:I KioskOcrEngine:I | grep -E "SCREEN|ACQUIRE|OCR"
adb shell run-as com.maranella.kioskvision ls files/ocr-dumps
adb exec-out run-as com.maranella.kioskvision cat files/ocr-dumps/<name>.png > machine_screen.png
```

5. For the preprocessing comparison, drop that dump in and run the harness:

```sh
cp machine_screen.png android/app/src/androidTest/assets/
./gradlew connectedDebugAndroidTest --tests '*OcrPreprocessingComparisonTest*'
```

6. Repeat at 2–3 distances and at 0°, 20° and 40° off-axis. Change **one** variable per run.

## Reading the result

| Overlay / log | Conclusion |
|---|---|
| `screen NOT TRACKED — TooSmall  area 1.3% …` | detection, on size. Move closer |
| `acquire … scan WHOLE FRAME at 1.0x` | the crop guard refused; OCR is reading everything at 1× |
| `out raw 0 kept 0` | **image quality or size.** Tesseract genuinely saw nothing |
| `out raw 9 kept 0 ← ALL DISCARDED` | **downstream filtering.** Recognition worked |
| `out raw 9 kept 7`, Georgian names present, numbers missing | the two-letter floor, as expected |
| Latin reads, Georgian does not | **language.** Check `lang kat+eng` on the `ocr` line |

## What this target cannot tell you

It is a flat, evenly lit, noise-free render on an emissive display. A real machine adds glare, a
curved or recessed bezel, uneven backlight, a matte anti-glare coating and its own font. Good
numbers here are a **necessary** condition, not evidence the app works at a machine. Keep the
real capture; this is for ranking configurations against each other.

## Note on where this file lives

`src/main/assets/` ships inside the APK, so this PNG (~0.2 MB) is in release builds too. That
was the requested location. If it should not ship, `src/androidTest/assets/` is the equivalent
test-only path and nothing else needs to change.

Regenerate or vary it with the Pillow script used to build it — Georgian, Latin, digits and
₾ (U+20BE) all come from `Helvetica.ttc`, which was verified to cover all four. Arial Unicode
was rejected: it has Georgian but **no lari sign**.
