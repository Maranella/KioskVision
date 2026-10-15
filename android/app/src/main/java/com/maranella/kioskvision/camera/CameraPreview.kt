package com.maranella.kioskvision.camera

import android.util.Log
import androidx.camera.core.CameraSelector
import android.util.Size
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor

enum class CameraIssue {
    NoCamera,

    /** In use by another app or briefly unavailable; CameraX retries on its own. */
    TemporarilyUnavailable,

    Disabled,
    Failed,
}

sealed interface CameraEvent {
    data object Starting : CameraEvent
    data object Streaming : CameraEvent
    data object Stopped : CameraEvent
    data class Failed(val issue: CameraIssue) : CameraEvent
}

private const val TAG = "CameraPreview"

/**
 * Full-size live preview from the rear camera.
 *
 * The Preview use case is bound to the current [LocalLifecycleOwner], so CameraX itself
 * closes the camera on ON_STOP and reopens it on ON_START; nothing here needs to react
 * to pause/resume. The binding is created once per composition (or per [bindAttempt])
 * and removed when this composable leaves composition, which prevents duplicate bindings
 * across recompositions, configuration changes, and permission loss.
 *
 * When [analyzer] is given, an ImageAnalysis use case is bound in the same session. Both use
 * cases share the PreviewView's ViewPort, so analysis frames cover exactly what the preview
 * shows (see [FrameGeometry]).
 *
 * A [focusController] is attached to the bound camera for the lifetime of the binding, which
 * is the only thing that may drive focus; see [ScreenFocusController].
 */
@Composable
fun CameraPreview(
    bindAttempt: Int,
    onEvent: (CameraEvent) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    analyzer: ImageAnalysis.Analyzer? = null,
    analysisExecutor: Executor? = null,
    focusController: ScreenFocusController? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnEvent by rememberUpdatedState(onEvent)

    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // The user is holding the phone up to a kiosk, not touching it; don't let it sleep.
            keepScreenOn = true
            this.contentDescription = contentDescription
        }
    }

    AndroidView(
        factory = { previewView },
        update = { it.contentDescription = contentDescription },
        modifier = modifier,
    )

    LaunchedEffect(previewView, lifecycleOwner, bindAttempt, analyzer, analysisExecutor) {
        currentOnEvent(CameraEvent.Starting)

        val provider = try {
            ProcessCameraProvider.awaitInstance(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Camera provider initialization failed", e)
            currentOnEvent(CameraEvent.Failed(CameraIssue.Failed))
            return@LaunchedEffect
        }

        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val hasBackCamera = try {
            provider.hasCamera(selector)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to query cameras", e)
            false
        }
        if (!hasBackCamera) {
            currentOnEvent(CameraEvent.Failed(CameraIssue.NoCamera))
            return@LaunchedEffect
        }

        val preview = Preview.Builder().build()
        preview.surfaceProvider = previewView.surfaceProvider
        val analysis = if (analyzer != null && analysisExecutor != null) {
            buildAnalysis().also { it.setAnalyzer(analysisExecutor, analyzer) }
        } else {
            null
        }
        val useCases = UseCaseGroup.Builder().addUseCase(preview).apply {
            analysis?.let(::addUseCase)
            // Without a ViewPort the analysis frame may show more than the preview; guidance is
            // unaffected (it never uses view coordinates), only the developer overlay would be off.
            previewView.awaitViewPort()?.let(::setViewPort) ?: Log.w(TAG, "No ViewPort; binding without one")
        }.build()

        val camera = try {
            provider.bindToLifecycle(lifecycleOwner, selector, useCases)
        } catch (e: Exception) {
            Log.e(TAG, "Binding the camera use cases failed", e)
            analysis?.clearAnalyzer()
            provider.unbind(*listOfNotNull(preview, analysis).toTypedArray())
            currentOnEvent(CameraEvent.Failed(CameraIssue.Failed))
            return@LaunchedEffect
        }

        val cameraStateObserver = Observer<CameraState> { state ->
            val error = state.error
            when {
                error != null -> currentOnEvent(CameraEvent.Failed(error.toIssue()))
                state.type == CameraState.Type.OPENING -> currentOnEvent(CameraEvent.Starting)
            }
        }
        val streamObserver = Observer<PreviewView.StreamState> { streamState ->
            currentOnEvent(
                if (streamState == PreviewView.StreamState.STREAMING) {
                    CameraEvent.Streaming
                } else {
                    CameraEvent.Stopped
                },
            )
        }

        focusController?.attach(camera) {
            val width = previewView.width.toFloat()
            val height = previewView.height.toFloat()
            // Before layout there is nothing meaningful to meter on.
            if (width <= 0f || height <= 0f) {
                null
            } else {
                previewView.meteringPointFactory.centrePoint(width, height)
            }
        }

        try {
            camera.cameraInfo.cameraState.observe(lifecycleOwner, cameraStateObserver)
            previewView.previewStreamState.observe(lifecycleOwner, streamObserver)
            awaitCancellation()
        } finally {
            // Runs on the main thread when this composable leaves composition or the
            // binding is retried, releasing the camera immediately.
            focusController?.detach()
            camera.cameraInfo.cameraState.removeObserver(cameraStateObserver)
            previewView.previewStreamState.removeObserver(streamObserver)
            analysis?.clearAnalyzer()
            provider.unbind(*listOfNotNull(preview, analysis).toTypedArray())
        }
    }
}

/**
 * RGBA frames, about 1280×720 (16:9 to match common sensors), keeping only the latest frame
 * so a slow analyzer never builds a queue.
 */
private fun buildAnalysis(): ImageAnalysis = ImageAnalysis.Builder()
    .setResolutionSelector(
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build(),
    )
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
    .build()

/** The ViewPort exists once the PreviewView is attached and laid out. */
private suspend fun PreviewView.awaitViewPort(): ViewPort? = withTimeoutOrNull(2_000) {
    var port = viewPort
    while (port == null) {
        delay(16)
        port = viewPort
    }
    port
}

private fun CameraState.StateError.toIssue(): CameraIssue = when {
    type == CameraState.ErrorType.RECOVERABLE -> CameraIssue.TemporarilyUnavailable
    code == CameraState.ERROR_CAMERA_DISABLED -> CameraIssue.Disabled
    else -> CameraIssue.Failed
}
