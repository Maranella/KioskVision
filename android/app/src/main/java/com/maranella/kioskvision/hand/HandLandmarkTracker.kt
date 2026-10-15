package com.maranella.kioskvision.hand

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.Closeable

/** Index fingertip in frame pixels. */
data class HandObservation(
    val x: Float,
    val y: Float,
    /**
     * MediaPipe's handedness score for the hand. HandLandmarker does not expose its
     * hand-presence score (it only filters by it), so this is used as a quality proxy: it
     * drops for partial or ambiguous hands.
     */
    val score: Float,
)

/**
 * MediaPipe Tasks Hand Landmarker in VIDEO mode (synchronous, temporally tracked), running on
 * the caller's thread. Not thread-safe; use and close on the pipeline thread.
 */
class HandLandmarkTracker private constructor(private val landmarker: HandLandmarker) : Closeable {

    private var lastTimestampMs = Long.MIN_VALUE

    /**
     * Why the last frame produced no fingertip. Distinguishes "MediaPipe saw no hand at all"
     * from "it saw one but the app rejected it", which look identical from the outside and
     * need completely different fixes.
     */
    @Volatile
    var lastMiss: String? = null
        private set

    /**
     * Returns the index fingertip of the most prominent hand, or null when no hand passes
     * MediaPipe's presence threshold or the fingertip is outside the frame.
     *
     * Does not take ownership of [bitmap].
     */
    fun detect(bitmap: Bitmap, timestampMs: Long): HandObservation? {
        // VIDEO mode requires strictly increasing timestamps.
        val timestamp = if (timestampMs <= lastTimestampMs) lastTimestampMs + 1 else timestampMs
        lastTimestampMs = timestamp

        // Deliberately not closed: closing a bitmap-backed MPImage recycles the caller's bitmap,
        // and it holds no native resources of its own.
        val image = BitmapImageBuilder(bitmap).build()
        val result = landmarker.detectForVideo(image, timestamp)

        val landmarks = result.landmarks().firstOrNull()
        if (landmarks == null) {
            lastMiss = "no hand in frame"
            return null
        }
        val tip = landmarks.getOrNull(INDEX_FINGER_TIP)
        if (tip == null) {
            lastMiss = "no index fingertip landmark"
            return null
        }
        val score = result.handedness().firstOrNull()?.firstOrNull()?.score() ?: 0f
        if (tip.x() !in EDGE_MARGIN..1f - EDGE_MARGIN || tip.y() !in EDGE_MARGIN..1f - EDGE_MARGIN) {
            lastMiss = "fingertip off frame at %.2f,%.2f score %.2f".format(tip.x(), tip.y(), score)
            return null
        }
        lastMiss = null
        return HandObservation(x = tip.x() * bitmap.width, y = tip.y() * bitmap.height, score = score)
    }

    override fun close() {
        landmarker.close()
    }

    companion object {
        const val MODEL_ASSET = "hand_landmarker.task"
        /** Index finger tip in MediaPipe's 21-point hand landmark model. */
        const val INDEX_FINGER_TIP = 8
        /** A fingertip this close to the frame edge is likely partly outside it. */
        private const val EDGE_MARGIN = 0.01f

        /** Loads the bundled model. Blocking; call off the main thread. */
        fun create(context: Context): HandLandmarkTracker {
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.6f)
                .setMinTrackingConfidence(0.5f)
                .build()
            return HandLandmarkTracker(HandLandmarker.createFromOptions(context, options))
        }
    }
}
