package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
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
import okio.Timeout
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * OPT-IN T065 two-instrumentation test. NEVER schedule with an unrestricted test-class suite.
 * Seed: -e qa_phase interrupt_seed -e qa_token <unique-nonsecret-token> -e rea_runtime emulator
 * Host waits for external-files/qa-interrupt-<token>.json and phase=held in ordinary
 * qa_interrupt_receipt preferences, then force-stops ONLY the .qa.preview app.
 * Verify: same qa_token with -e qa_phase interrupt_verify in a fresh instrumentation process.
 * This draft does not execute adb, kill, build, or network operations itself.
 * Seed intentionally has no finally/@After cleanup: the owned receipt must survive process death.
 */
class InterruptedProcessingCompletionTest {
    @get:Rule val compose = createAndroidComposeRule<InventoryRecreationTestActivity>()
    private val context get() = compose.activity.applicationContext
    private val receiptPrefs get() = context.getSharedPreferences("qa_interrupt_receipt", Context.MODE_PRIVATE)
    private val args get() = InstrumentationRegistry.getArguments()
    private val store = ViewModelStore()
    private lateinit var vm: VideoViewModel
    private lateinit var repo: VideoRepository
    private val requests = AtomicInteger()
    private lateinit var receiptFile: File

    @Test fun interruptedProcessingSeedOrVerify() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview"))
        check(args.getString("rea_runtime") == "emulator")
        val phase = args.getString("qa_phase")
        val token = requireNotNull(args.getString("qa_token")) { "Explicit unique nonsecret qa_token required" }
        check(token.matches(Regex("[A-Za-z0-9_-]{8,80}")))
        receiptFile = File(context.getExternalFilesDir(null), "qa-interrupt-$token.json")
        when (phase) {
            "interrupt_seed" -> seed(token)
            "interrupt_verify" -> verify(token)
            else -> error("Opt-in only: qa_phase interrupt_seed or interrupt_verify required")
        }
    }

    private fun seed(token: String) {
        check(!receiptFile.exists()) { "Existing receipt must be verified before a new seed" }
        check(!receiptPrefs.contains("token")) { "Outstanding interrupted test exists" }
        val db = VideoDatabase.getDatabase(context)
        check(io { db.videoItemDao().getAllVideosDirect().isEmpty() }) { "An empty isolated QA database is required" }
        val prefs = context.getSharedPreferences("app_prefs", 0)
        val old = io { db.appSettingsDao().getSettingsDirect() }
        val credentialsName = "qa_interrupt_empty_$token"
        val fixture = File(context.cacheDir, "qa-interrupt-$token.png")
        Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            fixture.outputStream().use { check(compress(Bitmap.CompressFormat.PNG, 100, it)) }
            recycle()
        }
        val held = HeldFactory()
        repo = VideoRepository(context, analysisClient = held,
            credentialPreferences = context.getSharedPreferences(credentialsName, 0))
        val note = "QA interruption note $token"
        val uri = Uri.fromFile(fixture).toString()
        io { repo.videoDao.insertVideos(listOf(VideoItem(mediaStoreId = -System.nanoTime(), uriString = uri,
            displayName = fixture.name, relativePath = null, mimeType = "image/png", sizeBytes = fixture.length(),
            durationMs = null, dateAdded = null, dateModified = null, reviewDecision = "keep",
            mediaCollection = "image", manualTranscript = note))) }
        val video = io { requireNotNull(repo.videoDao.getVideoByUri(uri)) }
        // Store cleanup ownership before work begins, but expose phase=held only after actual HTTP waits.
        val receipt = JSONObject().put("schema", 1).put("token", token).put("phase", "prepared")
            .put("package", context.packageName).put("id", video.id).put("uri", uri)
            .put("fixture", fixture.canonicalPath).put("sha256", digest(fixture.readBytes()))
            .put("notes", note).put("review", "keep").put("credentials", credentialsName)
            .put("hadProvider", prefs.contains("llm_provider")).put("provider", prefs.getString("llm_provider", ""))
            .put("hadAuto", prefs.contains("auto_ingest_enabled")).put("auto", prefs.getBoolean("auto_ingest_enabled", false))
            .put("hadSettings", old != null)
        old?.let { receipt.put("settings", JSONObject().put("enabled", it.ollamaEnabled)
            .put("endpoint", it.ollamaEndpointUrl).put("model", it.ollamaModelName)
            .put("timeout", it.requestTimeoutSeconds).put("warning", it.confirmedNonLocalEndpointWarning)) }
        persist(receipt)
        prefs.edit().putString("llm_provider", "ollama_local").putBoolean("auto_ingest_enabled", false).commit()
        io { repo.settingsDao.insertSettings(AppSettings(ollamaEnabled = true,
            ollamaEndpointUrl = "http://127.0.0.1:1/api/generate", ollamaModelName = "qa-interrupt-model")) }
        mount()
        await { vm.startupReady.value && vm.allVideos.value.any { it.id == video.id } }
        navigate("nav_queue")
        compose.onNodeWithContentDescription("Preview").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Process media").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        await { held.executed && row(video.id).status == "processing" }
        assertNull(io { repo.analysisDao.getAnalysisForVideoDirect(video.id) })
        assertEquals(note, row(video.id).manualTranscript)
        receipt.put("phase", "held").put("seedRequests", requests.get())
        persist(receipt)
        // Intentionally remains in the real live processing phase until the authorized host stops QA.
        // A timeout is a host orchestration failure, never a successful interruption test.
        assertFalse("Host did not force-stop QA within ten minutes; retain receipt for inspection",
            CountDownLatch(1).await(10, TimeUnit.MINUTES))
        fail("Host force-stop was not performed; seed cannot pass by returning normally")
    }

    private fun verify(token: String) {
        check(receiptFile.exists()) { "Seed receipt missing" }
        val receipt = JSONObject(receiptFile.readText())
        check(receipt.getString("token") == token && receipt.getString("package") == context.packageName)
        check(receipt.getString("phase") == "held" && receiptPrefs.getString("phase", null) == "held")
        check(receiptPrefs.getString("token", null) == token)
        val id = receipt.getLong("id")
        val fixture = File(receipt.getString("fixture"))
        check(fixture.parentFile!!.canonicalFile == context.cacheDir.canonicalFile)
        check(fixture.name == "qa-interrupt-$token.png")
        val credentialsName = receipt.getString("credentials")
        check(credentialsName == "qa_interrupt_empty_$token")
        // Inject an empty isolated credential store before constructing any production VM.
        repo = VideoRepository(context, analysisClient = Call.Factory {
            requests.incrementAndGet(); error("Device recovery must not issue any network request")
        }, credentialPreferences = context.getSharedPreferences(credentialsName, 0))
        assertEquals("The host must stop a genuinely unfinished production row", "processing", row(id).status)
        assertNull(io { repo.analysisDao.getAnalysisForVideoDirect(id) })
        assertEquals(receipt.getString("sha256"), digest(fixture.readBytes()))
        repo.prefs.edit().putString("llm_provider", "local").putBoolean("auto_ingest_enabled", false).commit()
        io { repo.settingsDao.insertSettings(AppSettings(ollamaEnabled = false)) }
        mount() // Startup recovery must requeue the interrupted production processing state.
        await { row(id).status == "queued" && vm.allVideos.value.any { it.id == id && it.status == "queued" } }
        assertNull("Startup recovery must not fabricate analysis", io { repo.analysisDao.getAnalysisForVideoDirect(id) })
        assertNull(row(id).errorMessage)
        assertEquals(receipt.getString("notes"), row(id).manualTranscript)
        assertEquals(receipt.getString("review"), row(id).reviewDecision)
        navigate("nav_home")
        compose.onNodeWithContentDescription("Start Batch").performClick()
        await { row(id).status == "processed" && vm.processor.processorState.value == VideoProcessor.State.FINISHED }
        compose.onNodeWithText("Batch: FINISHED").assertExists()
        assertEquals("fallback", io { repo.analysisDao.getAnalysisForVideoDirect(id)?.source })
        assertEquals(1, io { repo.analysisDao.getAllAnalysis().first().count { it.videoId == id } })
        assertEquals(0, requests.get())
        assertEquals(receipt.getString("notes"), row(id).manualTranscript)
        assertEquals("keep", row(id).reviewDecision)
        assertEquals(receipt.getString("sha256"), digest(fixture.readBytes()))
        val saved = io { requireNotNull(repo.analysisDao.getAnalysisForVideoDirect(id)) }
        compose.onNodeWithContentDescription("Start Batch").performClick()
        compose.waitForIdle()
        assertEquals(saved, io { repo.analysisDao.getAnalysisForVideoDirect(id) })
        assertEquals(1, io { repo.analysisDao.getAllAnalysis().first().count { it.videoId == id } })
        // Cleanup happens only after verification succeeds; failures retain the receipt and evidence.
        compose.runOnUiThread { store.clear() }
        io {
            repo.analysisDao.deleteAnalysisForVideo(id)
            repo.frameDao.deleteFramesForVideo(id)
            repo.videoDao.deleteVideo(row(id))
            if (receipt.getBoolean("hadSettings")) {
                val old = receipt.getJSONObject("settings")
                repo.settingsDao.insertSettings(AppSettings(ollamaEnabled = old.getBoolean("enabled"),
                    ollamaEndpointUrl = old.getString("endpoint"), ollamaModelName = old.getString("model"),
                    requestTimeoutSeconds = old.getInt("timeout"), confirmedNonLocalEndpointWarning = old.getBoolean("warning")))
            } else VideoDatabase.getDatabase(context).openHelper.writableDatabase.execSQL("DELETE FROM app_settings WHERE id = 1")
        }
        repo.prefs.edit().apply {
            if (receipt.getBoolean("hadProvider")) putString("llm_provider", receipt.getString("provider")) else remove("llm_provider")
            if (receipt.getBoolean("hadAuto")) putBoolean("auto_ingest_enabled", receipt.getBoolean("auto")) else remove("auto_ingest_enabled")
        }.commit()
        context.deleteSharedPreferences(credentialsName)
        check(fixture.delete())
        receiptPrefs.edit().clear().commit()
        check(receiptFile.delete())
    }

    private fun persist(receipt: JSONObject) {
        val temp = File(receiptFile.parentFile, receiptFile.name + ".tmp")
        temp.writeText(receipt.toString())
        check(temp.renameTo(receiptFile))
        check(receiptPrefs.edit().putString("token", receipt.getString("token"))
            .putString("phase", receipt.getString("phase")).putLong("id", receipt.getLong("id")).commit())
    }
    private fun mount() = compose.runOnUiThread {
        vm = VideoViewModel(context as Application, repo)
        store.put("interrupt", vm)
        compose.activity.setContent { MaterialTheme { MainScreen(vm, false, {}) } }
    }
    private fun navigate(tag: String) {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag(tag).performClick()
    }
    private fun row(id: Long) = io { requireNotNull(repo.videoDao.getVideoByIdDirect(id)) }
    private fun await(condition: () -> Boolean) = compose.waitUntil(90000, condition)
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private inner class HeldFactory : Call.Factory {
        @Volatile var executed = false
        override fun newCall(request: Request): Call = object : Call {
            private var cancelled = false
            override fun request() = request
            override fun execute(): Response = error("Only cancellable async fixture allowed")
            override fun enqueue(callback: Callback) { requests.incrementAndGet(); executed = true }
            override fun cancel() { cancelled = true }
            override fun isExecuted() = executed
            override fun isCanceled() = cancelled
            override fun timeout() = Timeout.NONE
            override fun clone(): Call = newCall(request)
        }
    }
}
