package com.maranella.kioskvision.ui.assist

import android.content.pm.ApplicationInfo
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maranella.kioskvision.R
import com.maranella.kioskvision.camera.CameraEvent
import com.maranella.kioskvision.camera.CameraPreview
import com.maranella.kioskvision.permission.MICROPHONE_PERMISSION
import com.maranella.kioskvision.permission.canRequestPermissionAgain
import com.maranella.kioskvision.permission.hasPermission
import com.maranella.kioskvision.permission.openAppSettings
import com.maranella.kioskvision.pipeline.AssistancePhase
import com.maranella.kioskvision.speech.SpeechOutputStatus
import com.maranella.kioskvision.speech.SpokenMessageText
import com.maranella.kioskvision.speech.VoiceMode
import com.maranella.kioskvision.ui.main.CameraStatus
import com.maranella.kioskvision.ui.main.LargeActionButton
import com.maranella.kioskvision.ui.main.MainUiState
import com.maranella.kioskvision.ui.main.PermissionExplanation
import com.maranella.kioskvision.ui.main.PermissionStatus
import com.maranella.kioskvision.voice.VoiceSessionState
import com.maranella.kioskvision.ui.main.toCameraStatusCopy
import com.maranella.kioskvision.ui.theme.HighContrastAccent
import com.maranella.kioskvision.ui.theme.HighContrastOnSurface
import com.maranella.kioskvision.ui.theme.HighContrastSurface

/**
 * Camera preview plus the live-assist controls. Shown only while camera permission is held.
 */
