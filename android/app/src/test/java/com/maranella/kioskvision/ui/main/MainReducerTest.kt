package com.maranella.kioskvision.ui.main

import com.maranella.kioskvision.camera.CameraIssue
import org.junit.Assert.assertEquals
import org.junit.Test

class MainReducerTest {

    private fun MainUiState.after(vararg intents: MainIntent) = intents.fold(this, ::reduce)

    private val granted = MainUiState().after(MainIntent.PermissionChecked(granted = true))

    @Test
    fun `first check without permission shows the explanation, not the camera`() {
        val notGranted = MainUiState().after(MainIntent.PermissionChecked(granted = false))
        assertEquals(PermissionStatus.NotGranted, notGranted.permission)
        assertEquals(CameraStatus.Idle, notGranted.camera)
    }

    @Test
    fun `relaunch with permission already granted goes straight to the camera`() {
        assertEquals(PermissionStatus.Granted, granted.permission)
    }

    @Test
    fun `grant from the dialog after a denial switches to the camera`() {
        val state = MainUiState().after(
            MainIntent.PermissionChecked(false),
            MainIntent.PermissionResult(granted = false, canAskAgain = true),
            MainIntent.PermissionResult(granted = true, canAskAgain = false),
        )
        assertEquals(PermissionStatus.Granted, state.permission)
    }

    @Test
    fun `denial maps to denied or permanently denied`() {
        val base = MainUiState().after(MainIntent.PermissionChecked(false))

        assertEquals(
            PermissionStatus.Denied,
            base.after(MainIntent.PermissionResult(granted = false, canAskAgain = true)).permission,
        )
        assertEquals(
            PermissionStatus.PermanentlyDenied,
            base.after(MainIntent.PermissionResult(granted = false, canAskAgain = false)).permission,
        )
    }

    @Test
    fun `foreground recheck keeps denial reason but picks up grant from settings`() {
        val permanentlyDenied = MainUiState().after(
            MainIntent.PermissionChecked(false),
            MainIntent.PermissionResult(granted = false, canAskAgain = false),
        )
        assertEquals(
            PermissionStatus.PermanentlyDenied,
            permanentlyDenied.after(MainIntent.PermissionChecked(false)).permission,
        )
        assertEquals(
            PermissionStatus.Granted,
            permanentlyDenied.after(MainIntent.PermissionChecked(true)).permission,
        )
    }

    @Test
    fun `revocation while backgrounded clears camera state`() {
        val revoked = granted.after(
            MainIntent.CameraStreaming,
            MainIntent.PermissionChecked(granted = false),
        )
        assertEquals(PermissionStatus.NotGranted, revoked.permission)
        assertEquals(CameraStatus.Idle, revoked.camera)
    }

    @Test
    fun `camera events are ignored without permission`() {
        val state = MainUiState().after(
            MainIntent.PermissionChecked(false),
            MainIntent.CameraStreaming,
            MainIntent.CameraFailed(CameraIssue.Failed),
            MainIntent.RetryCamera,
        )
        assertEquals(CameraStatus.Idle, state.camera)
        assertEquals(0, state.bindAttempt)
    }

    @Test
    fun `stream stop after streaming returns to starting but does not hide errors`() {
        assertEquals(
            CameraStatus.Starting,
            granted.after(MainIntent.CameraStreaming, MainIntent.CameraStopped).camera,
        )
        val failed = granted.after(MainIntent.CameraFailed(CameraIssue.Disabled))
        assertEquals(failed, failed.after(MainIntent.CameraStopped))
    }

    @Test
    fun `retry forces a new binding`() {
        val retried = granted.after(MainIntent.CameraFailed(CameraIssue.Failed), MainIntent.RetryCamera)
        assertEquals(CameraStatus.Starting, retried.camera)
        assertEquals(1, retried.bindAttempt)
    }
}
