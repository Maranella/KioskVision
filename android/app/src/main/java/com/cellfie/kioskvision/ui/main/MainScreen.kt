package com.cellfie.kioskvision.ui.main

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cellfie.kioskvision.R
import com.cellfie.kioskvision.camera.CameraEvent
import com.cellfie.kioskvision.camera.CameraIssue
import com.cellfie.kioskvision.camera.CameraPreview
import com.cellfie.kioskvision.permission.CAMERA_PERMISSION
import com.cellfie.kioskvision.permission.canRequestCameraPermissionAgain
import com.cellfie.kioskvision.permission.hasCameraPermission
import com.cellfie.kioskvision.permission.openAppSettings
import com.cellfie.kioskvision.ui.theme.HighContrastAccent
import com.cellfie.kioskvision.ui.theme.HighContrastOnAccent
import com.cellfie.kioskvision.ui.theme.HighContrastOnSurface
import com.cellfie.kioskvision.ui.theme.HighContrastSurface

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
                canAskAgain = activity?.canRequestCameraPermissionAgain() ?: true,
            ),
        )
    }

    // ON_START (not ON_RESUME): returning from Settings or the background restarts the
    // activity, while the permission dialog itself only pauses it.
    LifecycleStartEffect(Unit) {
        viewModel.onIntent(MainIntent.PermissionChecked(context.hasCameraPermission()))
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

            PermissionStatus.Granted -> CameraContent(
                state = state,
                onCameraEvent = onCameraEvent,
                onRetryCamera = onRetryCamera,
            )

            PermissionStatus.NotGranted,
            PermissionStatus.Denied,
            PermissionStatus.PermanentlyDenied,
            -> PermissionExplanation(
                permission = state.permission,
                onRequestPermission = onRequestPermission,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

@Composable
private fun PermissionExplanation(
    permission: PermissionStatus,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val copy = permission.toPermissionCopy()
    val title = stringResource(copy.title)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp)
            // TalkBack announces the pane title once when this screen appears or changes
            // state (e.g. the system dialog closes with a denial), without an app-driven
            // live region competing with its own speech.
            .semantics { paneTitle = title },
    ) {
        // Scrollable so the text stays reachable at the largest font sizes; the button
        // stays last in both visual and TalkBack order.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = title,
                color = HighContrastOnSurface,
                fontSize = 30.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(copy.message),
                color = HighContrastOnSurface,
                fontSize = 22.sp,
                lineHeight = 32.sp,
            )
        }
        LargeActionButton(
            label = stringResource(copy.action),
            onClick = if (copy.opensSettings) onOpenSettings else onRequestPermission,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun CameraContent(
    state: MainUiState,
    onCameraEvent: (CameraEvent) -> Unit,
    onRetryCamera: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Leaving composition (e.g. permission lost) unbinds the camera.
        CameraPreview(
            bindAttempt = state.bindAttempt,
            onEvent = onCameraEvent,
            contentDescription = stringResource(R.string.camera_preview_description),
            modifier = Modifier.fillMaxSize(),
        )

        val status = state.camera.toCameraStatusCopy()
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(HighContrastSurface)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(status.message),
                color = HighContrastOnSurface,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold,
                // TalkBack announces camera status changes, e.g. "Rear camera is on."
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (status.canRetry) {
                LargeActionButton(
                    label = stringResource(R.string.action_try_again),
                    onClick = onRetryCamera,
                )
            }
        }
    }
}

@Composable
private fun LargeActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = HighContrastAccent,
            contentColor = HighContrastOnAccent,
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
    ) {
        Text(text = label, fontSize = 22.sp, fontWeight = FontWeight.Bold)
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

private data class CameraStatusCopy(@param:StringRes val message: Int, val canRetry: Boolean = false)

private fun CameraStatus.toCameraStatusCopy(): CameraStatusCopy = when (this) {
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
