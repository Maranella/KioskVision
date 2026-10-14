package com.cellfie.kioskvision.camera

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.Preview
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
 */
@Composable
fun CameraPreview(
    bindAttempt: Int,
    onEvent: (CameraEvent) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
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

    LaunchedEffect(previewView, lifecycleOwner, bindAttempt) {
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

        val camera = try {
            provider.bindToLifecycle(lifecycleOwner, selector, preview)
        } catch (e: Exception) {
            Log.e(TAG, "Binding the preview failed", e)
            provider.unbind(preview)
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

        try {
            camera.cameraInfo.cameraState.observe(lifecycleOwner, cameraStateObserver)
            previewView.previewStreamState.observe(lifecycleOwner, streamObserver)
            awaitCancellation()
        } finally {
            // Runs on the main thread when this composable leaves composition or the
            // binding is retried, releasing the camera immediately.
            camera.cameraInfo.cameraState.removeObserver(cameraStateObserver)
            previewView.previewStreamState.removeObserver(streamObserver)
            provider.unbind(preview)
        }
    }
}

private fun CameraState.StateError.toIssue(): CameraIssue = when {
    type == CameraState.ErrorType.RECOVERABLE -> CameraIssue.TemporarilyUnavailable
    code == CameraState.ERROR_CAMERA_DISABLED -> CameraIssue.Disabled
    else -> CameraIssue.Failed
}
