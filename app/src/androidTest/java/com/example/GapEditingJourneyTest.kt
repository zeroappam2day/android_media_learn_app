package com.example

import android.app.Activity
import android.os.SystemClock
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import kotlinx.coroutines.flow.first
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AnalysisResult
import com.example.data.VideoDatabase
import com.example.data.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/**
 * Draft: real MainActivity entry for all saves/archive/restore/removal.
 * Analysis is deliberately synthetic; these tests make no inference claim.
 * Export intercepts ACTION_CREATE_DOCUMENT with an owned destination: it checks actual
 * callback-written bytes, NOT DocumentsUI navigation, folder grants, or picker failure UX.
 * No Settings, credentials, whole-tree dumps, global DB reset, or unowned deletion.
 * No force-stop from instrumentation: T080/T227 restart coverage requires an external driver.
 */
@RunWith(AndroidJUnit4::class)
class GapEditingJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val db get() = VideoDatabase.getDatabase(context)
    private val dao get() = db.videoItemDao()
    private val token = "editing_qa_${System.nanoTime()}"
    private val ownedUris = mutableListOf<Uri>()
    private val ownedIds = mutableListOf<Long>()
    private lateinit var item: VideoItem
    // Editor order is verified against VideoDetailPanel; all seven are non-lazy children.
    private val original = listOf("Original QA", "QA", "Testing", "Review", "QA title", "QA detail", "QA step")

    private val ownedByteMap = linkedMapOf<Uri, ByteArray>()
    private var oldFolders: Set<String>? = null
    private var scopeCaptured = false
    private val prefs get() = context.getSharedPreferences("app_prefs", 0)

    @Before fun runtimeGate() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview")) { "Dedicated QA preview package required" }
        check(InstrumentationRegistry.getArguments().getString("rea_runtime") == "emulator")
        check(io { dao.getAllVideosDirect().isEmpty() }) { "Gap journeys require an empty isolated QA index" }
        check(!prefs.getBoolean("auto_ingest_enabled", false))
        oldFolders = prefs.getStringSet("target_folders", null)?.toSet()
        scopeCaptured = true
    }

    @After fun cleanOnlyOwnedFixtures() {
        if (ownedIds.isNotEmpty()) io {
            ownedIds.forEach { id ->
                db.binDao().remove(id)
                db.extractedFrameDao().deleteFramesForVideo(id)
                db.analysisResultDao().deleteAnalysisForVideo(id)
                dao.getVideoByIdDirect(id)?.let { dao.deleteVideo(it) }
            }
        }
        ownedUris.forEach { context.contentResolver.delete(it, null, null) }
        if (scopeCaptured) prefs.edit().apply { if (oldFolders == null) remove("target_folders") else putStringSet("target_folders", oldFolders) }.commit()
    }

    fun saveSevenFieldsReopenAndOutput() {
        // T078; T093/T227 only field consistency portions, not process restart.
        fixture()
        openPreview()
        edit(original)
        saveAndAwait(original)
        close()
        openPreview()
        assertEditor(original)
        close()
        openAction("Output")
        compose.onNodeWithText("QA > Testing > Review").assertExists()
        listOf("QA title", "QA detail", "QA step").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Original QA", substring = false).assertDoesNotExist()
        close()
        assertPhysicalBytes()
    }

    fun clearAllSevenFieldsDoesNotRestoreDefaults() {
        // T079, including exact empty-string database oracle.
        fixture()
        openPreview()
        val empty = List(7) { "" }
        edit(empty)
        saveAndAwait(empty)
        close()
        openPreview()
        assertEditor(empty)
        close()
    }

    fun unicodeTenThousandCharactersAndActualExportBytes() {
        // T080 partial (no process restart); T078/T160/T165 actual data path.
        fixture()
        val text = "مرحبا 😀\n".repeat(1500).take(9998) + "終\n"
        assertEquals(10000, text.length)
        val values = original.toMutableList().apply { this[0] = text; this[6] = "خطوة أولى 😀\nQA step two\n最後" }
        openPreview()
        edit(values)
        saveAndAwait(values)
        close()
        openPreview()
        assertEditor(values)
        close()
        val exported = exportOwnedRecord()
        assertEquals(values[0], exported.getString("notes"))
        assertEquals(values[4], exported.getString("summary"))
        assertEquals(values[5], exported.getString("details"))
        assertEquals(values[6], exported.getString("roadmap"))
        assertEquals(values.subList(1, 4).joinToString(" / "), exported.getString("category"))
        assertEquals(item.displayName, exported.getString("name"))
        assertEquals(item.uriString, exported.getString("uri"))
        assertPhysicalBytes()
    }

    fun closeDiscardsUnsavedDraftAndMinimizeDoesNotSave() {
        // T074, T081, T166 current intended draft behavior; QA's old disposal expectation is stale.
        fixture()
        openPreview()
        edit(original)
        saveAndAwait(original)
        fields()[0].performScrollTo().performTextReplacement("Unsaved QA")
        compose.onNodeWithContentDescription("Full Window").performClick()
        compose.onNodeWithContentDescription("Standard Window").performClick()
        compose.onNodeWithContentDescription("Minimize").performClick()
        assertStored(original)
        compose.onNodeWithContentDescription("Restore preview").performClick()
        fields()[0].performScrollTo().assertTextEquals("Unsaved QA")
        close()
        openPreview()
        assertEditor(original)
        assertStored(original)
        close()
    }

    fun rotationNeverCommitsDraft() {
        // T209; actual configuration changes, not just ActivityScenario.recreate().
        fixture()
        openPreview()
        edit(original)
        saveAndAwait(original)
        fields()[0].performScrollTo().performTextReplacement("Unsaved QA")
        try {
            rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            assertStored(original)
            rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            assertStored(original)
            // Rotation may dismiss the panel: T209 promises persisted saved data, not draft
            // or window survival. Close only an existing panel, then prove ordinary reopening.
            if (compose.onAllNodesWithContentDescription("Close").fetchSemanticsNodes().isNotEmpty()) close()
            navigate("Queue")
            openPreview()
            assertEditor(original)
            close()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    fun archiveRestoreVideoKeepsNotesAnalysisAndPhysicalBytes() = archiveRestore(video = true)
    fun archiveRestoreImageKeepsNotesAnalysisAndPhysicalBytes() = archiveRestore(video = false)

    private fun archiveRestore(video: Boolean) {
        // T066/T068; actual Queue Archive selected and archive Restore selected.
        fixture(video = video)
        openPreview()
        edit(original)
        saveAndAwait(original)
        close()
        selectOwnedRow()
        compose.onNodeWithContentDescription("Archive selected").performClick()
        await { io { dao.getVideoByIdDirect(item.id)?.status == "archived" } }
        navigate(if (video) "Videos" else "Images")
        awaitName()
        selectOwnedRow()
        compose.onNodeWithContentDescription("Restore selected").performClick()
        await { io { dao.getVideoByIdDirect(item.id)?.status == "processed" } }
        assertStored(original)
        assertPhysicalBytes()
        navigate("Queue")
        openPreview()
        assertEditor(original)
        close()
        // A restored completed item must expose its retained saved summary immediately.
        nearestAction("Output").assertIsEnabled()
        openAction("Output")
        compose.onNodeWithText("QA > Testing > Review").assertExists()
        listOf("QA title", "QA detail", "QA step").forEach { compose.onNodeWithText(it).assertExists() }
        close()
    }

    fun previewArchiveAndIndexRemovalPreservePhysicalImage() {
        // T084 then T069, no broad rescan (T069 rediscovery remains external).
        fixture()
        openPreview()
        compose.onNodeWithContentDescription("Archive media").performScrollTo().performClick()
        await { io { dao.getVideoByIdDirect(item.id)?.status == "archived" } }
        close()
        navigate("Images")
        awaitName()
        selectOwnedRow()
        compose.onNodeWithContentDescription("Delete selected index entries").performClick()
        await { io { dao.getVideoByIdDirect(item.id) == null } }
        assertPhysicalBytes()
    }

    fun removeIndexEntryFromPreviewPreservesPhysicalImage() {
        // T086; T204 only removal boundary, no tag-count claim.
        fixture()
        openPreview()
        compose.onNodeWithContentDescription("Remove index entry").performScrollTo().performClick()
        await { io { dao.getVideoByIdDirect(item.id) == null } }
        compose.onNodeWithText("Media Details Preview: ${item.displayName}").assertDoesNotExist()
        assertPhysicalBytes()
    }

    fun outputAndPreviewBadgesRestoreTheirOwnWindow() {
        // T075 complete; T223 partial (Settings deliberately excluded).
        fixture()
        openPreview()
        compose.onNodeWithContentDescription("Minimize").performClick()
        openAction("Output")
        compose.onNodeWithContentDescription("Full Window").performClick()
        compose.onNodeWithContentDescription("Standard Window").performClick()
        compose.onNodeWithContentDescription("Minimize").performClick()
        compose.onNodeWithContentDescription("Restore preview").performClick()
        compose.onNodeWithText("Media Details Preview: ${item.displayName}").assertIsDisplayed()
        close()
        compose.onNodeWithContentDescription("Restore output").performClick()
        compose.onNodeWithText("Processed Result Summary: ${item.displayName}").assertIsDisplayed()
        close()
        assertPhysicalBytes()
    }

    fun threeMinimizedWindowsRestoreTheirOwnVisibleContentWithoutSaving() {
        fixture()
        val before = stored()
        openPreview()
        compose.onNodeWithContentDescription("Minimize").performClick()
        openAction("Output")
        compose.onNodeWithContentDescription("Minimize").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithContentDescription("Minimize").performClick()
        listOf("Restore preview", "Restore output", "Restore settings").forEach {
            compose.onNodeWithContentDescription(it).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("Restore preview").performClick()
        compose.onNodeWithText("Media Details Preview: ${item.displayName}").assertIsDisplayed()
        close()
        compose.onNodeWithContentDescription("Restore output").performClick()
        compose.onNodeWithText("Processed Result Summary: ${item.displayName}").assertIsDisplayed()
        close()
        compose.onNodeWithContentDescription("Restore settings").performClick()
        compose.onNodeWithText("Ollama Advanced Connection Settings").assertIsDisplayed()
        close()
        assertEquals(before, stored())
        assertPhysicalBytes()
    }

    fun saveBeforeProcessingDoesNotMarkItemProcessed() {
        // T082 save/reopen portion; no inference or export picker claim.
        fixture(processed = false)
        openPreview()
        edit(original)
        saveAndAwait(original)
        close()
        openPreview()
        assertEditor(original)
        close()
        assertEquals("new", io { dao.getVideoByIdDirect(item.id)?.status })
        nearestAction("Output").assertIsNotEnabled()
    }

    fun resetExplicitlyClearsSavedNotesAndAnalysis() {
        // T083 and T220 reset boundary only; deliberately no reprocessing/network claim.
        fixture()
        openPreview()
        edit(original)
        saveAndAwait(original)
        dismissQaKeyboard(compose.activity)
        compose.waitForIdle()
        compose.onNode(hasClickAction() and hasContentDescription("Clear saved result"))
            .performScrollTo().assertIsDisplayed().performClick()
        try {
            await { io { dao.getVideoByIdDirect(item.id)?.status == "new" && db.analysisResultDao().getAnalysisForVideoDirect(item.id) == null } }
        } catch (failure: Throwable) {
            val state = io {
                val row = dao.getVideoByIdDirect(item.id)
                "owned reset state: status=${row?.status}, notesPresent=${row?.manualTranscript != null}, analysisPresent=${db.analysisResultDao().getAnalysisForVideoDirect(item.id) != null}"
            }
            throw AssertionError(state, failure)
        }
        assertNull(io { dao.getVideoByIdDirect(item.id)?.manualTranscript })
        close()
        nearestAction("Output").assertIsNotEnabled()
        assertPhysicalBytes()
    }

    fun markAndUnmarkTrashCandidateDoesNotDeleteOriginal() {
        // T085 status/storage portion only; separate Sources/Bin count assertions remain pending.
        fixture()
        openPreview()
        compose.onNodeWithContentDescription("Mark Trash Candidate").performScrollTo().performClick()
        await { io { dao.getVideoByIdDirect(item.id)?.status == "ready_to_delete" } }
        assertPhysicalBytes()
        compose.onNodeWithContentDescription("Unmark Trash Candidate").performScrollTo().performClick()
        await { io { dao.getVideoByIdDirect(item.id)?.status == "processed" } }
        assertEquals("keep", io { dao.getVideoByIdDirect(item.id)?.reviewDecision })
        close()
        assertPhysicalBytes()
    }

    private var physicalBytes = byteArrayOf()
    private fun fixture(video: Boolean = false, processed: Boolean = true, showInQueue: Boolean = true) {
        val name = "000_${token}_${ownedUris.size}.${if (video) "mp4" else "png"}"
        val mime = if (video) "video/mp4" else "image/png"
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = requireNotNull(context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${if (video) "Movies" else "Pictures"}/$token/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        ownedUris += uri
        // Video fixture is owned opaque bytes, intentionally never decoded or processed.
        // Archive tests exercise MediaStore identity and byte preservation, not valid-video playback.
        context.contentResolver.openOutputStream(uri)!!.use { out ->
            if (video) out.write("QA opaque archive fixture $token".toByteArray())
            else Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).let { bitmap ->
                try { bitmap.eraseColor(0xff336699.toInt()); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                finally { bitmap.recycle() }
            }
        }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        physicalBytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val row = VideoItem(mediaStoreId = android.content.ContentUris.parseId(uri), uriString = uri.toString(), displayName = name,
            relativePath = "${if (video) "Movies" else "Pictures"}/$token/", mimeType = mime, sizeBytes = physicalBytes.size.toLong(),
            durationMs = null, dateAdded = null, dateModified = null, status = if (processed) "processed" else "new",
            lastCompletedStep = if (processed) "result_saved" else "discovered", reviewDecision = "keep",
            mediaCollection = if (video) "video" else "image")
        val id = io { dao.insertVideo(row) }
        ownedIds += id
        item = row.copy(id = id)
        if (processed) io { db.analysisResultDao().insertAnalysis(AnalysisResult(videoId = id, descriptionLine1 = "Seed title",
            descriptionLine2 = "Seed detail", categoryLevel1 = "Seed", categoryLevel2 = "QA", categoryLevel3 = "Fixture",
            formattedActionPlan = "Seed roadmap", urls = emptyList(), codeOrCommands = emptyList(), evidenceJson = "{}", source = "edited")) }
        if (showInQueue) {
            navigate("Queue")
            awaitName()
        }
    }

    private fun exportOwnedRecord(): JSONObject {
        // Export is global: refuse before opening it when any unrelated record exists.
        check(io { dao.getAllVideosDirect().all { it.id in ownedIds } }) { "Export requires isolated owned-only QA database" }
        val destination = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$token.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$token/")
        }))
        ownedUris += destination
        val filter = IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("application/json") }
        val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(destination)), true)
        try {
            compose.onNodeWithContentDescription("Save file details").performClick()
            await {
                runCatching {
                    context.contentResolver.openInputStream(destination)!!.bufferedReader().use { JSONObject(it.readText()) }
                        .getJSONArray("media").length() == ownedIds.size
                }.getOrDefault(false)
            }
            assertEquals(1, monitor.hits)
            val document = context.contentResolver.openInputStream(destination)!!.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            assertEquals(1, document.getInt("schema"))
            val media = document.getJSONArray("media")
            return (0 until media.length()).map { media.getJSONObject(it) }.single { it.getString("uri") == item.uriString }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun fields() = compose.onAllNodes(hasSetTextAction())
    private fun edit(values: List<String>) {
        assertEquals(7, fields().fetchSemanticsNodes().size)
        values.forEachIndexed { index, value -> fields()[index].performScrollTo().performTextReplacement(value) }
    }
    private fun assertEditor(values: List<String>) {
        assertEquals(7, fields().fetchSemanticsNodes().size)
        values.forEachIndexed { index, value -> fields()[index].performScrollTo().assertTextContains(value) }
        // Empty text needs an exact editable-text oracle: assertTextContains("") alone is vacuous.
        values.forEachIndexed { index, value ->
            assertEquals(value, fields()[index].fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        }
    }
    private fun saveAndAwait(values: List<String>) {
        compose.onNodeWithContentDescription("Save edits").performScrollTo().performClick()
        await { stored() == values }
        assertStored(values)
    }
    private fun stored(): List<String?> = io {
        val row = dao.getVideoByIdDirect(item.id)
        val analysis = db.analysisResultDao().getAnalysisForVideoDirect(item.id)
        listOf(row?.manualTranscript, analysis?.categoryLevel1, analysis?.categoryLevel2, analysis?.categoryLevel3,
            analysis?.descriptionLine1, analysis?.descriptionLine2, analysis?.formattedActionPlan)
    }
    private fun assertStored(values: List<String>) = assertEquals(values, stored())
    private fun assertPhysicalBytes() = assertArrayEquals(physicalBytes, context.contentResolver.openInputStream(Uri.parse(item.uriString))!!.use { it.readBytes() })
    private fun openPreview() = openAction("Preview")
    private fun openAction(label: String) { awaitName(); nearestAction(label).performClick(); compose.waitForIdle() }
    private fun close() { compose.onNodeWithContentDescription("Close").performClick(); compose.waitForIdle() }
    private fun nearestAction(label: String): SemanticsNodeInteraction {
        val queueTag = "queue_${item.id}_${label.lowercase()}"
        if (compose.onAllNodesWithTag(queueTag).fetchSemanticsNodes().size == 1) return compose.onNodeWithTag(queueTag).performQaQueueScrollTo(compose)
        val y = compose.onNodeWithText(item.displayName, substring = false).fetchSemanticsNode().boundsInRoot.center.y
        val nodes = compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
        check(nodes.isNotEmpty()) { "Missing owned row action: $label" }
        val index = nodes.indices.minBy { kotlin.math.abs(nodes[it].boundsInRoot.center.y - y) }
        check(kotlin.math.abs(nodes[index].boundsInRoot.center.y - y) < 70f) { "Action not proven to belong to owned row" }
        return compose.onAllNodesWithContentDescription(label)[index]
    }
    private fun selectOwnedRow() {
        val nameBounds = compose.onNodeWithText(item.displayName, substring = false).getUnclippedBoundsInRoot()
        val y = (nameBounds.top.value + nameBounds.bottom.value) / 2f
        val checkboxes = compose.onAllNodes(isToggleable())
        val nodes = checkboxes.fetchSemanticsNodes()
        check(nodes.isNotEmpty()) { "Missing checkbox for owned row" }
        val centers = nodes.indices.map { index ->
            val bounds = checkboxes[index].getUnclippedBoundsInRoot()
            (bounds.top.value + bounds.bottom.value) / 2f
        }
        val index = centers.indices.minBy { kotlin.math.abs(centers[it] - y) }
        val distancePx = kotlin.math.abs(centers[index] - y) * context.resources.displayMetrics.density
        check(distancePx < 70f) { "Checkbox not proven to belong to owned row" }
        checkboxes[index].performQaQueueScrollTo(compose).assertIsDisplayed().performClick()
        checkboxes[index].assertIsOn()
    }

    private fun navigate(label: String) {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("nav_${label.lowercase()}").performClick()
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close menu").performClick()
        compose.waitForIdle()
    }
    private fun awaitName() = await { runCatching { compose.onNodeWithText(item.displayName, substring = false).performQaQueueScrollTo(compose).assertIsDisplayed() }.isSuccess }
    private fun rotate(orientation: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
        val desired = if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT
        await { context.resources.configuration.orientation == desired }
        compose.waitForIdle()
    }
    private fun await(predicate: () -> Boolean) = compose.waitUntil(15000, predicate)
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    @Test fun t067_archiveVideoIndexRemovalThenRealRefreshRediscoversPhysicalFile() = archiveRemoveRediscover(true)
    @Test fun t069_archiveImageIndexRemovalThenRealRefreshRediscoversPhysicalFile() = archiveRemoveRediscover(false)

    private fun archiveRemoveRediscover(video: Boolean) {
        fixture(video = video)
        val physical = Uri.parse(item.uriString)
        val beforeBytes = physicalBytes.copyOf()
        val originalUri = item.uriString
        val scope = "content://com.android.externalstorage.documents/tree/" + Uri.encode("primary:" + item.relativePath!!.trimEnd('/'))
        prefs.edit().putStringSet("target_folders", setOf(scope)).commit()
        selectOwnedRow()
        compose.onNodeWithContentDescription("Archive selected").performClick()
        await { io { dao.getVideoByIdDirect(item.id)?.status == "archived" } }
        navigate(if (video) "Videos" else "Images")
        awaitName(); selectOwnedRow()
        compose.onNodeWithContentDescription("Delete selected index entries").performClick()
        await { io { dao.getVideoByIdDirect(item.id) == null } }
        assertArrayEquals(beforeBytes, context.contentResolver.openInputStream(physical)!!.use { it.readBytes() })
        navigate("Sources")
        compose.onNodeWithTag("inventory_scan_button").performClick()
        await { io { dao.getAllVideosDirect().count { it.uriString == originalUri && it.isCurrentlyAvailable } == 1 } }
        val rediscovered = io { dao.getAllVideosDirect().single { it.uriString == originalUri } }
        ownedIds += rediscovered.id
        assertEquals("new", rediscovered.status)
        assertEquals("unreviewed", rediscovered.reviewDecision)
        assertNull(rediscovered.manualTranscript)
        assertNull(io { db.analysisResultDao().getAnalysisForVideoDirect(rediscovered.id) })
        assertArrayEquals(beforeBytes, context.contentResolver.openInputStream(physical)!!.use { it.readBytes() })
    }

    @Test fun t071_realArchiveNameSizeStatusHaveIndependentThreeItemOrders() {
        val z = realOwned("z", pad = 20, lastScan = 100)
        val a = realOwned("a", pad = 900, lastScan = 200)
        val m = realOwned("m", pad = 200, lastScan = 300)
        listOf(z, a, m).forEach { row -> io { dao.updateVideo(row.copy(status = "archived")) } }
        navigate("Images")
        await { listOf(z, a, m).all { compose.onAllNodesWithText(it.displayName).fetchSemanticsNodes().isNotEmpty() } }
        compose.onNode(hasText("Name", substring = false) and hasClickAction()).performClick()
        assertOrder(a, m, z)
        compose.onNode(hasText("Size", substring = false) and hasClickAction()).performClick()
        assertOrder(z, m, a)
        compose.onNode(hasText("Status", substring = false) and hasClickAction()).performClick()
        // All statuses are archived; stable sorting must retain known scan order 300,200,100.
        assertOrder(m, a, z)
        assertAllOwnedBytes()
    }

    @Test fun t072_restoreImageCannotRestoreVideoSelectedOnAnotherArchive() {
        val video = realOwned("v", video = true)
        val image = realOwned("i")
        listOf(video, image).forEach { io { dao.updateVideo(it.copy(status = "archived")) } }
        navigate("Videos"); useItem(video); awaitName(); selectOwnedRow()
        navigate("Images"); useItem(image); awaitName(); selectOwnedRow()
        compose.onNodeWithContentDescription("Restore selected").performClick()
        await { io { dao.getVideoByIdDirect(image.id)?.status == "processed" } }
        assertEquals("archived", io { dao.getVideoByIdDirect(video.id)?.status })
        navigate("Videos"); compose.onNodeWithText(video.displayName).assertExists()
        navigate("Images"); compose.onNodeWithText(image.displayName).assertDoesNotExist()
        navigate("Queue"); compose.onNodeWithText(image.displayName).assertExists(); compose.onNodeWithText(video.displayName).assertDoesNotExist()
        assertAllOwnedBytes()
    }

    @Test fun t082_savePreanalysisFieldsThenExportSameUnfinishedItem() {
        fixture(processed = false)
        openPreview(); edit(original); saveAndAwait(original); close()
        openPreview(); assertEditor(original); close()
        assertEquals("new", io { dao.getVideoByIdDirect(item.id)?.status })
        nearestAction("Output").assertIsNotEnabled()
        val exported = exportOwnedRecord()
        assertEquals(original[0], exported.getString("notes"))
        assertEquals(original[4], exported.getString("summary"))
        assertEquals(original[5], exported.getString("details"))
        assertEquals("new", exported.getString("status"))
        assertPhysicalBytes()
    }

    @Test fun t085_candidateMarkUnmarkKeepsSeparateReviewCountsAndBin() {
        fixture()
        val decision = io { dao.getVideoByIdDirect(item.id)!!.reviewDecision }
        openPreview()
        compose.onNodeWithContentDescription("Mark Trash Candidate").performScrollTo().performClick()
        await { io { dao.getVideoByIdDirect(item.id)?.status == "ready_to_delete" } }
        assertEquals(decision, io { dao.getVideoByIdDirect(item.id)?.reviewDecision })
        close(); assertSourceKeepAndEmptyBin()
        navigate("Queue"); openPreview()
        compose.onNodeWithContentDescription("Unmark Trash Candidate").performScrollTo().performClick()
        await { io { dao.getVideoByIdDirect(item.id)?.status == "processed" } }
        close(); assertSourceKeepAndEmptyBin()
        assertEquals(decision, io { dao.getVideoByIdDirect(item.id)?.reviewDecision })
        assertPhysicalBytes()
    }

    private fun assertSourceKeepAndEmptyBin() {
        navigate("Sources")
        if (compose.onAllNodesWithContentDescription("Show all sources").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Show all sources").performClick()
        val category = io { dao.getVideoByIdDirect(item.id)?.sourceCategory } ?: "Other"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("source_category_$category"))
        compose.onNodeWithTag("source_category_$category").performClick()
        compose.onNodeWithText("0 unreviewed   •   1 kept   •   0 ready to delete", substring = true).assertExists()
        compose.onNodeWithContentDescription("Show all sources").performClick()
        compose.onNodeWithTag("open_bin").performClick()
        compose.onNodeWithText("Bin empty").assertIsDisplayed()
        assertNull(io { db.binDao().entry(item.id) })
        compose.onNodeWithContentDescription("Back").performClick()
    }

    @Test fun t154_binCountSizeScrollingPlaceholderAndBackHaveExactOwnedOracles() {
        val entries = (0 until 12).map { index ->
            val row = realOwned("bin_${index.toString().padStart(2, '0')}", processed = false)
            val bytes = ("bad-" + index).toByteArray()
            context.contentResolver.openOutputStream(Uri.parse(row.uriString), "wt")!!.use { it.write(bytes) }
            ownedByteMap[Uri.parse(row.uriString)] = bytes
            val ready = row.copy(sizeBytes = bytes.size.toLong(), reviewDecision = "ready_to_delete")
            io { dao.updateVideo(ready); db.binDao().add(com.example.data.BinEntry(row.id, "keep", null)) }
            ready
        }
        val snapshot = entries.associateBy { it.id }
        navigate("Sources")
        if (compose.onAllNodesWithContentDescription("Show all sources").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Show all sources").performClick()
        compose.onNodeWithTag("open_bin").performClick()
        val total = entries.sumOf { it.sizeBytes ?: 0L }
        check(total < 1024)
        compose.onNodeWithText("12 • $total B").assertIsDisplayed()
        val last = entries.last()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("bin_${last.id}"))
        compose.onNodeWithTag("bin_${last.id}").assertIsDisplayed()
        compose.onNodeWithContentDescription("Preview unavailable: ${last.displayName}").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("open_bin").assertIsDisplayed()
        assertEquals(snapshot, io { dao.getAllVideosDirect().filter { it.id in snapshot }.associateBy { it.id } })
        assertEquals(entries.map { it.id }.toSet(), io { db.binDao().observeEntries().first().map { it.mediaId }.toSet() })
        assertAllOwnedBytes()
    }

    @Test fun t207_unfinishedVideoGuidanceAndAndroidBackDoNotMutate() {
        fixture(video = true, processed = false)
        val before = io { dao.getVideoByIdDirect(item.id) }
        nearestAction("Output").assertIsNotEnabled()
        openPreview()
        compose.onNodeWithText("Media Details Preview: ${item.displayName}").assertIsDisplayed()
        compose.onNodeWithText("Run processing to build timeline", substring = true).performScrollTo().assertIsDisplayed()
        dismissQaKeyboard(compose.activity)
        onView(isRoot()).inRoot(isDialog()).perform(pressBack())
        compose.onNodeWithText("Media Details Preview: ${item.displayName}").assertDoesNotExist()
        compose.onNodeWithText(item.displayName).assertExists()
        assertEquals(before, io { dao.getVideoByIdDirect(item.id) })
        assertNull(io { db.analysisResultDao().getAnalysisForVideoDirect(item.id) })
        assertPhysicalBytes()
    }

    @Test fun t208_androidBackDiscardsUnsavedPreviewAndKeepsAllSavedFields() {
        fixture(); openPreview(); edit(original); saveAndAwait(original)
        fields()[0].performScrollTo().performTextReplacement("Unsaved QA")
        dismissQaKeyboard(compose.activity)
        onView(isRoot()).inRoot(isDialog()).perform(pressBack())
        compose.onNodeWithText("Media Details Preview: ${item.displayName}").assertDoesNotExist()
        assertStored(original)
        openPreview()
        original.forEachIndexed { index, value -> assertEquals(value, fields()[index].performScrollTo().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text) }
        close(); assertStored(original); assertPhysicalBytes()
    }

    private fun realOwned(label: String, video: Boolean = false, processed: Boolean = true, pad: Int = 0, lastScan: Long = 1): VideoItem {
        fixture(video, processed, showInQueue = false)
        val uri = Uri.parse(item.uriString)
        val bytes = physicalBytes + ByteArray(pad) { 0 }
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        val name = "${label}_$token.${if (video) "mp4" else "png"}"
        assertEquals(1, context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null))
        val changed = item.copy(displayName = name, sizeBytes = bytes.size.toLong(), lastScannedAt = lastScan)
        io { dao.updateVideo(changed) }
        item = changed; physicalBytes = bytes; ownedByteMap[uri] = bytes
        return changed
    }
    private fun useItem(row: VideoItem) { item = row; physicalBytes = requireNotNull(ownedByteMap[Uri.parse(row.uriString)]) }
    private fun assertAllOwnedBytes() = ownedByteMap.forEach { (uri, bytes) -> assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }) }
    private fun assertOrder(vararg rows: VideoItem) {
        val positions = rows.map { compose.onNodeWithText(it.displayName).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(positions.zipWithNext().all { (a, b) -> a < b })
    }

}
