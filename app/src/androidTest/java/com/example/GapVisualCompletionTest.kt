package com.example

import android.app.Application
import androidx.compose.ui.graphics.toArgb
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

/** Production UI screenshots and geometry evidence; synthetic owned media and injected empty credentials only. */
@RunWith(AndroidJUnit4::class)
class GapVisualCompletionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val db get() = VideoDatabase.getDatabase(context)
    private val dao get() = db.videoItemDao()
    private val prefs get() = context.getSharedPreferences("app_prefs", 0)
    private val token = "VisualGapQA${System.nanoTime()}"
    private val owned = linkedMapOf<Uri, ByteArray>()
    private val ids = mutableListOf<Long>()
    private var settingsBefore: AppSettings? = null
    private var providerBefore: String? = null
    private var autoBefore: Boolean? = null
    private var configured = false
    private var oldFolders: Set<String>? = null
    private val fontScale = androidx.compose.runtime.mutableStateOf(1f)
    private val dark = androidx.compose.runtime.mutableStateOf(false)
    @Volatile private var palette: Map<String, Int> = emptyMap()
    private val snapshots = org.json.JSONArray()
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
        oldFolders = if (prefs.contains("target_folders")) prefs.getStringSet("target_folders", emptySet())!!.toSet() else null
        configured = true
        prefs.edit().putString("llm_provider", "ollama_local").putBoolean("auto_ingest_enabled", false)
            .putStringSet("target_folders", setOf(tree("Pictures/$token"), tree("Movies/$token"))).commit()
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
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, fontScale.value)) {
                MyApplicationTheme(darkTheme = dark.value) {
                    val colors = androidx.compose.material3.MaterialTheme.colorScheme
                    androidx.compose.runtime.SideEffect {
                        palette = mapOf("surface" to colors.surface.toArgb(), "onSurface" to colors.onSurface.toArgb(),
                            "surfaceVariant" to colors.surfaceVariant.toArgb(), "onSurfaceVariant" to colors.onSurfaceVariant.toArgb(),
                            "primary" to colors.primary.toArgb(), "onPrimary" to colors.onPrimary.toArgb(),
                            "outline" to colors.outline.toArgb())
                    }
                    MainScreen(viewModel = vm, isDark = dark.value, onToggleTheme = { dark.value = !dark.value })
                }
            }
        }
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
            if (oldFolders == null) remove("target_folders") else putStringSet("target_folders", oldFolders)
            if (providerBefore == null) remove("llm_provider") else putString("llm_provider", providerBefore)
            if (autoBefore == null) remove("auto_ingest_enabled") else putBoolean("auto_ingest_enabled", requireNotNull(autoBefore))
        }.commit()
        context.deleteSharedPreferences("${token}_empty_credentials")
    }


    @Test fun t008_allOrdinaryDestinationsPanelsAndEditorSurfacesReadableInBothThemes() {
        val image = create("a_visual_reference.png", "image/png", textImage(listOf("QA VISUAL REFERENCE", "Clear readable content")))
        val archiveImage = create("b_archived_image.png", "image/png", textImage(listOf("ARCHIVED IMAGE")))
        val archiveVideo = create("c_archived_video.mp4", "video/mp4", instrumentation.context.assets.open("qa_no_text.mp4").use { it.readBytes() })
        val bin = create("d_bin_image.png", "image/png", textImage(listOf("BIN REVIEW")))
        io {
            dao.updateVideo(image.copy(status = "processed", manualTranscript = "QA readable notes — العربية 😀"))
            db.analysisResultDao().insertAnalysis(AnalysisResult(videoId = image.id,
                descriptionLine1 = "QA readable summary", descriptionLine2 = "QA readable details — العربية",
                categoryLevel1 = "QA", categoryLevel2 = "Visual", categoryLevel3 = "Review",
                formattedActionPlan = "1. Review the image.\n2. Keep useful information.", urls = emptyList(), codeOrCommands = emptyList(), evidenceJson = "[]", source = "edited"))
            dao.updateVideo(archiveImage.copy(status = "archived")); dao.updateVideo(archiveVideo.copy(status = "archived"))
            db.binDao().add(BinEntry(bin.id, "keep", null)); dao.updateReviewDecision(bin.id, "ready_to_delete")
        }
        for (theme in listOf("light", "dark")) {
            if (dark.value != (theme == "dark")) compose.onNodeWithContentDescription("Toggle Theme").performClick()
            compose.waitForIdle()
            for (destination in listOf("Home", "Folders", "Sources", "Queue", "Videos", "Images")) {
                navigate(destination)
                compose.onNodeWithTag("offcanvas_content").assertIsDisplayed()
                compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
                capture("$theme-${destination.lowercase()}")
                if (destination == "Home") {
                    compose.onNodeWithText("Overview").performClick()
                    capture("$theme-home-overview")
                    compose.onNodeWithText("Overview").performClick()
                }
            }
            navigate("Queue")
            action(image, "Preview").performClick()
            compose.onNodeWithText("Media Details Preview: ${image.displayName}").assertIsDisplayed()
            capture("$theme-preview-top")
            compose.onAllNodes(hasSetTextAction()).onLast().performScrollTo()
            capture("$theme-preview-fields")
            close()
            action(image, "Output").performClick()
            compose.onNodeWithText("QA readable summary", substring = false).assertIsDisplayed()
            capture("$theme-output"); close()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("AI setup").assertIsDisplayed(); capture("$theme-settings-top")
            compose.onNodeWithTag("settings_prompt").performScrollTo()
            capture("$theme-settings-prompt"); close()
            navigate("Sources")
            for (tool in listOf("rules", "tags", "bin")) {
                compose.onNodeWithTag("open_$tool").performClick()
                compose.onNodeWithTag("${tool}_screen").assertIsDisplayed(); capture("$theme-$tool")
                if (tool == "rules") {
                    for (rule in listOf("copy", "obscured")) {
                        compose.onNodeWithTag("add_${rule}_rule").performClick()
                        compose.onNodeWithText("Add rule", substring = false).assertIsDisplayed()
                        capture("$theme-rule-$rule")
                        compose.onNodeWithContentDescription("Cancel").performClick()
                    }
                }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithTag("media_sources_screen").assertIsDisplayed()
            }
            assertTrue("Normal body text must meet AA contrast", androidx.core.graphics.ColorUtils.calculateContrast(palette.getValue("onSurface"), palette.getValue("surface")) >= 4.5)
            assertTrue("Supporting text must meet AA contrast", androidx.core.graphics.ColorUtils.calculateContrast(palette.getValue("onSurfaceVariant"), palette.getValue("surface")) >= 4.5)
            assertTrue("Primary action text must meet AA contrast", androidx.core.graphics.ColorUtils.calculateContrast(palette.getValue("onPrimary"), palette.getValue("primary")) >= 4.5)
        }
        assertEquals(0, repo.mediaOperationCounters.llmRequests); assertEquals(0, repo.mediaOperationCounters.frameExtractions)
        assertBytes()
    }

    @Test fun t210_longNameLargeFontHasReadablePanelsAndExactNonoverlappingActionTargets() {
        val name = "q".repeat(196) + ".png"
        val target = create(name, "image/png", textImage(listOf("TARGET OWNED IMAGE")))
        val neighbor = create("z_neighbor.png", "image/png", textImage(listOf("NEIGHBOR OWNED IMAGE")))
        val before = io { dao.getAllVideosDirect().sortedBy { it.id } }
        fontScale.value = 1.5f
        for (theme in listOf("light", "dark")) {
            if (dark.value != (theme == "dark")) compose.onNodeWithContentDescription("Toggle Theme").performClick()
            navigate("Sources")
            if (compose.onAllNodesWithContentDescription("Show all sources").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithContentDescription("Show all sources").performClick()
            await { compose.onAllNodesWithTag("source_category_Camera").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("source_category_Camera").performScrollTo().performClick()
            compose.onNodeWithTag("source_media_${target.id}").assertIsDisplayed(); capture("large-$theme-sources")
            navigate("Queue"); compose.onNodeWithText(name, substring = false).performQaQueueScrollTo(compose).assertIsDisplayed()
            assertReadableText(compose.onNodeWithTag("app_title"), label = "title-$theme")
            val rename = compose.onNodeWithTag("queue_${target.id}_rename").performQaQueueScrollTo(compose).assertIsDisplayed()
            capture("large-$theme-queue-rename-before-layout-assert")
            assertReadableText(compose.onNodeWithText(name, substring = false).performQaQueueScrollTo(compose), label = "file-name-$theme")
            assertTrue("Rename must remain an accessible icon action", rename.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].contains("Rename"))
            val header = compose.onNodeWithText("Select all", substring = false)
            header.assertIsDisplayed()
            assertReadableText(header, singleLine = true, label = "bulk-selection-$theme")
            val viewport = compose.onNodeWithTag("queue_table_viewport").fetchSemanticsNode().boundsInRoot
            val content = compose.onNodeWithTag("queue_table_content").fetchSemanticsNode().boundsInRoot
            assertEquals("All queue fields must fit without horizontal scrolling", viewport.width, content.width, 1f)
            val folder = requireNotNull(target.relativePath).trim('/')
            assertReadableText(compose.onNode(hasText(folder, substring = false) and hasAnyAncestor(hasTestTag("queue_row_${target.id}"))).performQaQueueScrollTo(compose), label = "folder-$theme")
            assertReadableText(compose.onNode(hasText("New", substring = false) and hasAnyAncestor(hasTestTag("queue_row_${target.id}"))).performQaQueueScrollTo(compose), label = "status-$theme")
            rename.performQaQueueScrollTo(compose); capture("large-$theme-queue-identities")
            val controls = listOf("rename", "folder", "output", "preview", "process").map { suffix ->
                compose.onNodeWithTag("queue_${target.id}_$suffix").performQaQueueScrollTo(compose).assertIsDisplayed()
            }
            val actionBounds = controls.map { it.fetchSemanticsNode().boundsInRoot }
            val minimum = 48f * context.resources.displayMetrics.density - 1f
            actionBounds.forEach { bounds ->
                assertTrue("Queue target width must be at least 48dp", bounds.width >= minimum)
                assertTrue("Queue target height must be at least 48dp", bounds.height >= minimum)
            }
            actionBounds.forEachIndexed { i, bounds -> actionBounds.drop(i + 1).forEach { other ->
                assertFalse("Queue 48dp targets must not overlap", bounds.overlaps(other))
            } }
            capture("large-$theme-queue-actions")
            action(target, "Preview").performClick()
            compose.onNode(hasText(name, substring = false) and hasAnyAncestor(androidx.compose.ui.test.isDialog())).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("URI: ${target.uriString}", substring = false).assertExists()
            for (control in listOf("Close", "Minimize", "Full Window")) compose.onNodeWithContentDescription(control).assertIsDisplayed()
            capture("large-$theme-preview"); close()
        }
        assertEquals(before, io { dao.getAllVideosDirect().sortedBy { it.id } })
        assertEquals("new", row(neighbor.id)?.status)
        assertEquals(0, repo.mediaOperationCounters.llmRequests); assertEquals(0, repo.mediaOperationCounters.frameExtractions)
        assertBytes()
    }

    private fun assertReadableText(node: SemanticsNodeInteraction, singleLine: Boolean = false, label: String = "text") {
        node.assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { get ->
            assertTrue("Rendered text must expose its actual layout", get(layouts))
        }
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        val semantics = node.fetchSemanticsNode()
        val bounds = semantics.boundsInRoot
        val glyphWidth = (0 until layout.lineCount).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
        val input = layout.layoutInput
        val constraints = input.constraints
        val details = org.json.JSONObject()
            .put("label", label).put("text", input.text.text)
            .put("input_constraints", constraints.toString())
            .put("min_width", constraints.minWidth).put("max_width", constraints.maxWidth)
            .put("min_height", constraints.minHeight).put("max_height", constraints.maxHeight)
            .put("layout_size", layout.size.toString()).put("layout_width", layout.size.width).put("layout_height", layout.size.height)
            .put("paragraph_width", layout.multiParagraph.width).put("paragraph_height", layout.multiParagraph.height)
            .put("did_overflow_width", layout.didOverflowWidth).put("did_overflow_height", layout.didOverflowHeight)
            .put("has_visual_overflow", layout.hasVisualOverflow).put("line_count", layout.lineCount).put("glyph_width", glyphWidth)
            .put("soft_wrap", input.softWrap).put("max_lines", input.maxLines).put("overflow", input.overflow.toString())
            .put("font_size", input.style.fontSize.toString()).put("line_height", input.style.lineHeight.toString())
            .put("density", input.density.density).put("font_scale", input.density.fontScale)
            .put("bounds_in_root", bounds.toString()).put("position_in_root", semantics.positionInRoot.toString())
            .put("lines", org.json.JSONArray((0 until layout.lineCount).map { line ->
                org.json.JSONObject().put("index", line).put("start", layout.getLineStart(line)).put("end", layout.getLineEnd(line))
                    .put("left", layout.getLineLeft(line)).put("right", layout.getLineRight(line))
                    .put("top", layout.getLineTop(line)).put("bottom", layout.getLineBottom(line)).put("ellipsized", layout.isLineEllipsized(line))
            }))
        check(label.matches(Regex("[A-Za-z0-9-]+")))
        val directory = java.io.File(context.getExternalFilesDir(null), "qa71-visual").apply { mkdirs() }
        val allCharactersCovered = layout.lineCount > 0 && layout.getLineStart(0) == 0 &&
            layout.getLineEnd(layout.lineCount - 1, false) == input.text.length &&
            (1 until layout.lineCount).all { layout.getLineStart(it) == layout.getLineEnd(it - 1, false) }
        val noEllipsis = (0 until layout.lineCount).none { layout.isLineEllipsized(it) }
        val glyphsFitCanvas = (0 until layout.lineCount).all {
            layout.getLineLeft(it) >= -1f && layout.getLineRight(it) <= layout.size.width + 1f
        }
        val heightCovered = !layout.didOverflowHeight && layout.multiParagraph.height <= layout.size.height + 1f &&
            bounds.height + 1f >= layout.size.height
        val visibleWidthCovered = bounds.width + 1f >= glyphWidth
        details.put("all_characters_covered", allCharactersCovered).put("no_ellipsis", noEllipsis)
            .put("glyphs_fit_canvas", glyphsFitCanvas).put("height_covered", heightCovered).put("visible_width_covered", visibleWidthCovered)
        java.io.File(directory, "$token-layout-$label.json").writeText(details.toString(2))
        val message = details.toString()
        if (!allCharactersCovered || !noEllipsis || !glyphsFitCanvas || !heightCovered || !visibleWidthCovered || (singleLine && layout.lineCount != 1))
            println("QA71_TEXT_LAYOUT_FAILURE $message")
        assertTrue("Every input character must be represented by contiguous rendered lines: $message", allCharactersCovered)
        assertTrue("Rendered labels must not be ellipsized: $message", noEllipsis)
        assertTrue("Every line's actual glyphs must fit the measured text canvas: $message", glyphsFitCanvas)
        if (singleLine) assertEquals("Table labels and Rename must stay on one line: $message", 1, layout.lineCount)
        assertTrue("Text canvas and parent must cover the full rendered paragraph height: $message", heightCovered)
        assertTrue("Visible text space must fit its actual glyphs: $message", visibleWidthCovered)
    }

    private fun capture(label: String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(200, 5000)
        val protectedSettings = label.contains("settings")
        if (protectedSettings) {
            var secure = false
            instrumentation.runOnMainSync {
                secure = android.view.inspector.WindowInspector.getGlobalWindowViews().any { view ->
                    val attributes = view.layoutParams as? android.view.WindowManager.LayoutParams
                    attributes != null && attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0
                }
            }
            assertTrue("Settings privacy flag must remain enabled", secure)
            if (label.endsWith("settings-prompt")) compose.onNodeWithTag("settings_prompt").assertIsDisplayed()
            else compose.onNodeWithText("AI setup").assertIsDisplayed()
            compose.onAllNodesWithText("Access key", useUnmergedTree = true).assertCountEquals(0)
            val observed = if (label.endsWith("settings-prompt")) compose.onNodeWithTag("settings_prompt")
                else compose.onNodeWithText("AI setup")
            observed.assertIsDisplayed()
            val node = observed.fetchSemanticsNode()
            val bounds = node.boundsInRoot
            assertTrue("Protected Settings control must have visible geometry", bounds.width > 0f && bounds.height > 0f)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            observed.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { get ->
                assertTrue("Protected Settings text must expose measured layout", get(layouts))
            }
            assertTrue(layouts.isNotEmpty())
            val measured = layouts.last()
            val proof = org.json.JSONObject().put("label", label).put("flag_secure", secure)
                .put("visible", true).put("public_control", if (label.endsWith("settings-prompt")) "settings_prompt" else "AI setup")
                .put("bounds_in_root", bounds.toString()).put("width", bounds.width).put("height", bounds.height)
                .put("editable", node.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetText))
                .put("text_length", measured.layoutInput.text.length).put("line_count", measured.lineCount)
                .put("layout_width", measured.size.width).put("layout_height", measured.size.height)
                .put("font_size", measured.layoutInput.style.fontSize.toString()).put("font_scale", measured.layoutInput.density.fontScale)
                .put("palette", org.json.JSONObject().apply { palette.forEach { (k, v) -> put(k, v) } })
                .put("body_contrast", androidx.core.graphics.ColorUtils.calculateContrast(palette.getValue("onSurface"), palette.getValue("surface")))
                .put("supporting_contrast", androidx.core.graphics.ColorUtils.calculateContrast(palette.getValue("onSurfaceVariant"), palette.getValue("surface")))
            val evidenceDir = java.io.File(context.getExternalFilesDir(null), "qa71-visual").apply { mkdirs() }
            java.io.File(evidenceDir, "$token-protected-$label.json").writeText(proof.toString(2))
        }
        assertCaptureDestination(label)
        awaitTwoRenderedFrames()
        assertCaptureDestination(label)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val dir = java.io.File(context.getExternalFilesDir(null), "qa71-visual").apply { mkdirs() }
        val file = java.io.File(dir, "$token-$label.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val colors = org.json.JSONObject().apply { palette.forEach { (k, v) -> put(k, v) } }
        val contrasts = org.json.JSONObject()
            .put("body", androidx.core.graphics.ColorUtils.calculateContrast(palette.getValue("onSurface"), palette.getValue("surface")))
            .put("supporting", androidx.core.graphics.ColorUtils.calculateContrast(palette.getValue("onSurfaceVariant"), palette.getValue("surface")))
            .put("outline_on_surface", androidx.core.graphics.ColorUtils.calculateContrast(palette.getValue("outline"), palette.getValue("surface")))
        snapshots.put(org.json.JSONObject().put("label", label).put("path", file.absolutePath).put("width", bitmap.width).put("height", bitmap.height)
            .put("sha256", java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
            .put("dark", dark.value).put("font_scale", fontScale.value).put("palette", colors).put("contrast", contrasts)
            .put("settings_flag_secure_preserved", protectedSettings)
            .put("settings_screenshot_redaction_expected", protectedSettings))
        bitmap.recycle()
        java.io.File(dir, "$token-index.json").writeText(org.json.JSONObject().put("package", context.packageName).put("frames", snapshots).toString(2))
    }

    private fun create(name: String, mime: String, bytes: ByteArray): VideoItem {
        val video = mime.startsWith("video/")
        val folder = "${if (video) "Movies" else "Pictures"}/$token/Camera/"
        val created = requireNotNull(context.contentResolver.insert(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder); put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        owned[created] = bytes
        context.contentResolver.openOutputStream(created)!!.use { it.write(bytes) }
        context.contentResolver.update(created, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val item = VideoItem(mediaStoreId = android.content.ContentUris.parseId(created), uriString = created.toString(), displayName = name,
            relativePath = folder, mimeType = mime, sizeBytes = bytes.size.toLong(), durationMs = null, dateAdded = null, dateModified = null,
            reviewDecision = "keep", status = "new", sourceCategory = "Camera", mediaCollection = if (video) "video" else "image")
        return item.copy(id = io { dao.insertVideo(item) }.also { ids += it })
    }
    private fun process(item: VideoItem) {
        // A terminal DB row may precede the processor's final delay; wait for its
        // prior job to settle so a real retry click is not intentionally ignored.
        await(15000) { vm.processor.processorState.value != VideoProcessor.State.PROCESSING }
        navigate("Queue")
        await { compose.onAllNodesWithText(item.displayName, substring = false).fetchSemanticsNodes().isNotEmpty() }
        action(item, "Preview").performClick()
        compose.onNode(hasContentDescription("Process file") and hasClickAction()).performScrollTo().assertIsDisplayed().performClick()
    }
    private fun verifyOutput(item: VideoItem) {
        val analysis = io { requireNotNull(db.analysisResultDao().getAnalysisForVideoDirect(item.id)) }
        assertEquals("fallback", analysis.source)
        assertEquals(0, repo.mediaOperationCounters.llmRequests)
        close()
        action(item, "Output").assertIsEnabled().performClick()
        compose.onNodeWithText("Processed Result Summary: ${item.displayName}").assertIsDisplayed()
        compose.onNodeWithText(analysis.descriptionLine1, substring = false).assertExists()
        compose.onNodeWithText(analysis.descriptionLine2, substring = false).assertExists()
        close()
    }
    private fun sourceVideo(name: String): Uri = context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media._ID), "${MediaStore.Video.Media.DISPLAY_NAME} = ?", arrayOf(name), null)!!.use { cursor ->
        check(name in setOf("codex_emulator_workflow.mp4", "qa_no_text.mp4"))
        check(cursor.count == 1 && cursor.moveToFirst()) { "Provision exactly one approved synthetic $name" }
        android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
    }
    private fun rewrite(uri: Uri, bytes: ByteArray) {
        check(uri in owned)
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        owned[uri] = bytes
    }
    private fun textImage(lines: List<String>, jpeg: Boolean = false): ByteArray {
        val bitmap = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 58f }
            lines.forEachIndexed { index, line -> canvas.drawText(line, 30f, 110f + index * 135f, paint) }
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
    private fun tree(path: String) = "content://com.android.externalstorage.documents/tree/" + Uri.encode("primary:$path")
    private fun digest(uri: Uri): ByteArray = java.security.MessageDigest.getInstance("SHA-256").let { hash ->
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val buffer = ByteArray(8192)
            while (true) { val size = input.read(buffer); if (size < 0) break; hash.update(buffer, 0, size) }
        }; hash.digest()
    }
    private fun row(id: Long) = io { dao.getVideoByIdDirect(id) }
    private fun assertBytes() { owned.forEach { (uri, bytes) -> assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }) } }
    private fun action(item: VideoItem, label: String): SemanticsNodeInteraction {
        val eligible = io { dao.getAllVideosDirect() }.filter { it.isCurrentlyAvailable && !it.isPending && !it.isTrashed && it.status != "archived" }
        check(eligible.any { it.id == item.id } && eligible.all { it.id in ids }) { "Queue ownership not proven" }
        val suffix = mapOf("Folder" to "folder", "Output" to "output", "Preview" to "preview", "Process" to "process")[label]
        if (suffix != null) {
            val tagged = compose.onNodeWithTag("queue_${item.id}_$suffix")
            if (tagged.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick))
                return tagged.performQaQueueScrollTo(compose).assertIsDisplayed()
        }
        val name = compose.onNodeWithText(item.displayName, substring = false).assertIsDisplayed().fetchSemanticsNode()
        val matcher = hasContentDescription(label) and hasClickAction()
        val nodes = compose.onAllNodes(matcher).fetchSemanticsNodes()
        check(nodes.isNotEmpty())
        if (eligible.size == 1 && nodes.size == 1) return compose.onAllNodes(matcher)[0].assertIsDisplayed()
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
    private fun close() { compose.waitForIdle(); compose.onNode(hasContentDescription("Close") and hasClickAction()).performClick() }
    private fun navigate(label: String) {
        compose.waitForIdle()
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Open menu").performClick()
        await { compose.onAllNodesWithTag("nav_${label.lowercase()}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_${label.lowercase()}").assertIsDisplayed().performClick()
        // Navigation closes its own drawer; a second toggle can reopen it during recomposition.
        awaitMenuClosed()
        assertDestination(label)
    }
    private fun awaitMenuClosed() {
        compose.waitForIdle()
        await {
            compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("nav_panel").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }
    private fun assertDestination(label: String) {
        compose.onNode(hasText(label, substring = false) and hasAnyAncestor(hasTestTag("offcanvas_content"))).assertIsDisplayed()
        when (label) {
            "Home" -> compose.onNodeWithText("Overview", substring = false).assertIsDisplayed()
            "Folders" -> compose.onNodeWithText("Target Watch Folders", substring = false).assertIsDisplayed()
            "Sources" -> compose.onNodeWithTag("media_sources_screen").assertIsDisplayed()
            "Queue" -> compose.onNodeWithTag("queue_table_viewport").assertIsDisplayed()
            "Videos" -> compose.onNodeWithText("c_archived_video.mp4", substring = false).assertIsDisplayed()
            "Images" -> compose.onNodeWithText("b_archived_image.png", substring = false).assertIsDisplayed()
            else -> error("No destination guard for $label")
        }
    }
    private fun assertCaptureDestination(label: String) {
        awaitMenuClosed()
        when {
            label.endsWith("-home-overview") -> {
                assertDestination("Home")
                compose.onNodeWithText("New", substring = false).assertIsDisplayed()
                compose.onNodeWithText("Proc.", substring = false).assertIsDisplayed()
            }
            label.contains("settings") -> {
                if (label.endsWith("settings-prompt")) compose.onNodeWithTag("settings_prompt").assertIsDisplayed()
                else compose.onNodeWithText("AI setup").assertIsDisplayed()
            }
            label.contains("rule-copy") || label.contains("rule-obscured") ->
                compose.onNode(hasText("Add rule", substring = false) and hasAnyAncestor(androidx.compose.ui.test.isDialog())).assertIsDisplayed()
            label.endsWith("-rules") || label.endsWith("-tags") || label.endsWith("-bin") -> {
                val tool = label.substringAfterLast('-')
                compose.onNodeWithTag("${tool}_screen").assertIsDisplayed()
                if (tool == "bin") {
                    val filename = compose.onNode(hasText("d_bin_image.png", substring = false) and hasAnyAncestor(hasTestTag("bin_screen"))).assertIsDisplayed()
                    val textBounds = filename.fetchSemanticsNode().boundsInRoot
                    val handleBounds = compose.onNodeWithContentDescription("Open menu").fetchSemanticsNode().boundsInRoot
                    assertFalse("Collapsed drawer handle must not overlap the Bin filename", textBounds.overlaps(handleBounds))
                    assertTrue("Bin filename must reserve the collapsed handle's horizontal extent", textBounds.left >= handleBounds.right)
                }
            }
            label.contains("-preview") ->
                compose.onNode(hasText("Media Details Preview:", substring = true) and hasAnyAncestor(androidx.compose.ui.test.isDialog())).assertIsDisplayed()
            label.endsWith("-output") ->
                compose.onNode(hasText("QA readable summary", substring = false) and hasAnyAncestor(androidx.compose.ui.test.isDialog())).assertIsDisplayed()
            label.contains("-queue") -> assertDestination("Queue")
            else -> {
                val destination = label.substringAfterLast('-').replaceFirstChar { it.uppercase() }
                check(destination in setOf("Home", "Folders", "Sources", "Videos", "Images")) { "No capture guard for $label" }
                assertDestination(destination)
            }
        }
    }
    private fun awaitTwoRenderedFrames() {
        repeat(2) {
            val frame = java.util.concurrent.CountDownLatch(1)
            instrumentation.runOnMainSync { compose.activity.window.decorView.postOnAnimation { frame.countDown() } }
            check(frame.await(2, java.util.concurrent.TimeUnit.SECONDS)) { "Android frame did not arrive before screenshot" }
        }
        instrumentation.waitForIdleSync()
    }

    private fun await(timeout: Long = 15000, predicate: () -> Boolean) = compose.waitUntil(timeout, predicate)
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}
