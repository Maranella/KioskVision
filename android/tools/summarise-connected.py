#!/usr/bin/env python3
"""Turn one validation capture into the few numbers a report should quote.

Reads whatever is in the directory: instrumented-test XML if the suite ran, and the logcat
capture either way. Prints per-test verdicts, the latency percentiles the app measured itself,
and the OCR run costs it logged.

Nothing is inferred. A stage with no samples is reported as having none, because "no
measurement" and "fast" look identical in a summary that omits them.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET


def tests(directory):
    files = glob.glob(os.path.join(directory, "results", "**", "*.xml"), recursive=True)
    if not files:
        return None
    passed, failed, skipped, aborted = [], [], [], []
    for path in files:
        root = ET.parse(path).getroot()
        for case in root.iter("testcase"):
            name = "%s.%s" % (case.get("classname", "").split(".")[-1], case.get("name"))
            children = [child.tag for child in case]
            def first_line(child):
                attribute = child.get("message")
                if attribute:
                    return attribute
                lines = (child.text or "").strip().splitlines()
                return lines[0] if lines else ""

            message = next(
                (first_line(c) for c in case if c.tag in ("failure", "error")),
                "",
            ).strip()
            if "skipped" in children:
                skipped.append(name)
            elif "AssumptionViolatedException" in message:
                # A test that declined to run rather than one that failed. The harnesses here
                # skip deliberately when a capture they need is absent, because substituting a
                # stand-in would answer a different question — reporting that as a failure
                # buries the real ones.
                skipped.append("%s (declined: %s)" % (name, message.split(":")[-1].strip()[:80]))
            elif ("failure" in children or "error" in children) and not message:
                aborted.append(name)
            elif "failure" in children or "error" in children:
                failed.append((name, message[:200]))
            else:
                passed.append(name)
    return passed, failed, skipped, aborted


# "LATENCY hand n=57 p50=31ms p90=44ms max=120ms"
LATENCY = re.compile(r"LATENCY (\S+) n=(\d+) p50=(\d+)ms p90=(\d+)ms max=(\d+)ms")
# "OCR in=... 507ms raw=24 kept=14 ..."
OCR = re.compile(r"OCR in=(\S+).* (\d+)ms raw=(\d+) kept=(\d+)")
# Re-anchor runs: the cheap path that keeps a held target's position fresh.
ANCHOR = re.compile(r"ANCHOR in=(\S+).* (\d+)ms blocks=(\d+) -> (\w+)")
PHASE = re.compile(r"(AcquiringScreen|ScreenReady|GuidingFinger|WaitingForScreenChange|ReadingNewScreen)")


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(int((len(ordered) - 1) * fraction), len(ordered) - 1)]


def logs(directory):
    path = os.path.join(directory, "logcat.txt")
    if not os.path.isfile(path):
        return None
    with open(path, errors="replace") as handle:
        text = handle.read()

    stages = {}
    for stage, n, p50, p90, mx in LATENCY.findall(text):
        # Last line wins: the tracker reports a rolling window, so the final line is the most
        # samples it ever held.
        stages[stage] = (int(n), int(p50), int(p90), int(mx))

    ocr_ms = [int(ms) for _, ms, _, _ in OCR.findall(text)]
    kept = [int(k) for _, _, _, k in OCR.findall(text)]
    anchors = [(int(ms), outcome) for _, ms, _, outcome in ANCHOR.findall(text)]
    phases = PHASE.findall(text)
    return stages, ocr_ms, kept, anchors, phases


def main():
    directory = sys.argv[1] if len(sys.argv) > 1 else "."
    result = tests(directory)
    if result:
        passed, failed, skipped, aborted = result
        print("instrumented tests: %d passed, %d failed, %d skipped, %d aborted"
              % (len(passed), len(failed), len(skipped), len(aborted)))
        for name in passed:
            print("  PASS %s" % name)
        for name in skipped:
            print("  SKIP %s" % name)
        for name, message in failed:
            print("  FAIL %s\n       %s" % (name, message))
        for name in aborted:
            print("  ABORTED %s\n       no message and no stack: the runner never heard back "
                  "from this test. Almost always the device going away mid-run, not an "
                  "assertion. Check the capture for the test's own log tag." % name)
    else:
        print("instrumented tests: no results in this capture")

    parsed = logs(directory)
    if not parsed:
        print("logcat: not captured")
        return
    stages, ocr_ms, kept, anchors, phases = parsed

    print("\nlatency, as the app measured itself:")
    if not stages:
        print("  no LATENCY lines — they need a debuggable build with diagnostics on")
    for stage in ("frame-acquire", "hand", "decide", "announce-wait", "speak-start", "frame-total"):
        if stage in stages:
            n, p50, p90, mx = stages[stage]
            print("  %-14s n=%-5d p50=%-8s p90=%-8s max=%s"
                  % (stage, n, "%dms" % p50, "%dms" % p90, "%dms" % mx))
        else:
            print("  %-14s no samples" % stage)

    print("\nOCR runs: %d" % len(ocr_ms))
    if ocr_ms:
        print("  p50=%dms p90=%dms max=%dms" % (percentile(ocr_ms, 0.5), percentile(ocr_ms, 0.9), max(ocr_ms)))
        print("  lines kept: p50=%d max=%d" % (percentile(kept, 0.5), max(kept)))
    if anchors:
        times = [ms for ms, _ in anchors]
        outcomes = {}
        for _, outcome in anchors:
            outcomes[outcome] = outcomes.get(outcome, 0) + 1
        print("\nre-anchor runs: %d  p50=%dms p90=%dms max=%dms  %s"
              % (len(anchors), percentile(times, 0.5), percentile(times, 0.9), max(times), outcomes))
    else:
        print("\nre-anchor runs: none in this capture")

    if phases:
        order = []
        for phase in phases:
            if not order or order[-1] != phase:
                order.append(phase)
        print("\nphases seen: %s" % " -> ".join(order[:12]))


if __name__ == "__main__":
    main()
