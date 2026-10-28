package com.example

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

/** Removes only fixtures created by StorageCompletionTest after an interrupted teardown. */
// The historical method names now exercise cleanup using freshly created exact fixtures only.
class OwnedStorageCleanupTest {
    @Test fun removeInterruptedStorageFixturesOnly() {
        withFreshFixtures { scope ->
            val victim = scope.create("interrupted", saved = true)
            val control = scope.create("retained-control", saved = true)
            val retained = scope.state(control)
            scope.removeExactFixture(victim)
            assertNull(scope.row(victim))
            val removedChildren = scope.children(victim.id!!)
            assertNull(removedChildren.analysis)
            assertTrue(removedChildren.frames.isEmpty())
            assertNull(removedChildren.bin)
            assertFalse(scope.physicalExists(victim))
            assertEquals(retained, scope.state(control))
            scope.assertBytes(control)
            scope.assertOriginalRowsUnchanged()
        }
    }

    @Test fun removeScanOnlyRowsFromNewQaIndex() {
        withFreshFixtures { scope ->
            val victim = scope.create("scan-only", saved = false)
            val control = scope.create("retained-scan-only", saved = false)
            val retained = scope.state(control)
            // Index entries only: no resolver.delete, storage clear, or original preview access.
            scope.removeExactScanOnlyIndexEntry(victim)
            assertNull(scope.row(victim))
            assertEquals(retained, scope.state(control))
            scope.assertBytes(victim)
            scope.assertBytes(control)
            scope.assertOriginalRowsUnchanged()
        }
    }

    private data class Fixture(val uri: Uri, val name: String, val bytes: ByteArray, var id: Long? = null)
    private data class Children(val analysis: AnalysisResult?, val frames: List<ExtractedFrame>, val bin: BinEntry?)
    private data class State(val row: VideoItem?, val children: Children)
    private data class Choices(val hadFolders: Boolean, val folders: Set<String>?, val hadAuto: Boolean, val auto: Boolean?)

