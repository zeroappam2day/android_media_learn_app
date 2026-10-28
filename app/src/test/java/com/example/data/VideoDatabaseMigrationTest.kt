package com.example.data

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoDatabaseMigrationTest {
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun createVersionOneFixture() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = createV1Schema(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        db = helper.writableDatabase
        db.execSQL("PRAGMA foreign_keys = ON")
        assertEquals(1L, scalarLong("PRAGMA foreign_keys"))
        seedV1Graph(db)
    }

    @After
    fun closeDatabase() {
        helper.close()
    }

    @Test
    fun migrationPreservesGraphAndAddsEvidencePreservingDefaults() {
        val preMigrationSnapshots = listOf(
            "video_items", "extracted_frames", "analysis_results",
            "scan_runs", "processing_runs", "app_settings"
        ).associateWith(::snapshotTable)

        VideoDatabase.MIGRATION_1_2.migrate(db)

        assertEquals(
            setOf("video_items", "extracted_frames", "analysis_results", "scan_runs", "processing_runs", "app_settings"),
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        )
        assertEquals(
            expectedVideoColumns,
            db.query("PRAGMA table_info(video_items)").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
        )
        preMigrationSnapshots.forEach { (table, snapshot) -> assertEquals(snapshot, snapshotTable(table)) }
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM video_items"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM extracted_frames"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM analysis_results"))
        assertEquals("archived", scalarString("SELECT status FROM video_items WHERE id = 1"))
        assertEquals("result_saved", scalarString("SELECT lastCompletedStep FROM video_items WHERE id = 1"))
        assertEquals("fixture notes", scalarString("SELECT manualTranscript FROM video_items WHERE id = 1"))
        assertEquals("fixture error", scalarString("SELECT errorMessage FROM video_items WHERE id = 1"))
        assertEquals(14L, scalarLong("SELECT processedAt FROM video_items WHERE id = 1"))
        assertEquals(15L, scalarLong("SELECT archivedAt FROM video_items WHERE id = 1"))
        assertEquals(16L, scalarLong("SELECT readyToDeleteAt FROM video_items WHERE id = 1"))
        assertEquals(17L, scalarLong("SELECT trashedAt FROM video_items WHERE id = 1"))
        assertEquals(18L, scalarLong("SELECT deleteRequestedAt FROM video_items WHERE id = 1"))
        assertEquals("limited", scalarString("SELECT mediaAccessScope FROM video_items WHERE id = 1"))
        assertEquals("unreviewed", scalarString("SELECT reviewDecision FROM video_items WHERE id = 1"))
        assertEquals("video", scalarString("SELECT mediaCollection FROM video_items WHERE id = 1"))
        assertEquals(1L, scalarLong("SELECT isCurrentlyAvailable FROM video_items WHERE id = 1"))
        listOf(
            "bucketDisplayName", "volumeName", "sourceCategory", "sourceSubcategory",
            "categoryEvidenceKind", "subcategoryEvidenceKind", "categoryConfidence",
            "subcategoryConfidence", "classifiedAt", "lastSeenScanToken"
        ).forEach { column -> assertTrue(scalarStringOrNull("SELECT $column FROM video_items WHERE id = 1") == null) }
        assertEquals(0L, scalarLong("SELECT classificationRuleVersion FROM video_items WHERE id = 1"))
        assertEquals(0L, scalarLong("SELECT isPending FROM video_items WHERE id = 1"))
        assertEquals(0L, scalarLong("SELECT isTrashed FROM video_items WHERE id = 1"))
        assertFalse(db.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM video_items v JOIN extracted_frames f ON f.videoId = v.id WHERE v.id = 1"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM video_items v JOIN analysis_results a ON a.videoId = v.id WHERE v.id = 1"))

        db.execSQL("UPDATE video_items SET sizeBytes = 5000000000 WHERE id = 1")
        assertEquals(5_000_000_000L, scalarLong("SELECT COALESCE(SUM(sizeBytes), 0) FROM video_items"))
        db.execSQL("""INSERT INTO video_items (id, mediaStoreId, uriString, displayName, relativePath, mimeType, sizeBytes, durationMs, dateAdded, dateModified, firstSeenAt, lastScannedAt, mediaAccessScope, status, lastCompletedStep, reviewDecision, mediaCollection, classificationRuleVersion, isPending, isTrashed, isCurrentlyAvailable) VALUES (2, 200, 'content://fixture/video/200', 'second-fixture.mp4', NULL, 'video/mp4', 7, 1, 1, 1, 1, 1, 'full', 'new', 'discovered', 'keep', 'video', 1, 0, 0, 1)""")
        db.execSQL("INSERT INTO extracted_frames (id, videoId, timestampMs, ocrText, createdAt) VALUES (2, 2, 1, 'second OCR', 1)")
        db.execSQL("""INSERT INTO analysis_results (id, videoId, descriptionLine1, descriptionLine2, categoryLevel1, categoryLevel2, categoryLevel3, formattedActionPlan, urls, codeOrCommands, evidenceJson, source, createdAt, updatedAt) VALUES (2, 2, 'a', 'b', 'c', 'd', 'e', 'f', '', '', '{}', 'fallback', 1, 1)""")
        db.execSQL("UPDATE video_items SET reviewDecision = 'review_later' WHERE id = 2")
        assertEquals("review_later", scalarString("SELECT reviewDecision FROM video_items WHERE id = 2"))
        db.execSQL("DELETE FROM video_items WHERE id = 2")
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM extracted_frames WHERE videoId = 2"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM analysis_results WHERE videoId = 2"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM extracted_frames WHERE videoId = 1"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM analysis_results WHERE videoId = 1"))
        assertFalse(db.query("PRAGMA foreign_key_check").use { it.moveToFirst() })
    }

    private fun createV1Schema(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("""CREATE TABLE video_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, mediaStoreId INTEGER NOT NULL, uriString TEXT NOT NULL, displayName TEXT NOT NULL, relativePath TEXT, mimeType TEXT, sizeBytes INTEGER, durationMs INTEGER, dateAdded INTEGER, dateModified INTEGER, firstSeenAt INTEGER NOT NULL, lastScannedAt INTEGER NOT NULL, processedAt INTEGER, archivedAt INTEGER, readyToDeleteAt INTEGER, trashedAt INTEGER, deleteRequestedAt INTEGER, mediaAccessScope TEXT NOT NULL, status TEXT NOT NULL, lastCompletedStep TEXT NOT NULL, errorMessage TEXT, manualTranscript TEXT)""")
        db.execSQL("""CREATE TABLE extracted_frames (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, videoId INTEGER NOT NULL, timestampMs INTEGER NOT NULL, ocrText TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(videoId) REFERENCES video_items(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX index_extracted_frames_videoId ON extracted_frames(videoId)")
        db.execSQL("""CREATE TABLE analysis_results (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, videoId INTEGER NOT NULL, descriptionLine1 TEXT NOT NULL, descriptionLine2 TEXT NOT NULL, categoryLevel1 TEXT NOT NULL, categoryLevel2 TEXT NOT NULL, categoryLevel3 TEXT NOT NULL, formattedActionPlan TEXT NOT NULL, urls TEXT NOT NULL, codeOrCommands TEXT NOT NULL, evidenceJson TEXT NOT NULL, source TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(videoId) REFERENCES video_items(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX index_analysis_results_videoId ON analysis_results(videoId)")
        db.execSQL("""CREATE TABLE scan_runs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, startedAt INTEGER NOT NULL, finishedAt INTEGER, videosFound INTEGER NOT NULL, videosAdded INTEGER NOT NULL, videosUpdated INTEGER NOT NULL, accessScope TEXT NOT NULL, status TEXT NOT NULL, errorMessage TEXT)""")
        db.execSQL("""CREATE TABLE processing_runs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, videoId INTEGER, startedAt INTEGER NOT NULL, finishedAt INTEGER, status TEXT NOT NULL, currentStep TEXT NOT NULL, errorMessage TEXT)""")
        db.execSQL("""CREATE TABLE app_settings (id INTEGER NOT NULL PRIMARY KEY, ollamaEnabled INTEGER NOT NULL, ollamaEndpointUrl TEXT NOT NULL, ollamaModelName TEXT NOT NULL, requestTimeoutSeconds INTEGER NOT NULL, confirmedNonLocalEndpointWarning INTEGER NOT NULL)""")
    }

    private fun seedV1Graph(db: SupportSQLiteDatabase) {
        db.execSQL("""INSERT INTO video_items VALUES (1, 100, 'content://fixture/video/100', 'fixture.mp4', NULL, 'video/mp4', 1234, 8000, 10, 11, 12, 13, 14, 15, 16, 17, 18, 'limited', 'archived', 'result_saved', 'fixture error', 'fixture notes')""")
        db.execSQL("INSERT INTO extracted_frames VALUES (1, 1, 500, 'fixture OCR', 20)")
        db.execSQL("""INSERT INTO analysis_results VALUES (1, 1, 'one', 'two', 'Technical', 'Test', 'fixture', 'plan', 'https://fixture.invalid', 'command', 'evidence', 'fallback', 21, 22)""")
        db.execSQL("INSERT INTO scan_runs VALUES (1, 1, 2, 1, 1, 0, 'full', 'finished', NULL)")
        db.execSQL("INSERT INTO processing_runs VALUES (1, 1, 1, 2, 'finished', 'result_saved', NULL)")
        db.execSQL("INSERT INTO app_settings VALUES (1, 0, 'http://127.0.0.1', 'fixture', 30, 0)")
    }

    private fun scalarLong(sql: String): Long = db.query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun scalarString(sql: String): String = db.query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }

    private fun scalarStringOrNull(sql: String): String? = db.query(sql).use { cursor ->
        check(cursor.moveToFirst())
        if (cursor.isNull(0)) null else cursor.getString(0)
    }

    private fun snapshotTable(table: String): List<List<Any?>> {
        val projection = if (table == "video_items") oldVideoColumns.joinToString(",") else "*"
        return db.query("SELECT $projection FROM $table ORDER BY id").use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add((0 until cursor.columnCount).map { column ->
                    when (cursor.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                        else -> cursor.getString(column)
                    }
                })
            }
        }
    }
    }

    private val oldVideoColumns = listOf(
        "id", "mediaStoreId", "uriString", "displayName", "relativePath", "mimeType",
        "sizeBytes", "durationMs", "dateAdded", "dateModified", "firstSeenAt", "lastScannedAt",
        "processedAt", "archivedAt", "readyToDeleteAt", "trashedAt", "deleteRequestedAt",
        "mediaAccessScope", "status", "lastCompletedStep", "errorMessage", "manualTranscript"
    )

    private val expectedVideoColumns = listOf(
        "id", "mediaStoreId", "uriString", "displayName", "relativePath", "mimeType",
        "sizeBytes", "durationMs", "dateAdded", "dateModified", "firstSeenAt", "lastScannedAt",
        "processedAt", "archivedAt", "readyToDeleteAt", "trashedAt", "deleteRequestedAt",
        "mediaAccessScope", "status", "lastCompletedStep", "errorMessage", "manualTranscript",
        "reviewDecision", "mediaCollection", "bucketDisplayName", "volumeName", "sourceCategory",
        "sourceSubcategory", "categoryEvidenceKind", "subcategoryEvidenceKind", "categoryConfidence",
        "subcategoryConfidence", "classificationRuleVersion", "classifiedAt", "isPending", "isTrashed",
        "isCurrentlyAvailable", "lastSeenScanToken"
    )
}
