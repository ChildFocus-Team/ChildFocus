package com.childfocus.service

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.addCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen blocked site screen.
 *
 * Buttons:
 *  • "Go Back"        → finish() — returns user to browser which now shows about:blank
 *  • "Go Home"        → sends user to the Android home screen
 *
 * Back gesture is also intercepted and treated as "Go Back" (finish()).
 *
 * Uses Compose so there is no XML layout dependency — buttons are always wired.
 */
class BlockedWebActivity : ComponentActivity() {

    companion object {
        const val EXTRA_BLOCKED_URL = "blocked_url"
    }

    private var blockedUrl = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        blockedUrl.value = intent.getStringExtra(EXTRA_BLOCKED_URL) ?: ""

        // Intercept back gesture — finish() so browser shows about:blank, not blocked site
        onBackPressedDispatcher.addCallback(this) { finish() }

        setContent {
            val url by blockedUrl
            BlockedScreen(
                blockedUrl = url,
                onGoBack   = { finish() },
                onGoHome   = { goHome() }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        blockedUrl.value = intent.getStringExtra(EXTRA_BLOCKED_URL) ?: blockedUrl.value
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}

// ─── UI ───────────────────────────────────────────────────────────────────────

private val NavyDark = Color(0xFF0D1B2A)
private val NavyMid  = Color(0xFF1B2D3E)
private val RedAlert = Color(0xFFE63946)
private val Teal     = Color(0xFF00C9A7)
private val OffWhite = Color(0xFFF0F4F8)
private val Muted    = Color(0xFF8BA3B8)

@Composable
private fun BlockedScreen(
    blockedUrl: String,
    onGoBack:   () -> Unit,
    onGoHome:   () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(NavyDark, Color(0xFF1a0a0a)))
            ),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape  = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = NavyMid),
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp)
        ) {
            Column(
                modifier            = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── Icon ─────────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(RedAlert.copy(alpha = 0.15f), RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = null,
                        tint     = RedAlert,
                        modifier = Modifier.size(44.dp)
                    )
                }

                // ── Title ─────────────────────────────────────────────────
                Text(
                    text       = "Site Blocked",
                    fontSize   = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color      = OffWhite
                )

                Text(
                    text      = "This website has been blocked by ChildFocus parental controls.",
                    fontSize  = 14.sp,
                    color     = Muted,
                    textAlign = TextAlign.Center
                )

                // ── Blocked URL chip ──────────────────────────────────────
                if (blockedUrl.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = RedAlert.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text     = WebBlockerManager.normalise(blockedUrl),
                            fontSize = 13.sp,
                            color    = RedAlert,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Divider(color = Muted.copy(alpha = 0.15f))
                Spacer(Modifier.height(4.dp))

                // ── Buttons ───────────────────────────────────────────────
                Row(
                    modifier            = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Go Back — finish() reveals about:blank in browser
                    OutlinedButton(
                        onClick  = onGoBack,
                        shape    = RoundedCornerShape(12.dp),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = Teal),
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Go Back", fontWeight = FontWeight.SemiBold)
                    }

                    // Go Home — exits to Android launcher
                    Button(
                        onClick  = onGoHome,
                        shape    = RoundedCornerShape(12.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = Teal),
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) {
                        Icon(Icons.Default.Home, contentDescription = null, tint = NavyDark, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Go Home", color = NavyDark, fontWeight = FontWeight.SemiBold)
                    }
                }

                Text(
                    text     = "Contact a parent to unblock this site.",
                    fontSize = 11.sp,
                    color    = Muted.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}