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
class GapProcessingJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val db get() = VideoDatabase.getDatabase(context)
    private val dao get() = db.videoItemDao()
    private val prefs get() = context.getSharedPreferences("app_prefs", 0)
    private val token = "GapProcessingQA${System.nanoTime()}"
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


    @Test fun t053_t219_sameLaterIsBlockedFromQueuePreviewAndHomeWithoutMutation() {
        val later = photo("a_later", "review_later")
        val fresh = photo("b_new", "unreviewed")
        val before = listOf(row(later.id), row(fresh.id))
        launch(); navigate("Queue")
        val process = action(later, "Process")
        if (!process.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)) process.performClick()
        action(later, "Preview").performClick()
        val previewProcess = compose.onNode(hasContentDescription("Process file") and hasClickAction()).performScrollTo()
        if (!previewProcess.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)) previewProcess.performClick()
        click("Close"); navigate("Home"); click("Process Next"); click("Start Batch")
        await { compose.onAllNodesWithText("Batch: FINISHED", substring = false).fetchSemanticsNodes().isNotEmpty() }
        click("Pause Batch")
        compose.waitForIdle()
        assertEquals(before, listOf(row(later.id), row(fresh.id)))
        assertTrue(server.generates.isEmpty())
        assertTrue(io { db.analysisResultDao().getAllAnalysis().first().isEmpty() })
        compose.onAllNodes(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
        navigate("Queue"); action(later, "Output").assertIsNotEnabled()
        assertBytes()
    }

    @Test fun t061_pauseMixedBatchResumesBothOriginalItemsWithOneSavedResultEach() {
        val first = photo("a_keep", "keep"); val second = copyVideo("b_keep", "keep")
        val gate = CountDownLatch(1); server.reply = Plan(gate = gate)
        launch(); navigate("Home"); click("Start Batch")
        await { server.generates.size == 1 }
        val active = listOf(first, second).single { server.count(it) == 1 }
        val waiting = listOf(first, second).single { it.id != active.id }
        click("Pause Batch")
        await { row(active.id)?.status == "queued" }
        compose.onNodeWithText("Batch: PAUSED").assertIsDisplayed()
        assertNull(io { db.analysisResultDao().getAnalysisForVideoDirect(first.id) })
        assertNull(io { db.analysisResultDao().getAnalysisForVideoDirect(second.id) })
        assertEquals("keep", row(second.id)?.reviewDecision)
        server.reply = Plan(); gate.countDown(); click("Resume")
        completed(first); completed(second)
        assertEquals(2, server.count(active)); assertEquals(1, server.count(waiting))
        listOf(first, second).forEach { item -> assertEquals(1, io { db.analysisResultDao().getAllAnalysis().first().count { it.videoId == item.id } }); output(item, "ollama") }
        assertBytes()
    }

    @Test fun t063_rapidQueueDoubleTapThenHomeStartKeepsOneRequestAndOneResult() {
        val item = photo("rapid", "keep"); val gate = CountDownLatch(1); server.reply = Plan(gate = gate)
        launch(); navigate("Queue")
        action(item, "Process").performTouchInput { doubleClick() }
        await { server.count(item) == 1 }
        navigate("Home"); repeat(2) { click("Start Batch") }
        assertEquals(1, server.count(item)); assertNull(io { db.analysisResultDao().getAnalysisForVideoDirect(item.id) })
        gate.countDown(); completed(item); output(item, "ollama")
        assertEquals(1, server.count(item)); assertEquals(1, io { db.analysisResultDao().getAllAnalysis().first().count { it.videoId == item.id } })
        assertEquals(1, io { dao.getAllVideosDirect().count { it.uriString == item.uriString } }); assertBytes()
    }

    @Test fun t187_waitingKeepChangedThroughSourcesToLaterIsDequeuedUntilKeptAgain() {
        val a = photo("a_active", "keep"); val b = photo("b_waiting", "keep")
        io { listOf(a, b).forEach { item -> dao.updateVideo(requireNotNull(row(item.id)).copy(status = "queued")) } }
        val gate = CountDownLatch(1); server.reply = Plan(gate = gate)
        launch(); navigate("Home"); click("Start Batch"); await { server.generates.size == 1 }
        val first = listOf(a, b).single { server.count(it) == 1 }
        val waiting = listOf(a, b).single { it.id != first.id }
        showOverview(); await { runCatching { metric("Queued", "02") }.isSuccess }
        chooseReview(waiting, "Later"); await { row(waiting.id)?.reviewDecision == "review_later" && row(waiting.id)?.status == "new" }
        navigate("Queue"); action(waiting, "Output").assertIsNotEnabled()
        navigate("Home"); showOverview()
        assertEquals("processing", row(first.id)?.status)
        await { runCatching { metric("Queued", "01") }.isSuccess }
        gate.countDown(); completed(first)
        await { runCatching { metric("Queued", "00") }.isSuccess }
        assertEquals(0, server.count(waiting)); assertNull(io { db.analysisResultDao().getAnalysisForVideoDirect(waiting.id) })
        chooseReview(waiting, "Keep"); server.reply = Plan(); navigate("Home"); click("Start Batch")
        completed(waiting); output(waiting, "ollama"); assertEquals(1, server.count(waiting)); assertBytes()
    }

    @Test fun t188_activeSourcesLaterAndUiNotesSurviveCompletionAndNextBatchSkipsIt() {
        val item = photo("active_edit", "keep"); val gate = CountDownLatch(1); server.reply = Plan(gate = gate)
        launch(); navigate("Home"); click("Start Batch"); await { server.count(item) == 1 }
        chooseReview(item, "Later")
        navigate("Queue"); action(item, "Preview").performClick()
        val note = "QA latest note entered during processing"
        compose.onAllNodes(hasSetTextAction()).onFirst().performScrollTo().performTextReplacement(note)
        dismissQaKeyboard(requireNotNull(activity)); click("Save edits", scroll = true); click("Close")
        await { row(item.id)?.manualTranscript == note && row(item.id)?.reviewDecision == "review_later" }
        gate.countDown()
        await(60000) { row(item.id)?.status == "processed" }
        await { compose.onAllNodesWithText("Batch: FINISHED").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(note, row(item.id)?.manualTranscript); assertEquals("review_later", row(item.id)?.reviewDecision)
        val saved = io { requireNotNull(db.analysisResultDao().getAnalysisForVideoDirect(item.id)) }
        val requests = server.count(item)
        navigate("Home"); click("Start Batch"); compose.waitForIdle()
        assertEquals(requests, server.count(item)); assertEquals(saved, io { db.analysisResultDao().getAnalysisForVideoDirect(item.id) })
        chooseReview(item, null); compose.onNodeWithText("Later", substring = false).assertExists()
        navigate("Queue"); action(item, "Preview").performClick()
        compose.onAllNodes(hasSetTextAction()).onFirst().performScrollTo().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString(note)))
        click("Close"); assertBytes()
    }

    @Test fun t098_deviceSettingsSaveReopenAndProcessSameKeptImageWithoutNetwork() {
        val item = photo("device", "keep"); launch()
        click("Settings"); chooseProvider("Device")
        setting("Wait limit").performScrollTo().performTextReplacement("30")
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress)).performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.20f) }
        setting("Instructions").performScrollTo().performTextReplacement("Summarize QA text")
        dismissQaKeyboard(requireNotNull(activity)); click("Save settings", scroll = true)
        await { prefs.getString("llm_provider", null) == "local" && io { db.appSettingsDao().getSettingsDirect()?.requestTimeoutSeconds == 30 } }
        click("Settings")
        compose.onNodeWithText("Device", substring = false).assertExists()
        setting("Wait limit").performScrollTo().assertTextContains("30")
        setting("Instructions").performScrollTo().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("Summarize QA text")))
        assertEquals(0.20f, prefs.getFloat("llm_temperature", -1f), 0.0001f)
        compose.onNodeWithText("Uses simple word matching on this phone. No AI model is installed.").performScrollTo().assertIsDisplayed()
        click("Close"); processPreview(item); click("Close"); completed(item); output(item, "fallback")
        assertTrue(server.generates.isEmpty()); assertBytes()
    }

    @Test fun t115_actualFiveSecondDeadlineFallsBackThenVisibleResetRetries() = faultRetry("timeout", Plan(delayMs = 7000))
    @Test fun t116_midResponseDisconnectFallsBackThenVisibleResetRetries() = faultRetry("disconnect", Plan(dropMidBody = true))
    @Test fun t117_savedSettingsMalformedReplyFallsBackThenVisibleResetRetries() = faultRetry("malformed", Plan(bodyKind = "malformed"))
    @Test fun t194_http403FallsBackThenVisibleResetRetries() = faultRetry("forbidden", Plan(status = 403))
    @Test fun t195_emptyBodyFallsBackThenVisibleResetRetries() = faultRetry("empty", Plan(bodyKind = "empty"))

    @Test fun t191_savedThirtySecondTimeoutShowsProgressAndAcceptsSlowSuccessOnce() {
        val item = photo("slow_success", "keep"); server.reply = Plan(delayMs = 3000)
        launch(); configurePc(30); processPreview(item); click("Close"); navigate("Home")
        await { server.count(item) == 1 }; compose.onNodeWithText("Batch: PROCESSING").assertIsDisplayed()
        completed(item); output(item, "ollama")
        assertEquals(1, server.count(item)); assertEquals(1, io { db.analysisResultDao().getAllAnalysis().first().count { it.videoId == item.id } }); assertBytes()
    }

    private fun faultRetry(label: String, plan: Plan) {
        val item = photo(label, "keep"); server.reply = plan
        launch(); configurePc(5)
        processPreview(item); click("Close"); navigate("Home")
        await { server.count(item) >= 1 }
        if (label == "timeout") compose.onNodeWithText("Batch: PROCESSING").assertIsDisplayed()
        completed(item)
        if (label == "timeout") {
            val began = requireNotNull(server.receivedAt[item.manualTranscript])
            val elapsed = android.os.SystemClock.elapsedRealtime() - began
            assertTrue("Fallback must follow a real elapsed five-second network deadline: $elapsed", elapsed >= 4500)
            assertTrue("Configured five-second request must settle without an unbounded wait: $elapsed", elapsed < 15000)
        }
        output(item, "fallback")
        assertEquals("Deterministic fallback generated locally.", io { db.analysisResultDao().getAnalysisForVideoDirect(item.id)?.descriptionLine1 })
        val attempts = server.count(item)
        navigate("Queue"); action(item, "Preview").performClick(); click("Clear saved result", scroll = true)
        await { row(item.id)?.status == "new" && io { db.analysisResultDao().getAnalysisForVideoDirect(item.id) == null } }
        server.reply = Plan(); click("Process file", scroll = true); click("Close"); completed(item); output(item, "ollama")
        assertEquals(attempts + 1, server.count(item)); assertEquals(1, io { db.analysisResultDao().getAllAnalysis().first().count { it.videoId == item.id } }); assertBytes()
    }

    private fun configurePc(timeout: Int) {
        click("Settings"); compose.onNodeWithText("PC", substring = false).assertExists()
        setting("AI address").performScrollTo().performTextReplacement(server.url + "/api/generate")
        dismissQaKeyboard(requireNotNull(activity)); click("Test connection", scroll = true)
        await { compose.onAllNodesWithText("Connected", substring = false).fetchSemanticsNodes().isNotEmpty() }
        click("Sync models tag list", scroll = true)
        val model = "qa-controlled-loopback"
        await { compose.onAllNodes(hasText(model, substring = false) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("Select model").fetchSemanticsNodes().isNotEmpty() }
        val anchor = compose.onNode(hasClickAction() and (hasText(model, substring = false) or hasText("Select model", substring = false) or hasText("Tap sync to fetch models", substring = false)))
        anchor.performScrollTo().performClick(); compose.onAllNodesWithText(model, substring = false).onLast().performClick()
        setting("Wait limit").performScrollTo().performTextReplacement(timeout.toString())
        dismissQaKeyboard(requireNotNull(activity)); click("Save settings", scroll = true)
        await { io { db.appSettingsDao().getSettingsDirect()?.let { it.ollamaEndpointUrl == server.url + "/api/generate" && it.ollamaModelName == model && it.requestTimeoutSeconds == timeout } == true } }
        click("Settings"); setting("AI address").assertTextContains(server.url + "/api/generate"); setting("Wait limit").performScrollTo().assertTextContains(timeout.toString())
        compose.onNodeWithText(model, substring = false).assertExists(); click("Close")
    }
    private fun setting(label: String) = if (label == "Instructions") compose.onNodeWithTag("settings_prompt") else compose.onNode(hasText(label) and hasSetTextAction())
    private fun chooseProvider(name: String) {
        val current = when { compose.onAllNodesWithText("PC", substring = false).fetchSemanticsNodes().isNotEmpty() -> "PC"; compose.onAllNodesWithText("Device", substring = false).fetchSemanticsNodes().isNotEmpty() -> "Device"; else -> error("Unexpected provider; direct Cloud account is out of scope") }
        compose.onNodeWithText(current, substring = false).performScrollTo().performClick(); compose.onAllNodesWithText(name, substring = false).onLast().performClick()
    }
    private fun chooseReview(item: VideoItem, choice: String?) {
        navigate("Sources")
        if (compose.onAllNodesWithContentDescription("Show all sources").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Show all sources").performClick()
        compose.onNodeWithTag("source_category_Camera").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("source_media_${item.id}"))
        if (choice != null) {
            compose.onNodeWithTag("review_decision_${item.id}").onChildren().filter(hasClickAction()).onFirst().performClick()
            compose.onAllNodesWithText(choice, substring = false).onLast().performClick()
        }
    }
    private fun showOverview() { if (compose.onAllNodesWithText("Queued", substring = false).fetchSemanticsNodes().isEmpty()) compose.onNodeWithText("Overview", substring = false).performClick() }
    private fun metric(label: String, count: String) { compose.onNodeWithText(label, substring = false, useUnmergedTree = true).onParent().assert(hasAnyDescendant(hasText(count, substring = false))) }

    private fun launch() {
        check(scenario == null)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { activity = it }
        compose.waitForIdle()
    }
    private fun processPreview(item: VideoItem) {
        navigate("Queue")
        action(item, "Preview").performClick()
        click("Process file", scroll = true)
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

    private data class Plan(val gate: CountDownLatch? = null, val delayMs: Long = 0, val drop: Boolean = false, val status: Int = 200, val bodyKind: String = "valid", val dropMidBody: Boolean = false)
    private class WireServer : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val sockets = CopyOnWriteArrayList<Socket>()
        val files = java.util.concurrent.ConcurrentHashMap<String, String>()
        val generates = CopyOnWriteArrayList<String>()
        val receivedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
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
                            files.entries.single { prompt.contains("Filename: ${it.key}\n") }.value.also { generates += it; receivedAt.putIfAbsent(it, android.os.SystemClock.elapsedRealtime()) }
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
                        val responseBody = if (!generate) body else when (plan.bodyKind) { "empty" -> ""; "malformed" -> "not-json"; else -> body }
                        val bytes = responseBody.toByteArray(Charsets.UTF_8)
                        client.getOutputStream().apply {
                            val status = if (generate) plan.status else 200
                            write("HTTP/1.1 $status QA\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            if (generate && plan.dropMidBody) write(bytes, 0, bytes.size / 2) else write(bytes)
                            flush()
                        }
                    } } catch (_: Exception) { /* Canceled/dropped owned fixture request; never proxy. */ }
                    finally { sockets -= socket }
                }, "qa-lifecycle-response").apply { isDaemon = true; start() }
            } }, "qa-lifecycle-listener").apply { isDaemon = true; start() }
        }
        override fun close() { closed = true; reply.gate?.countDown(); runCatching { listener.close() }; sockets.forEach { runCatching { it.close() } } }
    }
}
