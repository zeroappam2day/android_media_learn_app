package com.example

import android.app.Activity
import android.os.Build
import android.view.inputmethod.InputMethodManager
import androidx.test.platform.app.InstrumentationRegistry

/** Dismisses the IME from the observed focused app window without inspecting any field values. */
internal fun dismissQaKeyboard(activity: Activity) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val manager = activity.getSystemService(InputMethodManager::class.java)
        val roots = if (Build.VERSION.SDK_INT >= 29) android.view.inspector.WindowInspector.getGlobalWindowViews()
            else listOf(activity.window.decorView)
        roots.filter { it.hasWindowFocus() }.forEach { manager.hideSoftInputFromWindow(it.windowToken, 0) }
    }
}
