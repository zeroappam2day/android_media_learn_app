package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaInventoryDaoTest {
    private lateinit var database: VideoDatabase
    private lateinit var dao: VideoItemDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, VideoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.videoItemDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun totalsAndGroupsExactlyExcludeIneligibleRowsAndOmitMissingSubcategories() = runTest {
        dao.insertVideos(
            listOf(
                fixture(1, 3, "Camera"),
                fixture(2, 5, "Camera", "Facebook", pending = true),
                fixture(3, 7, "Camera", "Instagram", trashed = true),
                fixture(4, 11, "WhatsApp", available = false),
                fixture(5, 2_500_000_000, "Screenshots", "Facebook"),
                fixture(6, 2_500_000_001, "Screenshots"),
                fixture(7, 13, null)
            )
        )

        assertEquals(MediaInventoryTotals(4, 5_000_000_017), dao.getEligibleInventoryTotals().first())
        assertEquals(
            listOf(
                SourceAggregate("Screenshots", 2, 5_000_000_001),
                SourceAggregate("Other", 1, 13),
                SourceAggregate("Camera", 1, 3)
            ),
            dao.getSourceAggregates().first()
        )

        val subcategories = dao.getSubcategoryAggregates("Screenshots").first()
        assertEquals(listOf(SubcategoryAggregate("Facebook", 1, 2_500_000_000)), subcategories)
        assertFalse(subcategories.any { it.subcategory == "Unknown" })
    }

    @Test
    fun reviewDecisionPersistsWithoutReplacingTheMediaRow() = runTest {
        val id = dao.insertVideo(fixture(8, 21, "Downloads"))

        dao.updateReviewDecision(id, "keep")

        val persisted = dao.getVideoByIdDirect(id)!!
        assertEquals("keep", persisted.reviewDecision)
        assertEquals(8, persisted.mediaStoreId)
        assertEquals("content://fixture/8", persisted.uriString)
    }

    private fun fixture(
        mediaStoreId: Long,
        sizeBytes: Long,
        category: String?,
        subcategory: String? = null,
        pending: Boolean = false,
        trashed: Boolean = false,
        available: Boolean = true
    ) = VideoItem(
        mediaStoreId = mediaStoreId,
        uriString = "content://fixture/$mediaStoreId",
        displayName = "fixture-$mediaStoreId",
        relativePath = null,
        mimeType = "image/jpeg",
        sizeBytes = sizeBytes,
        durationMs = null,
        dateAdded = null,
        dateModified = null,
        sourceCategory = category,
        sourceSubcategory = subcategory,
        isPending = pending,
        isTrashed = trashed,
        isCurrentlyAvailable = available
    )
}
