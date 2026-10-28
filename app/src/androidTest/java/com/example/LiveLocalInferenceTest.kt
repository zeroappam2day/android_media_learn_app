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
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AppSettings
import com.example.data.VideoDatabase
import com.example.data.VideoItem
import com.example.data.VideoProcessor
import com.example.data.VideoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in integration tests requiring a real, local Ollama server, never a fake HTTP response. */
@RunWith(AndroidJUnit4::class)
class LiveLocalInferenceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val db get() = VideoDatabase.getDatabase(context)
    private val fixtureToken = "rea_live_${System.nanoTime()}"
    private var fixtureUri: Uri? = null
    private var fixtureId: Long? = null
    private var originalSettings: AppSettings? = null
    private val savedPrefs = mutableMapOf<String, Any?>()
    private var processingScope: CoroutineScope? = null

    @Before
    fun requireExplicitLiveLocalRun() {
        assumeTrue("Enable the live local integration explicitly with -e rea_live_ollama true",
            args.getString("rea_live_ollama") == "true")
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        val endpoint = args.getString("rea_ollama_endpoint") ?: "http://127.0.0.1:11434/api/generate"
        require(Uri.parse(endpoint).host in setOf("127.0.0.1", "localhost", "10.0.2.2")) {
            "Live acceptance must use a local Ollama server"
        }
        val model = args.getString("rea_ollama_model") ?: "qwen3.5:0.8b"
        require(!model.contains("cloud", ignoreCase = true) || args.getString("rea_allow_cloud_alias") == "true") {
            "Cloud model aliases require explicit authorization through the local Ollama endpoint"
        }
        val prefs = context.getSharedPreferences("app_prefs", 0)
        for (key in listOf("llm_provider", "llm_system_prompt", "llm_temperature")) {
            savedPrefs[key] = prefs.all[key]
        }
        prefs.edit().putString("llm_provider", "ollama_local")
            .putString("llm_system_prompt", "")
            .putFloat("llm_temperature", 0f).commit()
        originalSettings = query { db.appSettingsDao().getSettingsDirect() }
        query {
            db.appSettingsDao().insertSettings((originalSettings ?: AppSettings()).copy(
                ollamaEnabled = true,
                ollamaEndpointUrl = endpoint,
                ollamaModelName = model,
                requestTimeoutSeconds = 180,
                confirmedNonLocalEndpointWarning = false
            ))
        }
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        permissions.forEach { permission ->
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("pm grant ${context.packageName} $permission")).use { it.readBytes() }
        }
    }

    @After
    fun restoreOnlyTouchedSettingsAndFixtures() {
        processingScope?.cancel()
        originalSettings?.let { query { db.appSettingsDao().insertSettings(it) } }
        if (savedPrefs.isNotEmpty()) {
            context.getSharedPreferences("app_prefs", 0).edit().apply {
                savedPrefs.forEach { (key, value) ->
                    when (value) {
                        null -> remove(key)
                        is String -> putString(key, value)
                        is Float -> putFloat(key, value)
                    }
                }
            }.commit()
        }
        fixtureId?.let { id -> query { db.videoItemDao().getVideoByIdDirect(id)?.let { db.videoItemDao().deleteVideo(it) } } }
        fixtureUri?.let { context.contentResolver.delete(it, null, null) }
    }

    @Test
    fun imageOcrReachesRealLocalModelAndPersistsGroundedAnalysis() {
        val uri = createFixture("png", "image/png", MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures")
        val bitmap = Bitmap.createBitmap(1080, 480, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 54f }
            val canvas = Canvas(this)
            canvas.drawText("PYTHON VERSION CHECK", 28f, 105f, paint)
            canvas.drawText("Run python --version", 28f, 220f, paint)
            canvas.drawText("Print installed Python version", 28f, 335f, paint)
        }
        try {
            requireNotNull(context.contentResolver.openOutputStream(uri)).use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
        publishFixture(uri)
        processAndVerify(uri, "PYTHON", "image")
    }

    @Test
    fun videoFramesReachRealLocalModelAndPersistGroundedAnalysis() {
        val sourceName = args.getString("rea_video_fixture") ?: "codex_emulator_workflow.mp4"
        val sourceUri = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID), "${MediaStore.Video.Media.DISPLAY_NAME} = ?", arrayOf(sourceName), null
        )?.use { cursor ->
            assertTrue("The acceptance runner must provision the synthetic MP4", cursor.moveToFirst())
            android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
        }
        assertNotNull("The synthetic video fixture must exist", sourceUri)
        val uri = createFixture("mp4", "video/mp4", MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies")
        requireNotNull(context.contentResolver.openInputStream(requireNotNull(sourceUri))).use { input ->
            requireNotNull(context.contentResolver.openOutputStream(uri)).use { output -> input.copyTo(output) }
        }
        publishFixture(uri)
        processAndVerify(uri, "ANDROID", "video")
    }

    private fun processAndVerify(uri: Uri, expectedText: String, mediaKind: String) {
        val repository = VideoRepository(context)
        query { repository.scanLocalVideos() }
        val media = requireNotNull(query { db.videoItemDao().getVideoByUri(uri.toString()) })
        fixtureId = media.id
        query { db.videoItemDao().updateReviewDecision(media.id, "keep") }
        val kept: VideoItem = requireNotNull(query { db.videoItemDao().getVideoByIdDirect(media.id) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        processingScope = scope
        val processor = VideoProcessor(repository, scope)
        val started = System.nanoTime()
        processor.processSingleVideo(kept)
        runBlocking {
            withTimeout(210_000) {
                while (query { db.videoItemDao().getVideoByIdDirect(media.id)?.status } !in setOf("processed", "error")) delay(200)
            }
        }
        val completed = requireNotNull(query { db.videoItemDao().getVideoByIdDirect(media.id) })
        val frames = query { db.extractedFrameDao().getFramesForVideoDirect(media.id) }
        assertEquals("Pipeline failed: ${completed.errorMessage}", "processed", completed.status)
        assertEquals("result_saved", completed.lastCompletedStep)
        assertTrue("Real OCR must contain fixture evidence", frames.any { it.ocrText.contains(expectedText, ignoreCase = true) })
        if (mediaKind == "video") assertTrue("Video evidence must have sampled timestamps", frames.any { it.timestampMs > 0 })
        val analysis = requireNotNull(query { db.analysisResultDao().getAnalysisForVideoDirect(media.id) })
        assertEquals("A deterministic fallback is not proof of live model inference", "ollama", analysis.source)
        assertEquals("Technical", analysis.categoryLevel1)
        assertTrue(analysis.descriptionLine1.isNotBlank())
        assertTrue(analysis.categoryLevel2.isNotBlank())
        assertTrue(analysis.categoryLevel3.isNotBlank())
        val rendered = listOf(analysis.descriptionLine1, analysis.descriptionLine2,
            analysis.formattedActionPlan, analysis.evidenceJson, analysis.codeOrCommands.joinToString(" ")).joinToString("\n")
        assertTrue("Model output must retain a real fixture fact", rendered.contains(expectedText, ignoreCase = true))
        assertTrue("Fixtures contain no URLs; the model must not invent any", analysis.urls.isEmpty())
        if (mediaKind == "image") {
            assertEquals("Only the command written on the image may be returned",
                listOf("python --version"), analysis.codeOrCommands)
        } else {
            assertTrue("The title-only video contains no executable commands", analysis.codeOrCommands.isEmpty())
        }
        assertEquals("Each fixture must make one real model request", 1, repository.mediaOperationCounters.llmRequests)
        val evidence = JSONObject().put("kind", mediaKind).put("source", analysis.source)
            .put("model", args.getString("rea_ollama_model") ?: "qwen3.5:0.8b")
            .put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
            .put("ocrFrames", frames.size).put("description", analysis.descriptionLine1)
            .put("category", analysis.categoryLevel1).put("evidence", analysis.evidenceJson)
        Log.i("LiveLocalInference", evidence.toString())
        java.io.File(context.getExternalFilesDir(null), "qa-ollama-$mediaKind.json").writeText(
            JSONObject().put("kind", mediaKind).put("source", analysis.source)
                .put("model", args.getString("rea_ollama_model"))
                .put("runId", args.getString("qa_run_id"))
                .put("passed", true).put("ocrFrames", frames.size)
                .put("modelRequests", repository.mediaOperationCounters.llmRequests)
                .put("elapsedMillis", (System.nanoTime() - started) / 1_000_000).toString()
        )
        if (mediaKind == "video" && args.getString("rea_keep_review_video") == "true") {
            Log.i("LiveLocalInference", "Retained synthetic review video: ${kept.displayName}")
            fixtureId = null
            fixtureUri = null
            originalSettings = null
            savedPrefs.clear()
        }
    }

    private fun createFixture(extension: String, mime: String, collection: Uri, folder: String): Uri {
        val uri = requireNotNull(context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$fixtureToken.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/REA_Live_Inference")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        fixtureUri = uri
        return uri
    }

    private fun publishFixture(uri: Uri) {
        assertEquals(1, context.contentResolver.update(uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
    }

    private fun <T> query(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}
