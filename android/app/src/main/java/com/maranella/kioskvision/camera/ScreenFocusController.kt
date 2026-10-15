package com.maranella.kioskvision.camera

import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import androidx.camera.core.MeteringPointFactory
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

enum class FocusState {
    /** No focus run has been requested yet, or the camera is not bound. */
    Unknown,
    Focusing,

    /** Converged on the kiosk screen region and held there (auto-cancel disabled). */
    Locked,

    /** The camera ran the request but did not converge; the image may be soft. */
    Failed,

    /** The device reports no focus/metering support for this region. Fixed-focus optics. */
    Unsupported,
}

/**
 * Points autofocus at the kiosk screen and keeps it there.
 *
 * By default CameraX leaves the camera in continuous autofocus, which re-converges on whatever
 * the AF algorithm finds most salient. A hand reaching into the frame, much closer to the lens
 * than the kiosk, is exactly that — so the screen text goes soft the moment the user moves
 * their finger, which is also when OCR can least afford it.
 *
 * Instead the screen is focused **once**, explicitly, on a region in the middle of the preview,
 * with [FocusMeteringAction.Builder.disableAutoCancel] so the camera holds that lens position
 * instead of resuming its own hunting. Nothing re-triggers focus from fingertip coordinates;
 * only [focusOnScreen] does, and only on acquisition, an explicit rescan, or detected blur.
 *
 * ### Hardware limits
 * Focus lock is a *request*. Some devices (fixed-focus modules, some HAL implementations)
 * ignore auto-cancel, re-run continuous AF on their own, or report no metering support at all.
 * Those cases surface as [FocusState.Failed] / [FocusState.Unsupported] rather than an error,
 * and the pipeline falls back to its own image-sharpness check.
 *
 * All methods must be called on [executor] (the main thread).
 */
class ScreenFocusController(
    private val executor: Executor,
    private val onState: (FocusState) -> Unit,
) {
    private var camera: Camera? = null
    private var pointFactory: (() -> MeteringPoint?)? = null
    private var lastRequestMs = Long.MIN_VALUE / 2

    var state: FocusState = FocusState.Unknown
        private set

    fun attach(camera: Camera, pointFactory: () -> MeteringPoint?) {
        this.camera = camera
        this.pointFactory = pointFactory
        publish(FocusState.Unknown)
    }

    fun detach() {
        camera = null
        pointFactory = null
        publish(FocusState.Unknown)
    }

    /**
     * Focuses and meters on the centre of the preview, where the kiosk screen is expected.
     *
     * Ignored if a request is already running or one was issued very recently, so repeated
     * calls (e.g. several blurred frames in a row) cannot turn into focus hunting.
     */
    fun focusOnScreen(nowMs: Long) {
        val camera = camera ?: return
        val point = pointFactory?.invoke() ?: return
        if (state == FocusState.Focusing && nowMs - lastRequestMs < REQUEST_TIMEOUT_MS) return
        if (nowMs - lastRequestMs < MIN_REQUEST_INTERVAL_MS) return
        lastRequestMs = nowMs

        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB,
        )
            // Without this the camera resumes its own AF scan after a few seconds, which is
            // precisely the hunting this class exists to stop.
            .disableAutoCancel()
            .build()

        if (!camera.cameraInfo.isFocusMeteringSupported(action)) {
            Log.i(TAG, "Focus metering unsupported on this camera; relying on fixed focus")
            publish(FocusState.Unsupported)
            return
        }

        publish(FocusState.Focusing)
        setAutoExposureLock(locked = false)
        val future = camera.cameraControl.startFocusAndMetering(action)
        future.addListener(
            {
                val converged = runCatching { future.get(0, TimeUnit.MILLISECONDS).isFocusSuccessful }
                    .getOrElse {
                        // Cancelled by a newer request, or rejected by the device.
                        Log.w(TAG, "Focus request did not complete", it)
                        null
                    }
                when (converged) {
                    true -> {
                        // Exposure and white balance drift badly when a hand shades the screen;
                        // pinning them keeps the text as readable as it was at acquisition.
                        setAutoExposureLock(locked = true)
                        publish(FocusState.Locked)
                    }
                    false -> publish(FocusState.Failed)
                    null -> if (state == FocusState.Focusing) publish(FocusState.Failed)
                }
            },
            executor,
        )
    }

    /** Releases the exposure/white-balance lock, e.g. before re-acquiring a new screen. */
    fun release() {
        setAutoExposureLock(locked = false)
        runCatching { camera?.cameraControl?.cancelFocusAndMetering() }
        publish(FocusState.Unknown)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun setAutoExposureLock(locked: Boolean) {
        val camera = camera ?: return
        // Camera2 interop: unsupported keys are dropped by the device, and a failure here must
        // never take down the session, so everything is best-effort.
        runCatching {
            Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, locked)
                    .build(),
            )
        }.onFailure { Log.w(TAG, "Exposure lock unsupported", it) }
    }

    private fun publish(next: FocusState) {
        if (state != next) {
            state = next
            onState(next)
        }
    }

    companion object {
        /** Fraction of the sensor area metered, centred on the preview. */
        const val REGION_SIZE = 0.25f

        /** Floor between focus requests, so blurred frames cannot cause hunting. */
        private const val MIN_REQUEST_INTERVAL_MS = 1_500L
        private const val REQUEST_TIMEOUT_MS = 3_000L
        private const val TAG = "ScreenFocusController"
    }
}

/** A metering point at the centre of the view, covering [ScreenFocusController.REGION_SIZE]. */
fun MeteringPointFactory.centrePoint(width: Float, height: Float): MeteringPoint =
    createPoint(width / 2f, height / 2f, ScreenFocusController.REGION_SIZE)
