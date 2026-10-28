package com.example

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.VideoDatabase
import com.example.data.VideoItem
import com.example.ui.VideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Sends only a generated test image's words. The test never obtains the credential value. */
class LiveCloudInferenceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dao get() = VideoDatabase.getDatabase(context).videoItemDao()
    private var uri: Uri? = null
    private var itemId: Long? = null

    @Before fun requireExplicitEmulatorCloudCheck() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(InstrumentationRegistry.getArguments().getString("qa_cloud_authorized") == "true") { "Explicit Cloud test authorization required" }
        check(context.getSharedPreferences("app_prefs", 0).getString("llm_provider", "") == "ollama_cloud") { "Save Cloud settings in the emulator first" }
    }
    @After fun removeOnlyTheGeneratedFixture() {
        query { itemId?.let { dao.getVideoByIdDirect(it)?.let { item -> dao.deleteVideo(item) } } }
        uri?.let { context.contentResolver.delete(it, null, null) }
    }

    @Test fun generatedImageReceivesRealCloudAnalysisWithoutCredentialInspection() {
        val name = "qa_cloud_${System.nanoTime()}.png"
        val created = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QACloud/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        uri = created
        val bitmap = Bitmap.createBitmap(1080, 480, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 46f }
            val canvas = Canvas(this)
            listOf("BASIL PLANTING GUIDE", "1. Fill a pot with soil.", "2. Plant basil seeds.", "3. Water the soil gently.").forEachIndexed { i, text -> canvas.drawText(text, 30f, 90f + i * 95f, paint) }
        }
        context.contentResolver.openOutputStream(created)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        context.contentResolver.update(created, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        val item = VideoItem(mediaStoreId = created.lastPathSegment!!.toLong(), uriString = created.toString(), displayName = name,
            relativePath = "Pictures/QACloud/", mimeType = "image/png", sizeBytes = null, durationMs = null,
            dateAdded = null, dateModified = null, reviewDecision = "keep", status = "new")
        val id = query { dao.insertVideo(item) }
        itemId = id
        compose.runOnUiThread { ViewModelProvider(compose.activity)[VideoViewModel::class.java].processSingle(item.copy(id = id)) }
        compose.waitUntil(180000) { query { dao.getVideoByIdDirect(id)?.status in setOf("processed", "error") } }
        val analysis = query { VideoDatabase.getDatabase(context).analysisResultDao().getAnalysisForVideoDirect(id) }
        assertTrue("Cloud must return analysis rather than a local fallback", analysis?.source == "ollama")
        val text = listOf(analysis?.descriptionLine1, analysis?.descriptionLine2, analysis?.categoryLevel2, analysis?.categoryLevel3, analysis?.formattedActionPlan).joinToString(" ").lowercase()
        assertTrue("Cloud summary must relate to the generated planting fixture", listOf("basil", "plant", "garden", "soil").any { it in text })
        File(context.getExternalFilesDir(null), "qa-cloud-result.json").writeText("{\"status\":\"PASS\",\"provider\":\"ollama_cloud\",\"remote_analysis\":true,\"fixture_only\":true,\"credential_value_logged\":false}\n")
    }
    private fun <T> query(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}
