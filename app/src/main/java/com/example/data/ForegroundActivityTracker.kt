package com.example.data

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * Tracks the resumed Activity so background work (app state screenshots requested by the VM)
 * can capture the window without holding a strong reference to it.
 */
object ForegroundActivityTracker {
    @Volatile
    private var ref: WeakReference<Activity>? = null

    fun onResumed(activity: Activity) {
        ref = WeakReference(activity)
    }

    fun onPaused(activity: Activity) {
        if (ref?.get() === activity) ref = null
    }

    /** The resumed, non-finishing Activity, or null when the app is in the background. */
    fun current(): Activity? = ref?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
}
