package com.example

import android.app.Application
import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
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
import java.io.File

/** Owned Room/cache fixtures; actual production Tags/Bin/Preview UI, no default credential store. */
class TagBinCompletionTest {
    @get:Rule val compose = createAndroidComposeRule<InventoryRecreationTestActivity>()
    private val context get() = compose.activity.applicationContext
    private lateinit var repo: VideoRepository
    private lateinit var vm: VideoViewModel
    private val store = ViewModelStore()
    private val ids = mutableListOf<Long>()
    private val files = linkedMapOf<Long, File>()
    private val tags = mutableListOf<Long>()
    private val ownedMediaStoreUris = mutableListOf<Uri>()
    private lateinit var credentials: String
    private var oldProvider: String? = null
    private var oldAuto = false

    @Before fun setup() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview"))
        check(InstrumentationRegistry.getArguments().getString("rea_runtime") == "emulator")
        check(Build.VERSION.SDK_INT >= 29) { "Owned pending MediaStore thumbnail fixture requires API29+" }
        val db = VideoDatabase.getDatabase(context)
        check(io { db.videoItemDao().getAllVideosDirect().isEmpty() })
        check(io { db.customTagDao().observeTags().first().isEmpty() })
        credentials = "qa_tag_bin_empty_${System.nanoTime()}"
        repo = VideoRepository(context, credentialPreferences = context.getSharedPreferences(credentials, 0))
        oldProvider = repo.prefs.getString("llm_provider", null)
        oldAuto = repo.prefs.getBoolean("auto_ingest_enabled", false)
        repo.prefs.edit().putString("llm_provider", "local").putBoolean("auto_ingest_enabled", false).commit()
        compose.runOnUiThread {
            vm = VideoViewModel(context as Application, repo)
            store.put("tag-bin", vm)
            compose.activity.setContent { MaterialTheme { MainScreen(vm, false, {}) } }
        }
    }
    @After fun cleanup() {
        if (::vm.isInitialized) compose.runOnUiThread { store.clear() }
        if (::repo.isInitialized) {
            io {
                ids.forEach { id ->
                    repo.binDao.remove(id); repo.analysisDao.deleteAnalysisForVideo(id); repo.frameDao.deleteFramesForVideo(id)
                    repo.videoDao.getVideoByIdDirect(id)?.let { repo.videoDao.deleteVideo(it) }
                }
                repo.customTagDao.observeTags().first().filter { it.id in tags }.forEach { repo.customTagDao.delete(it) }
            }
            repo.prefs.edit().apply {
                if (oldProvider == null) remove("llm_provider") else putString("llm_provider", oldProvider)
                putBoolean("auto_ingest_enabled", oldAuto)
            }.commit()
        }
        ownedMediaStoreUris.forEach { uri ->
            check(uri.authority == MediaStore.AUTHORITY)
            context.contentResolver.delete(uri, null, null)
        }
        files.values.forEach { it.delete() }
        if (::credentials.isInitialized) context.deleteSharedPreferences(credentials)
    }

    @Test fun tagsAssignmentListsOnlyAvailablePublishedUntrashedMedia() {
        // T153/T154. Fixtures are explicit inventory states, not Android permission/pending-publish proof.
        val allowed = fixture("available")
        val missing = fixture("unavailable", available = false)
        val pending = fixture("pending", pending = true)
        val trashed = fixture("trashed", trashed = true)
        val originals = files.mapValues { it.value.readBytes() }
        openTags()
        val tag = addTag("QA available only")
        compose.onNodeWithText(tag.name).performClick()
        mediaAction(allowed.displayName, "Add tag")
        await { assigned(allowed.id, tag.id) }
        for (excluded in listOf(missing, pending, trashed)) {
            // ScrollToNode traverses LazyColumn, preventing offscreen absence from passing falsely.
            val found = runCatching { tagList().performScrollToNode(hasText(excluded.displayName)) }.isSuccess
            assertFalse("Ineligible item was offered for assignment: ${excluded.displayName}", found)
            assertFalse(assigned(excluded.id, tag.id))
        }
        for (item in listOf(allowed, missing, pending, trashed)) {
            assertEquals(item, row(item.id))
            assertArrayEquals(originals.getValue(item.id), files.getValue(item.id).readBytes())
        }
    }

    @Test fun deletingAttachedTagPreservesMediaReviewNotesAndAnalysis() {
        // T152 attached-tag boundary, with a separate sentinel tag and assignment.
        val item = fixture("attached")
        val original = files.getValue(item.id).readBytes()
        val analysis = repo.generateDeterministicFallback(item, emptyList(), item.manualTranscript)
        io { repo.analysisDao.insertAnalysis(analysis) }
        openTags()
        val deleted = addTag("QA delete attached")
        val sentinel = addTag("QA keep separate")
        io { repo.customTagDao.assign(MediaTagCrossRef(item.id, sentinel.id)) }
        compose.onNodeWithText(deleted.name).performClick()
        mediaAction(item.displayName, "Add tag")
        await { assigned(item.id, deleted.id) && assigned(item.id, sentinel.id) }
        tagAction(deleted.name, "Delete")
        await { io { repo.customTagDao.byName(deleted.name) == null } }
        compose.onNodeWithText(deleted.name).assertDoesNotExist()
        compose.onNodeWithText(sentinel.name).assertExists()
        assertFalse(assigned(item.id, deleted.id))
        assertTrue(assigned(item.id, sentinel.id))
        assertEquals(item, row(item.id))
        assertEquals(analysis.copy(id = io { repo.analysisDao.getAnalysisForVideoDirect(item.id) }!!.id), io { repo.analysisDao.getAnalysisForVideoDirect(item.id) })
        assertArrayEquals(original, files.getValue(item.id).readBytes())
    }

    @Test fun corruptedBinThumbnailRepairsAtSameUriAndRecoverRetainsPriorChoiceAndTag() {
        // T156/T226. Obscured tag membership is setup; this does not claim rule-engine generation.
        val cacheSeed = fixture("corrupt-bin", decision = "ready_to_delete")
        val valid = files.getValue(cacheSeed.id).readBytes()
        val corrupt = byteArrayOf(0, 1, 2, 3)
        val uri = ownedPublishedImage(cacheSeed.displayName, corrupt)
        // This test alone uses a genuine MediaStore row: loadThumbnail has a supported provider.
        io { repo.videoDao.updateVideo(cacheSeed.copy(uriString = uri.toString(), sizeBytes = corrupt.size.toLong())) }
        val item = row(cacheSeed.id)
        assertArrayEquals(corrupt, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        val obscured = io { repo.customTagDao.insert(CustomTag(name = "Obscured")) }.also { tags += it }
        io {
            repo.customTagDao.assign(MediaTagCrossRef(item.id, obscured))
            repo.binDao.add(BinEntry(item.id, "review_later", null))
        }
        openBin()
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Preview unavailable: ${item.displayName}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Preview unavailable: ${item.displayName}").assertExists()
        // Dispose Bin's actual thumbnail composition, repair bytes without changing URI, reopen.
        icon("Back").performClick()
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(valid) }
        val priorModified = context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATE_MODIFIED), null, null, null)!!.use {
            check(it.moveToFirst()); it.getLong(0)
        }
        context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.DATE_MODIFIED, maxOf(System.currentTimeMillis() / 1000L, priorModified + 1L))
        }, null, null)
        assertArrayEquals(valid, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        showAllSources()
        compose.onNodeWithTag("open_bin").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Preview image: ${item.displayName}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Preview image: ${item.displayName}").assertExists()
        icon("Recover", hasAnyAncestor(hasTestTag("bin_${item.id}"))).performClick()
        await { io { repo.binDao.entry(item.id) == null } && row(item.id).reviewDecision == "review_later" }
        compose.onNodeWithTag("bin_${item.id}").assertDoesNotExist()
        assertTrue(assigned(item.id, obscured))
        assertEquals(item.uriString, row(item.id).uriString)
        assertEquals(item.manualTranscript, row(item.id).manualTranscript)
        assertArrayEquals(valid, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        icon("Back").performClick()
        showAllSources()
        compose.onNodeWithTag("open_tags").performClick()
        compose.onNodeWithText("Obscured").performClick()
        icon("Tagged").performScrollTo().performClick()
        compose.onNodeWithText(item.displayName).performScrollTo().assertIsDisplayed()
    }

    @Test fun removingTaggedIndexEntryUpdatesCountAndKeepsOtherOwnedRecordAndAllBytes() {
        // T154 index/assignment cascade boundary. Sentinel is intentionally unavailable in Queue.
        val removed = fixture("index-remove")
        val sentinel = fixture("sentinel", available = false)
        val originals = files.mapValues { it.value.readBytes() }
        val tagId = io { repo.customTagDao.insert(CustomTag(name = "QA index count")) }.also { tags += it }
        io {
            repo.customTagDao.assign(MediaTagCrossRef(removed.id, tagId))
            repo.customTagDao.assign(MediaTagCrossRef(sentinel.id, tagId))
        }
        openTags()
        assertTagCount("QA index count", 2)
        icon("Back").performClick()
        navigate("nav_queue")
        compose.onNodeWithTag("queue_${removed.id}_preview").performQaQueueScrollTo(compose).performClick()
        compose.onNodeWithContentDescription("Remove index entry").performScrollTo().performClick()
        await { io { repo.videoDao.getVideoByIdDirect(removed.id) == null } }
        assertFalse(assigned(removed.id, tagId))
        assertTrue(assigned(sentinel.id, tagId))
        assertEquals(sentinel, row(sentinel.id))
        originals.forEach { (id, bytes) -> assertArrayEquals(bytes, files.getValue(id).readBytes()) }
        openTags()
        assertTagCount("QA index count", 1)
    }

    private fun fixture(name: String, decision: String = "keep", available: Boolean = true, pending: Boolean = false, trashed: Boolean = false): VideoItem {
        val file = File(context.cacheDir, "qa-$name-${System.nanoTime()}.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
            file.outputStream().use { check(compress(Bitmap.CompressFormat.PNG, 100, it)) }
            recycle()
        }
        val item = VideoItem(mediaStoreId = -System.nanoTime(), uriString = Uri.fromFile(file).toString(),
            displayName = file.name, relativePath = null, mimeType = "image/png", sizeBytes = file.length(), durationMs = null,
            dateAdded = null, dateModified = null, reviewDecision = decision, mediaCollection = "image",
            manualTranscript = "QA preserved note", isCurrentlyAvailable = available, isPending = pending, isTrashed = trashed)
        val id = io { repo.videoDao.insertVideo(item) }
        ids += id; files[id] = file
        return row(id)
    }
    private fun ownedPublishedImage(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QA-TagBin-Only/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        ownedMediaStoreUris += uri
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        check(context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
        return uri
    }
    private fun addTag(name: String): CustomTag {
        compose.onNode(hasSetTextAction() and hasText("Tag")).performTextReplacement(name)
        icon("Add").performClick()
        await { io { repo.customTagDao.byName(name) != null } }
        return io { requireNotNull(repo.customTagDao.byName(name)) }.also { tags += it.id }
    }
    private fun showAllSources() {
        if (compose.onAllNodesWithContentDescription("Show all sources").fetchSemanticsNodes().isNotEmpty())
            icon("Show all sources").performClick()
    }
    private fun openTags() { navigate("nav_sources"); showAllSources(); compose.onNodeWithTag("open_tags").performClick() }
    private fun openBin() { navigate("nav_sources"); showAllSources(); compose.onNodeWithTag("open_bin").performClick(); await { vm.binMedia.value.isNotEmpty() } }
    private fun navigate(tag: String) {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) icon("Open menu").performClick()
        compose.onNodeWithTag(tag).performClick()
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) icon("Close menu").performClick()
    }
    private fun tagList() = compose.onNode(hasScrollAction() and !hasSetTextAction() and hasAnyAncestor(hasTestTag("tags_screen")))
    private fun mediaAction(name: String, action: String) {
        tagList().performScrollToNode(hasText(name))
        val bounds = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot
        icon(action, SemanticsMatcher("Action aligned with owned media") { it.boundsInRoot.center.y in bounds.top..bounds.bottom }).performClick()
    }
    private fun tagAction(name: String, action: String) {
        compose.onNodeWithText(name).performScrollTo()
        val bounds = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot
        icon(action, SemanticsMatcher("Action aligned with owned tag") { it.boundsInRoot.center.y in bounds.top..bounds.bottom }).performClick()
    }
    private fun assertTagCount(name: String, count: Int) {
        compose.onNodeWithText(name).performScrollTo()
        val bounds = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot
        compose.onNode(hasText(count.toString()) and SemanticsMatcher("Count aligned with owned tag") { it.boundsInRoot.center.y in bounds.top..bounds.bottom }).assertExists()
    }
    private fun icon(description: String, within: SemanticsMatcher? = null): SemanticsNodeInteraction {
        val action = hasClickAction() and hasContentDescription(description)
        return compose.onNode(if (within == null) action else action and within)
    }
    private fun assigned(mediaId: Long, tagId: Long) = io { repo.customTagDao.observeAssignments().first().any { it.mediaId == mediaId && it.tagId == tagId } }
    private fun row(id: Long) = io { requireNotNull(repo.videoDao.getVideoByIdDirect(id)) }
    private fun await(condition: () -> Boolean) = compose.waitUntil(15000, condition)
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}