    private fun withFreshFixtures(action: (FreshScope) -> Unit) {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".qa.preview"))
        check(Build.VERSION.SDK_INT == 36)
        check(InstrumentationRegistry.getArguments().getString("rea_runtime") == "emulator")
        val prefs = context.getSharedPreferences("app_prefs", 0)
        val choices = Choices(prefs.contains("target_folders"), prefs.getStringSet("target_folders", null)?.toSet(),
            prefs.contains("auto_ingest_enabled"), if (prefs.contains("auto_ingest_enabled")) prefs.getBoolean("auto_ingest_enabled", false) else null)
        val scope = FreshScope(context)
        try {
            val tree = android.provider.DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:${scope.path.trimEnd('/')}")
            check(prefs.edit().putStringSet("target_folders", setOf(tree.toString())).putBoolean("auto_ingest_enabled", false).commit())
            action(scope)
        } finally {
            try {
                scope.removeEveryFreshFixture()
            } finally {
                check(prefs.edit().apply {
                    if (choices.hadFolders) putStringSet("target_folders", choices.folders?.toSet()) else remove("target_folders")
                    if (choices.hadAuto) putBoolean("auto_ingest_enabled", requireNotNull(choices.auto)) else remove("auto_ingest_enabled")
                }.commit())
                assertEquals(choices.hadFolders, prefs.contains("target_folders"))
                assertEquals(choices.folders, prefs.getStringSet("target_folders", null)?.toSet())
                assertEquals(choices.hadAuto, prefs.contains("auto_ingest_enabled"))
                if (choices.hadAuto) assertEquals(choices.auto, prefs.getBoolean("auto_ingest_enabled", false))
                scope.assertOriginalRowsUnchanged()
            }
        }
    }

    private class FreshScope(private val context: Context) {
        private val resolver = context.contentResolver
        private val db = VideoDatabase.getDatabase(context)
        private val dao = db.videoItemDao()
        private val token = "QAStorage" + UUID.randomUUID().toString().replace("-", "").take(12)
        val path = "Pictures/$token/"
        private val fresh = mutableListOf<Fixture>()
        private val originalRows = io { dao.getAllVideosDirect().associateBy { it.id } }
        private val originalChildren = originalRows.keys.associateWith(::children)

        fun create(role: String, saved: Boolean): Fixture {
            check(role.matches(Regex("[a-z-]+")))
            val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(if (role.startsWith("retained")) Color.GREEN else Color.BLUE) }
            val bytes = try { ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray() } finally { bitmap.recycle() }
            val name = "$token-$role.png"
            check(fresh.none { it.name == name })
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, path); put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
            val fixture = Fixture(uri, name, bytes).also { fresh += it }
            check(uri.toString() !in originalRows.values.map { it.uriString })
            validatePhysical(fixture)
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            fixture.id = io { dao.insertVideo(VideoItem(mediaStoreId = ContentUris.parseId(uri), uriString = uri.toString(), displayName = name,
                relativePath = path, mimeType = "image/png", sizeBytes = bytes.size.toLong(), durationMs = null, dateAdded = null, dateModified = null,
                status = if (saved) "processed" else "new", lastCompletedStep = if (saved) "result_saved" else "discovered",
                reviewDecision = if (saved) "ready_to_delete" else "unreviewed", manualTranscript = if (saved) "QA fresh retained notes" else null,
                mediaCollection = "image", volumeName = "external_primary")) }
            check(fixture.id !in originalRows.keys)
            if (saved) io {
                db.analysisResultDao().insertAnalysis(AnalysisResult(videoId = fixture.id!!, descriptionLine1 = "QA retained summary", descriptionLine2 = "QA retained details",
                    categoryLevel1 = "QA", categoryLevel2 = "Cleanup", categoryLevel3 = "Control", formattedActionPlan = "QA retained roadmap",
                    urls = emptyList(), codeOrCommands = emptyList(), evidenceJson = "[]", source = "edited"))
                db.extractedFrameDao().insertFrame(ExtractedFrame(videoId = fixture.id!!, timestampMs = 0L, ocrText = "QA fresh retained frame"))
                db.binDao().add(BinEntry(fixture.id!!, "keep", null))
            }
            assertBytes(fixture)
            return fixture
        }

        private fun requireFresh(fixture: Fixture) {
            check(fresh.any { it === fixture }) { "Only the current in-memory fixture manifest may authorize cleanup" }
            check(fixture.name.startsWith("$token-") && fixture.name.endsWith(".png"))
            check(fixture.uri.toString() !in originalRows.values.map { it.uriString })
            fixture.id?.let { check(it !in originalRows.keys) }
        }
        private fun validatePhysical(fixture: Fixture): Boolean {
            requireFresh(fixture)
            return resolver.query(fixture.uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) false else {
                    check(cursor.getLong(0) == ContentUris.parseId(fixture.uri))
                    check(cursor.getString(1) == context.packageName)
                    check(cursor.getString(2) == path && cursor.getString(3) == fixture.name)
                    true
                }
            } ?: false
        }
        fun physicalExists(fixture: Fixture) = validatePhysical(fixture)
        fun row(fixture: Fixture): VideoItem? {
            requireFresh(fixture)
            val row = io { dao.getAllVideosDirect().singleOrNull { it.uriString == fixture.uri.toString() } }
            if (row != null) {
                check(row.id == fixture.id && row.displayName == fixture.name && row.relativePath == path)
                check(row.id !in originalRows.keys)
            }
            return row
        }
        fun children(id: Long) = io { Children(db.analysisResultDao().getAnalysisForVideoDirect(id), db.extractedFrameDao().getFramesForVideoDirect(id), db.binDao().entry(id)) }
        fun state(fixture: Fixture) = State(row(fixture), children(requireNotNull(fixture.id)))
        fun assertBytes(fixture: Fixture) {
            check(validatePhysical(fixture))
            assertArrayEquals(fixture.bytes, resolver.openInputStream(fixture.uri)!!.use { it.readBytes() })
        }
        fun removeExactScanOnlyIndexEntry(fixture: Fixture) {
            val row = requireNotNull(row(fixture)); val child = children(row.id)
            check(row.status == "new" && row.reviewDecision == "unreviewed" && row.manualTranscript == null)
            check(child.analysis == null && child.frames.isEmpty() && child.bin == null)
            io { dao.deleteVideo(row) }
        }
        fun removeExactFixture(fixture: Fixture) {
            requireFresh(fixture)
            val row = row(fixture)
            if (validatePhysical(fixture)) assertEquals(1, resolver.delete(fixture.uri, null, null))
            if (row != null) io {
                db.binDao().remove(row.id); db.analysisResultDao().deleteAnalysisForVideo(row.id)
                db.extractedFrameDao().deleteFramesForVideo(row.id); dao.deleteVideo(row)
            }
        }
        fun removeEveryFreshFixture() {
            var failure: Throwable? = null
            fresh.asReversed().forEach { fixture ->
                try { removeExactFixture(fixture) } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
        fun assertOriginalRowsUnchanged() {
            val current = io { dao.getAllVideosDirect().associateBy { it.id } }
            assertEquals(originalRows, current.filterKeys { it in originalRows })
            originalChildren.forEach { (id, value) -> assertEquals(value, children(id)) }
        }
        private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    }
}
