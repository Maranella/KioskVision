#!/usr/bin/env bash
# One physical-device validation run, captured so it can be read afterwards.
#
#   tools/device-validation.sh state            device, build and installed version
#   tools/device-validation.sh tests [<out>]    full instrumented suite + logs
#   tools/device-validation.sh session [secs]   capture a hand-held session's logs
#   tools/device-validation.sh latency <dir>    summarise LATENCY/OCR lines from a capture
#   tools/device-validation.sh privacy [secs]   watch for anything leaving the device
#
# Everything lands in a timestamped directory so one run cannot be mistaken for another, and
# so a claim in a report can be traced back to the line that supports it.
#
# What this cannot do: hold the phone. `session`, `privacy` and every acceptance test that
# needs a camera pointed at a screen require a person. This captures what happened while they
# did it.
set -euo pipefail

PKG=com.maranella.kioskvision
SERIAL="${ANDROID_SERIAL:-}"

# `adb` is not on the PATH of every shell that will run this, and a validation script that
# cannot find it reports "no device" for the wrong reason — which is the one failure a
# validation script must never produce.
ADB_BIN="$(command -v adb || true)"
for candidate in "${ANDROID_HOME:-}/platform-tools/adb" "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
                 "$HOME/Library/Android/sdk/platform-tools/adb" "$HOME/Android/Sdk/platform-tools/adb"; do
  [ -n "$ADB_BIN" ] && break
  [ -x "$candidate" ] && ADB_BIN="$candidate"
done
if [ -z "$ADB_BIN" ]; then
  echo "adb not found: install platform-tools or set ANDROID_HOME" >&2
  exit 2
fi
ADB=("$ADB_BIN")
[ -n "$SERIAL" ] && ADB=("$ADB_BIN" -s "$SERIAL")
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Tags worth keeping. Deliberately not the whole buffer: the app logs recognised screen text
# only to the developer overlay, never to logcat, and a capture that is read by a person
# should not become the one place it lands.
TAGS=(LiveAssistPipeline:I KioskOcrEngine:I LiveAssistViewModel:I StreamingVoice:I
      SpeechCommandRecognizer:I TextAssistant:I GuidanceSpeechOutput:I
      AndroidRuntime:E System.err:W
      # Every instrumented test's own tag: they print the measurements, and a capture that
      # omits them records that a test failed without recording why.
      GeorgianOcrEval:I HandVisibility:I HandheldMotion:I LiveAssistScenario:I
      ModelsOnDeviceTest:I OcrAcquisitionRouting:I OcrLangCompare:I OcrMotionDiagnostic:I
      OcrPerformanceTest:I OcrPreprocess:I OcrUpscaleCost:I PriceAssociation:I
      PriceLegibility:I RealKioskEval:I)

require_device() {
  local state
  state="$("${ADB[@]}" get-state 2>&1 || true)"
  if [ "$state" != "device" ]; then
    echo "no authorized device: $state" >&2
    echo "plug the phone in, unlock it, and accept the USB debugging prompt" >&2
    exit 2
  fi
}

out_dir() {
  local base="${1:-$HERE/build/device-validation}"
  local dir="$base/$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$dir"
  echo "$dir"
}

state() {
  require_device
  echo "serial:     $("${ADB[@]}" get-serialno)"
  echo "model:      $("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
  echo "android:    $("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r') (sdk $("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r'))"
  echo "fingerprint:$("${ADB[@]}" shell getprop ro.build.fingerprint | tr -d '\r')"
  local installed
  installed="$("${ADB[@]}" shell dumpsys package $PKG 2>/dev/null | grep -E "versionName|versionCode|lastUpdateTime" | tr -d '\r' | sed 's/^ *//' || true)"
  if [ -z "$installed" ]; then
    echo "installed:  (not installed)"
  else
    echo "installed:"
    echo "$installed" | sed 's/^/  /'
  fi
}

tests() {
  require_device
  local dir; dir="$(out_dir "${1:-}")"
  state | tee "$dir/device.txt"

  "${ADB[@]}" logcat -c || true
  echo "running the full instrumented suite; this takes a few minutes"
  set +e
  (cd "$HERE" && ./gradlew connectedDebugAndroidTest --console=plain) > "$dir/gradle.log" 2>&1
  local status=$?
  set -e
  "${ADB[@]}" logcat -d "${TAGS[@]}" '*:S' > "$dir/logcat.txt" || true

  # The XML is the record; gradle's summary line is not.
  cp -R "$HERE/app/build/outputs/androidTest-results/connected" "$dir/results" 2>/dev/null || true
  python3 "$HERE/tools/summarise-connected.py" "$dir" | tee "$dir/summary.txt"
  echo "exit=$status  full output in $dir"
  return $status
}

session() {
  require_device
  local secs="${1:-120}"
  local dir; dir="$(out_dir)"
  state > "$dir/device.txt"
  "${ADB[@]}" logcat -c || true
  echo "capturing $secs s. Open live assist, turn the developer overlay on, and use the app."
  "${ADB[@]}" logcat "${TAGS[@]}" '*:S' > "$dir/logcat.txt" &
  local pid=$!
  sleep "$secs"
  kill "$pid" 2>/dev/null || true
  python3 "$HERE/tools/summarise-connected.py" "$dir" | tee "$dir/summary.txt"
  echo "captured to $dir"
}

privacy() {
  require_device
  local secs="${1:-120}"
  local dir; dir="$(out_dir)"
  state > "$dir/device.txt"
  "${ADB[@]}" logcat -c || true
  echo "capturing $secs s. Exercise the voice button and record which consent answer is active."
  "${ADB[@]}" logcat "${TAGS[@]}" '*:S' > "$dir/logcat.txt" &
  local pid=$!
  sleep "$secs"
  kill "$pid" 2>/dev/null || true

  # Evidence that a session was or was not opened. `StreamingVoice` logs its own connects,
  # and the refusals added for R-31 log themselves too.
  {
    echo "--- streaming session lines ---"
    grep -E "StreamingVoice" "$dir/logcat.txt" || echo "(none: no Live session was opened)"
    echo
    echo "--- refusals ---"
    grep -E "refused: the user has not agreed" "$dir/logcat.txt" || echo "(none)"
    echo
    echo "--- on-device recognition ---"
    grep -E "SpeechCommandRecognizer" "$dir/logcat.txt" || echo "(none)"
  } | tee "$dir/privacy.txt"
  echo "captured to $dir"
}

case "${1:-}" in
  state)    state ;;
  tests)    tests "${2:-}" ;;
  session)  session "${2:-}" ;;
  latency)  python3 "$HERE/tools/summarise-connected.py" "${2:?usage: device-validation.sh latency <dir>}" ;;
  privacy)  privacy "${2:-}" ;;
  *) sed -n '2,15p' "$0"; exit 1 ;;
esac
