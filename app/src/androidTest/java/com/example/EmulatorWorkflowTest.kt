package com.example

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.BinEntry
import com.example.data.AppSettings
import com.example.data.MediaRuleEngine
import com.example.data.MediaRuleSchema
import com.example.data.MediaAnalysisPolicy
import com.example.data.VideoDatabase
import com.example.data.VideoItem
import com.example.ui.VideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** Exercises the production activity and real MediaStore, using only disposable emulator fixtures. */
@RunWith(AndroidJUnit4::class)
class EmulatorWorkflowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = VideoDatabase.getDatabase(context)
    private val fixtureToken = "codex_workflow_${System.nanoTime()}"
    private val fixtureName = "$fixtureToken.png"
    private var fixtureUri: Uri? = null
    private var fixtureDeleted = false
    private var exportUri: Uri? = null
    private var originalSettings: AppSettings? = null
    private var originalProvider: Pair<Boolean, String?>? = null
    private val createdRuleIds = mutableListOf<Long>()
    private val createdTagNames = mutableListOf<String>()
    private val scannedRows = linkedMapOf<Long, String>()
    private var originalFolders: Pair<Boolean, Set<String>>? = null
    private var originalAuto: Pair<Boolean, Boolean>? = null

    @Before
    fun requirePreviewEmulator() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".preview")) { "This test requires the preview package" }
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "The fixture uses scoped MediaStore storage" }
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("qa_scan_cleanup_records") != null) {
            check(context.packageName.endsWith(".qa.preview"))
            check(args.getString("class") == "com.example.EmulatorWorkflowTest#removeRecordedScanIndexMetadataOnly")
            return
        }
        check(query { db.videoItemDao().getAllVideosDirect().isEmpty() }) { "Empty isolated index required before workflow fixture setup" }
        val prefs = context.getSharedPreferences("app_prefs", 0)
        originalFolders = prefs.contains("target_folders") to prefs.getStringSet("target_folders", emptySet()).orEmpty().toSet()
        originalAuto = prefs.contains("auto_ingest_enabled") to prefs.getBoolean("auto_ingest_enabled", false)
        val trees = setOf("Pictures/CodexWorkflow", "Movies/CodexWorkflow").map {
            android.provider.DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:$it").toString()
        }.toSet()
        prefs.edit().putStringSet("target_folders", trees).putBoolean("auto_ingest_enabled", false).commit()
    }

    @After
    fun removeOnlyCreatedFixtures() {
        originalSettings?.let { settings -> query { db.appSettingsDao().insertSettings(settings) } }
        originalProvider?.let { (existed, value) ->
            context.getSharedPreferences("app_prefs", 0).edit().apply {
                if (existed) putString("llm_provider", value) else remove("llm_provider")
            }.commit()
        }
        exportUri?.let { context.contentResolver.delete(it, null, null) }
        fixtureUri?.let { uri ->
            query { db.videoItemDao().getVideoByUri(uri.toString())?.let { db.videoItemDao().deleteVideo(it) } }
            if (!fixtureDeleted) context.contentResolver.delete(uri, null, null)
        }
        query {
            createdRuleIds.forEach { db.mediaRuleDao().deleteRule(it) }
            createdTagNames.forEach { name -> db.customTagDao().byName(name)?.let { db.customTagDao().delete(it) } }
            scannedRows.forEach { (id, uri) -> db.videoItemDao().getVideoByIdDirect(id)?.let { row ->
                check(row.uriString == uri)
                db.binDao().remove(id); db.analysisResultDao().deleteAnalysisForVideo(id)
                db.extractedFrameDao().deleteFramesForVideo(id); db.videoItemDao().deleteVideo(row)
            } }
        }
        val prefs = context.getSharedPreferences("app_prefs", 0)
        prefs.edit().apply {
            originalFolders?.let { (had, value) -> if (had) putStringSet("target_folders", value) else remove("target_folders") }
            originalAuto?.let { (had, value) -> if (had) putBoolean("auto_ingest_enabled", value) else remove("auto_ingest_enabled") }
        }.commit()
    }

    @Test fun removeRecordedScanIndexMetadataOnly() {
        val encoded = requireNotNull(InstrumentationRegistry.getArguments().getString("qa_scan_cleanup_records"))
        val records = org.json.JSONArray(String(android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP), Charsets.UTF_8))
        check(records.length() in 1..256)
        val expected = (0 until records.length()).map { records.getJSONObject(it) }
        val rows = query { db.videoItemDao().getAllVideosDirect() }
        assertEquals(expected.map { it.getLong("id") }.toSet(), rows.map { it.id }.toSet())
        fun physical(uri: String): List<String?> = requireNotNull(context.contentResolver.query(Uri.parse(uri),
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)).use { c ->
            check(c.moveToFirst()); (0 until c.columnCount).map { c.getString(it) }
        }
        val before = rows.associate { it.uriString to physical(it.uriString) }
        expected.forEach { record ->
            val row = rows.single { it.id == record.getLong("id") }
            assertEquals(record.getString("uri"), row.uriString)
            assertEquals(record.getString("name"), row.displayName)
            assertEquals(record.getLong("scan"), row.lastSeenScanToken)
        }
        query { rows.forEach { row ->
            db.binDao().remove(row.id); db.analysisResultDao().deleteAnalysisForVideo(row.id)
            db.extractedFrameDao().deleteFramesForVideo(row.id); db.videoItemDao().deleteVideo(row)
        } }
        assertTrue(query { db.videoItemDao().getAllVideosDirect().isEmpty() })
        before.forEach { (uri, values) -> assertEquals("Historical physical media must remain unchanged", values, physical(uri)) }
        println("Exact recorded scan metadata removed: ${rows.size}; all physical MediaStore records unchanged")
    }

    private fun recordScannedRows() {
        query { db.videoItemDao().getAllVideosDirect() }.forEach { row ->
            scannedRows[row.id] = row.uriString
            check(row.relativePath?.trimEnd('/') in setOf("Pictures/CodexWorkflow", "Movies/CodexWorkflow"))
        }
    }

    @Test
    fun bothThemesKeepSystemBarsAndHeaderReadable() {
        navigate("Home")
        val controller = androidx.core.view.WindowCompat.getInsetsController(
            compose.activity.window, compose.activity.window.decorView)
        val originalLight = controller.isAppearanceLightStatusBars
        try {
            for (light in listOf(true, false)) {
                if (controller.isAppearanceLightStatusBars != light) {
                    compose.onNodeWithContentDescription("Toggle Theme").performClick()
                }
                compose.waitForIdle()
                await { controller.isAppearanceLightStatusBars == light }
                assertEquals("Navigation icons must match the chosen theme", light,
                    controller.isAppearanceLightNavigationBars)
                for (description in listOf("Open menu", "Toggle Theme", "Settings", "Export metadata")) {
                    compose.onNodeWithContentDescription(description).assertIsDisplayed().assertHasClickAction()
                }
                var rendered: Bitmap? = null
                await {
                    val attempt = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                    var contrastingClockPixels = 0
                    var contrastingNavigationPixels = 0
                    for (y in (attempt.height * 0.004).toInt() until (attempt.height * 0.03).toInt()) {
                        for (x in (attempt.width * 0.02).toInt() until (attempt.width * 0.16).toInt()) {
                            val luminance = Color.luminance(attempt.getPixel(x, y))
                            if ((light && luminance < 0.3f) || (!light && luminance > 0.7f)) contrastingClockPixels++
                        }
                    }
                    for (y in (attempt.height * 0.98).toInt() until (attempt.height * 0.998).toInt()) {
                        for (x in (attempt.width * 0.35).toInt() until (attempt.width * 0.65).toInt()) {
                            val luminance = Color.luminance(attempt.getPixel(x, y))
                            if ((light && luminance < 0.3f) || (!light && luminance > 0.7f)) contrastingNavigationPixels++
                        }
                    }
                    if (contrastingClockPixels > 12 && contrastingNavigationPixels > 12) rendered = attempt else attempt.recycle()
                    rendered != null
                }
                val bitmap = requireNotNull(rendered)
                val output = java.io.File(requireNotNull(context.getExternalFilesDir(null)),
                    "rea-theme-${if (light) "light" else "dark"}.png")
                output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
        } finally {
            if (controller.isAppearanceLightStatusBars != originalLight) {
                compose.onNodeWithContentDescription("Toggle Theme").performClick()
                compose.waitForIdle()
            }
        }
    }

    @Test
    fun everyProductionNavigationDestinationOpensAndMenuCloses() {
        listOf("Home", "Folders", "Sources", "Queue", "Videos", "Images").forEach { label ->
            navigate(label)
            compose.onAllNodes(hasText(label) and hasAnyAncestor(hasTestTag("offcanvas_content")))
                .onFirst().assertIsDisplayed()
            compose.onNodeWithContentDescription("Open menu").assertIsDisplayed().assertHasClickAction()
        }
    }

    @Test
    fun realMediaScanReviewAndTagAssignmentPersistWithoutAnalysis() {
        val media = createAndScanFixture()
        compose.onNodeWithTag("source_category_CodexWorkflow").performClick()
        compose.onNodeWithTag("source_media_${media.id}").assertIsDisplayed()
        compose.onNodeWithText(fixtureName).assertIsDisplayed()
        compose.onNodeWithTag("review_decision_${media.id}").onChildren().filter(hasClickAction()).onFirst().performClick()
        compose.onNode(hasText("Keep") and hasAnyAncestor(isPopup())).performClick()
        await { query { db.videoItemDao().getVideoByIdDirect(media.id)?.reviewDecision == "keep" } }
        assertNull(query { db.analysisResultDao().getAnalysisForVideo(media.id).first() })
        assertTrue(query { db.extractedFrameDao().getFramesForVideoDirect(media.id).isEmpty() })
        compose.onNodeWithContentDescription("Show all sources").performClick()
        compose.onNodeWithTag("open_tags").performClick()
        compose.onNodeWithTag("tags_screen").assertIsDisplayed()
        createdTagNames += fixtureToken
        compose.onNode(hasSetTextAction()).performTextInput(fixtureToken)
        compose.onNodeWithContentDescription("Add", useUnmergedTree = true).performClick()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        await { query { db.customTagDao().byName(fixtureToken) != null } }
        compose.onNodeWithText(fixtureToken).performClick()
        val addTag = fixtureRowTagAction("Add tag")
        await { compose.onAllNodes(addTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(addTag).assertCountEquals(1)
        compose.onNode(addTag).performClick()
        val tagId = requireNotNull(query { db.customTagDao().byName(fixtureToken)?.id })
        await { query { db.customTagDao().observeAssignments().first().any { it.mediaId == media.id && it.tagId == tagId } } }
        val removeTag = fixtureRowTagAction("Remove tag")
        await { compose.onAllNodes(removeTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(removeTag).assertCountEquals(1)
        compose.onNode(removeTag).performClick()
        await { query { db.customTagDao().observeAssignments().first().none { it.mediaId == media.id && it.tagId == tagId } } }
        assertTrue("Tag changes must preserve the physical image", fixtureExists())
    }

    @Test
    fun ruleCreationRevisionPreviewAndDeletionPreservePhysicalMedia() {
        val media = createAndScanFixture()
        compose.onNodeWithTag("open_rules").performClick()
        compose.onNodeWithTag("add_copy_rule").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(fixtureToken)
        compose.onNodeWithContentDescription("Save", useUnmergedTree = true).performClick()
        await { query { db.mediaRuleDao().observeRules().first().any { it.name == fixtureToken } } }
        val rule = query { db.mediaRuleDao().observeRules().first().single { it.name == fixtureToken } }
        createdRuleIds += rule.id
        val inRule = hasAnyAncestor(hasTestTag("rule_${rule.id}"))
        compose.onNode(hasContentDescription("Edit") and inRule, useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("${fixtureToken}_edited")
        compose.onNodeWithContentDescription("Save", useUnmergedTree = true).performClick()
        await { query { db.mediaRuleDao().observeRules().first().any { it.id == rule.id && it.currentRevision == 2 } } }
        val expectedPreview = MediaRuleEngine().preview(
            MediaRuleSchema.parse(MediaRuleSchema.duplicateJson()).getOrThrow(),
            query { db.videoItemDao().getAllVideosDirect() }
        )
        compose.onNode(hasContentDescription("Preview") and inRule, useUnmergedTree = true).performClick()
        await { compose.onAllNodesWithText("${expectedPreview.itemCount} •", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Preview must preserve the review decision", "unreviewed", query { db.videoItemDao().getVideoByIdDirect(media.id)?.reviewDecision })
        assertNull("Preview must not move the fixture to Bin", query { db.binDao().entry(media.id) })
        assertTrue("Preview must preserve the physical image", fixtureExists())
        compose.onNode(hasContentDescription("Delete") and inRule, useUnmergedTree = true).performClick()
        await { query { db.mediaRuleDao().observeRules().first().none { it.id == rule.id } } }
    }

    @Test
    fun binRecoveryRestoresPriorDecisionAndKeepsPhysicalImage() {
        val media = createAndScanFixture()
        query {
            db.binDao().add(BinEntry(media.id, "keep", null))
            db.videoItemDao().updateReviewDecision(media.id, "ready_to_delete")
        }
        compose.onNodeWithTag("open_bin").performClick()
        await { compose.onAllNodesWithTag("bin_${media.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bin_${media.id}").assertIsDisplayed()
        compose.onNode(hasContentDescription("Recover") and hasAnyAncestor(hasTestTag("bin_${media.id}")), useUnmergedTree = true).performClick()
        await { query { db.binDao().entry(media.id) == null && db.videoItemDao().getVideoByIdDirect(media.id)?.reviewDecision == "keep" } }
        await { compose.onAllNodesWithTag("bin_${media.id}").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("bin_${media.id}").assertDoesNotExist()
        assertTrue("Recovery must preserve the physical image", fixtureExists())
    }

    @Test
    fun savedSettingsImmediatelyReachTheProductionFlowAndSurviveRecreation() {
        originalSettings = query { db.appSettingsDao().getSettingsDirect() }
        val model = productionViewModel()
        compose.runOnUiThread { model.updateSettings(false, "http://127.0.0.1:9/api/generate", fixtureToken, 999, false) }
        await { model.settings.value.ollamaModelName == fixtureToken && model.settings.value.requestTimeoutSeconds == 300 }
        val saved = query { db.appSettingsDao().getSettingsDirect() }
        assertEquals(fixtureToken, saved?.ollamaModelName)
        assertEquals(false, saved?.ollamaEnabled)
        compose.activityRule.scenario.recreate()
        val recreatedModel = productionViewModel()
        await { recreatedModel.settings.value.ollamaModelName == fixtureToken }
        assertEquals("http://127.0.0.1:9/api/generate", recreatedModel.settings.value.ollamaEndpointUrl)
        assertEquals(300, recreatedModel.settings.value.requestTimeoutSeconds)
    }

    @Test
    fun renameChangesBothPhysicalMediaStoreNameAndRoomWhilePreservingContents() {
        val media = createAndScanFixture()
        val before = requireNotNull(context.contentResolver.openInputStream(requireNotNull(fixtureUri))).use { it.readBytes() }
        val renamedBase = "${fixtureToken}_renamed"
        val model = productionViewModel()
        compose.runOnUiThread { model.renameVideoFile(media.id, renamedBase) }
        await { query { db.videoItemDao().getVideoByIdDirect(media.id)?.displayName == "$renamedBase.png" } }
        val physicalName = context.contentResolver.query(requireNotNull(fixtureUri), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            assertTrue(it.moveToFirst())
            it.getString(0)
        }
        assertEquals("$renamedBase.png", physicalName)
        val after = requireNotNull(context.contentResolver.openInputStream(requireNotNull(fixtureUri))).use { it.readBytes() }
        assertArrayEquals("A rename must preserve all image bytes", before, after)
    }

    @Test
    fun storageReconciliationRequiresProviderAbsenceAndPreservesSavedMetadata() {
        val media = createAndScanFixture()
        val model = productionViewModel()
        compose.runOnUiThread {
            model.saveEdits(media.id, "Reconciliation fixture notes", "Retained title", "Retained details", "Test", "Storage", "Fixture", "Retained roadmap")
        }
        await { query { db.analysisResultDao().getAnalysisForVideoDirect(media.id)?.descriptionLine1 == "Retained title" } }
        val savedAnalysis = query { db.analysisResultDao().getAnalysisForVideoDirect(media.id) }
        compose.runOnUiThread {
            model.clearOperationMessage()
            model.reconcileStorageAction(media.id, false)
        }
        await { model.operationMessage.value != null }
        assertTrue("A still-present provider URI must remain available", query { db.videoItemDao().getVideoByIdDirect(media.id)?.isCurrentlyAvailable == true })
        assertTrue(fixtureExists())
        assertEquals(1, context.contentResolver.delete(requireNotNull(fixtureUri), null, null))
        fixtureDeleted = true
        compose.runOnUiThread { model.reconcileStorageAction(media.id, false) }
        await { query { db.videoItemDao().getVideoByIdDirect(media.id)?.isCurrentlyAvailable == false } }
        assertEquals("Physical deletion must preserve saved analysis", savedAnalysis, query { db.analysisResultDao().getAnalysisForVideoDirect(media.id) })
        assertEquals("Reconciliation fixture notes", query { db.videoItemDao().getVideoByIdDirect(media.id)?.manualTranscript })
        assertFalse(fixtureExists())
        val unavailable = requireNotNull(query { db.videoItemDao().getVideoByIdDirect(media.id) })
        assertFalse("Unavailable media must not qualify for analysis", MediaAnalysisPolicy.canAnalyze(unavailable))
        compose.runOnUiThread { assertFalse("Production processing must reject unavailable media", model.processSingle(unavailable)) }
        navigate("Queue")
        await { model.filteredVideos.value.none { it.id == media.id } }
        compose.onNodeWithText(fixtureName).assertDoesNotExist()
    }

    @Test
    fun exportWritesActualStructuredJsonContainingTheIndexedMedia() {
        val media = createAndScanFixture()
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$fixtureToken.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/CodexWorkflow")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        exportUri = uri
        val result = AtomicReference<Boolean?>(null)
        val model = productionViewModel()
        compose.runOnUiThread { model.exportMetadata(uri) { result.set(it) } }
        await { result.get() != null }
        assertEquals("Export must report a completed write", true, result.get())
        val json = requireNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(1, json.getInt("schema"))
        val items = json.getJSONArray("media")
        val exported = (0 until items.length()).map { items.getJSONObject(it) }.single { it.getString("uri") == media.uriString }
        assertEquals(fixtureName, exported.getString("name"))
        assertEquals("unreviewed", exported.getString("reviewDecision"))
        assertTrue("Export must preserve the source image", fixtureExists())
    }

    @Test
    fun keptImageRunsRealOcrAndSavesLocalFallbackAnalysis() {
        originalSettings = query { db.appSettingsDao().getSettingsDirect() }
        val prefs = context.getSharedPreferences("app_prefs", 0)
        originalProvider = prefs.contains("llm_provider") to prefs.getString("llm_provider", null)
        prefs.edit().putString("llm_provider", "local").commit()
        query { db.appSettingsDao().insertSettings(requireNotNull(originalSettings).copy(ollamaEnabled = false)) }
        val media = createAndScanFixture(withText = true)
        val model = productionViewModel()
        compose.runOnUiThread { model.setReviewDecision(media.id, "keep") }
        await { query { db.videoItemDao().getVideoByIdDirect(media.id)?.reviewDecision == "keep" } }
        val kept = requireNotNull(query { db.videoItemDao().getVideoByIdDirect(media.id) })
        compose.runOnUiThread { assertTrue(model.processSingle(kept)) }
        compose.waitUntil(timeoutMillis = 60_000) {
            query { db.videoItemDao().getVideoByIdDirect(media.id)?.status in setOf("processed", "error") }
        }
        val completed = requireNotNull(query { db.videoItemDao().getVideoByIdDirect(media.id) })
        assertEquals("Real local processing must complete: ${completed.errorMessage}", "processed", completed.status)
        val analysis = query { db.analysisResultDao().getAnalysisForVideoDirect(media.id) }
        assertEquals("fallback", analysis?.source)
        assertTrue("ML Kit must extract the synthetic fixture's text", query {
            db.extractedFrameDao().getFramesForVideoDirect(media.id).any { it.ocrText.contains("ANDROID", ignoreCase = true) }
        })
        assertEquals("result_saved", completed.lastCompletedStep)
        assertTrue(fixtureExists())
    }

    @Test
    fun generatedVideoRunsRealFrameSamplingOcrAndLocalFallback() {
        originalSettings = query { db.appSettingsDao().getSettingsDirect() }
        val prefs = context.getSharedPreferences("app_prefs", 0)
        originalProvider = prefs.contains("llm_provider") to prefs.getString("llm_provider", null)
        prefs.edit().putString("llm_provider", "local").commit()
        query { db.appSettingsDao().insertSettings(requireNotNull(originalSettings).copy(ollamaEnabled = false)) }
        grantMediaReadPermissions()
        navigate("Sources")
        compose.onNodeWithTag("inventory_scan_button").performClick()
        val fixtureVideoName = InstrumentationRegistry.getArguments().getString("rea_video_fixture")
            ?: "codex_emulator_workflow.mp4"
        await { query { db.videoItemDao().getAllVideosDirect().any { it.displayName == fixtureVideoName && it.isCurrentlyAvailable } } }
        val media = query { db.videoItemDao().getAllVideosDirect().single { it.displayName == fixtureVideoName && it.isCurrentlyAvailable } }
        recordScannedRows()
        val model = productionViewModel()
        if (media.status != "new") {
            compose.runOnUiThread { model.resetVideo(media.id) }
            await { query {
                db.videoItemDao().getVideoByIdDirect(media.id)?.status == "new" &&
                    db.analysisResultDao().getAnalysisForVideoDirect(media.id) == null &&
                    db.extractedFrameDao().getFramesForVideoDirect(media.id).isEmpty()
            } }
        }
        compose.runOnUiThread { model.setReviewDecision(media.id, "keep") }
        await { query { db.videoItemDao().getVideoByIdDirect(media.id)?.let { it.reviewDecision == "keep" && it.status == "new" } == true } }
        val kept = requireNotNull(query { db.videoItemDao().getVideoByIdDirect(media.id) })
        compose.runOnUiThread { assertTrue(model.processSingle(kept)) }
        compose.waitUntil(timeoutMillis = 60_000) {
            query { db.videoItemDao().getVideoByIdDirect(media.id)?.status in setOf("processed", "error") }
        }
        val completed = requireNotNull(query { db.videoItemDao().getVideoByIdDirect(media.id) })
        assertEquals("Video processing must complete: ${completed.errorMessage}", "processed", completed.status)
        assertEquals("fallback", query { db.analysisResultDao().getAnalysisForVideoDirect(media.id)?.source })
        val frames = query { db.extractedFrameDao().getFramesForVideoDirect(media.id) }
        assertTrue("Sampling must produce timestamped video OCR evidence", frames.any { it.timestampMs > 0 && it.ocrText.contains("ANDROID", ignoreCase = true) })
        assertEquals("result_saved", completed.lastCompletedStep)
        assertTrue(context.contentResolver.query(Uri.parse(media.uriString), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true)
    }

    private fun navigate(label: String) {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Open menu").performClick()
        }
        compose.onNodeWithTag("nav_${label.lowercase()}").performClick()
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Close menu").performClick()
        }
        compose.waitForIdle()
    }

    private fun createAndScanFixture(withText: Boolean = false): VideoItem {
        grantMediaReadPermissions()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fixtureName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CodexWorkflow")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        fixtureUri = uri
        val bitmap = if (withText) {
            Bitmap.createBitmap(720, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                Canvas(this).drawText("ANDROID WORKFLOW TEST", 20f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 44f
                })
            }
        } else Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        try {
            requireNotNull(context.contentResolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        navigate("Sources")
        compose.onNodeWithTag("inventory_scan_button").performClick()
        await { query { db.videoItemDao().getVideoByUri(uri.toString()) != null } }
        val media = requireNotNull(query { db.videoItemDao().getVideoByUri(uri.toString()) })
        recordScannedRows()
        await { compose.onAllNodesWithTag("source_category_CodexWorkflow").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("unreviewed", media.reviewDecision)
        assertNull("Inventory must not trigger AI analysis", query { db.analysisResultDao().getAnalysisForVideo(media.id).first() })
        return media
    }

    private fun grantMediaReadPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        permissions.forEach { permission ->
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant ${context.packageName} $permission")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private fun fixtureExists(): Boolean = fixtureUri?.let { uri ->
        context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { it.moveToFirst() }
    } == true

    private fun await(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 15_000, condition = condition)
    private fun <T> query(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private fun fixtureRowTagAction(description: String): SemanticsMatcher {
        compose.onNode(hasScrollAction() and !hasSetTextAction() and hasAnyAncestor(hasTestTag("tags_screen")))
            .performScrollToNode(hasText(fixtureName))
        val bounds = compose.onNodeWithText(fixtureName).performScrollTo().fetchSemanticsNode().boundsInRoot
        return hasContentDescription(description) and SemanticsMatcher("Tag action vertically aligned with the exact fixture filename") { node ->
            node.boundsInRoot.center.y in bounds.top..bounds.bottom
        }
    }

    private fun productionViewModel(): VideoViewModel {
        var model: VideoViewModel? = null
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[VideoViewModel::class.java] }
        return requireNotNull(model)
    }
}
