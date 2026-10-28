package com.example

import android.app.Application
import android.content.ContentValues
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import android.view.accessibility.AccessibilityEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import com.example.ui.VideoViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Draft only. Actual production MainScreen/UI and real VM/repository in test-manifest
 * ComponentActivity; owned empty credential prefs injected before VM construction.
 * No original MainActivity/default credential repository; no provider HTTP requests.
 * Requires empty QA media DB. No scans/global resets/unowned file mutation.
 * Search is Folders-only in production: no invented Queue search/status-filter route.
 * Rename is automatic suggestion only: no reachable blank/bad/unicode text-entry form.
 * Seeded analysis/OCR used for viewing/rename are synthetic, never claimed as inference.
 */
@RunWith(AndroidJUnit4::class)
class QueueCompletionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val db get() = VideoDatabase.getDatabase(context)
    private val dao get() = db.videoItemDao()
    private val prefs get() = context.getSharedPreferences("app_prefs", 0)
    private val token = "QueueQA${System.nanoTime()}"
    private val owned = linkedMapOf<Uri, ByteArray>()
    private val ids = mutableListOf<Long>()
    private var settingsBefore: AppSettings? = null
    private var providerBefore: String? = null
    private var autoBefore: Boolean? = null
    private var configured = false
    private lateinit var repo: VideoRepository
    private lateinit var vm: VideoViewModel

    @Before fun installSafeProductionScreen() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview"))
        check(InstrumentationRegistry.getArguments().getString("rea_runtime") == "emulator")
        check(io { dao.getAllVideosDirect().isEmpty() }) { "Empty QA media DB required; existing rows preserved" }
        settingsBefore = io { db.appSettingsDao().getSettingsDirect() }
        providerBefore = if (prefs.contains("llm_provider")) prefs.getString("llm_provider", null) else null
        autoBefore = if (prefs.contains("auto_ingest_enabled")) prefs.getBoolean("auto_ingest_enabled", false) else null
        configured = true
        prefs.edit().putString("llm_provider", "local").putBoolean("auto_ingest_enabled", false).commit()
        io { db.appSettingsDao().insertSettings((settingsBefore ?: AppSettings()).copy(ollamaEnabled = false)) }
        val empty = context.getSharedPreferences("${token}_empty_credentials", 0)
        check(empty.edit().clear().commit())
        repo = VideoRepository(context, credentialPreferences = empty)
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                check(modelClass == VideoViewModel::class.java)
                @Suppress("UNCHECKED_CAST")
                return VideoViewModel(context.applicationContext as Application, repo) as T
            }
        }
        compose.activityRule.scenario.onActivity { vm = ViewModelProvider(it, factory)[VideoViewModel::class.java] }
        compose.setContent { MyApplicationTheme { MainScreen(viewModel = vm, isDark = false, onToggleTheme = {}) } }
    }
    @After fun removeOnlyOwnedFixturesAndRestoreNonsecretChoices() {
        compose.activityRule.scenario.onActivity { it.viewModelStore.clear() }
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
            if (providerBefore == null) remove("llm_provider") else putString("llm_provider", providerBefore)
            if (autoBefore == null) remove("auto_ingest_enabled") else putBoolean("auto_ingest_enabled", requireNotNull(autoBefore))
        }.commit()
        context.deleteSharedPreferences("${token}_empty_credentials")
    }

    @Test fun t046_queueSortUsesNameSizeAndUnderlyingStatusWithoutMutation() {
        val a = seed("a.png", "new", "keep", padding = 600)
        val z = seed("z.png", "processed", "keep", padding = 100)
        val m = seed("m.png", "error", "review_later", padding = 300)
        val before = rows()
        navigate("Queue")
        sort("Name"); assertOrder(a, m, z)
        sort("Size"); assertOrder(z, m, a)
        sort("Status"); assertOrder(m, a, z)
        assertEquals(before, rows())
        assertBytes()
    }

    @Test fun t047_t048_selectCancelSelectAllAndDeselectNeverChangeRowsOrHiddenArchive() {
        val a = seed("a.png")
        val b = seed("b.png")
        seed("hidden.png", status = "archived")
        val before = rows()
        navigate("Queue")
        select(a)
        compose.onNodeWithText("1 files selected in backlog").assertIsDisplayed()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        compose.onNodeWithText("1 files selected in backlog").assertDoesNotExist()
        checkbox(a).assertIsOff(); checkbox(b).assertIsOff()
        // Header checkbox is the first toggle, physically above the two visible rows.
        val toggles = compose.onAllNodes(isToggleable())
        assertEquals(3, toggles.fetchSemanticsNodes().size)
        toggles[0].performClick()
        compose.onNodeWithText("2 files selected in backlog").assertIsDisplayed()
        checkbox(a).assertIsOn(); checkbox(b).assertIsOn()
        toggles[0].performClick()
        compose.onNodeWithText("2 files selected in backlog").assertDoesNotExist()
        checkbox(a).assertIsOff(); checkbox(b).assertIsOff()
        assertEquals(before, rows())
        assertBytes()
    }

    @Test fun t049_processSelectedFinishesOnlyKeepUsingDeviceFallback() {
        val kept = seed("keep.png", decision = "keep")
        val later = seed("later.png", decision = "review_later")
        val beforeLater = row(later.id)
        navigate("Queue"); select(kept); select(later)
        compose.onNodeWithContentDescription("Process selected").performClick()
        await(60000) { row(kept.id)?.status in setOf("processed", "error") }
        assertEquals("processed", row(kept.id)?.status)
        assertEquals(beforeLater, row(later.id))
        compose.onNodeWithText("2 files selected in backlog").assertDoesNotExist()
        assertEquals("fallback", io { db.analysisResultDao().getAnalysisForVideoDirect(kept.id)?.source })
        assertNull(io { db.analysisResultDao().getAnalysisForVideoDirect(later.id) })
        assertEquals(0, repo.mediaOperationCounters.llmRequests)
        listOf(kept, later).forEach { item ->
            action(item, "Preview").performClick()
            compose.onNodeWithText("Media Details Preview: ${item.displayName}").assertIsDisplayed()
            compose.onNodeWithText("Status: ${row(item.id)!!.status.uppercase()}", substring = true).assertExists()
            close()
        }
        assertBytes()
    }

    @Test fun t050_emptyQueueHasHonestGuidanceAndNoSelectionAction() {
        navigate("Queue")
        compose.onNodeWithText("Backlog is empty. Scan storage or clear filters.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Process selected").assertDoesNotExist()
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onNodeWithContentDescription("Process selected").assertDoesNotExist()
        assertTrue(rows().isEmpty())
    }

    @Test fun t051_folderClickReportsExactOwnedUriWithoutChangingMedia() {
        val item = seed("location.png")
        val before = rows()
        val seen = AtomicBoolean(false)
        instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && event.text.any { it.toString() == "Location: ${item.uriString}" }) seen.set(true)
        }
        try {
            navigate("Queue")
            action(item, "Folder").performClick()
            await(5000) { seen.get() }
            assertEquals(before, rows())
            assertBytes()
        } finally { instrumentation.uiAutomation.setOnAccessibilityEventListener(null) }
    }

    @Test fun t052_t053_nonKeepCannotProcessThroughQueueOrPreviewAndHasNoOutput() {
        val item = seed("later.png", decision = "review_later")
        val before = rows()
        navigate("Queue")
        action(item, "Output").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Processed Result Summary: ${item.displayName}").assertDoesNotExist()
        action(item, "Process").assertIsNotEnabled().performClick()
        action(item, "Preview").performClick()
        compose.onNodeWithText("Status: NEW", substring = true).assertExists()
        compose.onNodeWithContentDescription("Process media").performScrollTo().performClick()
        close()
        assertEquals(before, rows())
        assertNull(io { db.analysisResultDao().getAnalysisForVideoDirect(item.id) })
        assertTrue(io { db.extractedFrameDao().getFramesForVideoDirect(item.id).isEmpty() })
        assertEquals(0, repo.mediaOperationCounters.llmRequests)
        assertEquals(VideoProcessor.State.IDLE, vm.processor.processorState.value)
        assertBytes()
    }

    @Test fun t054_actualRenameUsesStoredOcrWordsAndPreservesIdentityAndPhysicalBytes() {
        val item = seed("ocr.png")
        // Explicit synthetic OCR records establish the rename input; no OCR extraction claim.
        io { db.extractedFrameDao().insertFrame(ExtractedFrame(videoId = item.id, timestampMs = 0, ocrText = "ALPHA BRAVO CHARLIE")) }
        navigate("Queue")
        rename(item)
        await { row(item.id)?.displayName == "alpha_bravo_charlie.png" }
        assertRename(item, "alpha_bravo_charlie.png")
        navigate("Sources")
        compose.onNodeWithTag("source_category_Camera").performClick()
        compose.onNodeWithText("alpha_bravo_charlie.png").assertExists()
        assertBytes()
    }

    @Test fun t055_noOcrUnicodeOriginalGetsBoundedSuggestionAndKeepsExtension() {
        val item = seed("مرحبا😀-long_original_filename.png")
        val expectedBase = item.displayName.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9_]"), "_").take(15) + "_vid"
        val expected = "$expectedBase.png"
        navigate("Queue"); rename(item)
        await { row(item.id)?.displayName == expected }
        assertRename(item, expected)
        assertTrue(row(item.id)!!.displayName.substringBeforeLast('.').length <= 20)
        assertBytes()
    }

    @Test fun t037_t038_t039_folderSearchUsesFullPathAndQueuesOnlySelectedKeep() {
        val inside = seed("inside.png", decision = "keep", folder = "Inside")
        val later = seed("later.png", decision = "review_later", folder = "Inside")
        val sibling = seed("sibling.png", decision = "keep", folder = "InsideOther")
        navigate("Folders")
        compose.onNodeWithContentDescription("Open menu").performClick()
        val search = compose.onNode(hasSetTextAction() and hasText("Search folders..."))
        search.performTextReplacement("does-not-exist-$token")
        compose.onNodeWithText("No matching folders found.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").performClick()
        search.performTextReplacement("inside")
        dismissQaKeyboard(compose.activity)
        compose.waitForIdle()
        val selectedPath = "Pictures/$token/Inside"
        compose.onNodeWithText(selectedPath, substring = false).assertExists()
        compose.onNodeWithText("Pictures/$token/InsideOther", substring = false).assertExists()
        val y = compose.onNodeWithText(selectedPath, substring = false).fetchSemanticsNode().boundsInRoot.center.y
        val toggles = compose.onAllNodes(isToggleable()).fetchSemanticsNodes()
        val index = toggles.indices.minBy { kotlin.math.abs(toggles[it].boundsInRoot.center.y - y) }
        check(kotlin.math.abs(toggles[index].boundsInRoot.center.y - y) < 70f)
        compose.onAllNodes(isToggleable())[index].performClick()
        compose.onNodeWithContentDescription("Add selected to queue").performClick()
        await { row(inside.id)?.status == "queued" }
        assertEquals("new", row(later.id)?.status)
        assertEquals("new", row(sibling.id)?.status)
        assertEquals(0, repo.mediaOperationCounters.llmRequests)
        assertBytes()
    }

    @Test fun t169_t170_t171_t172_t173_toolsAndReviewCancelPreserveOwnedMedia() {
        val item = seed("cancel.png")
        val before = rows()
        navigate("Sources")
        compose.onNodeWithTag("source_category_Camera").performClick()
        compose.onNodeWithTag("review_decision_${item.id}").onChildren().filter(hasClickAction()).onFirst().performClick()
        listOf("New", "Keep", "Later", "Remove").forEach { compose.onAllNodesWithText(it, substring = false).onLast().assertExists() }
        Espresso.pressBack()
        assertEquals("unreviewed", row(item.id)?.reviewDecision)
        navigate("Sources")
        // Category selection survives navigation; tool controls exist only at the
        // Sources overview. Return through the actual visible category-back control.
        compose.onNode(hasContentDescription("Show all sources") and hasClickAction())
            .assertIsDisplayed().performClick()
        compose.waitForIdle()
        listOf("rules", "tags", "bin").forEach { tool ->
            compose.onNodeWithTag("open_$tool").performClick()
            compose.onNodeWithTag("${tool}_screen").assertExists()
            if (tool == "rules") {
                listOf(false, true).forEach { obscure ->
                    compose.onNodeWithTag(if (obscure) "add_obscured_rule" else "add_copy_rule").performClick()
                    compose.onNode(hasSetTextAction() and hasText("Name")).assertTextContains(if (obscure) "Obscure" else "Copies")
                    if (obscure) {
                        assertEquals("", compose.onNode(hasSetTextAction() and hasText("Score")).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
                        compose.onNodeWithText("0–1").assertExists()
                    }
                    // Target the focused Android Dialog root explicitly, not the Activity
                    // root behind it. This preserves the actual Android Back scenario.
                    androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
                        .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                        .perform(androidx.test.espresso.action.ViewActions.pressBack())
                    compose.onNodeWithText("No rules").assertExists()
                }
            }
            if (tool == "tags") {
                compose.onNodeWithContentDescription("Add").assertIsNotEnabled()
                // No destructive clearing: require naturally empty personal labels for exact T173.
                assertTrue("T173 requires empty tags", io { db.customTagDao().observeTags().first().isEmpty() })
            }
            compose.onNodeWithTag("${tool}_screen").assertIsDisplayed()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
            compose.onNodeWithTag("media_sources_screen").assertExists()
        }
        assertEquals(before, rows())
        assertTrue(io { db.mediaRuleDao().observeRules().first().isEmpty() })
        assertBytes()
    }

    @Test fun t174_t175_savedOutputDoneAndPreviewEvidenceMinimizeAreReadOnly() {
        val item = seed("saved.png", status = "processed", decision = "keep")
        io {
            db.extractedFrameDao().insertFrame(ExtractedFrame(videoId = item.id, timestampMs = 0, ocrText = "OWNED QA IMAGE EVIDENCE"))
            db.analysisResultDao().insertAnalysis(AnalysisResult(videoId = item.id, descriptionLine1 = "QA saved headline", descriptionLine2 = "QA saved detail",
                categoryLevel1 = "Technical", categoryLevel2 = "QA", categoryLevel3 = "Fixture", formattedActionPlan = "QA saved roadmap",
                urls = emptyList(), codeOrCommands = emptyList(), evidenceJson = "{}", source = "edited"))
        }
        val before = rows()
        navigate("Queue")
        action(item, "Output").performClick()
        compose.onNodeWithContentDescription("Full Window").performClick()
        listOf("Technical > QA > Fixture", "QA saved headline", "QA saved detail").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithText("Processed Result Summary: ${item.displayName}").assertDoesNotExist()
        action(item, "Preview").performClick()
        compose.onNodeWithContentDescription("Full Window").performClick()
        compose.onNodeWithText("OWNED QA IMAGE EVIDENCE").performScrollTo().assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Group 1")).performScrollTo().assertTextContains("Technical")
        compose.onNodeWithContentDescription("Remove index entry").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Minimize").performClick()
        compose.onNodeWithContentDescription("Restore preview").performClick()
        compose.onNodeWithText("Media Details Preview: ${item.displayName}").assertIsDisplayed()
        close()
        assertEquals(before, rows())
        assertEquals("QA saved headline", io { db.analysisResultDao().getAnalysisForVideoDirect(item.id)?.descriptionLine1 })
        assertEquals(0, repo.mediaOperationCounters.llmRequests)
        assertBytes()
    }

    private fun seed(name: String, status: String = "new", decision: String = "unreviewed", padding: Int = 0, folder: String = "Camera"): VideoItem {
        val path = "Pictures/$token/$folder/"
        val created = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, path); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        owned[created] = byteArrayOf()
        val bitmap = Bitmap.createBitmap(600, 220, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(Color.WHITE)
            Canvas(bitmap).drawText("ALPHA BRAVO CHARLIE", 15f, 100f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 32f })
            ByteArrayOutputStream().use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray().let { it.copyOf(it.size + padding) } }
        } finally { bitmap.recycle() }
        owned[created] = bytes
        context.contentResolver.openOutputStream(created)!!.use { it.write(bytes) }
        context.contentResolver.update(created, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val item = VideoItem(mediaStoreId = android.content.ContentUris.parseId(created), uriString = created.toString(), displayName = name,
            relativePath = path, mimeType = "image/png", sizeBytes = bytes.size.toLong(), durationMs = null, dateAdded = null, dateModified = null,
            status = status, reviewDecision = decision, mediaCollection = "image", sourceCategory = "Camera")
        return item.copy(id = io { dao.insertVideo(item) }.also { ids += it })
    }
    private fun row(id: Long) = io { dao.getVideoByIdDirect(id) }
    private fun rows() = io { dao.getAllVideosDirect().sortedBy { it.id } }
    private fun assertBytes() { owned.forEach { (uri, bytes) -> assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }) } }
    private fun assertRename(item: VideoItem, expected: String) {
        assertEquals(expected, row(item.id)?.displayName)
        assertEquals(item.uriString, row(item.id)?.uriString)
        assertEquals(1, rows().count { it.mediaStoreId == item.mediaStoreId })
        val physical = context.contentResolver.query(Uri.parse(item.uriString), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use { it.moveToFirst(); it.getString(0) }
        assertEquals(expected, physical)
        compose.onNodeWithText(expected).assertExists()
        assertEquals(0, repo.mediaOperationCounters.llmRequests)
    }
    private fun rename(item: VideoItem) = nearest(item, hasText("Rename", substring = false) and hasClickAction()).performClick()
    private fun action(item: VideoItem, label: String) = nearest(item, hasContentDescription(label) and hasClickAction())
    private fun checkbox(item: VideoItem) = nearest(item, isToggleable())
    private fun select(item: VideoItem) { checkbox(item).performClick() }
    private fun nearest(item: VideoItem, matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val eligible = rows().filter { it.isCurrentlyAvailable && !it.isPending && !it.isTrashed && it.status != "archived" }
        check(eligible.any { it.id == item.id } && eligible.all { it.id in ids }) { "Queue ownership not proven" }
        val name = compose.onNodeWithText(item.displayName, substring = false).assertIsDisplayed().fetchSemanticsNode()
        val nodes = compose.onAllNodes(matcher).fetchSemanticsNodes()
        check(nodes.isNotEmpty()) { "Owned row action missing" }
        if (eligible.size == 1 && nodes.size == 1) {
            return compose.onAllNodes(matcher)[0].assertIsDisplayed()
        }
        // Production rows have no semantic tag. Filename top edges delimit rendered
        // rows; the file-column Rename control can sit well below its name at large font.
        // Every other visible name comes from the independently validated owned DB set.
        val nextTop = eligible.filter { it.id != item.id }.mapNotNull { other ->
            compose.onAllNodesWithText(other.displayName, substring = false).fetchSemanticsNodes()
                .singleOrNull()?.boundsInRoot?.takeIf { it.width > 0f && it.height > 0f }?.top
        }.filter { it > name.boundsInRoot.top }.minOrNull() ?: Float.POSITIVE_INFINITY
        val candidates = nodes.indices.filter { index ->
            val bounds = nodes[index].boundsInRoot
            bounds.width > 0f && bounds.height > 0f && bounds.center.y >= name.boundsInRoot.top && bounds.center.y < nextTop
        }
        check(candidates.size == 1) { "Owned rendered row action is ambiguous: ${candidates.size}" }
        return compose.onAllNodes(matcher)[candidates.single()].assertIsDisplayed()
    }
    private fun sort(label: String) { compose.onNode(hasText(label, substring = false) and hasClickAction()).performClick(); compose.waitForIdle() }
    private fun assertOrder(vararg items: VideoItem) {
        val positions = items.map { compose.onNodeWithText(it.displayName, substring = false).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(positions.sorted(), positions)
        assertEquals(positions.size, positions.distinct().size)
    }
    private fun close() {
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Close") and hasClickAction()).assertIsDisplayed().performClick()
    }
    private fun navigate(label: String) {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("nav_${label.lowercase()}").performClick()
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close menu").performClick()
        compose.waitForIdle()
    }
    private fun await(timeout: Long = 15000, predicate: () -> Boolean) = compose.waitUntil(timeout, predicate)
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}
