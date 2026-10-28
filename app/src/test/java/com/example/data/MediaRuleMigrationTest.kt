package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@Database(
    entities = [VideoItem::class, ExtractedFrame::class, AnalysisResult::class, ScanRun::class, ProcessingRun::class, AppSettings::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class LegacyMediaDatabase : RoomDatabase() {
    abstract fun media(): VideoItemDao
}

@RunWith(RobolectricTestRunner::class)
class MediaRuleMigrationTest {
    private lateinit var context: Context
    private val name = "media-rule-migration-test"

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(name)
    }

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun version2RowSurvivesAndFeatureTablesStartEmpty() = runBlocking {
        val legacy = Room.databaseBuilder(context, LegacyMediaDatabase::class.java, name).allowMainThreadQueries().build()
        legacy.media().insertVideo(sentinel())
        legacy.close()

        val migrated = Room.databaseBuilder(context, VideoDatabase::class.java, name)
            .addMigrations(VideoDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        val row = migrated.videoItemDao().getVideoByMediaStoreId(404)!!

        assertEquals("sentinel.jpg", row.displayName)
        assertEquals("keep", row.reviewDecision)
        assertEquals("Camera", row.sourceCategory)
        assertTrue(migrated.mediaRuleDao().revision(1, 1) == null)
        assertTrue(migrated.binDao().entry(row.id) == null)
        migrated.close()
    }

    private fun sentinel() = VideoItem(
        mediaStoreId = 404,
        uriString = "content://test/404",
        displayName = "sentinel.jpg",
        relativePath = "DCIM/Camera/",
        mimeType = "image/jpeg",
        sizeBytes = 2048,
        durationMs = null,
        dateAdded = 100,
        dateModified = 101,
        reviewDecision = "keep",
        mediaCollection = "image",
        sourceCategory = "Camera",
        isCurrentlyAvailable = true
    )
}

