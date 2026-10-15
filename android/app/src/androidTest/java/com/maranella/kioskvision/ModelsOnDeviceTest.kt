package com.maranella.kioskvision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maranella.kioskvision.assist.ScreenReader
import com.maranella.kioskvision.assist.TargetMatch
import com.maranella.kioskvision.assist.TargetMatcher
import com.maranella.kioskvision.hand.HandLandmarkTracker
import com.maranella.kioskvision.ocr.KioskOcrEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Runs the real bundled models on a device or emulator. No mocks: Tesseract with `kat+eng` and
 * MediaPipe's hand landmarker, on synthetic and official test images.
 */
@RunWith(AndroidJUnit4::class)
class ModelsOnDeviceTest {

    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    /**
     * Demo kiosk menu as a 2×2 grid at known centres on a portrait 720×1280 "screen". Labels
     * on the same row must come back as separate blocks, not one merged line.
     */
    private val labels = listOf(
        "გადახდა" to (180f to 420f),
        "ბალანსის შემოწმება" to (530f to 420f),
        "სხვა მომსახურება" to (180f to 820f),
        "დახმარება" to (530f to 820f),
    )

    private fun renderKioskMenu(): Bitmap {
        val bitmap = createBitmap(720, 1280)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 30f
            textAlign = Paint.Align.CENTER
        }
        for ((text, center) in labels) {
            // Baseline so the text is vertically centred on the given point.
            val baseline = center.second - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(text, center.first, baseline, paint)
        }
        return bitmap
    }

    @Test
    fun ocrReadsGeorgianKioskLabelsAndMatchesTheSpokenTarget() {
        val ocr = KioskOcrEngine.create(appContext)
        try {
            val started = SystemClock.elapsedRealtime()
            val blocks = ocr.recognize(renderKioskMenu())
            val elapsed = SystemClock.elapsedRealtime() - started
            Log.i(TAG, "OCR ${elapsed}ms: " + blocks.joinToString { "'${it.text}' ${"%.2f".format(it.confidence)} ${it.boundingBox}" })

            val match = TargetMatcher.match("გადახდა", blocks)
            assertTrue("payment not matched: $match", match is TargetMatch.Found)
            val box = (match as TargetMatch.Found).block.boundingBox
            assertTrue("payment centre ${box.centerX},${box.centerY}", abs(box.centerX - 180f) < 30f && abs(box.centerY - 420f) < 30f)

            for ((label, center) in labels) {
                val found = TargetMatcher.match(label, blocks)
                assertTrue("$label not matched: $found", found is TargetMatch.Found)
                val b = (found as TargetMatch.Found).block.boundingBox
                assertTrue("$label centre ${b.centerX},${b.centerY}", abs(b.centerX - center.first) < 30f && abs(b.centerY - center.second) < 30f)
            }

            val reading = ScreenReader.read(blocks)
            Log.i(TAG, "Screen reading: $reading")
            assertEquals(labels.map { it.first }, reading.lines)
        } finally {
            ocr.close()
        }
    }

    @Test
    fun handLandmarkerFindsTheIndexFingertipOnARealPhoto() {
        val photo = testContext.assets.open("pointing_up.jpg").use { BitmapFactory.decodeStream(it) }
            .copy(Bitmap.Config.ARGB_8888, false)
        assertEquals(358, photo.width)
        val tracker = HandLandmarkTracker.create(appContext)
        try {
            val tip = tracker.detect(photo, timestampMs = 0)
            Log.i(TAG, "Fingertip: $tip")
            assertNotNull("no hand found", tip)
            // The raised index fingertip in this image is at about (172, 68).
            assertTrue("fingertip at ${tip!!.x},${tip.y}", abs(tip.x - 172f) < 25f && abs(tip.y - 68f) < 25f)
            assertTrue("score ${tip.score}", tip.score >= 0.6f)

            // A frame with no hand must not produce a fingertip.
            val blank = createBitmap(358, 376).apply { eraseColor(Color.LTGRAY) }
            assertNull(tracker.detect(blank, timestampMs = 33))
        } finally {
            tracker.close()
        }
    }

    private companion object {
        const val TAG = "ModelsOnDeviceTest"
    }
}
