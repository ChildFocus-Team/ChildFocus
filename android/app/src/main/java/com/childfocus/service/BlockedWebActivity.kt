package com.childfocus.service

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.view.View
import android.content.Intent
import com.childfocus.R

/**
 * Full-screen activity shown when the user navigates to a blocked website.
 * Displays the blocked URL and offers to go back to the home screen.
 */
class BlockedWebActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked_web)

        val blockedUrl = intent.getStringExtra(WebBlockerAccessibilityService.EXTRA_BLOCKED_URL)
            ?: "this site"

        findViewById<TextView>(R.id.tv_blocked_url)?.text = blockedUrl

        // Go Home button
        findViewById<Button>(R.id.btn_go_home)?.setOnClickListener {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
            finish()
        }

        // Back button
        findViewById<Button>(R.id.btn_back)?.setOnClickListener {
            finish()
        }
    }

    /** Prevent back press from going back to the blocked page */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }
}