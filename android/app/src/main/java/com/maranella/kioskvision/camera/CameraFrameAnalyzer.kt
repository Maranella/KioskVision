package com.maranella.kioskvision.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import com.maranella.kioskvision.assist.FrameSize

/**
 * An upright analysis frame. The receiver owns [bitmap] and must recycle or hand it off.
 *
 * [capturedAtMs] is `SystemClock.elapsedRealtime()` when the `ImageProxy` arrived, before
 * conversion. It exists so acquisition can be *measured* rather than estimated: the difference
 * between it and the moment the pipeline starts on the frame is conversion plus queueing. Zero
 * when the frame did not come from a camera, as in the rendered-frame harnesses.
 */
class AnalysisFrame(val bitmap: Bitmap, val size: FrameSize, val capturedAtMs: Long = 0L)

/**
 * Converts each RGBA `ImageProxy` into an upright, viewport-cropped [AnalysisFrame] (see
 * [FrameGeometry]) and passes it on synchronously, on the analysis executor.
 *
 * Every `ImageProxy` is closed exactly once, in `finally`, before the (possibly slow)
 * consumer runs, so CameraX can deliver the next frame as soon as the consumer is done.
 */
class CameraFrameAnalyzer(
    private val isActive: () -> Boolean,
    private val onFrame: (AnalysisFrame) -> Unit,
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        val arrivedAtMs = SystemClock.elapsedRealtime()
        val frame = try {
            if (isActive()) toUprightFrame(image, arrivedAtMs) else null
        } catch (e: Exception) {
            Log.e(TAG, "Frame conversion failed", e)
            null
        } finally {
            image.close()
        }
        frame?.let(onFrame)
    }

    private fun toUprightFrame(image: ImageProxy, arrivedAtMs: Long): AnalysisFrame {
        val crop = image.cropRect
        val rotation = image.imageInfo.rotationDegrees
        val size = FrameGeometry.uprightSize(crop.width(), crop.height(), rotation)
        val transform = FrameGeometry.cropToFrame(crop.width(), crop.height(), rotation)

        // toBitmap() handles the RGBA row stride but applies neither crop nor rotation.
        val buffer = image.toBitmap()
        val upright = createBitmap(size.width, size.height)
        val matrix = Matrix().apply {
            setValues(transform.toMatrixValues())
            preTranslate(-crop.left.toFloat(), -crop.top.toFloat())
        }
        Canvas(upright).drawBitmap(buffer, matrix, null)
        buffer.recycle()
        return AnalysisFrame(upright, size, arrivedAtMs)
    }

    private companion object {
        const val TAG = "CameraFrameAnalyzer"
    }
}
