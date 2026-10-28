package com.example.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAnalysisPolicyTest {
    @Test
    fun onlyKeepIsEligibleForAnalysis() {
        assertFalse(MediaAnalysisPolicy.canAnalyze("unreviewed"))
        assertTrue(MediaAnalysisPolicy.canAnalyze("keep"))
        assertFalse(MediaAnalysisPolicy.canAnalyze("review_later"))
        assertFalse(MediaAnalysisPolicy.canAnalyze("ready_to_delete"))
    }

    @Test
    fun queueAttemptsEmitOnlyKeepItemsInInputOrder() {
        val items = listOf(
            fixture(1, "unreviewed"),
            fixture(2, "keep"),
            fixture(3, "review_later"),
            fixture(4, "keep"),
            fixture(5, "ready_to_delete")
        )
        val emitted = mutableListOf<Long>()

        MediaAnalysisPolicy.emitEligibleIdsInAttemptOrder(items, emitted::add)

        assertEquals(listOf(2L, 4L), emitted)
    }

    @Test
    fun keptMediaMustRemainAvailablePublishedAndOutsideAndroidTrash() {
        val available = fixture(1, "keep")
        val unavailable = fixture(2, "keep").copy(isCurrentlyAvailable = false)
        val pending = fixture(3, "keep").copy(isPending = true)
        val trashed = fixture(4, "keep").copy(isTrashed = true)
        val unreviewed = fixture(5, "unreviewed")
        val secondAvailable = fixture(6, "keep")
        val items = listOf(available, unavailable, pending, trashed, unreviewed, secondAvailable)

        assertTrue(MediaAnalysisPolicy.canAnalyze(available))
        listOf(unavailable, pending, trashed, unreviewed).forEach {
            assertFalse("Media ${it.id} must not be analyzed", MediaAnalysisPolicy.canAnalyze(it))
        }
        assertEquals(listOf(available, secondAvailable), MediaAnalysisPolicy.eligible(items))
        val emitted = mutableListOf<Long>()
        MediaAnalysisPolicy.emitEligibleIdsInAttemptOrder(items, emitted::add)
        assertEquals(listOf(1L, 6L), emitted)
    }

    private fun fixture(id: Long, decision: String) = VideoItem(
        id = id,
        mediaStoreId = id,
        uriString = "content://fixture/$id",
        displayName = "fixture-$id",
        relativePath = null,
        mimeType = "image/jpeg",
        sizeBytes = 1,
        durationMs = null,
        dateAdded = null,
        dateModified = null,
        reviewDecision = decision
    )
}
