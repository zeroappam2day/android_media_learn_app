package com.example

import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Draft: actual MainActivity launched only AFTER dedicated QA configuration and fixtures.
 * No injected host/VM/transport. The default production repository reads its known-empty
 * QA credential store normally; this test NEVER opens or inspects credential preferences.
 * Requires -e qa_empty_credentials_confirmed true from the isolated-package provisioner.
 * Real loopback HTTP fixture responses are controlled acceptance data, NOT live AI proof.
 * No external requests: PC endpoint is 127.0.0.1; automatic finding disabled; exact own roots.
 */
class ProcessingLifecycleCompletionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val db get() = VideoDatabase.getDatabase(context)
    private val dao get() = db.videoItemDao()
    private val prefs get() = context.getSharedPreferences("app_prefs", 0)
    private val token = "LifecycleQA${System.nanoTime()}"
    private val owned = linkedMapOf<Uri, ByteArray>()
    private val ids = mutableListOf<Long>()
    private val readOnlySources = linkedMapOf<Uri, ByteArray>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var activity: MainActivity? = null
    private var settingsBefore: AppSettings? = null
    private val prior = linkedMapOf<String, Any?>()
    private var configured = false
    private lateinit var server: WireServer

    @Before fun configureBeforeLaunchingActualMainActivity() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview"))
        check(InstrumentationRegistry.getArguments().getString("rea_runtime") == "emulator")
        check(InstrumentationRegistry.getArguments().getString("qa_empty_credentials_confirmed") == "true") {
            "Provisioner must confirm empty credentials in dedicated QA package; test never reads them"
        }
        check(io { dao.getAllVideosDirect().isEmpty() }) { "Empty dedicated QA media database required" }
        settingsBefore = io { db.appSettingsDao().getSettingsDirect() }
        listOf("llm_provider", "llm_system_prompt").forEach { key -> prior[key] = if (prefs.contains(key)) prefs.getString(key, null) else null }
        prior["llm_temperature"] = if (prefs.contains("llm_temperature")) prefs.getFloat("llm_temperature", 0f) else null
        prior["auto_ingest_enabled"] = if (prefs.contains("auto_ingest_enabled")) prefs.getBoolean("auto_ingest_enabled", false) else null
        prior["target_folders"] = prefs.getStringSet("target_folders", null)?.toSet()
        prior["llm_available_models"] = prefs.getStringSet("llm_available_models", null)?.toSet()
        configured = true
        server = WireServer()
        prefs.edit().putString("llm_provider", "ollama_local").putString("llm_system_prompt", "")
            .putFloat("llm_temperature", 0f).putBoolean("auto_ingest_enabled", false)
            .putStringSet("target_folders", setOf(tree("Pictures/$token"), tree("Movies/$token"))).commit()
        io { db.appSettingsDao().insertSettings(AppSettings(ollamaEnabled = true, ollamaEndpointUrl = server.url + "/api/generate",
            ollamaModelName = "qa-controlled-loopback", requestTimeoutSeconds = 30)) }
    }

    @After fun finishActivityBeforeRemovingOnlyOwnedRecordsAndMedia() {
        scenario?.close() // Production lifecycle cancels its actual viewModelScope/observers.
        if (::server.isInitialized) server.close()
        io {
            ids.forEach { id ->
                db.binDao().remove(id)
                db.extractedFrameDao().deleteFramesForVideo(id)
                db.analysisResultDao().deleteAnalysisForVideo(id)
                dao.getVideoByIdDirect(id)?.let { dao.deleteVideo(it) }
            }
            if (configured) {
                if (settingsBefore != null) db.appSettingsDao().insertSettings(requireNotNull(settingsBefore))
                else db.openHelper.writableDatabase.execSQL("DELETE FROM app_settings WHERE id = 1")
            }
        }
        owned.keys.forEach { context.contentResolver.delete(it, null, null) }
        if (configured) prefs.edit().apply {
            prior.forEach { (key, value) -> when (value) {
                null -> remove(key)
                is String -> putString(key, value)
                is Float -> putFloat(key, value)
                is Boolean -> putBoolean(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>) }
            } }
        }.commit()
    }

    @Test fun t058_t059_processNextRequiresKeepThenActualSourceChoiceEnablesOneSummary() {
        val item = photo("next", "unreviewed")
        launch()
        navigate("Home")
        click("Process Next")
        compose.waitForIdle()
        assertEquals("new", row(item.id)?.status)
        assertTrue(server.generates.isEmpty())
        navigate("Sources")
        compose.onNodeWithTag("source_category_Camera").performClick()
        compose.onNodeWithTag("review_decision_${item.id}").onChildren().filter(hasClickAction()).onFirst().performClick()
        compose.onAllNodesWithText("Keep", substring = false).onLast().performClick()
        await { row(item.id)?.reviewDecision == "keep" }
        navigate("Home")
        click("Process Next")
        completed(item)
        assertEquals(1, server.count(item))
        output(item, "ollama")
        assertBytes()
    }

    @Test fun t060_t061_t063_repeatedStartWhileHeldProcessesMixedKeepMediaOnceAndLeavesOthersUntouched() {
        val first = photo("a_keep", "keep")
        val clip = copyVideo("b_keep", "keep")
        val later = photo("c_later", "review_later")
        val fresh = photo("d_new", "unreviewed")
        val before = listOf(row(later.id), row(fresh.id))
        val gate = CountDownLatch(1)
        server.reply = Plan(gate = gate)
        launch(); navigate("Home"); click("Start Batch")
        await { server.generates.size == 1 }
        repeat(3) { click("Start Batch") }
        assertEquals(1, server.generates.size)
        gate.countDown()
        completed(first); completed(clip)
        await { compose.onAllNodesWithText("Batch: FINISHED").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, server.count(first)); assertEquals(1, server.count(clip))
        assertEquals(before, listOf(row(later.id), row(fresh.id)))
        assertNull(io { db.analysisResultDao().getAnalysisForVideoDirect(later.id) })
        assertNull(io { db.analysisResultDao().getAnalysisForVideoDirect(fresh.id) })
        output(first, "ollama"); output(clip, "ollama")
        assertBytes()
    }

    @Test fun t062_pauseCancelsHeldRequestAndActualResumeProducesOneFinalSavedAnalysis() {
        val item = photo("pause", "keep")
        val gate = CountDownLatch(1)
        server.reply = Plan(gate = gate)
        launch(); navigate("Home"); click("Start Batch")
        await { server.count(item) == 1 }
        click("Pause Batch")
        await { row(item.id)?.status == "queued" }
        compose.onNodeWithText("Batch: PAUSED").assertExists()
        assertNull(io { db.analysisResultDao().getAnalysisForVideoDirect(item.id) })
        server.reply = Plan()
        gate.countDown()
        click("Resume")
        completed(item)
        assertEquals("Canceled attempt plus explicit resumed attempt", 2, server.count(item))
        assertEquals(1, io { db.analysisResultDao().getAllAnalysis().first().count { it.videoId == item.id } })
        output(item, "ollama")
        assertBytes()
    }

    @Test fun t064_androidHomeAndReopenSameMainActivityPreserveHeldWorkAndCompletedOutput() {
        val item = photo("background", "keep")
        val gate = CountDownLatch(1)
        server.reply = Plan(gate = gate)
        launch(); navigate("Home"); click("Start Batch")
        await { server.count(item) == 1 }
        val originalActivity = requireNotNull(activity)
        scenario!!.onActivity { it.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        // Require an observed onStop boundary before reopening; immediate intents alone
        // could race without ever backgrounding the Activity.
        runBlocking { kotlinx.coroutines.withTimeout(10000) {
            while (scenario!!.state != androidx.lifecycle.Lifecycle.State.CREATED) kotlinx.coroutines.delay(50)
        } }
        // Real Android Home excursion; no synthetic onStop/onResume or alternate host.
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        runBlocking { kotlinx.coroutines.withTimeout(10000) {
            while (scenario!!.state != androidx.lifecycle.Lifecycle.State.RESUMED) kotlinx.coroutines.delay(50)
        } }
        scenario!!.onActivity { assertSame("Reopen must resume the same Activity instance", originalActivity, it) }
        gate.countDown()
        completed(item)
        assertEquals(1, server.count(item))
        output(item, "ollama")
        assertBytes()
    }

    @Test fun t194_t195_slowWithinFiveSecondsSucceedsAndDroppedConnectionFallbackNeedsExplicitResetRetry() {
        val slow = photo("slow", "keep")
        val drop = photo("drop", "keep")
        io { db.appSettingsDao().getSettingsDirect()!!.let { db.appSettingsDao().insertSettings(it.copy(requestTimeoutSeconds = 5)) } }
        server.reply = Plan(delayMs = 2000)
        launch()
        processPreview(slow)
        completed(slow)
        outputFromOpenPreview(slow, "ollama")
        server.reply = Plan(drop = true)
        processPreview(drop)
        completed(drop)
        val fallback = io { requireNotNull(db.analysisResultDao().getAnalysisForVideoDirect(drop.id)) }
        assertEquals("fallback", fallback.source)
        assertEquals("Deterministic fallback generated locally.", fallback.descriptionLine1)
        outputFromOpenPreview(drop, "fallback")
        val attemptsBeforeRetry = server.count(drop)
        assertTrue(attemptsBeforeRetry >= 1)
        navigate("Queue"); action(drop, "Preview").performClick()
        click("Reset model data", scroll = true)
        await { row(drop.id)?.status == "new" && io { db.analysisResultDao().getAnalysisForVideoDirect(drop.id) == null } }
        server.reply = Plan()
        click("Process media", scroll = true)
        completed(drop)
        assertEquals(attemptsBeforeRetry + 1, server.count(drop))
        outputFromOpenPreview(drop, "ollama")
        assertBytes()
    }

    private fun launch() {
        check(scenario == null)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { activity = it }
        compose.waitForIdle()
    }
    private fun processPreview(item: VideoItem) {
        navigate("Queue")
        action(item, "Preview").performClick()
        click("Process media", scroll = true)
    }
    private fun completed(item: VideoItem) {
        await(60000) { row(item.id)?.status in setOf("processed", "error") }
        assertEquals("Pipeline must complete", "processed", row(item.id)?.status)
        assertEquals("result_saved", row(item.id)?.lastCompletedStep)
        assertNull(row(item.id)?.errorMessage)
        assertEquals("keep", row(item.id)?.reviewDecision)
        assertEquals(item.uriString, row(item.id)?.uriString)
        // The persisted terminal row precedes the processor's final 600ms delay.
        // Wait for its actual visible settled state before another Process/Reset journey.
        await { compose.onAllNodesWithText("Batch: FINISHED", substring = false).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun outputFromOpenPreview(item: VideoItem, source: String) {
        click("Close")
        output(item, source)
    }
    private fun output(item: VideoItem, source: String) {
        val result = io { requireNotNull(db.analysisResultDao().getAnalysisForVideoDirect(item.id)) }
        assertEquals(source, result.source)
        if (source == "ollama") assertEquals("QA loopback answer ${item.manualTranscript}", result.descriptionLine1)
        navigate("Queue")
        action(item, "Output").assertIsEnabled().performClick()
        compose.onNodeWithText("Processed Result Summary: ${item.displayName}").assertIsDisplayed()
        compose.onNodeWithText(result.descriptionLine1, substring = false).assertExists()
        click("Close")
    }
    private fun photo(label: String, decision: String): VideoItem {
        val bitmap = Bitmap.createBitmap(800, 300, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(Color.WHITE)
            Canvas(bitmap).drawText("QA PHOTO RECEIPT", 30f, 140f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 54f })
            ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray() }
        } finally { bitmap.recycle() }
        return fixture(label, decision, bytes, video = false)
    }
    private fun copyVideo(label: String, decision: String): VideoItem {
        val source = context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DISPLAY_NAME} = ?", arrayOf("codex_emulator_workflow.mp4"), null)!!.use { cursor ->
            check(cursor.count == 1 && cursor.moveToFirst()) { "Provision exact approved synthetic MP4" }
            android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
        }
        val bytes = context.contentResolver.openInputStream(source)!!.use { it.readBytes() }
        readOnlySources[source] = bytes
        return fixture(label, decision, bytes, video = true)
    }
    private fun fixture(label: String, decision: String, bytes: ByteArray, video: Boolean): VideoItem {
        val name = "$label.${if (video) "mp4" else "png"}"
        val folder = "${if (video) "Movies" else "Pictures"}/$token/Camera/"
        val uri = requireNotNull(context.contentResolver.insert(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder); put(MediaStore.MediaColumns.IS_PENDING, 1) }))
        owned[uri] = bytes
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val marker = "${token}_$label"
        val item = VideoItem(mediaStoreId = android.content.ContentUris.parseId(uri), uriString = uri.toString(), displayName = name,
            relativePath = folder, mimeType = if (video) "video/mp4" else "image/png", sizeBytes = bytes.size.toLong(), durationMs = null,
            dateAdded = null, dateModified = null, status = "new", reviewDecision = decision, manualTranscript = marker,
            sourceCategory = "Camera", mediaCollection = if (video) "video" else "image")
        server.files[name] = marker
        return item.copy(id = io { dao.insertVideo(item) }.also { ids += it })
    }
    private fun action(item: VideoItem, label: String): SemanticsNodeInteraction {
        val eligible = io { dao.getAllVideosDirect() }.filter { it.isCurrentlyAvailable && !it.isPending && !it.isTrashed && it.status != "archived" }
        check(eligible.all { it.id in ids } && eligible.any { it.id == item.id })
        val queueTag = "queue_${item.id}_${label.lowercase()}"
        if (compose.onAllNodesWithTag(queueTag).fetchSemanticsNodes().size == 1) {
            return compose.onNodeWithTag(queueTag).performQaQueueScrollTo(compose).assertIsDisplayed()
        }
        val name = compose.onNodeWithText(item.displayName, substring = false).assertIsDisplayed().fetchSemanticsNode()
        val matcher = hasContentDescription(label) and hasClickAction()
        val nodes = compose.onAllNodes(matcher).fetchSemanticsNodes()
        if (eligible.size == 1 && nodes.size == 1) return compose.onAllNodes(matcher)[0].assertIsDisplayed()
        val nextTop = eligible.filter { it.id != item.id }.mapNotNull { other -> compose.onAllNodesWithText(other.displayName, substring = false)
            .fetchSemanticsNodes().singleOrNull()?.boundsInRoot?.takeIf { it.width > 0f && it.height > 0f }?.top }
            .filter { it > name.boundsInRoot.top }.minOrNull() ?: Float.POSITIVE_INFINITY
        val matches = nodes.indices.filter { nodes[it].boundsInRoot.center.y >= name.boundsInRoot.top && nodes[it].boundsInRoot.center.y < nextTop }
        check(matches.size == 1)
        return compose.onAllNodes(matcher)[matches.single()].assertIsDisplayed()
    }
    private fun click(description: String, scroll: Boolean = false) {
        val node = compose.onNode(hasContentDescription(description) and hasClickAction())
        if (scroll) node.performScrollTo()
        node.assertIsDisplayed().performClick()
    }
    private fun navigate(label: String) {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("nav_${label.lowercase()}").performClick()
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close menu").performClick()
        compose.waitForIdle()
    }
    private fun row(id: Long) = io { dao.getVideoByIdDirect(id) }
    private fun assertBytes() {
        (owned + readOnlySources).forEach { (uri, bytes) -> assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }) }
    }
    private fun tree(path: String) = "content://com.android.externalstorage.documents/tree/" + Uri.encode("primary:$path")
    private fun await(timeout: Long = 15000, predicate: () -> Boolean) = compose.waitUntil(timeout, predicate)
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private data class Plan(val gate: CountDownLatch? = null, val delayMs: Long = 0, val drop: Boolean = false)
    private class WireServer : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val sockets = CopyOnWriteArrayList<Socket>()
        val files = java.util.concurrent.ConcurrentHashMap<String, String>()
        val generates = CopyOnWriteArrayList<String>()
        @Volatile var reply = Plan()
        @Volatile private var closed = false
        val url = "http://127.0.0.1:${listener.localPort}"
        fun count(item: VideoItem) = generates.count { it == item.manualTranscript }
        init {
            Thread({ while (!closed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                sockets += socket
                Thread({
                    try { socket.use { client ->
                        client.soTimeout = 5000
                        val input = client.getInputStream()
                        fun headerLine(): String? {
                            val out = ByteArrayOutputStream()
                            while (true) {
                                val value = input.read()
                                if (value < 0) return if (out.size() == 0) null else out.toString("US-ASCII").trimEnd('\r')
                                if (value == 10) return out.toString("US-ASCII").trimEnd('\r')
                                out.write(value)
                            }
                        }
                        val request = headerLine() ?: return@use
                        var length = 0
                        while (true) {
                            val line = headerLine() ?: return@use
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        check(length in 0..1_000_000)
                        val bodyBytes = ByteArray(length)
                        var offset = 0
                        while (offset < length) { val n = input.read(bodyBytes, offset, length - offset); if (n < 0) break; offset += n }
                        val path = request.split(' ').getOrNull(1).orEmpty()
                        val generate = request.startsWith("POST ") && path == "/api/generate"
                        val plan = reply
                        val marker = if (generate) {
                            val prompt = JSONObject(String(bodyBytes, 0, offset, Charsets.UTF_8)).getString("prompt")
                            files.entries.single { prompt.contains("Filename: ${it.key}\n") }.value.also { generates += it }
                        } else ""
                        if (generate) {
                            plan.gate?.await(25, TimeUnit.SECONDS)
                            if (plan.delayMs > 0) Thread.sleep(plan.delayMs)
                            if (plan.drop) return@use
                        }
                        val result = JSONObject().put("description", JSONArray().put("QA loopback answer $marker").put("Controlled local transport fixture."))
                            .put("categoryLevel1", "Technical").put("categoryLevel2", "QA").put("categoryLevel3", "Fixture")
                            .put("headings", JSONArray()).put("urls", JSONArray()).put("codeOrCommands", JSONArray())
                            .put("evidenceNotes", JSONArray().put(marker))
                        val body = if (generate) JSONObject().put("response", result.toString()).toString()
                            else JSONObject().put("models", JSONArray().put(JSONObject().put("name", "qa-controlled-loopback"))).toString()
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); write(bytes); flush()
                        }
                    } } catch (_: Exception) { /* Canceled/dropped owned fixture request; never proxy. */ }
                    finally { sockets -= socket }
                }, "qa-lifecycle-response").apply { isDaemon = true; start() }
            } }, "qa-lifecycle-listener").apply { isDaemon = true; start() }
        }
        override fun close() { closed = true; reply.gate?.countDown(); runCatching { listener.close() }; sockets.forEach { runCatching { it.close() } } }
    }
}
