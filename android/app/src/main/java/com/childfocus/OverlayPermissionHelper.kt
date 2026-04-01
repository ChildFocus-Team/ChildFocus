package com.childfocus.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

class OverlayPermissionHelper(
    private val activity: ComponentActivity,
    private val onGranted: () -> Unit,
    private val onDenied: () -> Unit
) {

    private val launcher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            // Re-check after user returns from Settings
            if (Settings.canDrawOverlays(activity)) {
                onGranted()
            } else {
                onDenied()
            }
        }

    fun checkAndRequest() {
        if (Settings.canDrawOverlays(activity)) {
            onGranted()
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            launcher.launch(intent)
        }
    }

    companion object {
        fun hasPermission(context: Context): Boolean =
            Settings.canDrawOverlays(context)
    }
}