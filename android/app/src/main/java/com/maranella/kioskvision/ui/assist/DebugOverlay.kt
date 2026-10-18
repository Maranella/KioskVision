package com.maranella.kioskvision.ui.assist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maranella.kioskvision.assist.FrameSize
import com.maranella.kioskvision.assist.TargetMatcher
import com.maranella.kioskvision.assist.TextBox
import com.maranella.kioskvision.camera.FrameGeometry
import com.maranella.kioskvision.camera.ScreenDetector
import com.maranella.kioskvision.pipeline.DebugFrame
import java.util.Locale

/**
 * Developer-only visualisation of the pipeline, drawn over the preview. Hidden from TalkBack;
 * a blind user never needs it.
 */
@Composable
internal fun DebugOverlay(frame: DebugFrame?, modifier: Modifier = Modifier) {
    Box(modifier = modifier.clearAndSetSemantics { }) {
        if (frame == null) return@Box
        Canvas(modifier = Modifier.matchParentSize()) {
            val map = { x: Float, y: Float ->
                val (vx, vy) = FrameGeometry.frameToView(x, y, frame.frame, size.width, size.height)
                Offset(vx, vy)
            }
            frame.blocks.forEach { block ->
                drawBox(block.boundingBox, frame.frame, map, if (block.confidence >= 0.6f) Color.Green else Color.Gray)
            }
            frame.targetBox?.let { box ->
                drawBox(box, frame.frame, map, if (frame.targetStable) Color.Yellow else Color(0xFFFF9800), width = 6f)
                val center = map(box.centerX, box.centerY)
                drawCircle(Color.Yellow, radius = 14f, center = center)
                frame.fingertipSmoothed?.let { tip ->
                    drawLine(Color.Magenta, map(tip.x, tip.y), center, strokeWidth = 5f)
                }
            }
            // The whole hand, so "MediaPipe is not running" and "it runs and sees nothing"
            // stop looking the same. Twenty-one points landing on a real hand is the proof a
            // missing fingertip cannot give.
            frame.handLandmarks?.forEach { (x, y) ->
                drawCircle(Color(0xFF80DEEA), radius = 5f, center = map(x, y))
            }
            frame.fingertipRaw?.let { drawCircle(Color.Red, radius = 10f, center = map(it.x, it.y), style = Stroke(3f)) }
            frame.fingertipSmoothed?.let { drawCircle(Color.Cyan, radius = 16f, center = map(it.x, it.y)) }

            // The detected kiosk screen. Without it drawn, there is no way to tell a screen
            // that was never found from one found in the wrong place.
            frame.screenQuad?.let { quad ->
                val corners = quad.corners.map { map(it.x, it.y) }
                val edge = if (frame.screenStable) Color(0xFF4FC3F7) else Color(0xFF9E9E9E)
                for (i in corners.indices) {
                    drawLine(edge, corners[i], corners[(i + 1) % corners.size], strokeWidth = 4f)
                }
                // Marks the corner the transform treats as the screen's top-left: a corner
                // ordering bug looks like a plausible quadrilateral until this moves.
                drawCircle(edge, radius = 9f, center = corners[0])
            }

            // What guidance actually reasoned about, mapped back through the same homography.
            // Coincides with the raw box when the geometry is sound; separates visibly when it
            // is not. Drawn last so it sits on top of the box it is being compared against.
            frame.guidanceTargetBox?.let { box ->
                drawBox(box, frame.frame, map, Color(0xFFE040FB), width = 3f)
            }
            frame.guidanceFingertip?.let {
                drawCircle(Color(0xFFE040FB), radius = 7f, center = map(it.x, it.y), style = Stroke(3f))
            }
        }
        Text(
            text = frame.describe(),
            color = Color.White,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 8.dp, top = 56.dp),
        )
    }
}

private fun DrawScope.drawBox(
    box: TextBox,
    frame: FrameSize,
    map: (Float, Float) -> Offset,
    color: Color,
    width: Float = 3f,
) {
    val topLeft = map(box.left, box.top)
    val bottomRight = map(box.right, box.bottom)
    drawRect(color, topLeft, Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y), style = Stroke(width))
}

/** `ScreenDetector.MIN_COVERAGE` as a percentage, for the overlay's "needs" figure. */
private const val MIN_COVERAGE_PERCENT = ScreenDetector.MIN_COVERAGE * 100f

/** Raw OCR lines to show. Enough to see what a screen says without burying the rest. */
private const val MAX_RAW_LINES = 8

