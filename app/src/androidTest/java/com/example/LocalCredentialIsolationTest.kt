package com.example

import android.app.Application
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import com.example.ui.VideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A credential-presence bit exists only in the injected proxy. There is no credential value.
 * This does not construct the default encrypted preferences or inspect the user's key store.
 * Controlled metadata execute()/analysis enqueue() calls never open a network connection.
 */
@RunWith(AndroidJUnit4::class)
class LocalCredentialIsolationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val db get() = VideoDatabase.getDatabase(context)
    private val store = ViewModelStore()
    private val token = "qa_presence_only_" + UUID.randomUUID().toString().replace("-", "")
    private lateinit var delegate: SharedPreferences
    private lateinit var credentials: PresenceOnlyPreferences
    private lateinit var repo: VideoRepository
    private lateinit var vm: VideoViewModel
    private val wire = ControlledCalls()
    private var oldSettings: AppSettings? = null
    private var oldProvider: String? = null
    private var oldModels: Set<String>? = null
    private var oldAuto = false
    private var hadAuto = false
    private var configured = false
    private val endpoint = "http://127.0.0.1:11434/api/generate"

    @Before fun isolateWithoutOpeningAnyDefaultCredentialStore() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview"))
        check(InstrumentationRegistry.getArguments().getString("rea_runtime") == "emulator")
        check(io { db.videoItemDao().getAllVideosDirect().isEmpty() }) { "Credential isolation tests require empty isolated QA index" }
        delegate = context.getSharedPreferences(token, 0)
        check(delegate.edit().clear().commit())
        assertFalse("The ordinary test store must contain no credential entry", delegate.contains("llm_api_key"))
        credentials = PresenceOnlyPreferences(delegate)
        repo = VideoRepository(context, mediaInventorySource = object : MediaInventorySource {
            override fun currentAccessState() = "Full access"
            override suspend fun readSnapshot() = MediaInventorySnapshot("Full access", emptyList(), true)
        }, analysisClient = wire, credentialPreferences = credentials)
        oldSettings = io { repo.settingsDao.getSettingsDirect() }
        oldProvider = repo.prefs.getString("llm_provider", null)
        oldModels = repo.prefs.getStringSet("llm_available_models", null)?.toSet()
        oldAuto = repo.prefs.getBoolean("auto_ingest_enabled", false)
        hadAuto = repo.prefs.contains("auto_ingest_enabled")
        configured = true
        repo.prefs.edit().putString("llm_provider", "ollama_local").putBoolean("auto_ingest_enabled", false).commit()
        io { repo.settingsDao.insertSettings(AppSettings(ollamaEnabled = false, ollamaEndpointUrl = endpoint, ollamaModelName = "qa-isolation-model")) }
    }
    @After fun restoreOnlyOrdinaryChoicesAndClearOwnedVm() {
        instrumentation.runOnMainSync { store.clear() }
        if (::repo.isInitialized && configured) {
            io {
                if (oldSettings != null) repo.settingsDao.insertSettings(requireNotNull(oldSettings))
                else db.openHelper.writableDatabase.execSQL("DELETE FROM app_settings WHERE id = 1")
            }
            repo.prefs.edit().apply {
                if (oldProvider == null) remove("llm_provider") else putString("llm_provider", oldProvider)
                if (oldModels == null) remove("llm_available_models") else putStringSet("llm_available_models", oldModels)
                if (hadAuto) putBoolean("auto_ingest_enabled", oldAuto) else remove("auto_ingest_enabled")
            }.commit()
        }
        if (::delegate.isInitialized) context.deleteSharedPreferences(token)
    }

    @Test fun pcVmMetadataAndModelDiscoveryUseOpaquePresenceWithoutReadingACloudKey() {
        mountVm()
        assertPresenceOnly()
        instrumentation.runOnMainSync { vm.checkConnection(testUrl = endpoint, testProvider = "ollama_local") }
        await("PC root and tag responses accepted") {
            vm.connectionSuccess.value == true && vm.availableModels.value == listOf("qa-isolation-model") &&
                wire.requests.any { it.url.encodedPath == "/" } && wire.requests.any { it.url.encodedPath == "/api/tags" }
        }
        instrumentation.runOnMainSync { vm.syncModels(testUrl = "http://127.0.0.1:11434/v1/chat/completions", testProvider = "ollama_local") }
        await("PC V1 model response accepted") { vm.availableModels.value == listOf("qa-v1-isolation-model") }
        assertTrue(wire.requests.any { it.url.encodedPath == "/v1/models" })
        assertTrue("Metadata route must execute controlled GET calls", wire.executed.get() >= 3)
        assertEquals("Metadata discovery must not perform inference", 0, wire.enqueued.get())
        assertPresenceOnly(); assertNoAuthorization()
    }

    @Test fun pcInferenceUsesControlledOllamaResponseWithoutReadingOrSendingCloudKey() {
        mountVm(); assertPresenceOnly()
        val video = syntheticItem()
        val result = io { repo.analyzeVideoKnowledge(video, listOf(ExtractedFrame(videoId = video.id, timestampMs = 0, ocrText = "QA local credential isolation")),
            "QA owned notes", AppSettings(ollamaEnabled = true, ollamaEndpointUrl = endpoint, ollamaModelName = "qa-isolation-model")) }
        assertEquals("PC response must really pass provider parsing rather than silently falling back", "ollama", result.source)
        assertEquals("QA PC analysis result", result.descriptionLine1)
        assertEquals(video.id, result.videoId)
        assertEquals(1, wire.enqueued.get()); assertEquals(0, wire.executed.get())
        assertEquals(1, repo.mediaOperationCounters.llmRequests)
        assertTrue(wire.requests.single().url.encodedPath == "/api/generate")
        assertPresenceOnly(); assertNoAuthorization()
        assertTrue(io { db.videoItemDao().getAllVideosDirect().isEmpty() })
    }

    @Test fun deviceVmAndFallbackNeverReadCloudKeyOrIssueAnyRequestDespitePresence() {
        repo.prefs.edit().putString("llm_provider", "local").commit()
        mountVm(); assertPresenceOnly()
        instrumentation.runOnMainSync { vm.checkConnection(testProvider = "local"); vm.syncModels(testProvider = "local") }
        await("Device local model state") { vm.connectionSuccess.value == true && vm.availableModels.value == listOf("keyword-fallback") }
        val video = syntheticItem()
        val result = io { repo.analyzeVideoKnowledge(video, emptyList(), "QA local-only notes", AppSettings(ollamaEnabled = false)) }
        assertEquals("fallback", result.source)
        assertEquals("Deterministic fallback generated locally.", result.descriptionLine1)
        assertEquals(video.id, result.videoId)
        assertTrue(wire.requests.isEmpty()); assertEquals(0, wire.executed.get()); assertEquals(0, wire.enqueued.get())
        assertEquals(0, repo.mediaOperationCounters.llmRequests)
        assertPresenceOnly(); assertNoAuthorization()
        assertTrue(io { db.videoItemDao().getAllVideosDirect().isEmpty() })
    }

    private fun mountVm() {
        instrumentation.runOnMainSync {
            vm = VideoViewModel(context.applicationContext as Application, repo, connectionClient = wire)
            store.put(token, vm)
        }
        await("nonsecret settings loaded") { vm.settings.value.ollamaModelName == "qa-isolation-model" }
    }
    private fun syntheticItem() = VideoItem(id = 719001L, mediaStoreId = -719001L,
        uriString = "content://${context.packageName}.qa-isolation/owned-fixture", displayName = "$token.png", relativePath = "QA/$token/",
        mimeType = "image/png", sizeBytes = 0, durationMs = null, dateAdded = null, dateModified = null, reviewDecision = "keep", mediaCollection = "image")
    private fun assertPresenceOnly() {
        assertTrue("VM exposes only opaque credential presence", vm.hasApiKey.value)
        assertTrue("Presence initializer must use contains(), not a fabricated value", credentials.presenceChecks.get() > 0)
        assertEquals("PC/Device paths must never request the stored key value", 0, credentials.forbiddenReads.get())
        assertFalse("No test key value may be created", delegate.contains("llm_api_key"))
    }
    private fun assertNoAuthorization() {
        assertTrue("No PC/Device request may carry Authorization even with opaque presence=true", wire.requests.all { it.header("Authorization") == null })
    }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; SystemClock.sleep(25) }
        throw AssertionError("Timed out: $description")
    }
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private class PresenceOnlyPreferences(private val ordinary: SharedPreferences) : SharedPreferences by ordinary {
        val forbiddenReads = AtomicInteger()
        val presenceChecks = AtomicInteger()
        override fun contains(key: String?): Boolean {
            if (key == "llm_api_key") { presenceChecks.incrementAndGet(); return true }
            return ordinary.contains(key)
        }
        override fun getString(key: String?, defValue: String?): String? {
            if (key == "llm_api_key") {
                forbiddenReads.incrementAndGet()
                throw AssertionError("Forbidden key-value read on PC/Device route; only opaque presence is available")
            }
            return ordinary.getString(key, defValue)
        }
    }

    private class ControlledCalls : Call.Factory {
        val requests = CopyOnWriteArrayList<Request>()
        val executed = AtomicInteger()
        val enqueued = AtomicInteger()
        override fun newCall(request: Request): Call {
            check(request.url.host == "127.0.0.1" && request.url.port == 11434) { "Only controlled loopback URL metadata is allowed" }
            if (request.header("Authorization") != null) throw AssertionError("PC request unexpectedly contains Authorization")
            requests += request
            return ControlledCall(request)
        }
        private inner class ControlledCall(private val req: Request) : Call {
            private val didExecute = AtomicBoolean(false)
            private val cancelled = AtomicBoolean(false)
            override fun request() = req
            override fun execute(): Response {
                check(didExecute.compareAndSet(false, true)); check(!cancelled.get()); executed.incrementAndGet()
                check(req.method == "GET") { "Metadata execute() must use GET" }
                return response()
            }
            override fun enqueue(responseCallback: Callback) {
                check(didExecute.compareAndSet(false, true)); check(!cancelled.get()); enqueued.incrementAndGet()
                check(req.method == "POST" && req.url.encodedPath == "/api/generate") { "Inference enqueue() must use controlled generate POST" }
                responseCallback.onResponse(this, response())
            }
            private fun response(): Response {
                val body = when (req.url.encodedPath) {
                    "/" -> "Ollama is running"
                    "/api/tags" -> JSONObject().put("models", JSONArray().put(JSONObject().put("name", "qa-isolation-model"))).toString()
                    "/v1/models" -> JSONObject().put("data", JSONArray().put(JSONObject().put("id", "qa-v1-isolation-model"))).toString()
                    "/api/generate" -> JSONObject().put("response", JSONObject()
                        .put("description", JSONArray().put("QA PC analysis result").put("Controlled local inference response"))
                        .put("categoryLevel1", "Technical").put("categoryLevel2", "QA").put("categoryLevel3", "Credential isolation").toString()).toString()
                    else -> throw AssertionError("Unexpected controlled request path")
                }
                return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("QA controlled response").body(body.toResponseBody()).build()
            }
            override fun cancel() { cancelled.set(true) }
            override fun isExecuted() = didExecute.get()
            override fun isCanceled() = cancelled.get()
            override fun timeout() = Timeout.NONE
            override fun clone(): Call = ControlledCall(req)
        }
    }
}
