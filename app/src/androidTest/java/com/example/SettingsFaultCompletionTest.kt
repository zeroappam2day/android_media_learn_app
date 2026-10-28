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
class SettingsFaultCompletionTest {
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
        credentialFile = "qa_settings_fault_empty_${System.nanoTime()}"
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

    @Test fun t100_blankMistypedAndUnreachableHostCanBeCorrectedThroughActualUi() {
        val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        for (bad in listOf("", "http://", "http://127.0.0.1:$closedPort")) {
            replaceHost(bad)
            testConnection()
            await { vm.connectionSuccess.value == false }
            compose.onNodeWithText("Unreachable", substring = false).assertExists()
            compose.onNodeWithText("Connected", substring = false).assertDoesNotExist()
            assertEquals("Unsuccessful draft must not save Host", server.url, settings().ollamaEndpointUrl)
        }
        replaceHost(server.url)
        testConnection()
        await { vm.connectionSuccess.value == true && vm.availableModels.value == listOf("qa-model-a", "qa-model-b") }
        compose.onNodeWithText("Connected", substring = false).assertExists()
        chooseModel("qa-model-a", "qa-model-b")
        save()
        await { settings().ollamaModelName == "qa-model-b" }
        assertEquals(server.url, settings().ollamaEndpointUrl)
    }

