package com.example

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import com.example.ui.VideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

/**
 * T024/T025/T225/T221: genuine AndroidMediaInventorySource -> production scanner/Room/Sources UI.
 * Owned PNGs stay under Pictures/token; one owned opaque MP4 stays under Movies/token.
 * The MP4 tests inventory/path attribution only, never decoding. Two exact owned folder scopes
 * prevent unrelated media from entering the scan. Other/unattributed remains unproven here.
 * No preset Room source labels, fake snapshots, actual credentials, or analysis operations.
 */
class SourceOriginCompletionTest {
    @get:Rule val compose = createAndroidComposeRule<InventoryRecreationTestActivity>()
    private val context get() = compose.activity.applicationContext
    private val resolver get() = context.contentResolver
    private val token = "QAOrigin" + UUID.randomUUID().toString().replace("-", "").take(12)
    private val rootPath = "Pictures/$token/"
    private val owned = mutableListOf<Owned>()
    private lateinit var repo: VideoRepository
    private lateinit var vm: VideoViewModel
    private val store = ViewModelStore()
    private var oldFolders: Set<String>? = null
    private var oldProvider: String? = null
    private var oldAuto = false
    private lateinit var credentials: String
    private data class Owned(val uri: Uri, val name: String, val category: String, val size: Long, val pending: Boolean = false)

    @Before fun setup() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview"))
        check(InstrumentationRegistry.getArguments().getString("rea_runtime") == "emulator")
        check(Build.VERSION.SDK_INT >= 29)
        // Parent runner grants media permissions in the isolated emulator; do not open OS permission UI.
        if (Build.VERSION.SDK_INT >= 33) {
            check(context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)
            check(context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED)
        } else check(context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
        val db = VideoDatabase.getDatabase(context)
        check(io { db.videoItemDao().getAllVideosDirect().isEmpty() })
        credentials = "qa_origin_empty_$token"
        repo = VideoRepository(context, credentialPreferences = context.getSharedPreferences(credentials, 0))
        oldFolders = repo.prefs.getStringSet("target_folders", null)?.toSet()
        oldProvider = repo.prefs.getString("llm_provider", null)
        oldAuto = repo.prefs.getBoolean("auto_ingest_enabled", false)
        val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:${rootPath.trimEnd('/')}")
        val movieTree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Movies/$token")
        repo.prefs.edit().putStringSet("target_folders", setOf(tree.toString(), movieTree.toString()))
            .putString("llm_provider", "local").putBoolean("auto_ingest_enabled", false).commit()
        compose.runOnUiThread {
            vm = VideoViewModel(context as Application, repo)
            store.put("origin", vm)
            compose.activity.setContent { MaterialTheme { MainScreen(vm, false, {}) } }
        }
    }
    @After fun cleanup() {
        if (::vm.isInitialized) compose.runOnUiThread { store.clear() }
        if (::repo.isInitialized) {
            io { owned.forEach { fixture -> repo.videoDao.getVideoByUri(fixture.uri.toString())?.let { row ->
                repo.analysisDao.deleteAnalysisForVideo(row.id)
                repo.frameDao.deleteFramesForVideo(row.id)
                repo.videoDao.deleteVideo(row)
            } } }
            repo.prefs.edit().apply {
                if (oldFolders == null) remove("target_folders") else putStringSet("target_folders", oldFolders)
                if (oldProvider == null) remove("llm_provider") else putString("llm_provider", oldProvider)
                putBoolean("auto_ingest_enabled", oldAuto)
            }.commit()
        }
        // Exact inserted URI ownership only; never delete by directory/prefix or sweep MediaStore.
        owned.forEach { resolver.delete(it.uri, null, null) }
        if (::credentials.isInitialized) context.deleteSharedPreferences(credentials)
    }

