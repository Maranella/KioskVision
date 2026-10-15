#!/usr/bin/env bash
# Real-kiosk OCR evaluation: push a dataset to the phone, run the frozen configurations
# against it, and pull the results back.
#
#   tools/ocr-eval.sh push <dataset-dir>   photos + dataset.json -> device
#   tools/ocr-eval.sh run                  run the evaluation
#   tools/ocr-eval.sh pull <out-dir>       results.csv -> here
#
# The dataset directory holds the photographs and a dataset.json describing them; see
# tools/dataset.template.json for the schema.
set -euo pipefail

PKG=com.maranella.kioskvision
REMOTE="/sdcard/Android/data/$PKG/files/ocr-eval"
SERIAL="${ANDROID_SERIAL:-}"
ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

case "${1:-}" in
  push)
    SRC="${2:?usage: ocr-eval.sh push <dataset-dir>}"
    [ -f "$SRC/dataset.json" ] || { echo "no dataset.json in $SRC" >&2; exit 1; }
    "${ADB[@]}" shell mkdir -p "$REMOTE"
    "${ADB[@]}" push "$SRC/." "$REMOTE/" >/dev/null
    echo "pushed $(ls "$SRC" | wc -l | tr -d ' ') files to $REMOTE"
    ;;
  run)
    ./gradlew connectedDebugAndroidTest --console=plain \
      -Pandroid.testInstrumentationRunnerArguments.class=$PKG.RealKioskEvaluationTest
    "${ADB[@]}" logcat -d -s RealKioskEval | sed 's/^.*RealKioskEval: //'
    ;;
  pull)
    OUT="${2:?usage: ocr-eval.sh pull <out-dir>}"
    mkdir -p "$OUT"
    "${ADB[@]}" pull "$REMOTE/results.csv" "$OUT/" >/dev/null
    echo "wrote $OUT/results.csv"
    ;;
  *)
    sed -n '2,12p' "$0"
    exit 1
    ;;
esac