    @Test fun t103_successfulEmptyModelsHasVisibleExplanationAndCannotInventSelection() {
        server.mode = Reply(models = emptyList())
        sync()
        await { server.completed.get() >= 1 }
        await { compose.onAllNodesWithText("No models returned by this service.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("No models returned by this service.", substring = true).performScrollTo().assertIsDisplayed()
        assertTrue(vm.availableModels.value.isEmpty())
        modelField("Tap sync to fetch models").performScrollTo().performClick()
        compose.onNodeWithText("No model", substring = false).assertExists()
        compose.onNodeWithText("qa-model-a", substring = false).assertDoesNotExist()
        compose.onNodeWithText("No model", substring = false).performClick()
        assertEquals("", settings().ollamaModelName)
    }

    @Test fun t192_sameEndpointFailedSyncRetainsKnownChoicesAndSelectedModel() {
        sync()
        await { vm.availableModels.value == listOf("qa-model-a", "qa-model-b") }
        chooseModel("qa-model-a", "qa-model-b")
        val beforePrefs = repo.prefs.getStringSet("llm_available_models", emptySet())!!.toSet()
        val completed = server.completed.get()
        server.mode = Reply(status = 503)
        sync()
        await { server.completed.get() > completed }
        assertStable {
            vm.availableModels.value == listOf("qa-model-a", "qa-model-b") &&
                repo.prefs.getStringSet("llm_available_models", emptySet()) == beforePrefs
        }
        modelField("qa-model-b").assertExists()
        chooseModel("qa-model-b", "qa-model-a")
        save()
        await { settings().ollamaModelName == "qa-model-a" }
        assertEquals(server.url, settings().ollamaEndpointUrl)
    }

    @Test fun t118_oldSlowModelResponseCannotReplaceFastNewHostModels() {
        val gate = java.util.concurrent.CountDownLatch(1)
        server.mode = Reply(models = listOf("old-delayed-model"), gate = gate)
        sync()
        await { server.accepted.get() >= 1 }
        val newer = extra(Reply(models = listOf("new-fast-model", "new-fast-second")))
        replaceHost(newer.url)
        sync()
        await { vm.availableModels.value == listOf("new-fast-model", "new-fast-second") }
        assertEquals("Old model request must still be held when the new response is accepted", 0, server.completed.get())
        chooseModel("new-fast-model", "new-fast-second")
        gate.countDown()
        await { server.completed.get() >= 1 }
        assertStable { vm.availableModels.value == listOf("new-fast-model", "new-fast-second") }
        modelField("new-fast-second").assertExists()
        modelField("new-fast-second").performScrollTo().performClick()
        compose.onNodeWithText("old-delayed-model", substring = false).assertDoesNotExist()
        compose.onNodeWithText("new-fast-model", substring = false).performClick()
        save()
        await { settings().ollamaEndpointUrl == newer.url && settings().ollamaModelName == "new-fast-model" }
    }

    @Test fun t118_oldSlowConnectionSuccessCannotOverrideNewHostUnreachable() {
        val gate = java.util.concurrent.CountDownLatch(1)
        server.mode = Reply(gate = gate)
        testConnection()
        await { server.accepted.get() >= 1 }
        val newer = extra(Reply(status = 503))
        replaceHost(newer.url)
        testConnection()
        await { vm.connectionSuccess.value == false }
        compose.onNodeWithText("Unreachable", substring = false).assertExists()
        assertEquals("Old connection request must still be held when the new failure is accepted", 0, server.completed.get())
        gate.countDown()
        await { server.completed.get() >= 1 }
        assertStable { vm.connectionSuccess.value == false }
        compose.onNodeWithText("Unreachable", substring = false).assertExists()
        compose.onNodeWithText("Connected", substring = false).assertDoesNotExist()
        assertFalse("An obsolete successful connection must not launch a model fetch", server.paths.any { it == "/api/tags" })
    }

    @Test fun t193_modelProvenanceNormalizesGenerateAndV1PathsButSeparatesHosts() {
        replaceHost(server.url + "/api/generate")
        sync()
        await { vm.availableModels.value == listOf("qa-model-a", "qa-model-b") }
        modelField("qa-model-a").assertExists()
        replaceHost(server.url + "/")
        // Equivalent root retains the usable discovered list; selected model may reset.
        await { compose.onAllNodes(hasText("qa-model-a") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        chooseModel("qa-model-a", "qa-model-b")
        val before = server.accepted.get()
        replaceHost(server.url + "/v1/chat/completions")
        sync()
        await { server.accepted.get() > before && server.paths.contains("/v1/models") && vm.availableModelEndpoint.value == server.url + "/v1" && vm.availableModels.value == listOf("qa-model-a", "qa-model-b") }
        await { compose.onAllNodes(hasText("qa-model-a") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        modelField("qa-model-a").assertExists()
        replaceHost(server.url + "/v1/")
        await { compose.onAllNodes(hasText("qa-model-a") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        chooseModel("qa-model-a", "qa-model-b")
        val newer = extra(Reply(models = listOf("different-host-only")))
        replaceHost(newer.url)
        modelField("qa-model-b").assertDoesNotExist()
        modelField("Tap sync to fetch models").performScrollTo().performClick()
        compose.onNodeWithText("No model", substring = false).assertExists()
        compose.onNodeWithText("qa-model-a", substring = false).assertDoesNotExist()
        compose.onNodeWithText("No model", substring = false).performClick()
        sync()
        await { vm.availableModels.value == listOf("different-host-only") }
        modelField("different-host-only").assertExists()
        assertEquals("Draft host edits have not saved", server.url, settings().ollamaEndpointUrl)
    }

    @Test fun publicPcConsentSavesOnlyWhenChosenAndChangingHostClearsTheDraftPermission() {
        replaceHost("https://example.invalid/api/generate")
        val permission = compose.onNodeWithTag("allow_outside_service")
        permission.performScrollTo().assertIsOff()
        compose.onNodeWithText("Allow this outside service").assertExists()
        permission.performClick()
        save()
        await { settings().ollamaEndpointUrl == "https://example.invalid/api/generate" && settings().confirmedNonLocalEndpointWarning }
        open()
        compose.onNodeWithTag("allow_outside_service").performScrollTo().assertIsOn()
        replaceHost("https://other.invalid/api/generate")
        compose.onNodeWithTag("allow_outside_service").performScrollTo().assertIsOff()
        compose.onNodeWithContentDescription("Close").performClick()
        assertEquals("Unsaved host edits must not change the saved permission", "https://example.invalid/api/generate", settings().ollamaEndpointUrl)
        assertTrue(settings().confirmedNonLocalEndpointWarning)
    }

    private fun open() {
        compose.onNode(hasContentDescription("Settings") and hasClickAction()).performClick()
        await { compose.onAllNodesWithText("AI setup").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun replaceHost(value: String) {
        compose.onNode(hasText("AI address") and hasSetTextAction()).performScrollTo().performTextReplacement(value)
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
