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
            frame.fingertipRaw?.let { drawCircle(Color.Red, radius = 10f, center = map(it.x, it.y), style = Stroke(3f)) }
            frame.fingertipSmoothed?.let { drawCircle(Color.Cyan, radius = 16f, center = map(it.x, it.y)) }
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

private fun DebugFrame.describe(): String = buildString {
    appendLine("phase $phase")
    appendLine("frame ${frame.width}×${frame.height}px  hand ${handMs}ms")
    appendLine(
        "ocr ${if (ocrRunning) "RUNNING" else "idle"}  last ${ocrMs}ms" +
            (sinceLastOcrMs?.let { "  ${it}ms ago" } ?: "  never") +
            "  jobs $ocrJobsStarted",
    )
    appendLine("anchor ${anchorMs}ms  jobs $anchorJobsStarted  blur-skips $blurSkips")
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