private fun DebugFrame.describe(): String = buildString {
    appendLine("phase $phase")
    appendLine(
        String.format(
            Locale.ROOT,
            "frame #%d %d×%dpx  hand %dms  brightness %.0f%s",
            frameId, frame.width, frame.height, handMs, frameLuminance,
            if (frameLuminance in 0f..25f) "  ← BLACK IMAGE" else "",
        ),
    )
    // When no screen is tracked, *why* is the whole diagnosis. A bare "NOT TRACKED" cannot
    // distinguish a machine whose display is smaller than the detector accepts from one whose
    // housing is brighter than its screen, and those need opposite fixes.
    appendLine(
        if (screenQuad == null) {
            String.format(
                Locale.ROOT,
                "screen NOT TRACKED — %s  area %.1f%% of frame (needs %.0f%%)",
                screenRejection ?: screenLoss ?: "no reason recorded",
                screenCoverage * 100f,
                MIN_COVERAGE_PERCENT,
            )
        } else {
            String.format(
                Locale.ROOT,
                "screen conf %.2f  %s  age %dms  geom v%d  %s",
                screenConfidence,
                if (screenStable) "stable" else "settling",
                screenAgeMs ?: -1L,
                geometryVersion,
                if (rectifies) "rectifies" else "WILL NOT RECTIFY",
            )
        },
    )
    if (screenQuad == null && screenCoverage >= 0f) {
        appendLine(
            String.format(
                Locale.ROOT,
                "  detector  fill %.2f  rect %.2f  separation %.2f",
                screenFill, screenRectangularity, screenSeparation,
            ),
        )
    }
    // How far acquisition has got, and the two counters that gate it. A screen that never
    // reaches ScreenReady is almost always short of one of these, and neither was visible.
    appendLine(
        String.format(
            Locale.ROOT,
            "acquire  runs %d/%d  lines %d/%d  scan %s at %.1fx",
            consistentRuns, acquisitionRunsNeeded,
            retainedLines, informativeLinesNeeded,
            if (scanCroppedToScreen) "cropped" else "WHOLE FRAME",
            fullScanUpscale,
        ),
    )
    // The error vector guidance is acting on, in units of the on-target zone. Printed because
    // the drawn line shows direction but not how close "close" actually is.
    if (zoneOffsetX != null && zoneOffsetY != null) {
        appendLine(
            String.format(Locale.ROOT, "error  x %+.2f  y %+.2f  (zone units)", zoneOffsetX, zoneOffsetY),
        )
    }
    appendLine(
        "ocr ${if (ocrRunning) "RUNNING" else "idle"}  last ${ocrMs}ms" +
            (sinceLastOcrMs?.let { "  ${it}ms ago" } ?: "  never") +
            "  jobs $ocrJobsStarted  lang $ocrLanguages",
    )
    // What Tesseract was actually given. "OCR read nothing" and "OCR was shown the wrong
    // pixels" look identical without it.
    ocrInput?.let { appendLine("  in  $it") }
    // Raw output against what survived intake. This is the line that separates a recognition
    // failure from a filtering one, which the block list above cannot: it is already filtered.
    appendLine(
        "  out raw $ocrRawBlocks  kept $ocrRetainedBlocks" +
            (if (ocrDroppedLowConfidence > 0) "  low-conf $ocrDroppedLowConfidence" else "") +
            (if (ocrDroppedTooFewLetters > 0) "  <2 letters $ocrDroppedTooFewLetters" else "") +
            if (ocrRawBlocks > 0 && ocrRetainedBlocks == 0) "  ← ALL DISCARDED" else "",
    )
    // Kiosk screen content: shown here, on the device, and never logged.
    ocrRawText.take(MAX_RAW_LINES).forEach { appendLine("    $it") }
    if (ocrRawText.size > MAX_RAW_LINES) appendLine("    … ${ocrRawText.size - MAX_RAW_LINES} more")
    appendLine("anchor ${anchorMs}ms  jobs $anchorJobsStarted  blur-skips $blurSkips")
    appendLine(
        "hand " + (if (handsSeen > 0) "${handsSeen}/${handInferences} seen" else "NEVER SEEN in $handInferences") +
            "  ${handMs}ms" + (handMiss?.let { "  miss: $it" } ?: "  tip ok"),
    )
    appendLine("lines ${blocks.size}  steady $deviceSteady${if (!gyroscopeAvailable) " (no gyro)" else ""}")
    appendLine(
        "change ${if (screenChangeSuspected) "SUSPECTED" else "no"}" +
            (screenSignatureDistance?.let { String.format(Locale.ROOT, "  Δ%.3f", it) } ?: "") +
            "  last ${lastOcrUpdate ?: "-"}",
    )
    appendLine(
        String.format(
            Locale.ROOT,
            "focus %s  sharp %.3f of %.3f",
            focusState,
            sharpness,
            referenceSharpness,
        ),
    )
    appendLine(
        if (targetBox == null) {
            "target -"
        } else {
            "target ${targetLabel ?: "?"} " +
                (if (targetStable) "[usable]" else "[unconfirmed]") +
                (if (targetEstimated) " (estimated)" else "") +
                (targetAnchorAgeMs?.let { "  anchored ${it}ms ago" } ?: "  not anchored")
        },
    )
    targetBox?.let {
        appendLine(
            String.format(
                Locale.ROOT,
                "box %.0f,%.0f–%.0f,%.0f  centre %.0f,%.0f",
                it.left, it.top, it.right, it.bottom, it.centerX, it.centerY,
            ),
        )
    }
    fingertipSmoothed?.let { appendLine(String.format(Locale.ROOT, "tip %.0f,%.0f  score %.2f", it.x, it.y, it.confidence)) }
        ?: appendLine("tip -")
    if (zoneOffsetX != null && zoneOffsetY != null) {
        appendLine(String.format(Locale.ROOT, "offset %.2f, %.2f zones", zoneOffsetX, zoneOffsetY))
    }
    appendLine("guidance ${direction ?: "-"}")
    lastMatchOutcome?.let { appendLine("match $it") }
    // The exact readings behind every decision: text, confidence, and how many recent runs
    // agreed on it — the three numbers that decide whether a label may be offered.
    labels.sortedByDescending { it.bestConfidence }.take(6).forEach { label ->
        appendLine(
            String.format(
                Locale.ROOT,
                "  %-18s %.2f x%d%s",
                label.block.text.trim().take(18),
                label.bestConfidence,
                label.timesSeen,
                if (TargetMatcher.isTrustworthy(label)) "" else "  (untrusted)",
            ),
        )
    }
    append("")
}
