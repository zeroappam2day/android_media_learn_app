package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import com.example.ui.VideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Controlled Cloud branch ONLY; not live Cloud API/account/network proof.
 * Requires parent VM constructor seam connectionClient: Call.Factory? = null for discovery.
 * Both injected factories below are the SAME in-memory interceptor: no socket is ever opened.
 * Unique ordinary QA credential preferences contain only this test's public dummy marker.
 * No default secure preference store, key-field dumps, full semantics dumps, or screenshots.
 */
class CloudUiCompletionTest {
    @get:Rule val compose = createAndroidComposeRule<InventoryRecreationTestActivity>()
    private val context get() = compose.activity.applicationContext
    private val marker = "PUBLIC-QA-DUMMY-NEVER-A-REAL-KEY-" + UUID.randomUUID()
    private val wire = CloudWire()
    private val store = ViewModelStore()
    private lateinit var repo: VideoRepository
    private lateinit var vm: VideoViewModel
    private lateinit var credentialsName: String
    private val files = mutableListOf<File>()
    private val ids = mutableListOf<Long>()
    private var oldSettings: AppSettings? = null
    private var oldProvider: String? = null
    private var oldAuto = false
    private var oldModels: Set<String>? = null
    private var oldModelProvider: String? = null
    private var oldModelEndpoint: String? = null
    private var oldPrompt: String? = null
    private var hadTemperature = false
    private var oldTemperature = 0f

