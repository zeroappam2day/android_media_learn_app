package com.example

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Uses an app-owned PNG and fake HTTP to prove production pause/resume without a provider. */
@RunWith(AndroidJUnit4::class)
class VideoProcessorCancellationTest {
    @Test
    fun pauseCancelsHttpRequeuesTheItemAndResumeCompletesTheSameItem() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".preview")) { "Preview package required" }
        val database = VideoDatabase.getDatabase(context)
        val settingsDao = database.appSettingsDao()
        val videoDao = database.videoItemDao()
        val originalSettings = query { settingsDao.getSettingsDirect() }
        val prefs = context.getSharedPreferences("app_prefs", 0)
        val hadProvider = prefs.contains("llm_provider")
        val originalProvider = prefs.getString("llm_provider", null)
        val fixture = File(context.cacheDir, "codex_processor_${System.nanoTime()}.png")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var fixtureId: Long? = null
        try {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            fixture.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            val uri = Uri.fromFile(fixture).toString()
            query {
                videoDao.insertVideos(listOf(VideoItem(
                    mediaStoreId = -System.nanoTime(), uriString = uri, displayName = fixture.name,
                    relativePath = null, mimeType = "image/png", sizeBytes = fixture.length(),
                    durationMs = null, dateAdded = null, dateModified = null,
                    reviewDecision = "keep", mediaCollection = "image"
                )))
            }
            val video = query { requireNotNull(videoDao.getVideoByUri(uri)) }
            fixtureId = video.id
            query { settingsDao.insertSettings(AppSettings(ollamaEnabled = true, ollamaModelName = "synthetic-model")) }
            prefs.edit().putString("llm_provider", "ollama_local").commit()
            val requests = AtomicInteger()
            val first = AtomicReference<ControlledCall?>()
            val factory = Call.Factory { request ->
                val attempt = requests.incrementAndGet()
                ControlledCall(request, complete = attempt > 1).also { if (attempt == 1) first.set(it) }
            }
            val repository = VideoRepository(context, analysisClient = factory)
            val processor = VideoProcessor(repository, scope)
            instrumentation.runOnMainSync { processor.processSingleVideo(video) }
            await("first HTTP request after actual PNG decoding/OCR") { first.get()?.executed == true }
            assertEquals("processing", query { videoDao.getVideoByIdDirect(video.id)?.status })

            instrumentation.runOnMainSync { processor.pauseBatch() }
            await("pause cleanup to requeue the active media") {
                processor.processorState.value == VideoProcessor.State.PAUSED &&
                    processor.activeVideoId.value == null &&
                    query { videoDao.getVideoByIdDirect(video.id)?.status } == "queued"
            }
            assertTrue("Pausing must cancel the in-flight HTTP request", first.get()!!.cancelled)
            assertNull("Pausing must not fabricate a fallback analysis", query {
                database.analysisResultDao().getAnalysisForVideo(video.id).first()
            })
            assertNull(query { videoDao.getVideoByIdDirect(video.id)?.errorMessage })

            val requeued = query { requireNotNull(videoDao.getVideoByIdDirect(video.id)) }
            instrumentation.runOnMainSync { processor.resumeBatch(listOf(requeued)) }
            await("resumed media to save its analysis") {
                processor.processorState.value == VideoProcessor.State.FINISHED &&
                    processor.activeVideoId.value == null
            }
            assertEquals(2, requests.get())
            assertEquals("processed", query { videoDao.getVideoByIdDirect(video.id)?.status })
            assertEquals("result_saved", query { videoDao.getVideoByIdDirect(video.id)?.lastCompletedStep })
            assertEquals("Resumed fixture", query {
                database.analysisResultDao().getAnalysisForVideo(video.id).first()?.descriptionLine1
            })
        } finally {
            query { scope.coroutineContext[Job]?.cancelAndJoin() }
            fixtureId?.let { id -> query { videoDao.getVideoByIdDirect(id)?.let { videoDao.deleteVideo(it) } } }
            originalSettings?.let { settings -> query { settingsDao.insertSettings(settings) } }
            prefs.edit().apply {
                if (hadProvider) putString("llm_provider", originalProvider) else remove("llm_provider")
            }.commit()
            fixture.delete()
        }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 90_000_000_000L
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail("Timed out waiting for $description")
            Thread.sleep(50)
        }
    }

    private fun <T> query(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private class ControlledCall(private val request: Request, private val complete: Boolean) : Call {
        @Volatile var executed = false
        @Volatile var cancelled = false
        override fun request() = request
        override fun execute(): Response = error("This test requires cancellable asynchronous HTTP")
        override fun enqueue(responseCallback: Callback) {
            executed = true
            if (complete) {
                val json = """{"response":"{\"description\":[\"Resumed fixture\",\"Synthetic response\"],\"categoryLevel1\":\"Technical\",\"categoryLevel2\":\"Test\",\"categoryLevel3\":\"Pause\"}"}"""
                responseCallback.onResponse(this, Response.Builder().request(request)
                    .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(json.toResponseBody()).build())
            }
        }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = ControlledCall(request, complete)
    }
}