@Composable
fun LiveAssistScreen(
    cameraState: MainUiState,
    onCameraEvent: (CameraEvent) -> Unit,
    onRetryCamera: () -> Unit,
    viewModel: LiveAssistViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onMicPermissionResult(
            granted = granted,
            canAskAgain = activity?.canRequestPermissionAgain(MICROPHONE_PERMISSION) ?: true,
        )
    }

    // Recheck the microphone on every return to the foreground (e.g. from Settings), and
    // stop listening, speech and guidance whenever the screen is not visible.
    LifecycleStartEffect(viewModel) {
        viewModel.onMicPermissionChecked(context.hasPermission(MICROPHONE_PERMISSION))
        viewModel.onForegroundChanged(visible = true)
        onStopOrDispose { viewModel.onForegroundChanged(visible = false) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            bindAttempt = cameraState.bindAttempt,
            onEvent = onCameraEvent,
            contentDescription = stringResource(R.string.camera_preview_description),
            analyzer = viewModel.analyzer,
            analysisExecutor = viewModel.analysisExecutor,
            focusController = viewModel.focusController,
            modifier = Modifier.fillMaxSize(),
        )

        if (state.debugOverlay) {
            val debugFrame by viewModel.debugFrame.collectAsStateWithLifecycle()
            DebugOverlay(frame = debugFrame, modifier = Modifier.fillMaxSize())
        }
        if (isDebuggable(context)) {
            TextButton(
                onClick = { viewModel.setDebugOverlay(!state.debugOverlay) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
                    .background(HighContrastSurface.copy(alpha = 0.6f)),
            ) {
                Text(stringResource(R.string.action_debug_overlay), color = HighContrastOnSurface)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(HighContrastSurface)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (cameraState.camera == CameraStatus.Streaming) {
                AssistPanel(
                    state = state,
                    onVoiceCommand = viewModel::onVoiceCommandPressed,
                    onRepeat = viewModel::repeatLast,
                    onRescan = viewModel::rescan,
                    onStop = viewModel::onStopPressed,
                )
            } else {
                CameraStatusPanel(cameraState.camera, onRetryCamera)
            }
        }

        if (state.showAssistantConsent) {
            AssistantConsentDialog(
                onAccept = { viewModel.onAssistantConsent(enabled = true) },
                onDecline = { viewModel.onAssistantConsent(enabled = false) },
                onDismiss = viewModel::dismissAssistantConsent,
            )
        }

        if (state.showMicExplanation) {
            MicPermissionExplanation(
                permission = state.micPermission,
                onRequest = { micLauncher.launch(MICROPHONE_PERMISSION) },
                onOpenSettings = context::openAppSettings,
                onDismiss = viewModel::dismissMicExplanation,
            )
        }
    }
}

@Composable
private fun AssistPanel(
    state: LiveAssistUiState,
    onVoiceCommand: () -> Unit,
    onRepeat: () -> Unit,
    onRescan: () -> Unit,
    onStop: () -> Unit,
) {
    val resources = LocalResources.current

    Warnings(state)

    if (state.listening) {
        // Deliberately not a live region: TalkBack speaking now would be heard by the microphone.
        Text(stringResource(R.string.status_listening), color = HighContrastAccent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
    // The voice session's own state. Previously nothing in the UI read `state.voice` at all,
    // so a session that failed or was reconnecting was invisible as well as inaudible.
    // A live region here is safe: these fire when the assistant is *not* holding the
    // microphone, so TalkBack reading them aloud cannot be captured as the user speaking.
    when (state.voice.state) {
        VoiceSessionState.Connecting ->
            StatusLine(stringResource(R.string.status_voice_connecting))
        VoiceSessionState.Reconnecting ->
            StatusLine(stringResource(R.string.status_voice_reconnecting), announce = true)
        VoiceSessionState.Error ->
            StatusLine(stringResource(R.string.status_voice_unavailable), announce = true)
        else -> Unit
    }
    state.heardText?.let { StatusLine(stringResource(R.string.label_heard, it)) }
    state.session.targetLabel?.let { StatusLine(stringResource(R.string.label_target, it)) }
    when {
        state.session.phase == AssistancePhase.WaitingForScreenChange -> StatusLine(stringResource(R.string.label_target_reached))
        state.session.direction != null -> StatusLine(
            stringResource(R.string.label_guidance, stringResource(SpokenMessageText.directionText(state.session.direction))),
        )
    }
    if (state.session.phase == AssistancePhase.GuidingFinger) {
        Text(stringResource(R.string.hint_hold_steady), color = HighContrastOnSurface, fontSize = 18.sp)
    }

    // App speech already says these messages. Only when no speech output exists are they put
    // in a live region, so TalkBack reads them instead of both talking at once.
    val speechMissing = state.speechOutput == SpeechOutputStatus.Unavailable
    state.lastMessage?.takeIf { speechMissing }?.let { message ->
        Text(
            text = SpokenMessageText.render(resources, message),
            color = HighContrastOnSurface,
            fontSize = 20.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }

    // One voice control. Which engine answers — the streaming assistant or on-device
    // push-to-talk — is decided in the ViewModel from the user's saved choice, because that is
    // an implementation detail and not a question to put to someone who cannot see the screen.
    // The label states what is happening now, so a screen reader announces the microphone's
    // actual state rather than an action.
    LargeActionButton(
        label = stringResource(
            when {
                // Only while the stream is actually open. The mode outlives the session, and
                // a label that says "microphone streaming" when nothing is listening is the
                // same lie in the other direction.
                state.listening && state.voiceMode == VoiceMode.LiveAssistant ->
                    R.string.action_assistant_on
                state.listening -> R.string.action_cancel_listening
                else -> R.string.action_voice_command
            },
        ),
        onClick = onVoiceCommand,
        modifier = Modifier.heightIn(min = 88.dp),
    )
    // The only way a locked target is given up other than stopping or a confirmed screen
    // transition: the user asking for the screen to be read again. It gets a full-width row
    // because a third button beside the others clips its label at large font sizes.
    LargeActionButton(
        label = stringResource(R.string.action_rescan),
        onClick = onRescan,
        containerColor = HighContrastOnSurface,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LargeActionButton(
            label = stringResource(R.string.action_repeat),
            onClick = onRepeat,
            modifier = Modifier.weight(1f),
            containerColor = HighContrastOnSurface,
        )
        LargeActionButton(
            label = stringResource(R.string.action_stop_guidance),
            onClick = onStop,
            modifier = Modifier.weight(1f),
            containerColor = HighContrastOnSurface,
        )
    }
}

@Composable
private fun Warnings(state: LiveAssistUiState) {
    val warnings = buildList {
        if (state.ocr == EngineStatus.Loading || state.hand == EngineStatus.Loading) add(R.string.status_engines_loading)
        if (state.ocr == EngineStatus.Failed) add(R.string.warning_ocr_unavailable)
        if (state.hand == EngineStatus.Failed) add(R.string.warning_hand_unavailable)
        when (state.speechOutput) {
            SpeechOutputStatus.EnglishFallback -> add(R.string.warning_georgian_voice_missing)
            SpeechOutputStatus.Unavailable -> add(R.string.warning_speech_output_unavailable)
            SpeechOutputStatus.Initializing, SpeechOutputStatus.Georgian -> Unit
        }
        // Deliberately not warning about on-device recognition any more: the app no longer
        // uses it (see LiveAssistViewModel.recognizer), so the warning described a capability
        // whose absence changes nothing for the user.
    }
    warnings.forEach { Text(stringResource(it), color = HighContrastAccent, fontSize = 18.sp) }
}

@Composable
private fun StatusLine(text: String, announce: Boolean = false) {
    Text(
        text = text,
        color = HighContrastOnSurface,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        // Announced only where the line reports something the user must act on and the app is
        // not speaking it: a reconnect, or the assistant being gone. Routine status must stay
        // silent or TalkBack narrates the whole panel over the guidance.
        modifier = if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
    )
}

@Composable
private fun CameraStatusPanel(camera: CameraStatus, onRetryCamera: () -> Unit) {
    val status = camera.toCameraStatusCopy()
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
        LargeActionButton(label = stringResource(R.string.action_try_again), onClick = onRetryCamera)
    }
}

@Composable
private fun MicPermissionExplanation(
    permission: PermissionStatus,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, message, action) = when (permission) {
        PermissionStatus.Denied -> Triple(R.string.mic_denied_title, R.string.mic_denied_message, R.string.action_try_again)
        PermissionStatus.PermanentlyDenied ->
            Triple(R.string.mic_blocked_title, R.string.mic_blocked_message, R.string.action_open_settings)
        else -> Triple(R.string.mic_permission_title, R.string.mic_permission_message, R.string.action_continue)
    }
    PermissionExplanation(
        title = stringResource(title),
        message = stringResource(message),
        actionLabel = stringResource(action),
        onAction = if (permission == PermissionStatus.PermanentlyDenied) onOpenSettings else onRequest,
        secondaryLabel = stringResource(R.string.action_not_now),
        onSecondary = onDismiss,
    )
}

private fun isDebuggable(context: android.content.Context): Boolean =
    context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

/**
 * Asked once, the first time the user tries to talk.
 *
 * The choice is real in both directions: declining leaves a working app, because recognition,
 * camera, OCR and guidance are all on-device. So the wording states the trade rather than
 * steering — what is sent, what is not, and that the app works either way.
 */
@Composable
private fun AssistantConsentDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.assistant_consent_title), fontSize = 24.sp) },
        text = { Text(stringResource(R.string.assistant_consent_body), fontSize = 18.sp) },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.assistant_consent_accept), fontSize = 20.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(stringResource(R.string.assistant_consent_decline), fontSize = 20.sp)
            }
        },
    )
}
