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
 * Strict oracles reject silent timeout coercion and stale host/provider model selections.
 */
class SettingsCompletionTest {
    @get:Rule val compose = createAndroidComposeRule<InventoryRecreationTestActivity>()
    private val context get() = compose.activity.applicationContext
    private lateinit var repo: VideoRepository
    private lateinit var vm: VideoViewModel
    private lateinit var server: Loopback
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
        credentialFile = "qa_settings_empty_${System.nanoTime()}"
        // Explicit empty injected store: constructing this VM cannot open secure_app_prefs.
        repo = VideoRepository(context, credentialPreferences = context.getSharedPreferences(credentialFile, 0))
        originalSettings = io { repo.settingsDao.getSettingsDirect() }
        originalProvider = repo.prefs.getString("llm_provider", null)
        originalPrompt = repo.prefs.getString("llm_system_prompt", null)
        originalTemp = repo.prefs.getFloat("llm_temperature", 0f)
        originalAuto = repo.prefs.getBoolean("auto_ingest_enabled", false)
        originalModels = repo.prefs.getStringSet("llm_available_models", null)?.toSet()
        server = Loopback()
        io { repo.settingsDao.insertSettings(AppSettings(ollamaEnabled = true,
            ollamaEndpointUrl = server.url, ollamaModelName = "qa-model-a", requestTimeoutSeconds = 30)) }
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
            originalSettings?.let { saved -> io { repo.settingsDao.insertSettings(saved) } }
            repo.prefs.edit().apply {
                if (originalProvider == null) remove("llm_provider") else putString("llm_provider", originalProvider)
                if (originalPrompt == null) remove("llm_system_prompt") else putString("llm_system_prompt", originalPrompt)
                putFloat("llm_temperature", originalTemp).putBoolean("auto_ingest_enabled", originalAuto)
                if (originalModels == null) remove("llm_available_models") else putStringSet("llm_available_models", originalModels)
            }.commit()
        }
        if (::server.isInitialized) server.close()
        if (::credentialFile.isInitialized) context.deleteSharedPreferences(credentialFile)
        // If originalSettings was null, isolated QA baseline remains; never touch user databases.
    }

    @Test fun providerFieldsExplainDeviceAndCloudWithoutSendingCloudRequests() {
        compose.waitUntil(10000) { vm.availableModels.value.contains("qa-model-a") }
        val initialDiscoveryRequests = server.requests.get()
        // T094–T098. Gemini has been retired; never require a removed provider to exist.
        field("Host").assertExists()
        provider("Cloud")
        field("Host").assertTextContains("https://ollama.com")
        // Presence-only assertion; never fetch API-key values or full semantics.
        compose.onNodeWithText("API key", useUnmergedTree = true).assertExists()
        provider("Device")
        field("Host").assertDoesNotExist()
        compose.waitUntil(10000) {
            runCatching {
                compose.onNodeWithText("Keyword fallback runs on this device. No on-device language model is installed.")
                    .performScrollTo().assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
        assertEquals("Changing provider drafts must not save", "ollama_local", repo.prefs.getString("llm_provider", null))
        assertEquals("Changing provider drafts must not send requests", initialDiscoveryRequests, server.requests.get())
    }

    @Test fun loopbackConnectionModelsAndSameProviderHostFreshness() {
        // T099–T103, T118, T192–T193, T224. Local Ollama contract only, no inference claim.
        compose.onNodeWithContentDescription("Test connection").performScrollTo().performClick()
        compose.waitUntil(10000) { vm.connectionSuccess.value == true && vm.availableModels.value.contains("qa-model-a") }
        compose.onNodeWithText("Connected").assertExists()
        compose.onNodeWithText("qa-model-a").performScrollTo().performClick()
        compose.onAllNodesWithText("qa-model-b").onLast().performClick()
        save()
        compose.waitUntil(10000) { settings().ollamaModelName == "qa-model-b" }
        open()
        compose.onNodeWithText("qa-model-b").assertExists()
        // Change only the host; a model discovered for the old host is no evidence for this one.
        replace("Host", server.url + "/different-instance")
        compose.onNodeWithText("Connected").assertDoesNotExist()
        compose.onNodeWithText("qa-model-b").assertDoesNotExist()
        // Deliberately strict: stale displayed model selection is a product failure, not acceptance.
    }

    @Test fun switchingToDeviceDoesNotRetainPcModelSelection() {
        // T103, T118, T193.
        compose.onNodeWithContentDescription("Sync models tag list").performScrollTo().performClick()
        compose.waitUntil(10000) { vm.availableModels.value.contains("qa-model-a") }
        provider("Device")
        compose.onNodeWithText("qa-model-a").assertDoesNotExist()
        compose.onNodeWithContentDescription("Sync models tag list").performScrollTo().performClick()
        compose.waitUntil(10000) { vm.availableModels.value == listOf("keyword-fallback") }
        save()
        compose.waitUntil(10000) { repo.prefs.getString("llm_provider", null) == "local" }
        assertEquals("A PC model must never be saved as Device's selected model", "keyword-fallback", settings().ollamaModelName)
    }

    @Test fun timeoutBoundaryValuesSaveExactly() {
        // T104–T105.
        for (seconds in listOf("5", "300")) {
            replace("Timeout", seconds)
            save()
            compose.waitUntil(10000) { settings().requestTimeoutSeconds == seconds.toInt() }
            open()
            field("Timeout").performScrollTo().assertTextContains(seconds)
        }
    }

    @Test fun invalidTimeoutCannotSilentlyOverwriteSavedValue() {
        // T106–T109. Independent contract: reject invalid draft and keep the saved 30 seconds.
        for (invalid in listOf("4", "301", "abc", "", "2147483648")) {
            replace("Timeout", invalid)
            val saveButton = compose.onNode(hasContentDescription("Save settings") and hasClickAction())
            saveButton.performScrollTo()
            val enabled = saveButton.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled).not()
            if (enabled) saveButton.performClick()
            compose.waitForIdle()
            assertEquals("Invalid Timeout '$invalid' was silently saved/coerced", 30, settings().requestTimeoutSeconds)
            field("Timeout").assertExists()
            // User must remain able to correct the draft; field retains the invalid input.
            field("Timeout").performScrollTo().assertTextContains(invalid)
        }
    }

    @Test fun temperatureEndpointsPersistExactly() {
        // T110. Only inspect the slider, never arbitrary editable fields.
        val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        for (temperature in listOf(0f, 1f)) {
            slider.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(temperature) }
            save()
            compose.waitUntil(10000) { repo.prefs.getFloat("llm_temperature", -1f) == temperature }
            open()
        }
    }

    @Test fun promptLongDraftSavesExactlyAndCloseDiscardsUnsavedChanges() {
        // T111–T112; no arbitrary maximum: long text must be preserved or explicitly rejected.
        val prompt = "QA deterministic instruction. ".repeat(256)
        replace("Prompt", prompt)
        save()
        compose.waitUntil(10000) { repo.prefs.getString("llm_system_prompt", null) == prompt }
        open()
        field("Prompt").performScrollTo().assertTextContains(prompt)
        replace("Prompt", "QA unsaved prompt")
        compose.onNodeWithContentDescription("Close").performClick()
        open()
        field("Prompt").performScrollTo().assertTextContains(prompt)
    }

    @Test fun blankPromptSavesExactlyAndResetProvidesUsableDefault() {
        // T112 explicitly permits a blank prompt: save/reopen must preserve exactly that value.
        replace("Prompt", "")
        save()
        compose.waitUntil(10000) { repo.prefs.getString("llm_system_prompt", null) == "" }
        open()
        field("Prompt").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithContentDescription("Reset prompt").performScrollTo().performClick()
        save()
        compose.waitUntil(10000) { repo.prefs.getString("llm_system_prompt", "").orEmpty().isNotBlank() }
        val reset = repo.prefs.getString("llm_system_prompt", "").orEmpty()
        assertTrue("Reset must supply meaningful instructions", reset.isNotBlank())
        open()
        field("Prompt").performScrollTo().assertTextContains(reset)
    }

    @Test fun minimizeDoesNotCommitUnsavedHostOrTimeout() {
        // T094/T224 window lifecycle. Retained or discarded draft must not silently save.
        replace("Host", server.url + "/unsaved")
        replace("Timeout", "77")
        compose.onNodeWithContentDescription("Minimize").performClick()
        assertEquals(server.url, settings().ollamaEndpointUrl)
        assertEquals(30, settings().requestTimeoutSeconds)
        open()
        assertEquals(server.url, settings().ollamaEndpointUrl)
        assertEquals(30, settings().requestTimeoutSeconds)
    }

    // The production prompt field requires testTag("settings_prompt") and label "System prompt".
    // Exact empty editable text is inspected only for this non-secret prompt field.
    private fun open() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("AI setup").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun field(label: String) = if (label == "Prompt") compose.onNodeWithTag("settings_prompt") else compose.onNode(hasText(label) and hasSetTextAction())
    private fun replace(label: String, value: String) = field(label).performScrollTo().performTextReplacement(value)
    private fun provider(name: String) {
        val current = when {
            compose.onAllNodesWithText("PC").fetchSemanticsNodes().isNotEmpty() -> "PC"
            compose.onAllNodesWithText("Cloud").fetchSemanticsNodes().isNotEmpty() -> "Cloud"
            else -> "Device"
        }
        compose.onNodeWithText(current).performScrollTo().performClick()
        compose.onAllNodesWithText(name).onLast().performClick()
    }
    private fun save() = compose.onNode(hasContentDescription("Save settings") and hasClickAction()).performScrollTo().performClick()
    private fun settings() = io { requireNotNull(repo.settingsDao.getSettingsDirect()) }
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    /** Tiny emulator-only HTTP fixture. Never bind all interfaces or proxy upstream requests. */
    private class Loopback : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val sockets = CopyOnWriteArrayList<Socket>()
        val requests = AtomicInteger()
        val url = "http://127.0.0.1:${listener.localPort}"
        @Volatile private var closed = false
        init {
            Thread({
                while (!closed) {
                    val socket = try { listener.accept() } catch (_: Exception) { break }
                    sockets += socket
                    Thread({
                        socket.use { s ->
                            s.soTimeout = 4000
                            val reader = s.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val requestLine = reader.readLine() ?: return@use
                            while (!reader.readLine().isNullOrEmpty()) { /* Consume headers; never retain credentials. */ }
                            requests.incrementAndGet()
                            val path = requestLine.split(' ').getOrNull(1).orEmpty()
                            val body = if (path == "/api/tags")
                                """{"models":[{"name":"qa-model-a"},{"name":"qa-model-b"}]}"""
                                else """{"status":"ok"}"""
                            val bytes = body.toByteArray(Charsets.UTF_8)
                            val header = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            s.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
                        }
                        sockets -= socket
                    }, "qa-settings-http-response").apply { isDaemon = true; start() }
                }
            }, "qa-settings-http-listener").apply { isDaemon = true; start() }
        }
        override fun close() {
            closed = true
            listener.close()
            sockets.forEach { runCatching { it.close() } }
        }
    }
}
