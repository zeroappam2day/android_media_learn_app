package com.example

import android.content.ContentUris
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.VideoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.assertTrue

/** Removes only fixtures created by StorageCompletionTest after an interrupted teardown. */
class OwnedStorageCleanupTest {
    @Test fun removeInterruptedStorageFixturesOnly() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".qa.preview"))
        val resolver = context.contentResolver
        val ownedPath = Regex("Pictures/QAStorage[0-9a-f]{12}/")
        val ownedName = Regex("QAStorage[0-9a-f]{12}-.+\\.png")
        val db = VideoDatabase.getDatabase(context)
        runBlocking(Dispatchers.IO) {
            db.videoItemDao().getAllVideosDirect().filter { ownedPath.matches(it.relativePath.orEmpty()) && ownedName.matches(it.displayName) }.forEach { media ->
                val uri = android.net.Uri.parse(media.uriString)
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) { check(cursor.getString(0) == context.packageName); resolver.delete(uri, null, null) }
                }
                db.binDao().remove(media.id)
                db.analysisResultDao().deleteAnalysisForVideo(media.id)
                db.extractedFrameDao().deleteFramesForVideo(media.id)
                db.videoItemDao().deleteVideo(media)
            }
            assertTrue(db.videoItemDao().getAllVideosDirect().none { ownedPath.matches(it.relativePath.orEmpty()) && ownedName.matches(it.displayName) })
        }
    }
    @Test fun removeScanOnlyRowsFromNewQaIndex() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("qa_remove_scan_only") == "true")
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".qa.preview"))
        val db = VideoDatabase.getDatabase(context)
        runBlocking(Dispatchers.IO) {
            val rows = db.videoItemDao().getAllVideosDirect()
            check(rows.all { media -> media.reviewDecision == "unreviewed" && media.status == "new" && media.manualTranscript == null && db.analysisResultDao().getAnalysisForVideoDirect(media.id) == null && db.extractedFrameDao().getFramesForVideoDirect(media.id).isEmpty() && db.binDao().entry(media.id) == null }) { "Refusing cleanup: QA index contains edited/reviewed/processed records" }
            rows.forEach { db.videoItemDao().deleteVideo(it) }
            assertTrue(db.videoItemDao().getAllVideosDirect().isEmpty())
        }
        context.getSharedPreferences("app_prefs", 0).edit().remove("target_folders").putBoolean("auto_ingest_enabled", false).commit()
        // Index entries only: no resolver.delete, storage clear, or original preview access.
    }
}
