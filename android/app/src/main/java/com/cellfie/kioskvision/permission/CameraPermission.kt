package com.cellfie.kioskvision.permission

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

const val CAMERA_PERMISSION = Manifest.permission.CAMERA

fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED

/**
 * Only meaningful right after a denial: `true` means the system will show the dialog again,
 * `false` means it was denied permanently (or the dialog is otherwise suppressed).
 */
fun Activity.canRequestCameraPermissionAgain(): Boolean =
    ActivityCompat.shouldShowRequestPermissionRationale(this, CAMERA_PERMISSION)

/** Opens this app's system settings page, falling back to the main Settings screen. */
fun Context.openAppSettings() {
    val appDetails = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    )
    try {
        startActivity(appDetails)
    } catch (e: ActivityNotFoundException) {
        Log.w("CameraPermission", "App details settings unavailable", e)
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Log.e("CameraPermission", "Settings app unavailable", e)
        }
    }
}
