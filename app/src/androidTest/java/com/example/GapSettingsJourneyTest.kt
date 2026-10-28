package com.example

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import com.example.data.*
import com.example.ui.VideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * UNEXECUTED DRAFT. Isolated .qa.preview emulator only; no production MainActivity/default VM.
 * Uses actual production MainScreen Settings hosted in the existing debug ComponentActivity.
 * No real credential is entered or read; credentialPreferences is an empty unique QA file.
 * No semantics-tree dumps or screenshots. Only named non-secret controls are inspected.
 * All PC requests terminate at the in-emulator loopback fixture. Cloud auth/inference excluded.
 * Strict oracles reject stale model/connection responses and lost same-host model caches.
 * T194/T195 processing faults belong to StateFaultCompletionTest; no inference claim here.
 */
class GapSettingsJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<InventoryRecreationTestActivity>()
    private val context get() = compose.activity.applicationContext
    private lateinit var repo: VideoRepository
    private lateinit var vm: VideoViewModel
    private lateinit var server: Loopback
    private val extraServers = mutableListOf<Loopback>()
    private val store = ViewModelStore()
    private lateinit var credentialFile: String
    private var originalSettings: AppSettings? = null
    private var originalProvider: String? = null
    private var originalPrompt: String? = null
    private var originalTemp = 0f
    private var originalAuto = false
    private var originalModels: Set<String>? = null

    @Before fun setup() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview"))
        check(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("rea_runtime") == "emulator")
        check(io { VideoDatabase.getDatabase(context).videoItemDao().getAllVideosDirect().isEmpty() }) { "Empty isolated QA index required" }
        credentialFile = "qa71_settings_empty_${System.nanoTime()}"
        // Explicit empty injected store: constructing this VM cannot open secure_app_prefs.
        repo = VideoRepository(context, credentialPreferences = context.getSharedPreferences(credentialFile, 0))
        originalSettings = io { repo.settingsDao.getSettingsDirect() }
        originalProvider = repo.prefs.getString("llm_provider", null)
        originalPrompt = repo.prefs.getString("llm_system_prompt", null)
        originalTemp = repo.prefs.getFloat("llm_temperature", 0f)
        originalAuto = repo.prefs.getBoolean("auto_ingest_enabled", false)
        originalModels = repo.prefs.getStringSet("llm_available_models", null)?.toSet()
        server = Loopback()
        io { repo.settingsDao.insertSettings(AppSettings(ollamaEnabled = false,
            ollamaEndpointUrl = server.url, ollamaModelName = "", requestTimeoutSeconds = 30)) }
        repo.prefs.edit().putString("llm_provider", "ollama_local")
            .putString("llm_system_prompt", "QA saved prompt")
            .putFloat("llm_temperature", 0.25f).putBoolean("auto_ingest_enabled", false).commit()
        compose.runOnUiThread {
            vm = VideoViewModel(context as Application, repo)
            store.put("settings-qa", vm)
            compose.activity.setContent { MaterialTheme { MainScreen(vm, false, {}) } }
        }
        compose.waitUntil(10000) { vm.settings.value.ollamaEndpointUrl == server.url }
        open()
    }

    @After fun cleanup() {
        if (::vm.isInitialized) compose.runOnUiThread { store.clear() }
        if (::repo.isInitialized) {
            if (originalSettings != null) io { repo.settingsDao.insertSettings(requireNotNull(originalSettings)) }
            else io { VideoDatabase.getDatabase(context).openHelper.writableDatabase.execSQL("DELETE FROM app_settings WHERE id = 1") }
            repo.prefs.edit().apply {
                if (originalProvider == null) remove("llm_provider") else putString("llm_provider", originalProvider)
                if (originalPrompt == null) remove("llm_system_prompt") else putString("llm_system_prompt", originalPrompt)
                putFloat("llm_temperature", originalTemp).putBoolean("auto_ingest_enabled", originalAuto)
                if (originalModels == null) remove("llm_available_models") else putStringSet("llm_available_models", originalModels)
            }.commit()
        }
        extraServers.forEach { it.close() }
        if (::server.isInitialized) server.close()
        if (::credentialFile.isInitialized) context.deleteSharedPreferences(credentialFile)
    }


    @Test fun t076_fullScrollStandardMinimizeRestoreCloseNeverCommitsAnySetting() {
        val before = snapshot()
        compose.onNodeWithContentDescription("Full Window").performClick()
        field("Prompt").performScrollTo().assertExists()
        field("Host").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Standard Window").performClick()
        compose.onNodeWithContentDescription("Minimize").performClick()
        assertEquals(before, snapshot())
        compose.onNodeWithContentDescription("Restore settings").performClick()
        compose.onNodeWithText("AI setup", substring = false).assertExists()
        field("Prompt").performScrollTo().assert(editable("QA saved prompt"))
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        assertEquals(before, snapshot())
    }

    @Test fun t102_backAndOutsideDismissOnlyTheirDropdownWithoutChangingDraftOrSavedSelection() {
        sync(); await { vm.availableModels.value == listOf("qa-model-a", "qa-model-b") }
        chooseModel("qa-model-a", "qa-model-b"); save(); await { settings().ollamaModelName == "qa-model-b" }; open()
        val before = snapshot()
        compose.onNodeWithText("PC", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Device", substring = false).assertExists()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        await { compose.onAllNodesWithText("Device", substring = false).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("PC", substring = false).assertExists()
        modelField("qa-model-b").performScrollTo()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        val titleBounds = compose.onNodeWithText("Ollama Advanced Connection Settings", substring = false)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Observed Settings title must be a visible outside-popup touch target", titleBounds.width > 0f && titleBounds.height > 0f)
        val composeOrigin = IntArray(2)
        instrumentation.runOnMainSync {
            fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
                if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
                else emptyList()
            val focusedComposeRoot = android.view.inspector.WindowInspector.getGlobalWindowViews()
                .filter { it.hasWindowFocus() }
                .flatMap { descendants(it) }
                .single { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }
            assertTrue("Title must lie within observed focused Compose root", titleBounds.left >= 0f && titleBounds.top >= 0f &&
                titleBounds.right <= focusedComposeRoot.width && titleBounds.bottom <= focusedComposeRoot.height)
            focusedComposeRoot.getLocationOnScreen(composeOrigin)
        }
        val touchX = composeOrigin[0] + titleBounds.center.x
        val touchY = composeOrigin[1] + titleBounds.center.y
        modelField("qa-model-b").performClick()
        compose.onNodeWithText("qa-model-a", substring = false).assertExists()
        val downTime = android.os.SystemClock.uptimeMillis()
        listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
            val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action,
                touchX, touchY, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        await { compose.onAllNodesWithText("qa-model-a", substring = false).fetchSemanticsNodes().isEmpty() }
        modelField("qa-model-b").assertExists()
        assertEquals(before, snapshot())
    }

    @Test fun t111_unsavedPromptResetThenCloseRestoresExactSavedPrompt() {
        val before = snapshot()
        replace("Prompt", "QA unsaved prompt")
        compose.onNodeWithContentDescription("Reset prompt").performScrollTo().performClick()
        val reset = field("Prompt").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertTrue(reset.isNotBlank()); assertNotEquals("QA unsaved prompt", reset)
        compose.onNodeWithContentDescription("Close").performClick(); open()
        field("Prompt").performScrollTo().assert(editable("QA saved prompt"))
        assertEquals(before, snapshot())
    }

    @Test fun t167_promptDraftSurvivesMinimizeRestoreButCloseDiscardsItWithoutSaving() {
        val before = snapshot(); val draft = "QA unsaved Prompt مرحبا 😀"
        replace("Prompt", draft)
        compose.onNodeWithContentDescription("Minimize").performClick()
        assertEquals(before, snapshot())
        compose.onNodeWithContentDescription("Restore settings").performClick()
        field("Prompt").performScrollTo().assert(editable(draft))
        assertEquals(before, snapshot())
        compose.onNodeWithContentDescription("Close").performClick(); open()
        field("Prompt").performScrollTo().assert(editable("QA saved prompt"))
        assertEquals(before, snapshot())
    }

    @Test fun t176_unsavedDeviceCloseReopensSavedPcAndItsExactSettings() {
        val before = snapshot()
        provider("Device")
        await {
            runCatching {
                compose.onNodeWithText("Keyword fallback runs on this device. No on-device language model is installed.")
                    .performScrollTo().assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
        compose.onNodeWithContentDescription("Close").performClick(); open()
        compose.onNodeWithText("PC", substring = false).assertExists()
        field("Host").performScrollTo().assertTextContains(server.url)
        assertEquals(before, snapshot())
    }

    @Test fun t192_sameHostFailureKeepsAThenRestoredServiceBIsSelectedSavedAndReopened() {
        server.mode = Reply(models = listOf("qa-model-a")); sync()
        await { vm.availableModels.value == listOf("qa-model-a") }
        save(); await { settings().ollamaModelName == "qa-model-a" }; open()
        val before = server.completed.get(); server.mode = Reply(status = 503)
        sync(); await { server.completed.get() > before }
        assertStable { vm.availableModels.value == listOf("qa-model-a") }
        modelField("qa-model-a").assertExists()
        server.mode = Reply(models = listOf("qa-model-b")); sync()
        await { vm.availableModels.value == listOf("qa-model-b") }
        val current = if (compose.onAllNodes(hasText("qa-model-a", substring = false) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) "qa-model-a" else "qa-model-b"
        chooseModel(current, "qa-model-b"); save()
        await { settings().ollamaModelName == "qa-model-b" }; open()
        modelField("qa-model-b").assertExists(); assertEquals(server.url, settings().ollamaEndpointUrl)
    }

    @Test fun t193_pcDeviceNamespacesRemainSeparateAndExplicitResyncRestoresPcChoices() {
        sync(); await { vm.availableModels.value == listOf("qa-model-a", "qa-model-b") }
        chooseModel("qa-model-a", "qa-model-b"); save(); await { settings().ollamaModelName == "qa-model-b" }; open()
        provider("Device")
        compose.onNodeWithText("qa-model-b", substring = false).assertDoesNotExist()
        sync(); await { vm.availableModels.value == listOf("keyword-fallback") }
        modelField("keyword-fallback").assertExists(); save()
        await { repo.prefs.getString("llm_provider", null) == "local" && settings().ollamaModelName == "keyword-fallback" }; open()
        provider("PC")
        compose.onNodeWithText("keyword-fallback", substring = false).assertDoesNotExist()
        modelField("Tap sync to fetch models").performScrollTo().performClick()
        compose.onNodeWithText("No model", substring = false).assertExists()
        compose.onNodeWithText("qa-model-a", substring = false).assertDoesNotExist()
        compose.onNodeWithText("No model", substring = false).performClick()
        sync(); await { vm.availableModels.value == listOf("qa-model-a", "qa-model-b") }
        chooseModel("qa-model-a", "qa-model-b"); save()
        await { repo.prefs.getString("llm_provider", null) == "ollama_local" && settings().ollamaModelName == "qa-model-b" }; open()
        modelField("qa-model-b").assertExists()
    }

    private fun field(label: String) = if (label == "Prompt") compose.onNodeWithTag("settings_prompt") else compose.onNode(hasText(label) and hasSetTextAction())
    private fun replace(label: String, value: String) { field(label).performScrollTo().performTextReplacement(value); dismissQaKeyboard(compose.activity); compose.waitForIdle() }
    private fun editable(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString(value))
    private fun snapshot() = Triple(settings(), listOf(repo.prefs.getString("llm_provider", null), repo.prefs.getString("llm_system_prompt", null), repo.prefs.getFloat("llm_temperature", -1f).toString()), repo.prefs.getStringSet("llm_available_models", null)?.toSet())
    private fun provider(name: String) {
        val current = when { compose.onAllNodesWithText("PC", substring = false).fetchSemanticsNodes().isNotEmpty() -> "PC"; compose.onAllNodesWithText("Device", substring = false).fetchSemanticsNodes().isNotEmpty() -> "Device"; else -> error("Direct Cloud account is outside this task") }
        compose.onNodeWithText(current, substring = false).performScrollTo().performClick(); compose.onAllNodesWithText(name, substring = false).onLast().performClick()
    }

    private fun open() {
        compose.onNode(hasContentDescription("Settings") and hasClickAction()).performClick()
        await { compose.onAllNodesWithText("AI setup").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun replaceHost(value: String) {
        compose.onNode(hasText("Host") and hasSetTextAction()).performScrollTo().performTextReplacement(value)
        dismissQaKeyboard(compose.activity)
        compose.waitForIdle()
    }
    private fun modelField(text: String) = compose.onNode(hasText(text, substring = false) and hasClickAction())
    private fun chooseModel(current: String, choice: String) {
        modelField(current).performScrollTo().performClick()
        compose.onAllNodesWithText(choice, substring = false).onLast().performClick()
        modelField(choice).assertExists()
    }
    private fun sync() {
        dismissQaKeyboard(compose.activity)
        compose.onNode(hasContentDescription("Sync models tag list") and hasClickAction()).performScrollTo().assertIsDisplayed().performClick()
    }
    private fun testConnection() {
        dismissQaKeyboard(compose.activity)
        compose.onNode(hasContentDescription("Test connection") and hasClickAction()).performScrollTo().assertIsDisplayed().performClick()
    }
    private fun save() = compose.onNode(hasContentDescription("Save settings") and hasClickAction()).performScrollTo().performClick()
    private fun settings() = io { requireNotNull(repo.settingsDao.getSettingsDirect()) }
    private fun await(predicate: () -> Boolean) = compose.waitUntil(10000, predicate)
    private fun assertStable(predicate: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 1500
        while (android.os.SystemClock.elapsedRealtime() < end) {
            compose.waitForIdle()
            assertTrue("Obsolete/failing request changed current endpoint state", predicate())
            android.os.SystemClock.sleep(40)
        }
    }
    private fun extra(reply: Reply) = Loopback().also { it.mode = reply; extraServers += it }
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private data class Reply(val status: Int = 200, val models: List<String> = listOf("qa-model-a", "qa-model-b"),
        val gate: java.util.concurrent.CountDownLatch? = null)
    /** Loopback only, independent concurrent replies, bounded holds, never proxy upstream. */
    private class Loopback : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val sockets = CopyOnWriteArrayList<Socket>()
        val accepted = AtomicInteger()
        val completed = AtomicInteger()
        val paths = CopyOnWriteArrayList<String>()
        val url = "http://127.0.0.1:${listener.localPort}"
        @Volatile var mode = Reply()
        @Volatile private var closed = false
        init {
            Thread({
                while (!closed) {
                    val socket = try { listener.accept() } catch (_: Exception) { break }
                    sockets += socket
                    Thread({
                        try {
                            socket.use { s ->
                                s.soTimeout = 4000
                                val reader = s.getInputStream().bufferedReader(Charsets.US_ASCII)
                                val request = reader.readLine() ?: return@use
                                while (!reader.readLine().isNullOrEmpty()) { /* Never retain headers or credential values. */ }
                                val path = request.split(' ').getOrNull(1).orEmpty()
                                val reply = mode
                                paths += path
                                accepted.incrementAndGet()
                                reply.gate?.await(3500, java.util.concurrent.TimeUnit.MILLISECONDS)
                                val json = org.json.JSONObject()
                                    .put("models", org.json.JSONArray().apply { reply.models.forEach { put(org.json.JSONObject().put("name", it)) } })
                                    .put("data", org.json.JSONArray().apply { reply.models.forEach { put(org.json.JSONObject().put("id", it)) } })
                                    .put("status", "ok")
                                val body = json.toString().toByteArray(Charsets.UTF_8)
                                val header = "HTTP/1.1 ${reply.status} QA\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                s.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
                            }
                        } catch (_: Exception) { /* Closing an obsolete test connection is allowed. */ }
                        finally { completed.incrementAndGet(); sockets -= socket }
                    }, "qa-settings-fault-response").apply { isDaemon = true; start() }
                }
            }, "qa-settings-fault-listener").apply { isDaemon = true; start() }
        }
        override fun close() {
            closed = true
            runCatching { listener.close() }
            sockets.forEach { runCatching { it.close() } }
            mode.gate?.countDown()
        }
    }
}
