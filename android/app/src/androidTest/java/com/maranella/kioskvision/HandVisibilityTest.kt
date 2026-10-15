package com.maranella.kioskvision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maranella.kioskvision.hand.HandLandmarkTracker
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * How much of a hand must be in the camera view before MediaPipe will track the fingertip?
 *
 * The live app reports "no hand in frame" continuously while the user believes their finger is
 * in shot. MediaPipe's hand landmarker needs the palm, not just a protruding finger, so this
 * measures both the usable size range and how much of the hand may be cut off by the frame
 * edge — which turns "it never sees my finger" into concrete advice.
 */
@RunWith(AndroidJUnit4::class)
class HandVisibilityTest {

    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val width = 576
    private val height = 1280

    private lateinit var hand: Bitmap

    /** Draws the hand photo scaled by [scale], with its centre at ([cx], [cy]) of the frame. */
    private fun frameWith(scale: Float, cx: Float, cy: Float): Bitmap {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(230, 232, 238))
        val w = hand.width * scale
        val h = hand.height * scale
        canvas.drawBitmap(
            hand,
            null,
            RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return bitmap
    }

    @Test
    fun handSizeAndFramingLimits() {
        hand = testContext.assets.open("pointing_up.jpg").use { BitmapFactory.decodeStream(it) }
            .copy(Bitmap.Config.ARGB_8888, false)
        val tracker = HandLandmarkTracker.create(appContext)
        var clock = 0L
        try {
            Log.i(TAG, "=== hand size: fully in frame, centred (frame ${width}x$height) ===")
            for (scale in listOf(0.4f, 0.7f, 1.0f, 1.4f, 1.8f, 2.4f, 3.2f)) {
                val bitmap = frameWith(scale, width / 2f, height / 2f)
                clock += 40
                val tip = tracker.detect(bitmap, clock)
                Log.i(
                    TAG,
                    String.format(
                        Locale.ROOT,
                        "scale %.1f  hand %4.0fx%4.0f px (%3.0f%% of frame width)  detected=%b %s",
                        scale, hand.width * scale, hand.height * scale,
                        100f * hand.width * scale / width,
                        tip != null,
                        tip?.let { String.format(Locale.ROOT, "tip %.0f,%.0f score %.2f", it.x, it.y, it.score) }
                            ?: "(${tracker.lastMiss})",
                    ),
                )
                bitmap.recycle()
            }

            Log.i(TAG, "=== how far the hand may hang outside the frame (scale 1.2) ===")
            // Pushed progressively off the bottom edge, as a hand reaching up into shot is.
            for (fraction in listOf(0.5f, 0.7f, 0.85f, 1.0f, 1.15f, 1.3f)) {
                val bitmap = frameWith(1.2f, width / 2f, height * fraction)
                clock += 40
                val tip = tracker.detect(bitmap, clock)
                val visible = ((height - (height * fraction - hand.height * 1.2f / 2f)) /
                    (hand.height * 1.2f)).coerceIn(0f, 1f)
                Log.i(
                    TAG,
                    String.format(
                        Locale.ROOT,
                        "centre at %.0f%% of height  ~%2.0f%% of the hand in frame  detected=%b %s",
                        fraction * 100, visible * 100, tip != null, tracker.lastMiss ?: "",
                    ),
                )
                bitmap.recycle()
            }
        } finally {
            tracker.close()
            hand.recycle()
        }
    }

    private companion object {
        const val TAG = "HandVisibility"
    }
}