    @Before fun setup() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview"))
        check(InstrumentationRegistry.getArguments().getString("rea_runtime") == "emulator")
        check(io { VideoDatabase.getDatabase(context).videoItemDao().getAllVideosDirect().isEmpty() })
        credentialsName = "qa_cloud_public_dummy_${System.nanoTime()}"
        val emptyCredentials = context.getSharedPreferences(credentialsName, 0)
        check(emptyCredentials.all.isEmpty())
        repo = VideoRepository(context, analysisClient = wire, credentialPreferences = emptyCredentials)
        oldSettings = io { repo.settingsDao.getSettingsDirect() }
        oldPrompt = repo.prefs.getString("llm_system_prompt", null)
        hadTemperature = repo.prefs.contains("llm_temperature")
        oldTemperature = repo.prefs.getFloat("llm_temperature", 0f)
        oldProvider = repo.prefs.getString("llm_provider", null)
        oldAuto = repo.prefs.getBoolean("auto_ingest_enabled", false)
        oldModels = repo.prefs.getStringSet("llm_available_models", null)?.toSet()
        oldModelProvider = repo.prefs.getString("llm_available_model_provider", null)
        oldModelEndpoint = repo.prefs.getString("llm_available_model_endpoint", null)
        repo.prefs.edit().putString("llm_provider", "local").putBoolean("auto_ingest_enabled", false).commit()
        io { repo.settingsDao.insertSettings(AppSettings(ollamaEnabled = false)) }
        compose.runOnUiThread {
            vm = VideoViewModel(context as Application, repo, connectionClient = wire)
            store.put("cloud-qa", vm)
            compose.activity.setContent { MaterialTheme { MainScreen(vm, false, {}) } }
        }
        openSettings()
        compose.onNodeWithText("Device").performClick()
        compose.onAllNodesWithText("Cloud").onLast().performClick()
        host().assertTextContains("https://ollama.com")
        key().performScrollTo().performTextReplacement(marker)
        key().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.runOnUiThread {
            val focused = android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() }
            assertTrue("Cloud settings must have a focused app dialog", focused.isNotEmpty())
            assertTrue("Cloud settings must prevent screenshots", focused.all {
                val params = it.layoutParams as? android.view.WindowManager.LayoutParams
                params != null && params.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0
            })
        }
        dismissQaKeyboard(compose.activity)
    }

    @After fun cleanup() {
        if (::vm.isInitialized) compose.runOnUiThread { store.clear() }
        if (::repo.isInitialized) {
            io {
                ids.forEach { id -> repo.analysisDao.deleteAnalysisForVideo(id); repo.frameDao.deleteFramesForVideo(id)
                    repo.videoDao.getVideoByIdDirect(id)?.let { repo.videoDao.deleteVideo(it) } }
                oldSettings?.let { repo.settingsDao.insertSettings(it) }
            }
            repo.prefs.edit().apply {
                if (oldProvider == null) remove("llm_provider") else putString("llm_provider", oldProvider)
                if (oldPrompt == null) remove("llm_system_prompt") else putString("llm_system_prompt", oldPrompt)
                if (hadTemperature) putFloat("llm_temperature", oldTemperature) else remove("llm_temperature")
                putBoolean("auto_ingest_enabled", oldAuto)
                if (oldModels == null) remove("llm_available_models") else putStringSet("llm_available_models", oldModels)
                if (oldModelProvider == null) remove("llm_available_model_provider") else putString("llm_available_model_provider", oldModelProvider)
                if (oldModelEndpoint == null) remove("llm_available_model_endpoint") else putString("llm_available_model_endpoint", oldModelEndpoint)
            }.commit()
        }
        files.forEach { it.delete() }
        if (::credentialsName.isInitialized) context.deleteSharedPreferences(credentialsName)
    }

    @Test fun cloudDiscoverySaveHiddenDraftAndActualProcessingUseInterceptedCloudBranch() {
        // Controlled UI portions of T098–T103/T118/T179/T193/T224, not live service proof.
        connectAndSync()
        // Discovery's first model may already be selected. Open actual dropdown and choose explicitly.
        compose.onNodeWithText("cloud-fixture").performScrollTo().performClick()
        compose.onAllNodesWithText("cloud-fixture").onLast().performClick()
        compose.onNodeWithContentDescription("Save settings").performScrollTo().performClick()
        await { vm.llmProvider.value == "ollama_cloud" && vm.hasApiKey.value && settings().ollamaModelName == "cloud-fixture" }
        openSettings()
        // Inspect only a boolean about the known-empty draft; never emit its editable contents.
        assertTrue("Reopened Cloud credential draft must be blank",
            key().fetchSemanticsNode().config[SemanticsProperties.EditableText].text.isEmpty())
        compose.onNodeWithText("Saved key is hidden. Leave blank to keep it.").performScrollTo().assertExists()
        assertTrue(vm.hasApiKey.value)
        compose.onNodeWithContentDescription("Close").performClick()
        val item = fixture()
        navigate("nav_queue")
        await { vm.allVideos.value.any { it.id == item.id } }
        compose.onNodeWithTag("queue_${item.id}_preview").performQaQueueScrollTo(compose).performClick()
        compose.onNodeWithContentDescription("Process media").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        await { row(item.id).status == "processed" && vm.processor.processorState.value == VideoProcessor.State.FINISHED }
        val result = io { requireNotNull(repo.analysisDao.getAnalysisForVideoDirect(item.id)) }
        assertEquals("QA cloud success", result.descriptionLine1)
        // Shared source field cannot distinguish PC vs Cloud; transport/provider assertions below do.
        assertEquals("ollama", result.source)
        assertEquals("ollama_cloud", repo.prefs.getString("llm_provider", null))
        assertEquals(1, wire.events.count { it == "cloud-post" })
        assertTrue(wire.events.any { it == "metadata-get" })
        assertNull("An intercepted request violated Cloud endpoint/auth constraints", wire.violation.get())
        compose.onNodeWithTag("queue_${item.id}_output").performQaQueueScrollTo(compose).performClick()
        compose.onNodeWithText("QA cloud success").assertExists()
        compose.onNodeWithText("Deterministic fallback generated locally.").assertDoesNotExist()
        assertEquals(1, io { repo.analysisDao.getAllAnalysis().first().count { it.videoId == item.id } })
    }

    @Test fun unauthorizedCloudDiscoveryShowsUnreachableThenSuccessfulRetryIsFresh() {
        // Controlled T194/T224 discovery failure/recovery; no live auth claim.
        wire.status = 401
        compose.onNodeWithContentDescription("Test connection").performScrollTo().performClick()
        await { vm.connectionSuccess.value == false && wire.events.isNotEmpty() }
        compose.onNodeWithText("Unreachable").assertExists()
        compose.onNodeWithText("Service reachable").assertDoesNotExist()
        assertTrue(vm.availableModels.value.isEmpty())
        wire.status = 200
        connectAndSync()
        compose.onNodeWithText("Unreachable").assertDoesNotExist()
        assertNull(wire.violation.get())
    }

    @Test fun untrustedCloudHostIsRejectedBeforeEitherFactoryCanReceiveCredential() {
        val before = wire.events.size
        host().performScrollTo().performTextReplacement("https://qa-untrusted.invalid")
        dismissQaKeyboard(compose.activity)
        compose.onNodeWithContentDescription("Test connection").performScrollTo().performClick()
        await { vm.connectionSuccess.value == false }
        compose.onNodeWithText("Unreachable").assertExists()
        compose.onNodeWithText("Service reachable").assertDoesNotExist()
        compose.onNodeWithContentDescription("Sync models tag list").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(vm.availableModels.value.isEmpty())
        assertEquals("Untrusted host must be blocked before constructing a call", before, wire.events.size)
        assertNull(wire.violation.get())
    }

    private fun connectAndSync() {
        compose.onNodeWithContentDescription("Test connection").performScrollTo().performClick()
        await { vm.connectionSuccess.value == true }
        compose.onNodeWithText("Service reachable").assertExists()
        compose.onNodeWithContentDescription("Sync models tag list").performScrollTo().performClick()
        await { vm.availableModels.value == listOf("cloud-fixture") }
        assertNull(wire.violation.get())
    }
    private fun fixture(): VideoItem {
        val file = File(context.cacheDir, "qa-cloud-${System.nanoTime()}.png").also { files += it }
        val bitmap = Bitmap.createBitmap(800, 220, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.WHITE); drawText("QA Cloud fixture", 40f, 120f, Paint().apply { color = Color.BLACK; textSize = 64f; isAntiAlias = true }) }
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
        val id = io { repo.videoDao.insertVideo(VideoItem(mediaStoreId = -System.nanoTime(), uriString = Uri.fromFile(file).toString(),
            displayName = file.name, relativePath = null, mimeType = "image/png", sizeBytes = file.length(), durationMs = null,
            dateAdded = null, dateModified = null, reviewDecision = "keep", mediaCollection = "image")) }
        ids += id
        return row(id)
    }
    private fun host() = compose.onNode(hasSetTextAction() and hasText("Host"))
    private fun key() = compose.onNode(hasSetTextAction() and hasText("API key"))
    private fun openSettings() { compose.onNodeWithContentDescription("Settings").performClick(); compose.onNodeWithText("AI setup").assertExists() }
    private fun navigate(tag: String) {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag(tag).performClick()
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close menu").performClick()
    }
    private fun settings() = io { requireNotNull(repo.settingsDao.getSettingsDirect()) }
    private fun row(id: Long) = io { requireNotNull(repo.videoDao.getVideoByIdDirect(id)) }
    private fun await(condition: () -> Boolean) = compose.waitUntil(90000, condition)
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private inner class CloudWire : Call.Factory {
        val events = CopyOnWriteArrayList<String>()
        val violation = AtomicReference<String?>()
        @Volatile var status = 200
        override fun newCall(request: Request): Call {
            // Record only operation classes; never retain/log URLs, headers, key values, or payloads.
            if (request.url.scheme != "https" || request.url.host != "ollama.com") violation.set("untrusted endpoint")
            if (request.header("Authorization") != "Bearer $marker") violation.set("unexpected credential")
            val inference = request.method == "POST"
            if (!inference && (request.method != "GET" || request.url.encodedPath !in setOf("/api/tags", "/v1/models"))) violation.set("unexpected metadata operation")
            if (inference && request.url.encodedPath !in setOf("/api/generate", "/v1/chat/completions")) violation.set("unexpected inference operation")
            events += if (inference) "cloud-post" else "metadata-get"
            return object : Call {
                private var executed = false
                private var cancelled = false
                override fun request() = request
                override fun execute(): Response { executed = true; return response() }
                override fun enqueue(callback: Callback) { executed = true; if (!cancelled) callback.onResponse(this, response()) }
                private fun response(): Response {
                    val payload = JSONObject().put("description", JSONArray().put("QA cloud success").put("Controlled Cloud fixture response"))
                        .put("categoryLevel1", "Technical").put("categoryLevel2", "Testing").put("categoryLevel3", "Cloud branch")
                    val body = if (inference) {
                        if (request.url.encodedPath.startsWith("/v1/")) JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", payload.toString())))).toString()
                        else JSONObject().put("response", payload.toString()).toString()
                    } else if (request.url.encodedPath == "/v1/models") """{"data":[{"id":"cloud-fixture"}]}"""
                    else """{"models":[{"name":"cloud-fixture"}]}"""
                    return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (violation.get() == null) status else 403)
                        .message("Controlled QA response").body(body.toResponseBody()).build()
                }
                override fun cancel() { cancelled = true }
                override fun isExecuted() = executed
                override fun isCanceled() = cancelled
                override fun timeout() = Timeout.NONE
                override fun clone(): Call = newCall(request)
            }
        }
    }
}
