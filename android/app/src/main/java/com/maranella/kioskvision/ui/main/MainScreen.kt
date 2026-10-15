package com.maranella.kioskvision.ui.main

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maranella.kioskvision.R
import com.maranella.kioskvision.camera.CameraEvent
import com.maranella.kioskvision.camera.CameraIssue
import com.maranella.kioskvision.permission.CAMERA_PERMISSION
import com.maranella.kioskvision.permission.canRequestPermissionAgain
import com.maranella.kioskvision.permission.hasPermission
import com.maranella.kioskvision.permission.openAppSettings
import com.maranella.kioskvision.ui.assist.LiveAssistScreen
import com.maranella.kioskvision.ui.theme.HighContrastSurface

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onIntent(
            MainIntent.PermissionResult(
                granted = granted,
                canAskAgain = activity?.canRequestPermissionAgain(CAMERA_PERMISSION) ?: true,
            ),
        )
    }

    // ON_START (not ON_RESUME): returning from Settings or the background restarts the
    // activity, while the permission dialog itself only pauses it.
    LifecycleStartEffect(Unit) {
        viewModel.onIntent(MainIntent.PermissionChecked(context.hasPermission(CAMERA_PERMISSION)))
        onStopOrDispose { }
    }

    MainContent(
        state = state,
        onCameraEvent = { viewModel.onIntent(it.toIntent()) },
        // The system dialog is only ever opened by an explicit tap, after the explanation
        // has been read, so a denial can never turn into a prompt loop.
        onRequestPermission = { permissionLauncher.launch(CAMERA_PERMISSION) },
        onOpenSettings = context::openAppSettings,
        onRetryCamera = { viewModel.onIntent(MainIntent.RetryCamera) },
    )
}

@Composable
private fun MainContent(
    state: MainUiState,
    onCameraEvent: (CameraEvent) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryCamera: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HighContrastSurface),
    ) {
        when (state.permission) {
            // Lasts until the first ON_START check; showing nothing avoids flashing (and
            // TalkBack announcing) a screen that may not apply.
            PermissionStatus.Unknown -> Unit

            // Leaving composition (e.g. permission lost) unbinds the camera and stops guidance.
            PermissionStatus.Granted -> LiveAssistScreen(
                cameraState = state,
                onCameraEvent = onCameraEvent,
                onRetryCamera = onRetryCamera,
            )

            PermissionStatus.NotGranted,
            PermissionStatus.Denied,
            PermissionStatus.PermanentlyDenied,
            -> {
                val copy = state.permission.toPermissionCopy()
                PermissionExplanation(
                    title = stringResource(copy.title),
                    message = stringResource(copy.message),
                    actionLabel = stringResource(copy.action),
                    onAction = if (copy.opensSettings) onOpenSettings else onRequestPermission,
                )
            }
        }
    }
}

private data class PermissionCopy(
    @param:StringRes val title: Int,
    @param:StringRes val message: Int,
    @param:StringRes val action: Int,
    val opensSettings: Boolean = false,
)

private fun PermissionStatus.toPermissionCopy(): PermissionCopy = when (this) {
    PermissionStatus.Denied -> PermissionCopy(
        title = R.string.permission_denied_title,
        message = R.string.permission_denied_message,
        action = R.string.action_try_again,
    )
    PermissionStatus.PermanentlyDenied -> PermissionCopy(
        title = R.string.permission_blocked_title,
        message = R.string.permission_blocked_message,
        action = R.string.action_open_settings,
        opensSettings = true,
    )
    else -> PermissionCopy(
        title = R.string.permission_needed_title,
        message = R.string.permission_needed_message,
        action = R.string.action_continue,
    )
}

data class CameraStatusCopy(@param:StringRes val message: Int, val canRetry: Boolean = false)

fun CameraStatus.toCameraStatusCopy(): CameraStatusCopy = when (this) {
    CameraStatus.Idle, CameraStatus.Starting -> CameraStatusCopy(R.string.status_camera_starting)
    CameraStatus.Streaming -> CameraStatusCopy(R.string.status_camera_on)
    is CameraStatus.Unavailable -> when (issue) {
        CameraIssue.NoCamera -> CameraStatusCopy(R.string.status_camera_missing, canRetry = true)
        CameraIssue.TemporarilyUnavailable -> CameraStatusCopy(R.string.status_camera_busy)
        CameraIssue.Disabled -> CameraStatusCopy(R.string.status_camera_disabled, canRetry = true)
        CameraIssue.Failed -> CameraStatusCopy(R.string.status_camera_failed, canRetry = true)
    }
}

private fun CameraEvent.toIntent(): MainIntent = when (this) {
    CameraEvent.Starting -> MainIntent.CameraStarting
    CameraEvent.Streaming -> MainIntent.CameraStreaming
    CameraEvent.Stopped -> MainIntent.CameraStopped
    is CameraEvent.Failed -> MainIntent.CameraFailed(issue)
}