    @Test fun actualSourceMatrixInferredFiltersAndPendingPublicationRemainInventoryOnly() {
        val paths = listOf(
            "Camera" to "Camera", "Screenshots" to "Screenshots",
            "Downloads" to "Downloads", "WhatsApp/Media/WhatsApp Images" to "WhatsApp",
            "WhatsApp Business/Media/WhatsApp Business Images" to "WhatsApp Business",
            "Messenger" to "Messenger", "DJI" to "DJI", "Facebook" to "Facebook",
            "Skype" to "Skype", "Zalo" to "Zalo", "Twitter" to "Twitter", "CustomQA" to token)
        val published = paths.map { (path, category) -> image(path, "qa-neutral-$category.png", category) }.toMutableList()
        // Existing contract chooses the FIRST nonstandard folder: CustomQA's label is token.
        published += opaqueRecording()
        val facebook = image("Screenshots", "Screenshot_facebook_01.png", "Screenshots").also { published += it }
        val instagram = image("Screenshots", "Screenshot_instagram_02.png", "Screenshots").also { published += it }
        val pending = image("Camera", "QA-pending.png", "Camera", pending = true)
        sources()
        refresh()
        assertMatrix(published)
        assertFalse("Pending provider row must not inflate eligible inventory", rows().any { it.uriString == pending.uri.toString() && it.isCurrentlyAvailable && !it.isPending })
        assertEquals(published.size.toLong(), vm.inventoryTotals.value.itemCount)
        sourceCategory("Screenshots").performClick()
        val fb = rows().single { it.uriString == facebook.uri.toString() }
        val ig = rows().single { it.uriString == instagram.uri.toString() }
        val neutral = published.single { it.category == "Screenshots" && it != facebook && it != instagram }
        val neutralRow = rows().single { it.uriString == neutral.uri.toString() }
        assertEquals("Facebook", fb.sourceSubcategory)
        assertEquals("Instagram", ig.sourceSubcategory)
        assertNull(neutralRow.sourceSubcategory)
        assertEquals("filename_token", fb.subcategoryEvidenceKind)
        assertEquals("INFERRED", fb.subcategoryConfidence)
        compose.onNodeWithTag("source_evidence_${fb.id}").assertTextContains("Inferred from filename")
        compose.onNodeWithTag("source_subcategory_Facebook").performClick()
        compose.onNodeWithTag("source_media_${fb.id}").assertExists()
        compose.onNodeWithTag("source_media_${ig.id}").assertDoesNotExist()
        compose.onNodeWithTag("source_media_${neutralRow.id}").assertDoesNotExist()
        compose.onNodeWithTag("source_subcategory_Facebook").performClick()
        compose.onNodeWithTag("source_media_${ig.id}").performScrollTo().assertExists()
        compose.onNodeWithTag("source_subcategory_Instagram").performClick()
        compose.onNodeWithTag("source_media_${ig.id}").assertExists()
        compose.onNodeWithTag("source_media_${fb.id}").assertDoesNotExist()
        compose.onNodeWithTag("source_subcategory_Instagram").performClick()
        compose.onNodeWithTag("source_media_${neutralRow.id}").performScrollTo().assertExists()
        // T221: same genuine pending URI is published, then actual Refresh sees it exactly once.
        compose.onNodeWithContentDescription("Show all sources").performClick()
        check(resolver.update(pending.uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
        refresh()
        published += pending.copy(pending = false)
        assertMatrix(published)
        assertEquals(1, rows().count { it.uriString == pending.uri.toString() })
        val stable = rows().associate { it.uriString to it.id }
        refresh()
        assertEquals(stable, rows().associate { it.uriString to it.id })
        assertMatrix(published)
        assertEquals(0, repo.mediaOperationCounters.frameExtractions)
        assertEquals(0, repo.mediaOperationCounters.ocrRequests)
        assertEquals(0, repo.mediaOperationCounters.hashRequests)
        assertEquals(0, repo.mediaOperationCounters.llmRequests)
        assertTrue(io { repo.analysisDao.getAllAnalysis().first().isEmpty() })
        rows().forEach { assertEquals("new", it.status); assertEquals("unreviewed", it.reviewDecision) }
        // Source totals derive from independently retained bytes, not values copied from aggregates.
        published.forEach { fixture -> assertEquals(fixture.size, resolver.openInputStream(fixture.uri)!!.use { it.readBytes().size.toLong() }) }
    }

    private fun sourceCategory(category: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("source_category_$category"))
        return compose.onNodeWithTag("source_category_$category").assertIsDisplayed()
    }

    private fun assertMatrix(expected: List<Owned>) {
        await {
            vm.inventoryTotals.value.itemCount == expected.size.toLong() &&
                vm.sourceAggregates.value.sumOf { it.itemCount } == expected.size.toLong() &&
                vm.allVideos.value.count { it.isCurrentlyAvailable && !it.isPending && !it.isTrashed } == expected.size
        }
        assertEquals(expected.sumOf { it.size }, vm.inventoryTotals.value.totalBytes)
        compose.onNodeWithTag("inventory_total").assertTextEquals("${expected.size} available items • ${sizeText(expected.sumOf { it.size })}")
        assertEquals(expected.map { it.uri.toString() }.toSet(), rows().filter { it.isCurrentlyAvailable && !it.isPending && !it.isTrashed }.map { it.uriString }.toSet())
        for ((category, fixtures) in expected.groupBy { it.category }) {
            val aggregate = vm.sourceAggregates.value.singleOrNull { it.category == category }
            assertNotNull("Expected actual path category $category", aggregate)
            assertEquals(fixtures.size.toLong(), aggregate!!.itemCount)
            assertEquals(fixtures.sumOf { it.size }, aggregate.totalBytes)
            val card = sourceCategory(category)
            card.assert(hasText("${fixtures.size} items"))
            card.assert(hasText(sizeText(fixtures.sumOf { it.size })))
            fixtures.forEach { fixture ->
                val row = rows().single { it.uriString == fixture.uri.toString() }
                assertEquals(category, row.sourceCategory)
                assertEquals("relative_path", row.categoryEvidenceKind)
                assertEquals("DIRECT", row.categoryConfidence)
            }
        }
        assertEquals(expected.groupBy { it.category }.size, vm.sourceAggregates.value.size)
    }
    private fun image(folder: String, name: String, category: String, pending: Boolean = false): Owned {
        val bytes = ByteArrayOutputStream().use { stream ->
            Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.CYAN); check(compress(Bitmap.CompressFormat.PNG, 100, stream)); recycle()
            }
            stream.toByteArray()
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "$rootPath$folder/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        val fixture = Owned(uri, name, category, bytes.size.toLong(), pending).also { owned += it }
        resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        if (!pending) check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
        return fixture
    }
    private fun opaqueRecording(): Owned {
        val bytes = "QA inventory-only opaque MP4; never process".toByteArray(Charsets.UTF_8)
        val uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "qa-owned-opaque.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/$token/Screen recordings/")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }))
        val fixture = Owned(uri, "qa-owned-opaque.mp4", "Screen recordings", bytes.size.toLong()).also { owned += it }
        resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) == 1)
        return fixture
    }
    private fun sources() {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("nav_sources").performClick()
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close menu").performClick()
    }
    private fun refresh() {
        val prior = vm.lastScanResult.value
        compose.onNodeWithTag("inventory_scan_button").performClick()
        await { !vm.isScanning.value && vm.lastScanResult.value != prior }
        assertNotEquals("error", vm.lastScanResult.value?.status)
    }
    private fun rows() = io { repo.videoDao.getAllVideosDirect() }
    private fun await(condition: () -> Boolean) = compose.waitUntil(30000, condition)
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun sizeText(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble(); var unit = -1
        while (value >= 1024.0 && unit < units.lastIndex) { value /= 1024.0; unit++ }
        return String.format(Locale.getDefault(), if (value >= 100) "%.0f %s" else "%.1f %s", value, units[unit])
    }
}
