package com.example

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.accessibility.AccessibilityEvent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import com.example.ui.VideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * UNEXECUTED DRAFT. Actual production MainScreen, injected empty QA credentials, emulator only.
 * Actual Preview/Bin failures are UI assertions; reconciliation entry and export callback tests
 * are explicitly partial integration checks. No claim of SAF picker or Android approval proof.
 * Only this suite's owned cache files / freshly inserted MediaStore entries may be changed.
 */
class FailureUiCompletionTest {
    @get:Rule val compose = createAndroidComposeRule<InventoryRecreationTestActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = compose.activity.applicationContext
    private lateinit var repo: VideoRepository
    private lateinit var vm: VideoViewModel
    private val store = ViewModelStore()
    private val ownedFiles = mutableListOf<File>()
    private val ownedUris = mutableListOf<Uri>()
    private val ownedIds = mutableListOf<Long>()
    private lateinit var credentialsName: String
    private var oldProvider: String? = null
    private var oldAuto = false

    @Before fun setup() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview"))
        check(InstrumentationRegistry.getArguments().getString("rea_runtime") == "emulator")
        check(Build.VERSION.SDK_INT >= 30)
        check(io { VideoDatabase.getDatabase(context).videoItemDao().getAllVideosDirect().isEmpty() })
        credentialsName = "qa_failure_empty_${System.nanoTime()}"
        repo = VideoRepository(context, credentialPreferences = context.getSharedPreferences(credentialsName, 0))
        oldProvider = repo.prefs.getString("llm_provider", null)
        oldAuto = repo.prefs.getBoolean("auto_ingest_enabled", false)
        repo.prefs.edit().putString("llm_provider", "local").putBoolean("auto_ingest_enabled", false).commit()
        compose.runOnUiThread {
            vm = VideoViewModel(context as Application, repo)
            store.put("failure-qa", vm)
            compose.activity.setContent { MaterialTheme { MainScreen(vm, false, {}) } }
        }
    }
    @After fun cleanup() {
        if (::vm.isInitialized) compose.runOnUiThread { store.clear() }
        if (::repo.isInitialized) {
            io { ownedIds.forEach { id ->
                repo.binDao.remove(id)
                repo.analysisDao.deleteAnalysisForVideo(id)
                repo.frameDao.deleteFramesForVideo(id)
                repo.videoDao.getVideoByIdDirect(id)?.let { repo.videoDao.deleteVideo(it) }
            } }
            repo.prefs.edit().apply {
                if (oldProvider == null) remove("llm_provider") else putString("llm_provider", oldProvider)
                putBoolean("auto_ingest_enabled", oldAuto)
            }.commit()
        }
        ownedUris.forEach { uri -> context.contentResolver.delete(uri, null, null) }
        ownedFiles.forEach { it.delete() }
        if (::credentialsName.isInitialized) context.deleteSharedPreferences(credentialsName)
    }

    @Test fun previewTrashRequestFailureIsVisibleAndOwnedMediaSurvives() = previewFailure(true)
    @Test fun previewPermanentDeleteRequestFailureIsVisibleAndOwnedMediaSurvives() = previewFailure(false)
    private fun previewFailure(trash: Boolean) {
        // T183/T184: cache-file URIs deliberately cannot form MediaStore system requests.
        val video = fixture()
        val original = ownedFiles.single().readBytes()
        queuePreview(video)
        expectToast("Android could not open the storage confirmation. Scan and try again.") {
            compose.onNodeWithContentDescription(if (trash) "Move media to trash" else "Delete media forever")
                .performScrollTo().performClick()
        }
        assertUnchanged(video, original)
        compose.onNodeWithContentDescription("Close").performClick()
        // Explicit partial recovery seam: fix this same row to freshly owned MediaStore content.
        // Request creation succeeds after repair. No Android dialog is launched/approved here.
        val repaired = publishedCopy(original)
        io { repo.videoDao.updateVideo(row(video.id).copy(uriString = repaired.toString())) }
        await { vm.allVideos.value.any { it.id == video.id && it.uriString == repaired.toString() } }
        val pending = if (trash) vm.createTrashPendingIntent(video.id) else vm.createDeletePendingIntent(video.id)
        assertNotNull("Repaired MediaStore URI must permit request construction", pending)
        assertArrayEquals(original, context.contentResolver.openInputStream(repaired)!!.use { it.readBytes() })
        assertEquals(video.manualTranscript, row(video.id).manualTranscript)
    }

    @Test fun binDeleteFailureIsVisibleWithoutDroppingRowThenRepairCanCreateAndCancelRequest() {
        // T222 failure surface. Bin creation and URI repair are explicitly controlled setup seams.
        val video = fixture("ready_to_delete")
        val original = ownedFiles.single().readBytes()
        io { repo.binDao.add(BinEntry(video.id, "review_later", null)) }
        navigate("nav_sources")
        compose.onNodeWithTag("open_bin").performClick()
        await { vm.binMedia.value.any { it.id == video.id } }
        compose.onNodeWithTag("delete_bin").assertIsEnabled().performClick()
        compose.onNodeWithText("Delete failed").assertIsDisplayed()
        compose.onNodeWithTag("bin_${video.id}").assertExists()
        assertNotNull(io { repo.binDao.entry(video.id) })
        assertUnchanged(video, original)
        val repaired = publishedCopy(original)
        io { repo.videoDao.updateVideo(row(video.id).copy(uriString = repaired.toString())) }
        await { vm.binMedia.value.any { it.id == video.id && it.uriString == repaired.toString() } }
        val pending = vm.createBinDeletePendingIntent()
        assertNotNull(pending)
        // Explicit partial cancellation callback: does not claim a visible system Cancel interaction.
        compose.runOnUiThread { vm.finishBinDelete(false) }
        compose.waitForIdle()
        assertNotNull(io { repo.binDao.entry(video.id) })
        assertArrayEquals(original, context.contentResolver.openInputStream(repaired)!!.use { it.readBytes() })
        assertEquals(video.manualTranscript, row(video.id).manualTranscript)
    }

    @Test fun unqueryableStorageReconciliationShowsUnverifiedMessageAndKeepsRow() {
        // T183/T184 partial result seam: invoke production reconciliation; actual MainScreen emits toast.
        val video = fixture()
        val original = ownedFiles.single().readBytes()
        expectToast("Storage action could not be verified. Scan to refresh.") {
            compose.runOnUiThread { vm.reconcileStorageAction(video.id, true) }
        }
        assertUnchanged(video, original)
        assertTrue(row(video.id).isCurrentlyAvailable)
        assertFalse(row(video.id).isTrashed)
    }

    @Test fun unwritableExportReturnsFailureThenOwnedWritableFileReceivesValidDocument() {
        // T164 PARTIAL: production VM callback and serialized output only; no SAF-picker/toast proof.
        val video = fixture()
        val original = ownedFiles.single().readBytes()
        val result = AtomicReference<Boolean?>()
        val missingOwnedAuthority = Uri.parse("content://${context.packageName}.qa-unregistered/export-${System.nanoTime()}")
        compose.runOnUiThread { vm.exportMetadata(missingOwnedAuthority) { result.set(it) } }
        await { result.get() != null }
        assertEquals(false, result.get())
        assertUnchanged(video, original)
        result.set(null)
        val output = File(context.cacheDir, "qa-export-${System.nanoTime()}.json").also { ownedFiles += it }
        compose.runOnUiThread { vm.exportMetadata(Uri.fromFile(output)) { result.set(it) } }
        await { result.get() != null }
        assertEquals(true, result.get())
        val document = JSONObject(output.readText())
        assertEquals(1, document.getInt("schema"))
        val media = document.getJSONArray("media")
        assertEquals(1, media.length())
        assertEquals(video.displayName, media.getJSONObject(0).getString("name"))
        assertEquals(video.manualTranscript, media.getJSONObject(0).getString("notes"))
        assertArrayEquals(original, ownedFiles.first().readBytes())
    }

    private fun fixture(decision: String = "keep"): VideoItem {
        val file = File(context.cacheDir, "qa-failure-${System.nanoTime()}.png").also { ownedFiles += it }
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
            file.outputStream().use { check(compress(Bitmap.CompressFormat.PNG, 100, it)) }
            recycle()
        }
        val uri = Uri.fromFile(file).toString()
        io { repo.videoDao.insertVideos(listOf(VideoItem(mediaStoreId = -System.nanoTime(), uriString = uri,
            displayName = file.name, relativePath = null, mimeType = "image/png", sizeBytes = file.length(),
            durationMs = null, dateAdded = null, dateModified = null, reviewDecision = decision,
            mediaCollection = "image", manualTranscript = "QA failure-preservation note"))) }
        return io { requireNotNull(repo.videoDao.getVideoByUri(uri)) }.also { ownedIds += it.id }
    }
    private fun publishedCopy(bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "qa-repaired-${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QA-Failure-Only/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        ownedUris += uri
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
    private fun assertUnchanged(video: VideoItem, bytes: ByteArray) {
        val latest = row(video.id)
        assertEquals(video.reviewDecision, latest.reviewDecision)
        assertEquals(video.status, latest.status)
        assertEquals(video.manualTranscript, latest.manualTranscript)
        assertTrue(latest.isCurrentlyAvailable)
        assertArrayEquals(bytes, ownedFiles.first().readBytes())
    }
    private fun navigate(tag: String) {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag(tag).performClick()
    }
    private fun queuePreview(video: VideoItem) {
        await { vm.allVideos.value.any { it.id == video.id } }
        navigate("nav_queue")
        compose.onNodeWithContentDescription("Preview").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Delete media forever").performScrollTo().assertExists()
    }
    private fun expectToast(expected: String, action: () -> Unit) {
        // Capture only the expected notification event; never traverse or dump other UI content.
        val event = instrumentation.uiAutomation.executeAndWaitForEvent(Runnable { action(); compose.waitForIdle() }, { event ->
            event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                event.packageName?.toString() == context.packageName &&
                event.text.any { it.toString() == expected }
        }, 10000)
        try { assertTrue(event.text.any { it.toString() == expected }) } finally { event.recycle() }
    }
    private fun row(id: Long) = io { requireNotNull(repo.videoDao.getVideoByIdDirect(id)) }
    private fun await(condition: () -> Boolean) = compose.waitUntil(15000, condition)
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}
