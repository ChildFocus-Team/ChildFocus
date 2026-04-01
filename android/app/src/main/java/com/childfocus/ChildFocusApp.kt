package com.childfocus

import android.app.Application
import com.childfocus.service.WebBlockerManager

/**
 * Application class — ensures WebBlockerManager is initialised with a Context
 * before any component (Service, Activity, ViewModel) tries to use it.
 *
 * Register this in AndroidManifest.xml:
 *   <application android:name=".ChildFocusApp" ...>
 */
class ChildFocusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WebBlockerManager.init(this)
    }
}