package com.maranella.kioskvision.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import com.maranella.kioskvision.assist.FrameSize

/** An upright analysis frame. The receiver owns [bitmap] and must recycle or hand it off. */
class AnalysisFrame(val bitmap: Bitmap, val size: FrameSize)

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
        val frame = try {
            if (isActive()) toUprightFrame(image) else null
        } catch (e: Exception) {
            Log.e(TAG, "Frame conversion failed", e)
            null
        } finally {
            image.close()
        }
        frame?.let(onFrame)
    }

    private fun toUprightFrame(image: ImageProxy): AnalysisFrame {
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
        return AnalysisFrame(upright, size)
    }

    private companion object {
        const val TAG = "CameraFrameAnalyzer"
    }
}
