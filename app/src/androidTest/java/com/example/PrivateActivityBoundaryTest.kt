package com.example

import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*

class PrivateActivityBoundaryTest {
    @Test fun normalLauncherHasNoExternallyLaunchableMutationHelpers() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".qa.preview"))
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        val names = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.name }
        assertEquals(listOf("com.example.MainActivity"), names)
        for (name in listOf("com.example.UxEvidenceActivity", "com.example.MediaDeleteEvidenceActivity")) {
            assertFalse("Special test screens must be private", pm.getActivityInfo(ComponentName(context.packageName, name), 0).exported)
        }
        val pipes = instrumentation.uiAutomation.executeShellCommandRwe(
            "am start -W -n ${context.packageName}/com.example.UxEvidenceActivity"
        )
        pipes[1].close()
        val output = ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() } +
            ParcelFileDescriptor.AutoCloseInputStream(pipes[2]).bufferedReader().use { it.readText() }
        assertTrue("Android must reject external launch of the harmless test-only UI", output.contains("Permission Denial") && output.contains("not exported"))
    }
}
