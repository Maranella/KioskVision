# Bundled model assets

| File | Source | License | SHA-256 |
|---|---|---|---|
| `src/main/assets/hand_landmarker.task` | https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task (downloaded 2026-10-14) | Apache-2.0 | `fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1` |
| `src/main/assets/tessdata/kat.traineddata` | https://github.com/tesseract-ocr/tessdata_fast (main, downloaded 2026-10-14) | Apache-2.0 | `557abb6f1c68bc1b286f1bdd00bb6b82f85a427a91899807dab6c2f6c7986731` |
| `src/main/assets/tessdata/eng.traineddata` | https://github.com/tesseract-ocr/tessdata_fast (main, downloaded 2026-10-14) | Apache-2.0 | `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2` |

## Test fixtures (instrumented tests only, not in the app)

`kat_best.traineddata` is kept only so the model comparison in R-16 stays reproducible.
It is **not** shipped: that benchmark found it no more accurate than the bundled
`tessdata_fast` model for Georgian, and slower.

| File | Source | License | SHA-256 |
|---|---|---|---|
| `src/androidTest/assets/pointing_up.jpg` | https://storage.googleapis.com/mediapipe-assets/pointing_up.jpg (MediaPipe test data, downloaded 2026-10-14) | Apache-2.0 | `ecf8ca2611d08fa25948a4fc10710af9120e88243a54da6356bacea17ff3e36e` |
| `src/androidTest/assets/kat_best.traineddata` | https://github.com/tesseract-ocr/tessdata_best (main, downloaded 2026-10-15) | Apache-2.0 | `557f48b753fdbbee2c77d83bae2542af249f915be6cf80883892a579ea2a81db` |
