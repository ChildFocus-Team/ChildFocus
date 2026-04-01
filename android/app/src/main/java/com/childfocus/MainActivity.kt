package com.childfocus

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.childfocus.BlockOverlayService
import com.childfocus.ui.theme.ChildFocusTheme
import com.childfocus.ui.LandingScreen
import com.childfocus.ui.SafetyModeScreen
import com.childfocus.viewmodel.ClassifyState
import com.childfocus.viewmodel.SafetyViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: SafetyViewModel by viewModels()

    // Launcher that re-checks the permission after user returns from Settings
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // No action needed — next Blocked state will trigger the overlay automatically
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChildFocusTheme {
                val safetyOn by viewModel.safetyModeOn.collectAsState()
                val classifyState by viewModel.classifyState.collectAsState()

                // React to every state change and show/hide the overlay
                LaunchedEffect(classifyState) {
                    when (val state = classifyState) {
                        is ClassifyState.Blocked -> {
                            if (Settings.canDrawOverlays(this@MainActivity)) {
                                BlockOverlayService.show(
                                    context = this@MainActivity,
                                    videoId = state.videoId,
                                    label   = state.label,
                                    score   = state.score
                                )
                            } else {
                                // No permission yet — send user to Settings
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                                overlayPermissionLauncher.launch(intent)
                            }
                        }
                        else -> BlockOverlayService.hide(this@MainActivity)
                    }
                }

                if (safetyOn) {
                    SafetyModeScreen(
                        classifyState = classifyState,
                        onTurnOff     = { viewModel.toggleSafetyMode() },
                        onDismissBlock = { viewModel.dismissBlock() }
                    )
                } else {
                    LandingScreen(
                        onTurnOn = {
                            // 1. Check overlay permission first
                            if (!Settings.canDrawOverlays(this@MainActivity)) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                                overlayPermissionLauncher.launch(intent)
                            }
                            // 2. Check accessibility service
                            if (!isAccessibilityServiceEnabled()) {
                                openAccessibilitySettings()
                            }
                            viewModel.toggleSafetyMode()
                        }
                    )
                }
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${packageName}.service.ChildFocusAccessibilityService"
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.split(":").any { it.equals(service, ignoreCase = true) }
        } catch (e: Exception) {
            false
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}