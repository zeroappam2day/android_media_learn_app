package com.example

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.VideoDatabase
import com.example.data.VideoRepository
import com.example.data.MediaFolderScope
import com.example.data.MediaInventoryRecord
import com.example.data.MediaInventorySource
import com.example.data.MediaInventorySnapshot
import com.example.data.FolderScopedInventorySource
import com.example.data.FolderScopeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Real MediaStore fixtures; only this test's images and records are removed. */
class FolderScopeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo by lazy { VideoRepository(context) }
    private val dao get() = VideoDatabase.getDatabase(context).videoItemDao()
    private val token = "QAScope${System.nanoTime()}"
    private val created = mutableListOf<Uri>()
    private var originalFolders: Set<String>? = null

    @Before fun emulatorOnly() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        originalFolders = repo.getTargetFolders().toSet()
    }
    @After fun restoreChoicesAndRemoveOnlyFixtures() {
        query {
            created.forEach { uri ->
                dao.getVideoByUri(uri.toString())?.let { dao.deleteVideo(it) }
                context.contentResolver.delete(uri, null, null)
            }
            originalFolders?.let { repo.prefs.edit().putStringSet("target_folders", it).commit() }
            repo.scanLocalVideos()
        }
    }

    @Test fun chosenFolderIncludesChildrenExcludesSiblingAndPreservesSavedNotes() {
        val inside = createImage("Pictures/$token/Inside/", "inside.png")
        val nested = createImage("Pictures/$token/Inside/Nested/", "nested.png")
        val sibling = createImage("Pictures/$token/InsideOther/", "outside.png")
        repo.prefs.edit().putStringSet("target_folders", emptySet()).commit()
        query { repo.scanLocalVideos() }
        val siblingBefore = query { requireNotNull(dao.getVideoByUri(sibling.toString())) }
        query { dao.updateVideo(siblingBefore.copy(manualTranscript = "Keep these test notes")) }
        val folder = "content://com.android.externalstorage.documents/tree/" + Uri.encode("primary:Pictures/$token/Inside")
        repo.prefs.edit().putStringSet("target_folders", setOf(folder)).commit()
        query { repo.scanLocalVideos() }
        assertTrue(query { dao.getVideoByUri(inside.toString())?.isCurrentlyAvailable == true })
        assertTrue(query { dao.getVideoByUri(nested.toString())?.isCurrentlyAvailable == true })
        assertFalse("A similarly named sibling folder must stay outside the search", query { dao.getVideoByUri(sibling.toString())?.isCurrentlyAvailable == true })
        assertEquals("Keep these test notes", query { dao.getVideoByUri(sibling.toString())?.manualTranscript })
        navigateSources()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("source_category_$token").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("inventory_total").assertTextContains("2 available items", substring = true)
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "qa-folder-scope.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        repo.prefs.edit().putStringSet("target_folders", emptySet()).commit()
        query { repo.scanLocalVideos() }
        assertTrue(query { dao.getVideoByUri(sibling.toString())?.isCurrentlyAvailable == true })
        assertEquals("Keep these test notes", query { dao.getVideoByUri(sibling.toString())?.manualTranscript })
    }

    @Test fun volumeBoundariesAndUnsupportedFoldersNeverWidenSearch() {
        val scope = MediaFolderScope.fromTreeUri("content://com.android.externalstorage.documents/tree/primary%3APictures%2FInside")
        val record = MediaInventoryRecord(1, "content://qa/1", "sample.png", "Pictures/Inside/", "image/png", 10, null,
            null, null, "image", "Inside", "external_primary", false, false)
        assertTrue(scope.contains(record))
        assertTrue(scope.contains(record.copy(relativePath = "Pictures/Inside/Child/")))
        assertFalse(scope.contains(record.copy(relativePath = "Pictures/InsideOther/")))
        assertFalse(scope.contains(record.copy(volumeName = "1234-abcd")))
        assertFalse(scope.contains(record.copy(relativePath = null)))
        val sdScope = MediaFolderScope.fromTreeUri("content://com.android.externalstorage.documents/tree/1234-ABCD%3APictures%2FInside")
        assertTrue(sdScope.contains(record.copy(volumeName = "1234-abcd")))
        assertFalse(sdScope.contains(record))
        var reads = 0
        val source = object : MediaInventorySource {
            override fun currentAccessState() = "Full access"
            override suspend fun readSnapshot(): MediaInventorySnapshot {
                reads++
                return MediaInventorySnapshot("Full access", listOf(record), true)
            }
        }
        val result = runCatching { query { FolderScopedInventorySource(source) { listOf("content://cloud.example/tree/pictures") }.readSnapshot() } }
        assertTrue(result.exceptionOrNull() is FolderScopeException)
        assertEquals("An unsupported folder must fail before searching all media", 0, reads)
    }

    private fun createImage(path: String, name: String): Uri {
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, path)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        created += uri
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        context.contentResolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
    private fun navigateSources() {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("nav_sources").performClick()
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close menu").performClick()
    }
    private fun <T> query(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}
