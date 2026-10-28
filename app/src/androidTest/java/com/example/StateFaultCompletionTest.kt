package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import com.example.data.*
import com.example.ui.VideoViewModel
import kotlinx.coroutines.CompletableDeferred
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
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * DRAFT: compile/run only against the empty .qa.preview emulator application.
 * Existing debug ComponentActivity hosts the unmodified production MainScreen with injected VM.
 * Requests are intercepted in memory; these tests do NOT establish real network/provider proof.
 * No secure_app_prefs is accessed. Fake credentials live in per-test ordinary QA preferences.
 */
class StateFaultCompletionTest {
    @get:Rule val compose = createAndroidComposeRule<InventoryRecreationTestActivity>()
    private val context get() = compose.activity.applicationContext
    private lateinit var repo: VideoRepository
    private lateinit var vm: VideoViewModel
    private val store = ViewModelStore()
    private val ids = mutableListOf<Long>()
    private val files = mutableListOf<File>()
    private lateinit var credentialsName: String
    private var oldSettings: AppSettings? = null
    private var oldProvider: String? = null
    private var oldAuto = false
    private var oldFolders: Set<String>? = null
    private val wire = Wire()
    private val source = Source()

    @Before fun setup() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview"))
        check(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("rea_runtime") == "emulator")
        val db = VideoDatabase.getDatabase(context)
        check(io { db.videoItemDao().getAllVideosDirect().isEmpty() }) { "Install isolated empty QA preview before this suite" }
        credentialsName = "qa_state_fault_${System.nanoTime()}"
        val credentials = context.getSharedPreferences(credentialsName, 0)
        credentials.edit().putString("llm_api_key", "fake-qa-key-never-valid").commit()
        repo = VideoRepository(context, mediaInventorySource = source, analysisClient = wire, credentialPreferences = credentials)
        oldProvider = repo.prefs.getString("llm_provider", null)
        oldAuto = repo.prefs.getBoolean("auto_ingest_enabled", false)
        oldFolders = repo.prefs.getStringSet("target_folders", null)?.toSet()
        repo.prefs.edit().putString("llm_provider", "ollama_local").putBoolean("auto_ingest_enabled", false).remove("target_folders").commit()
        oldSettings = io { repo.settingsDao.getSettingsDirect() }
        io { repo.settingsDao.insertSettings(AppSettings(ollamaEnabled = true, ollamaEndpointUrl = "http://127.0.0.1:1/api/generate", ollamaModelName = "qa-model")) }
        compose.runOnUiThread {
            vm = VideoViewModel(context as Application, repo)
            store.put("qa", vm)
            compose.activity.setContent { MaterialTheme { MainScreen(vm, false, {}) } }
        }
        compose.waitForIdle()
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription("Close menu").performClick()
    }

    @After fun cleanup() {
        if (::vm.isInitialized) compose.runOnUiThread { store.clear() }
        if (::repo.isInitialized) {
            io {
                ids.forEach { id ->
                    repo.analysisDao.deleteAnalysisForVideo(id)
                    repo.frameDao.deleteFramesForVideo(id)
                    repo.videoDao.getVideoByIdDirect(id)?.let { repo.videoDao.deleteVideo(it) }
                }
                oldSettings?.let { repo.settingsDao.insertSettings(it) }
            }
            repo.prefs.edit().apply {
                if (oldProvider == null) remove("llm_provider") else putString("llm_provider", oldProvider)
                putBoolean("auto_ingest_enabled", oldAuto)
                if (oldFolders == null) remove("target_folders") else putStringSet("target_folders", oldFolders)
            }.commit()
        }
        files.forEach { it.delete() }
        if (::credentialsName.isInitialized) context.deleteSharedPreferences(credentialsName)
    }

    @Test fun rejectedMalformedEmptyAndTimeoutResponsesFallBackThenExplicitResetRetries() {
        // T114–T117, T191, T194–T195; controlled transport classification, not live cloud proof.
        for ((code, body, failure) in listOf(
            Triple(401, "denied", null), Triple(403, "denied", null),
            Triple(200, "", null), Triple(200, "not-json", null),
            Triple(200, "{\"response\":\"{}\"}", null),
            Triple(200, "", java.net.SocketTimeoutException("fixture timeout"))
        )) {
            val video = fixture()
            wire.responseCode = code; wire.responseBody = body; wire.failure = failure
            processInPreview(video)
            finished(video.id)
            assertEquals("fallback", analysis(video.id)?.source)
            showOutput()
            compose.onNodeWithText("Deterministic fallback generated locally.").assertExists()
            closeOutput()
            val count = wire.calls.size
            compose.runOnUiThread { vm.processSingle(row(video.id)) }
            compose.waitForIdle()
            assertEquals("Completed items must not silently reprocess", count, wire.calls.size)
            compose.runOnUiThread { vm.resetVideo(video.id) }
            await { row(video.id).status == "new" && analysis(video.id) == null }
            wire.responseCode = 200; wire.responseBody = success(); wire.failure = null
            processInPreview(row(video.id))
            finished(video.id)
            assertEquals("QA provider answer", analysis(video.id)?.descriptionLine1)
            assertEquals("ollama", analysis(video.id)?.source)
            // Archive completed fixture to keep the next Queue Output selector unambiguous.
            io { repo.videoDao.updateVideo(row(video.id).copy(status = "archived")) }
        }
    }

    @Test fun pausedVisibleWorkCancelsRequestWithoutFabricatingResultAndResumesOnce() {
        // T060–T065, T188. Actual image decoding/OCR precedes the controlled pending request.
        val video = fixture()
        wire.hold = true
        processInPreview(video)
        await { wire.calls.firstOrNull()?.executed == true }
        compose.onNodeWithText("Batch: PROCESSING").assertExists()
        home()
        compose.onNodeWithContentDescription("Pause").performClick()
        await { row(video.id).status == "queued" && vm.processor.activeVideoId.value == null }
        compose.onNodeWithText("Batch: PAUSED").assertExists()
        assertTrue(wire.calls.single().cancelled)
        assertNull(analysis(video.id))
        wire.hold = false
        compose.onNodeWithContentDescription("Resume").performClick()
        finished(video.id)
        assertEquals(2, wire.calls.size)
        assertEquals(1, io { repo.analysisDao.getAllAnalysis().first().count { it.videoId == video.id } })
    }

    @Test fun queuedItemChangedToLaterIsSkippedUntilKeptAgain() {
        // T026–T030, T058–T059, T180, T187, T218.
        val first = fixture(); val second = fixture()
        wire.hold = true
        await { vm.allVideos.value.size == 2 }
        compose.onNodeWithContentDescription("Start Batch").performClick()
        await { wire.calls.size == 1 }
        val active = vm.processor.activeVideoId.value!!
        val waiting = if (active == first.id) second else first
        chooseVisibleDecision(waiting.id, "Keep", "Later")
        await { row(waiting.id).reviewDecision == "review_later" }
        wire.hold = false; wire.calls.single().answer()
        await { vm.processor.processorState.value == VideoProcessor.State.FINISHED }
        assertNull(analysis(waiting.id))
        assertEquals(1, wire.calls.size)
        chooseVisibleDecision(waiting.id, "Later", "Keep")
        await { row(waiting.id).reviewDecision == "keep" && vm.allVideos.value.any { it.id == waiting.id && it.reviewDecision == "keep" && it.status in setOf("new", "queued") } }
        home()
        compose.onNodeWithContentDescription("Process Next").performClick()
        finished(waiting.id)
        assertEquals(2, wire.calls.size)
    }

    @Test fun activeDecisionAndNotesEditsSurviveCompletionWithoutRewritingOriginalMedia() {
        // T188: let this active item finish; its new Later decision applies to subsequent work.
        // Actual Sources decision dropdown and Queue Preview notes/save controls while HTTP is held.
        val video = fixture()
        val original = files.single().readBytes()
        val note = "QA note edited while the provider request was already pending"
        wire.hold = true
        processInPreview(video)
        await { wire.calls.singleOrNull()?.executed == true && row(video.id).status == "processing" }
        compose.onNodeWithText("Batch: PROCESSING").assertExists()
        chooseVisibleDecision(video.id, "Keep", "Later")
        await { row(video.id).reviewDecision == "review_later" }
        assertNull(analysis(video.id))
        saveNotesWhileProcessing(video.id, note)
        await { row(video.id).manualTranscript == note }
        assertEquals("processing", row(video.id).status)
        // saveEdits may create an edited analysis; the original pending provider request replaces it.
        wire.hold = false
        wire.calls.single().answer()
        finished(video.id)
        val completed = row(video.id)
        assertEquals("review_later", completed.reviewDecision)
        assertEquals(note, completed.manualTranscript)
        assertEquals("result_saved", completed.lastCompletedStep)
        assertEquals("QA provider answer", analysis(video.id)?.descriptionLine1)
        assertEquals(1, wire.calls.size)
        assertEquals(1, io { repo.analysisDao.getAllAnalysis().first().count { it.videoId == video.id } })
        assertArrayEquals("Processing and review edits must leave original bytes unchanged", original, files.single().readBytes())
        // Home batch sees the fresh Later decision and must not request this completed item again.
        home()
        compose.onNodeWithContentDescription("Start Batch").performClick()
        await { vm.processor.processorState.value == VideoProcessor.State.FINISHED }
        assertEquals(1, wire.calls.size)
        assertEquals("review_later", row(video.id).reviewDecision)
    }

    @Test fun noKeepItemsFinishWithoutAnalysisOrReviewMutation() {
        // T053, T059, T219. Uses the actual Home controls.
        val video = fixture("unreviewed")
        await { vm.allVideos.value.any { it.id == video.id } }
        compose.onNodeWithContentDescription("Start Batch").performClick()
        await { vm.processor.processorState.value == VideoProcessor.State.FINISHED }
        compose.onNodeWithText("Batch: FINISHED").assertExists()
        compose.onNodeWithContentDescription("Pause Batch").performClick()
        assertEquals("unreviewed", row(video.id).reviewDecision)
        assertEquals("new", row(video.id).status)
        assertNull(analysis(video.id)); assertTrue(wire.calls.isEmpty())
    }

    @Test fun repeatedScanIsSingleFlightAndSourceFailureCanBeRetried() {
        // T014, T022–T023. Injected source proves VM/UI state, not MediaStore pending-file behavior.
        source.gate = CompletableDeferred()
        compose.runOnUiThread { vm.scanVideos(); vm.scanVideos() }
        await { source.reads == 1 && vm.isScanning.value }
        compose.onNodeWithContentDescription("Scan Videos").assertIsNotEnabled()
        source.failure = IOException("fixture source unavailable")
        source.gate!!.complete(Unit)
        await { !vm.isScanning.value }
        assertEquals("error", vm.lastScanResult.value?.status)
        compose.onNodeWithContentDescription("Scan Videos").assertIsEnabled()
        source.gate = null; source.failure = null
        compose.runOnUiThread { vm.scanVideos() }
        await { source.reads == 2 && !vm.isScanning.value }
        assertNotEquals("error", vm.lastScanResult.value?.status)
        assertEquals(0, io { repo.videoDao.getAllVideosDirect().size })
    }

    private fun fixture(decision: String = "keep"): VideoItem {
        val file = File(context.cacheDir, "qa-state-${System.nanoTime()}.png").also { files += it }
        Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            file.outputStream().use { check(compress(Bitmap.CompressFormat.PNG, 100, it)) }
            recycle()
        }
        val uri = Uri.fromFile(file).toString()
        io { repo.videoDao.insertVideos(listOf(VideoItem(mediaStoreId = -System.nanoTime(), uriString = uri,
            displayName = file.name, relativePath = null, mimeType = "image/png", sizeBytes = file.length(),
            durationMs = null, dateAdded = null, dateModified = null, reviewDecision = decision, mediaCollection = "image"))) }
        return io { requireNotNull(repo.videoDao.getVideoByUri(uri)) }.also { ids += it.id }
    }
    private fun home() {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("nav_home").performClick()
    }
    private fun queue() {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("nav_queue").performClick()
    }
    private fun processInPreview(video: VideoItem) {
        await { vm.allVideos.value.any { it.id == video.id && it.status == video.status } }
        queue()
        // Queue Preview is the actual production action that opens local modal state.
        // Only this test's current non-archived fixture remains in Queue.
        compose.onNodeWithContentDescription("Preview").performQaQueueScrollTo(compose).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Process file").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Process file").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Close").performClick()
    }
    private fun showOutput() {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("nav_queue").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Output").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Output").performQaQueueScrollTo(compose).performClick()
    }
    private fun closeOutput() {
        compose.onNodeWithContentDescription("Close").performClick()
    }
    private fun chooseVisibleDecision(id: Long, current: String, next: String) {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("nav_sources").performClick()
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription("Close menu").performClick()
        if (compose.onAllNodesWithContentDescription("Show all sources").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription("Show all sources").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("source_category_Other").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("source_category_Other").performScrollTo().performClick()
        val decision = hasClickAction() and hasText(current) and hasAnyAncestor(hasTestTag("source_media_$id"))
        compose.onNode(decision).performScrollTo().performClick()
        compose.onAllNodesWithText(next).onLast().performClick()
        val value = if (next == "Later") "review_later" else "keep"
        await { row(id).reviewDecision == value && vm.allVideos.value.any { it.id == id && it.reviewDecision == value } }
        compose.onNode(hasClickAction() and hasText(next) and hasAnyAncestor(hasTestTag("source_media_$id"))).assertExists()
    }
    private fun saveNotesWhileProcessing(id: Long, notes: String) {
        queue()
        // This active-edit test has one fixture, so Preview identifies the owned row unambiguously.
        compose.onNodeWithContentDescription("Preview").performQaQueueScrollTo(compose).performClick()
        compose.onAllNodes(hasSetTextAction()).onFirst().performScrollTo().performTextReplacement(notes)
        dismissQaKeyboard(compose.activity)
        compose.onNode(hasClickAction() and hasContentDescription("Save edits")).performScrollTo().performClick()
        await { row(id).manualTranscript == notes }
        compose.onNodeWithContentDescription("Close").performClick()
    }
    private fun finished(id: Long) = await { row(id).status == "processed" && vm.processor.processorState.value == VideoProcessor.State.FINISHED }
    private fun row(id: Long) = io { requireNotNull(repo.videoDao.getVideoByIdDirect(id)) }
    private fun analysis(id: Long) = io { repo.analysisDao.getAnalysisForVideoDirect(id) }
    private fun await(condition: () -> Boolean) = compose.waitUntil(90000, condition)
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun success() = JSONObject().put("response", JSONObject()
        .put("description", JSONArray().put("QA provider answer").put("Controlled fixture response"))
        .put("categoryLevel1", "Technical").put("categoryLevel2", "Testing").put("categoryLevel3", "Fault recovery").toString()).toString()

    private class Source : MediaInventorySource {
        @Volatile var reads = 0
        @Volatile var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        override fun currentAccessState() = "Full access"
        override suspend fun readSnapshot(): MediaInventorySnapshot {
            reads++; gate?.await(); failure?.let { throw it }
            return MediaInventorySnapshot("Full access", emptyList(), true)
        }
    }
    private inner class Wire : Call.Factory {
        val calls = CopyOnWriteArrayList<FakeCall>()
        var responseCode = 200
        var responseBody: String? = null
        var failure: IOException? = null
        @Volatile var hold = false
        override fun newCall(request: Request): Call = FakeCall(request).also { calls += it }
        inner class FakeCall(private val req: Request) : Call {
            @Volatile var cancelled = false
            @Volatile var executed = false
            private lateinit var callback: Callback
            override fun request() = req
            override fun execute(): Response = error("No blocking or real network allowed")
            override fun enqueue(responseCallback: Callback) {
                callback = responseCallback; executed = true
                if (!hold) answer()
            }
            fun answer() {
                if (cancelled) return
                failure?.let { callback.onFailure(this, it); return }
                callback.onResponse(this, Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                    .code(responseCode).message("QA fixture").body((responseBody ?: success()).toResponseBody()).build())
            }
            override fun cancel() { cancelled = true }
            override fun isCanceled() = cancelled
            override fun isExecuted() = executed
            override fun timeout() = Timeout.NONE
            override fun clone(): Call = FakeCall(req)
        }
    }
}
