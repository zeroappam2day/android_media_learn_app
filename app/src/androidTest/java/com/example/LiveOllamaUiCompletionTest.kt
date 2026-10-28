package com.example

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.*
import com.example.ui.VideoViewModel
import com.example.ui.theme.MyApplicationTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * Opt-in live integration draft. The test-manifest ComponentActivity hosts the actual
 * production MainScreen with the actual VideoViewModel/VideoRepository/VideoProcessor.
 * Empty owned credential preferences are injected before constructing the view model;
 * MainActivity's default repository is deliberately never constructed by this host.
 * Real clicks enter Queue -> Preview -> Process media -> Close -> Output.
 * Images is the archive destination and does not offer the unprocessed-image route.
 * No fake HTTP client, canned analysis, settings UI, real credential access, global scan,
 * process invocation from the test, or mutation of the read-only source video.
 */
@RunWith(AndroidJUnit4::class)
class LiveOllamaUiCompletionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val db get() = VideoDatabase.getDatabase(context)
    private val token = "qa_live_ui_${System.nanoTime()}"
    private val prefs get() = context.getSharedPreferences("app_prefs", 0)
    private var oldProvider: String? = null
    private var oldPrompt: String? = null
    private var oldTemperature: Float? = null
    private var oldAuto: Boolean? = null
    private var oldSettings: AppSettings? = null
    private var configured = false
    private var vm: VideoViewModel? = null
    private var repository: VideoRepository? = null
    private var uri: Uri? = null
    private var id: Long? = null
    private var fixtureDigest: ByteArray? = null
    private lateinit var model: String

    @Before fun requireExplicitApprovedRouteAndInstallProductionScreen() {
        assumeTrue("Pass -e rea_live_ollama true", args.getString("rea_live_ollama") == "true")
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview")) { "Dedicated QA preview required" }
        check(args.getString("rea_runtime") == "emulator")
        val endpoint = args.getString("rea_ollama_endpoint") ?: "http://127.0.0.1:11434/api/generate"
        val parsed = Uri.parse(endpoint)
        check(parsed.scheme == "http" && parsed.host in setOf("127.0.0.1", "localhost", "10.0.2.2") && parsed.userInfo == null) {
            "Only the approved local Ollama HTTP route is accepted"
        }
        model = requireNotNull(args.getString("rea_ollama_model")) { "Pass the exact approved DeepSeek cloud model alias" }
        check(model.contains("deepseek", ignoreCase = true) && model.contains("cloud", ignoreCase = true))
        check(args.getString("rea_allow_cloud_alias") == "true") { "Explicit cloud-alias opt-in required" }
        oldProvider = if (prefs.contains("llm_provider")) prefs.getString("llm_provider", null) else null
        oldPrompt = if (prefs.contains("llm_system_prompt")) prefs.getString("llm_system_prompt", null) else null
        oldTemperature = if (prefs.contains("llm_temperature")) prefs.getFloat("llm_temperature", 0f) else null
        oldAuto = if (prefs.contains("auto_ingest_enabled")) prefs.getBoolean("auto_ingest_enabled", false) else null
        oldSettings = io { db.appSettingsDao().getSettingsDirect() }
        configured = true
        prefs.edit().putString("llm_provider", "ollama_local").putString("llm_system_prompt", "")
            .putFloat("llm_temperature", 0f).putBoolean("auto_ingest_enabled", false).commit()
        io { db.appSettingsDao().insertSettings((oldSettings ?: AppSettings()).copy(ollamaEnabled = true,
            ollamaEndpointUrl = endpoint, ollamaModelName = model, requestTimeoutSeconds = 180,
            confirmedNonLocalEndpointWarning = false)) }
        val emptyCredentials = context.getSharedPreferences("${token}_empty_credentials", 0)
        check(emptyCredentials.edit().clear().commit())
        val repo = VideoRepository(context, credentialPreferences = emptyCredentials)
        repository = repo
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                check(modelClass == VideoViewModel::class.java)
                @Suppress("UNCHECKED_CAST")
                return VideoViewModel(context.applicationContext as Application, repo) as T
            }
        }
        compose.activityRule.scenario.onActivity { activity -> vm = ViewModelProvider(activity, factory)[VideoViewModel::class.java] }
        compose.setContent { MyApplicationTheme { MainScreen(viewModel = requireNotNull(vm), isDark = false, onToggleTheme = {}) } }
    }

    @After fun stopOwnedWorkRestoreSettingsAndDeleteOwnedFixture() {
        // Clearing this host's store cancels its processor/viewModelScope and observers.
        compose.activityRule.scenario.onActivity { it.viewModelStore.clear() }
        io {
            id?.let { ownedId ->
                db.extractedFrameDao().deleteFramesForVideo(ownedId)
                db.analysisResultDao().deleteAnalysisForVideo(ownedId)
                db.videoItemDao().getVideoByIdDirect(ownedId)?.let { db.videoItemDao().deleteVideo(it) }
            }
            if (configured) {
                if (oldSettings != null) db.appSettingsDao().insertSettings(requireNotNull(oldSettings))
                else db.openHelper.writableDatabase.execSQL("DELETE FROM app_settings WHERE id = 1")
            }
        }
        uri?.let { context.contentResolver.delete(it, null, null) }
        if (configured) prefs.edit().apply {
            if (oldProvider == null) remove("llm_provider") else putString("llm_provider", oldProvider)
            if (oldPrompt == null) remove("llm_system_prompt") else putString("llm_system_prompt", oldPrompt)
            if (oldTemperature == null) remove("llm_temperature") else putFloat("llm_temperature", requireNotNull(oldTemperature))
            if (oldAuto == null) remove("auto_ingest_enabled") else putBoolean("auto_ingest_enabled", requireNotNull(oldAuto))
        }.commit()
        context.deleteSharedPreferences("${token}_empty_credentials")
    }

    @Test fun photoPreviewProcessAndOutputUseRealDeepSeekCloudThroughLocalOllama() {
        val created = create("png", "image/png", false)
        val bitmap = Bitmap.createBitmap(1080, 480, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 54f }
            val canvas = Canvas(bitmap)
            canvas.drawText("PYTHON VERSION CHECK", 28f, 105f, paint)
            canvas.drawText("Run python --version", 28f, 220f, paint)
            canvas.drawText("Print installed Python version", 28f, 335f, paint)
            context.contentResolver.openOutputStream(created)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        publishAndSeed(created, video = false)
        processThroughUiAndVerify("PYTHON", video = false)
    }

    @Test fun videoPreviewProcessAndOutputUseRealDeepSeekCloudThroughLocalOllama() {
        // Exact hardcoded synthetic source name prevents arbitrary personal-media upload.
        val sourceName = "codex_emulator_workflow.mp4"
        val source = context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID), "${MediaStore.Video.Media.DISPLAY_NAME} = ?", arrayOf(sourceName), null)!!.use { cursor ->
            check(cursor.count == 1 && cursor.moveToFirst()) { "Provision exactly one approved synthetic codex_emulator_workflow.mp4" }
            android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
        }
        val sourceBefore = digest(source)
        val created = create("mp4", "video/mp4", true)
        context.contentResolver.openInputStream(source)!!.use { input -> context.contentResolver.openOutputStream(created)!!.use { output -> input.copyTo(output) } }
        publishAndSeed(created, video = true)
        processThroughUiAndVerify("ANDROID", video = true)
        assertArrayEquals("Read-only synthetic source must remain unchanged", sourceBefore, digest(source))
    }

    private fun processThroughUiAndVerify(expected: String, video: Boolean) {
        val ownedId = requireNotNull(id)
        val ownedItem = io { requireNotNull(db.videoItemDao().getVideoByIdDirect(ownedId)) }
        navigateQueue()
        compose.waitUntil(15000) { compose.onAllNodesWithText(ownedItem.displayName, substring = false).fetchSemanticsNodes().isNotEmpty() }
        rowAction(ownedItem.displayName, "Preview").performClick()
        compose.onNodeWithText("Media Details Preview: ${ownedItem.displayName}").assertIsDisplayed()
        val requestsBefore = requireNotNull(repository).mediaOperationCounters.llmRequests
        compose.onNodeWithContentDescription("Process media").performScrollTo().performClick()
        // Poll persisted terminal state; UI remains responsive throughout real OCR/model work.
        compose.waitUntil(240000) { io { db.videoItemDao().getVideoByIdDirect(ownedId)?.status in setOf("processed", "error") } }
        val completed = io { requireNotNull(db.videoItemDao().getVideoByIdDirect(ownedId)) }
        assertEquals("Live pipeline failed: ${completed.errorMessage}", "processed", completed.status)
        assertEquals("result_saved", completed.lastCompletedStep)
        val frames = io { db.extractedFrameDao().getFramesForVideoDirect(ownedId) }
        assertTrue("Actual OCR must contain the known synthetic fixture fact", frames.any { it.ocrText.contains(expected, ignoreCase = true) })
        if (video) assertTrue(frames.any { it.timestampMs > 0 })
        val analysis = io { requireNotNull(db.analysisResultDao().getAnalysisForVideoDirect(ownedId)) }
        assertEquals("Fallback does not prove live model inference", "ollama", analysis.source)
        assertEquals("Technical", analysis.categoryLevel1)
        assertTrue(analysis.descriptionLine1.isNotBlank())
        assertTrue(analysis.descriptionLine2.isNotBlank())
        assertTrue(analysis.categoryLevel2.isNotBlank())
        assertTrue(analysis.categoryLevel3.isNotBlank())
        val facts = listOf(analysis.descriptionLine1, analysis.descriptionLine2, analysis.formattedActionPlan,
            analysis.evidenceJson, analysis.codeOrCommands.joinToString(" ")).joinToString("\n")
        assertTrue("Model must retain an observed fixture fact", facts.contains(expected, ignoreCase = true))
        assertTrue("No fixture contains a URL", analysis.urls.isEmpty())
        if (video) assertTrue("Title-only video contains no commands", analysis.codeOrCommands.isEmpty())
        else assertEquals(listOf("python --version"), analysis.codeOrCommands)
        assertEquals("Exactly one real model request must be attempted", requestsBefore + 1, requireNotNull(repository).mediaOperationCounters.llmRequests)
        compose.onNodeWithContentDescription("Close").performClick()
        rowAction(ownedItem.displayName, "Output").assertIsEnabled().performClick()
        compose.onNodeWithText("Processed Result Summary: ${ownedItem.displayName}").assertIsDisplayed()
        compose.onNodeWithText("${analysis.categoryLevel1} > ${analysis.categoryLevel2} > ${analysis.categoryLevel3}").assertExists()
        compose.onNodeWithText(analysis.descriptionLine1, substring = false).assertExists()
        compose.onNodeWithText(analysis.descriptionLine2, substring = false).assertExists()
        compose.onNodeWithText(analysis.formattedActionPlan, substring = false).assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        assertArrayEquals("Owned original media bytes must survive processing", fixtureDigest, digest(requireNotNull(uri)))
    }

    private fun create(extension: String, mime: String, video: Boolean): Uri {
        val created = requireNotNull(context.contentResolver.insert(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "000_$token.$extension")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${if (video) "Movies" else "Pictures"}/$token/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        uri = created
        return created
    }
    private fun publishAndSeed(created: Uri, video: Boolean) {
        assertEquals(1, context.contentResolver.update(created, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
        fixtureDigest = digest(created)
        val metadata = context.contentResolver.query(created, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null, null)!!.use { cursor ->
            check(cursor.moveToFirst()); cursor.getString(0) to cursor.getLong(1)
        }
        id = io { db.videoItemDao().insertVideo(VideoItem(mediaStoreId = android.content.ContentUris.parseId(created), uriString = created.toString(),
            displayName = metadata.first, relativePath = "${if (video) "Movies" else "Pictures"}/$token/", mimeType = if (video) "video/mp4" else "image/png",
            sizeBytes = metadata.second, durationMs = null, dateAdded = null, dateModified = null,
            reviewDecision = "keep", status = "new", mediaCollection = if (video) "video" else "image")) }
    }
    private fun digest(uri: Uri): ByteArray = MessageDigest.getInstance("SHA-256").let { hash ->
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        hash.digest()
    }
    private fun rowAction(name: String, label: String): SemanticsNodeInteraction {
        val ownedId = requireNotNull(id)
        val owned = io { requireNotNull(db.videoItemDao().getVideoByIdDirect(ownedId)) }
        check(owned.displayName == name && owned.uriString == requireNotNull(uri).toString())
        compose.onNodeWithText(name, substring = false).performQaQueueScrollTo(compose).assertIsDisplayed()
        val queueTag = "queue_${ownedId}_${label.lowercase()}"
        if (compose.onAllNodesWithTag(queueTag).fetchSemanticsNodes().size == 1) {
            return compose.onNodeWithTag(queueTag).performQaQueueScrollTo(compose).assertIsDisplayed()
        }
        val y = compose.onNodeWithText(name, substring = false).fetchSemanticsNode().boundsInRoot.center.y
        val actions = compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
        check(actions.isNotEmpty())
        val index = actions.indices.minBy { kotlin.math.abs(actions[it].boundsInRoot.center.y - y) }
        check(kotlin.math.abs(actions[index].boundsInRoot.center.y - y) < 70f) { "Action ownership not proven" }
        return compose.onAllNodesWithContentDescription(label)[index]
    }
    private fun navigateQueue() {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("nav_queue").performClick()
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close menu").performClick()
    }
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}
