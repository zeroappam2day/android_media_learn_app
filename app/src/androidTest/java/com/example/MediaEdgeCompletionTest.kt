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
 * Draft: real MainScreen with owned empty credentials, Device fallback, owned fixtures.
 * Clipboard is inspected only AFTER Copy Error writes the owned error text.
 * No provider network, Settings, broad scan scope, source-video writes, or real-key reads.
 * Assets expected: androidTest/assets/qa_no_text.mp4; optional qa_first_frame.gif whose
 * documented first frame reads QA GIF FIRST FRAME. GIF absence is a JUnit assumption skip.
 * Lifecycle recreation/force-stop is outside these tests; no restart coverage claimed.
 */
@RunWith(AndroidJUnit4::class)
class MediaEdgeCompletionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val db get() = VideoDatabase.getDatabase(context)
    private val dao get() = db.videoItemDao()
    private val prefs get() = context.getSharedPreferences("app_prefs", 0)
    private val token = "MediaEdgeQA${System.nanoTime()}"
    private val owned = linkedMapOf<Uri, ByteArray>()
    private val ids = mutableListOf<Long>()
    private var settingsBefore: AppSettings? = null
    private var providerBefore: String? = null
    private var autoBefore: Boolean? = null
    private var configured = false
    private var oldFolders: Set<String>? = null
    private val fontScale = androidx.compose.runtime.mutableStateOf(1f)
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
        prefs.edit().putString("llm_provider", "local").putBoolean("auto_ingest_enabled", false)
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
                MyApplicationTheme { MainScreen(viewModel = vm, isDark = false, onToggleTheme = {}) }
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

    @Test fun t088_t211_corruptVideoCopiesVisibleErrorThenSameUriRepairReachesOutput() {
        val broken = create("broken.mp4", "video/mp4", "owned corrupt QA media".toByteArray())
        process(broken)
        await(60000) { row(broken.id)?.status in setOf("error", "processed") }
        val failed = requireNotNull(row(broken.id))
        // This fixture is deliberately invalid; accepting a finished fallback would hide
        // a decoder failure. Retain a strict error oracle and inspect an unexpected result.
        assertEquals("Corrupt-video fixture must fail decoding visibly", "error", failed.status)
        val error = requireNotNull(failed.errorMessage)
        compose.onNodeWithText("Processing Error").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(error, substring = false) and hasAnyAncestor(androidx.compose.ui.test.isDialog())).assertExists()
        compose.onNode(hasContentDescription("Copy Error") and hasClickAction()).performScrollTo().performClick()
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        var copied: String? = null
        compose.runOnUiThread { copied = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }
        assertEquals(error, copied)
        compose.runOnUiThread { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Owned QA error cleared", "")) }
        close()
        val source = sourceVideo("codex_emulator_workflow.mp4")
        val sourceBefore = digest(source)
        val bytes = context.contentResolver.openInputStream(source)!!.use { it.readBytes() }
        rewrite(Uri.parse(broken.uriString), bytes)
        navigate("Sources")
        compose.onNodeWithTag("inventory_scan_button").performClick()
        await { row(broken.id)?.sizeBytes == bytes.size.toLong() }
        assertEquals("keep", row(broken.id)?.reviewDecision)
        assertEquals(broken.uriString, row(broken.id)?.uriString)
        assertEquals(1, io { dao.getAllVideosDirect().count { it.mediaStoreId == broken.mediaStoreId } })
        process(requireNotNull(row(broken.id)))
        await(60000) { row(broken.id)?.status == "processed" }
        assertNull(row(broken.id)?.errorMessage)
        val frames = io { db.extractedFrameDao().getFramesForVideoDirect(broken.id) }
        assertTrue(frames.any { it.ocrText.contains("ANDROID", ignoreCase = true) })
        assertTrue(frames.any { it.timestampMs > 0 })
        compose.onNodeWithText("Processing Error").assertDoesNotExist()
        verifyOutput(broken)
        assertArrayEquals(sourceBefore, digest(source))
        assertBytes()
    }

    @Test fun t212_blackSilentVideoProducesNoFabricatedOcrAndExplicitDeviceFallback() {
        val bytes = runCatching { instrumentation.context.assets.open("qa_no_text.mp4").use { it.readBytes() } }
            .getOrElse { context.contentResolver.openInputStream(sourceVideo("qa_no_text.mp4"))!!.use { input -> input.readBytes() } }
        val item = create("no_text.mp4", "video/mp4", bytes)
        process(item)
        await(60000) { row(item.id)?.status in setOf("processed", "error") }
        assertEquals("processed", row(item.id)?.status)
        assertTrue(io { db.extractedFrameDao().getFramesForVideoDirect(item.id).isEmpty() })
        compose.onNodeWithText("No on-screen frame text extracted yet. (Run processing to build timeline)").performScrollTo().assertIsDisplayed()
        val result = io { requireNotNull(db.analysisResultDao().getAnalysisForVideoDirect(item.id)) }
        assertEquals("fallback", result.source)
        assertTrue(result.urls.isEmpty())
        assertTrue(result.codeOrCommands.isEmpty())
        assertEquals("Deterministic fallback generated locally.", result.descriptionLine1)
        verifyOutput(item)
        assertBytes()
    }

    @Test fun t087_realOcrShowsObservedUrlCommandAndTimelineWithoutExternalNavigation() {
        val item = create("python_reference.png", "image/png", textImage(listOf("PYTHON REFERENCE", "https://example.com/qa", "pip install pytest")))
        process(item)
        await(60000) { row(item.id)?.status in setOf("processed", "error") }
        assertEquals("processed", row(item.id)?.status)
        val frames = io { db.extractedFrameDao().getFramesForVideoDirect(item.id) }
        val actualText = frames.joinToString("\n") { it.ocrText }
        assertTrue(actualText.contains("https://example.com/qa", ignoreCase = true))
        assertTrue(actualText.contains("pip install pytest", ignoreCase = true))
        assertTrue(frames.any { it.timestampMs == 0L })
        val result = io { requireNotNull(db.analysisResultDao().getAnalysisForVideoDirect(item.id)) }
        assertEquals("fallback", result.source)
        assertTrue(result.urls.contains("https://example.com/qa"))
        assertTrue(result.codeOrCommands.any { it.contains("pip install pytest") })
        compose.onNodeWithText("Extracted Links").performScrollTo().assertIsDisplayed()
        val url = compose.onNodeWithText("https://example.com/qa", substring = false)
        url.performScrollTo().assertIsDisplayed()
        url.assert(hasClickAction().not())
        url.performClick() // Actual display-only text tap; must keep the same modal visible.
        compose.onNodeWithText("Media Details Preview: ${item.displayName}").assertIsDisplayed()
        compose.onNodeWithText("Extracted Tech / Command Snippets").performScrollTo().assertIsDisplayed()
        result.codeOrCommands.forEach { compose.onNodeWithText(it, substring = false).assertExists() }
        verifyOutput(item)
        assertBytes()
    }

    @Test fun t210_twoHundredCharacterNameAtLargeFontTargetsExactOwnedImage() {
        fontScale.value = 1.5f // Explicit composition fontScale, not an OS accessibility setting.
        val name = "q".repeat(196) + ".png"
        assertEquals(200, name.length)
        val target = create(name, "image/png", textImage(listOf("TARGET OWNED IMAGE")))
        val neighbor = create("z_neighbor.png", "image/png", textImage(listOf("NEIGHBOR OWNED IMAGE")))
        val before = io { dao.getAllVideosDirect().sortedBy { it.id } }
        navigate("Sources")
        compose.onNodeWithTag("source_category_Camera").performClick()
        compose.onNodeWithTag("source_media_${target.id}").assertExists()
        navigate("Queue")
        action(target, "Preview").performClick()
        // Filename text semantics retains the full value even when title is ellipsized.
        compose.onNode(hasText(name, substring = false) and hasAnyAncestor(androidx.compose.ui.test.isDialog())).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("URI: ${target.uriString}", substring = false).assertExists()
        close()
        val seen = AtomicBoolean(false)
        instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && event.text.any { it.toString() == "Location: ${target.uriString}" }) seen.set(true)
        }
        try { action(target, "Folder").performClick(); await(5000) { seen.get() } }
        finally { instrumentation.uiAutomation.setOnAccessibilityEventListener(null) }
        assertEquals(before, io { dao.getAllVideosDirect().sortedBy { it.id } })
        assertEquals("new", row(neighbor.id)?.status)
        assertEquals(0, repo.mediaOperationCounters.llmRequests)
        assertEquals(0, repo.mediaOperationCounters.frameExtractions)
        assertBytes()
    }

    @Test fun t213_optionalDocumentedAnimatedGifFirstFrameAndTextJpeg() {
        val bytes = runCatching { instrumentation.context.assets.open("qa_first_frame.gif").use { it.readBytes() } }.getOrNull()
        org.junit.Assume.assumeTrue("Provide known two-frame qa_first_frame.gif with first-frame QA GIF FIRST FRAME text", bytes != null)
        @Suppress("DEPRECATION")
        val movie = android.graphics.Movie.decodeByteArray(requireNotNull(bytes), 0, bytes.size)
        check(movie != null && movie.duration() > 0) { "GIF fixture must be a documented animated image" }
        val gif = create("first_frame.gif", "image/gif", bytes)
        process(gif)
        await(60000) { row(gif.id)?.status in setOf("processed", "error") }
        assertEquals("Known-supported GIF must complete; an arbitrary error is not acceptance", "processed", row(gif.id)?.status)
        assertTrue(io { db.extractedFrameDao().getFramesForVideoDirect(gif.id).any { it.ocrText.contains("QA GIF FIRST FRAME") } })
        verifyOutput(gif)
        val jpeg = create("text.jpg", "image/jpeg", textImage(listOf("QA JPEG REFERENCE"), jpeg = true))
        process(jpeg)
        await(60000) { row(jpeg.id)?.status in setOf("processed", "error") }
        assertEquals("processed", row(jpeg.id)?.status)
        assertTrue(io { db.extractedFrameDao().getFramesForVideoDirect(jpeg.id).any { it.ocrText.contains("QA JPEG REFERENCE") } })
        verifyOutput(jpeg)
        assertBytes()
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
        compose.onNode(hasContentDescription("Process media") and hasClickAction()).performScrollTo().assertIsDisplayed().performClick()
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
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("nav_${label.lowercase()}").performClick()
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close menu").performClick()
        compose.waitForIdle()
    }
    private fun await(timeout: Long = 15000, predicate: () -> Boolean) = compose.waitUntil(timeout, predicate)
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}
