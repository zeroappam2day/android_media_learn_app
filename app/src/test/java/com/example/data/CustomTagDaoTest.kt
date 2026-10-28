package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.example.ui.BinCoordinator
import com.example.ui.DeletionResultPolicy

@RunWith(RobolectricTestRunner::class)
class CustomTagDaoTest {
    private lateinit var db: VideoDatabase

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VideoDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun cleanup() = db.close()

    @Test fun deletingTagRemovesAssignmentButKeepsMedia() = runBlocking {
        val mediaId = db.videoItemDao().insertVideo(media())
        val tagId = db.customTagDao().insert(CustomTag(name = "Work"))
        db.customTagDao().assign(MediaTagCrossRef(mediaId, tagId))
        assertEquals(1, db.customTagDao().mediaWithTag(tagId).first().size)

        db.customTagDao().delete(db.customTagDao().byName("Work")!!)

        assertNotNull(db.videoItemDao().getVideoByIdDirect(mediaId))
        assertEquals(0, db.customTagDao().observeAssignments().first().size)
    }

    @Test fun recoveryRestoresOnlyPriorDecisionAndRemovesBinEntry() = runBlocking {
        val mediaId = db.videoItemDao().insertVideo(media().copy(reviewDecision = "keep"))
        db.binDao().add(BinEntry(mediaId = mediaId, priorReviewDecision = "keep"))
        db.videoItemDao().updateReviewDecision(mediaId, "ready_to_delete")

        BinCoordinator(ApplicationProvider.getApplicationContext<Context>().contentResolver, db.videoItemDao(), db.binDao()).recover(mediaId)

        assertEquals("keep", db.videoItemDao().getVideoByIdDirect(mediaId)?.reviewDecision)
        assertEquals(null, db.binDao().entry(mediaId))
    }

    @Test fun reconciliationRemovesOnlyProviderAbsentMediaFromEligibleTotals() = runBlocking {
        val mediaId = db.videoItemDao().insertVideo(media())
        db.binDao().add(BinEntry(mediaId = mediaId, priorReviewDecision = "unreviewed"))

        val removed = BinCoordinator(ApplicationProvider.getApplicationContext<Context>().contentResolver, db.videoItemDao(), db.binDao()) { false }.reconcile(listOf(mediaId))

        assertEquals(listOf(mediaId), removed)
        assertEquals(false, db.videoItemDao().getVideoByIdDirect(mediaId)?.isCurrentlyAvailable)
        assertEquals(null, db.binDao().entry(mediaId))
    }

    @Test fun cancelledDeleteResultCannotStartReconciliation() {
        assertEquals(false, DeletionResultPolicy.shouldReconcile(false, listOf(1L)))
        assertEquals(false, DeletionResultPolicy.shouldReconcile(true, emptyList()))
        assertEquals(true, DeletionResultPolicy.shouldReconcile(true, listOf(1L)))
    }

    @Test fun binStateRetainsThumbnailSourceUri() = runBlocking {
        val mediaId = db.videoItemDao().insertVideo(media())
        db.binDao().add(BinEntry(mediaId = mediaId, priorReviewDecision = "unreviewed"))
        assertEquals("content://test/7", db.binDao().observeMedia().first().single().uriString)
    }

    private fun media() = VideoItem(
        mediaStoreId = 7,
        uriString = "content://test/7",
        displayName = "item.jpg",
        relativePath = "Pictures/",
        mimeType = "image/jpeg",
        sizeBytes = 8,
        durationMs = null,
        dateAdded = 1,
        dateModified = 1
    )
}
