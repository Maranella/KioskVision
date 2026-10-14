package com.cellfie.kioskvision.ui.main

import androidx.compose.runtime.Immutable
import com.cellfie.kioskvision.camera.CameraIssue

enum class PermissionStatus {
    /** Not checked yet; the first check happens when the screen starts. */
    Unknown,

    /** Not granted and not yet answered in this session: explain before asking. */
    NotGranted,

    /** Denied, but the system will still show the permission dialog again. */
    Denied,

    /** Denied and the system will no longer show the dialog; only Settings can grant it. */
    PermanentlyDenied,

    Granted,
}

sealed interface CameraStatus {
    data object Idle : CameraStatus
    data object Starting : CameraStatus
    data object Streaming : CameraStatus
    data class Unavailable(val issue: CameraIssue) : CameraStatus
}

@Immutable
data class MainUiState(
    val permission: PermissionStatus = PermissionStatus.Unknown,
    val camera: CameraStatus = CameraStatus.Idle,
    /** Incremented to force a fresh camera binding after an unrecoverable error. */
    val bindAttempt: Int = 0,
)

sealed interface MainIntent {
    /** The screen came to the foreground and re-read the permission state. */
    data class PermissionChecked(val granted: Boolean) : MainIntent

    /** [canAskAgain] mirrors `shouldShowRequestPermissionRationale` after the dialog closes. */
    data class PermissionResult(val granted: Boolean, val canAskAgain: Boolean) : MainIntent

    data object CameraStarting : MainIntent
    data object CameraStreaming : MainIntent
    data object CameraStopped : MainIntent
    data class CameraFailed(val issue: CameraIssue) : MainIntent
    data object RetryCamera : MainIntent
}

fun reduce(state: MainUiState, intent: MainIntent): MainUiState = when (intent) {
    is MainIntent.PermissionChecked -> when {
        intent.granted -> state.copy(permission = PermissionStatus.Granted)
        // Keep an earlier denial so the matching recovery action stays visible.
        state.permission == PermissionStatus.Denied ||
            state.permission == PermissionStatus.PermanentlyDenied -> state
        // Covers "revoked while backgrounded": drop any stale camera status.
        else -> state.copy(permission = PermissionStatus.NotGranted, camera = CameraStatus.Idle)
    }

    is MainIntent.PermissionResult -> state.copy(
        permission = when {
            intent.granted -> PermissionStatus.Granted
            intent.canAskAgain -> PermissionStatus.Denied
            // The dialog was suppressed or the user chose "Don't ask again".
            else -> PermissionStatus.PermanentlyDenied
        },
        camera = if (intent.granted) state.camera else CameraStatus.Idle,
    )

    // Camera callbacks can arrive late, after a permission change; ignore them then.
    MainIntent.CameraStarting -> state.withCamera(CameraStatus.Starting)
    MainIntent.CameraStreaming -> state.withCamera(CameraStatus.Streaming)
    // A stream that stops on its own (e.g. app backgrounded) is expected to restart.
    MainIntent.CameraStopped ->
        if (state.camera == CameraStatus.Streaming) state.withCamera(CameraStatus.Starting) else state
    is MainIntent.CameraFailed -> state.withCamera(CameraStatus.Unavailable(intent.issue))

    MainIntent.RetryCamera -> if (state.permission == PermissionStatus.Granted) {
        state.copy(camera = CameraStatus.Starting, bindAttempt = state.bindAttempt + 1)
    } else {
        state
    }
}

private fun MainUiState.withCamera(camera: CameraStatus): MainUiState =
    if (permission == PermissionStatus.Granted) copy(camera = camera) else this
