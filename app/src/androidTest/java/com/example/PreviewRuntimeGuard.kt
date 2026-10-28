package com.example

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** Requires an explicit test-runtime choice and matching Android runtime evidence. */
object PreviewRuntimeGuard {
    fun requireApprovedPreviewRuntime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName.endsWith(".preview")) { "Preview package required" }
        val requested = InstrumentationRegistry.getArguments().getString("rea_runtime")
        val qemu = property("ro.kernel.qemu") == "1" || property("ro.boot.qemu") == "1"
        val waydroid = listOf(Build.HARDWARE, Build.DEVICE, property("ro.product.system.device"))
            .any { it.contains("waydroid", ignoreCase = true) }
        when (requested) {
            "emulator" -> check(qemu && Build.HARDWARE in setOf("ranchu", "goldfish")) {
                "The selected emulator runtime lacks matching Android Emulator evidence"
            }
            "waydroid" -> check(waydroid && !qemu) {
                "The selected Waydroid runtime lacks matching container evidence"
            }
            else -> error("Pass -e rea_runtime emulator or -e rea_runtime waydroid; phones are not authorized")
        }
    }

    private fun property(name: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("getprop $name")
    ).bufferedReader().use { it.readText().trim() }
}
