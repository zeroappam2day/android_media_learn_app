package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import android.graphics.Bitmap
import android.graphics.Color

@RunWith(RobolectricTestRunner::class)
class MediaRuleEngineTest {
    @Test
    fun duplicateRuleKeepsEarliestThenLowestMediaStoreId() {
        val rows = listOf(
            media(id = 1, mediaStoreId = 30, dateAdded = 20),
            media(id = 2, mediaStoreId = 20, dateAdded = 10),
            media(id = 3, mediaStoreId = 10, dateAdded = 10),
            media(id = 4, mediaStoreId = 40, dateAdded = 10, name = "other.jpg")
        )

        val result = DuplicateEvaluator.groups(rows)

        assertEquals(listOf(DuplicateGroup(survivorId = 3, duplicateIds = listOf(2L, 1L))), result)
    }

    @Test
    fun duplicateRuleUsesLowestMediaStoreIdWhenDatesMissing() {
        val result = DuplicateEvaluator.groups(listOf(
            media(id = 1, mediaStoreId = 9, dateAdded = null),
            media(id = 2, mediaStoreId = 4, dateAdded = null)
        ))
        assertEquals(2L, result.single().survivorId)
        assertEquals(listOf(1L), result.single().duplicateIds)
    }

    @Test
    fun previewIsReadOnlyAndCountsExactBytes() {
        val rows = listOf(media(1, 1, 1), media(2, 2, 2))
        val before = rows.map { it.copy() }
        val definition = MediaRuleSchema.parse(MediaRuleSchema.duplicateJson()).getOrThrow()

        val preview = MediaRuleEngine().preview(definition, rows)

        assertEquals(RulePreview(listOf(2L), 1, 1234), preview)
        assertEquals(before, rows)
    }

    @Test
    fun generatedRuleRejectsUnknownFieldsAndUnknownActions() {
        assertTrue(MediaRuleSchema.parse("""{"predicate":{"type":"exact_duplicate","extra":1},"actions":[{"type":"move_to_bin"}]}""").isFailure)
        assertTrue(MediaRuleSchema.parse("""{"predicate":{"type":"exact_duplicate"},"actions":[{"type":"erase"}]}""").isFailure)
    }

    @Test
    fun obscuredRuleRequiresExplicitBoundedThreshold() {
        assertTrue(MediaRuleSchema.parse(MediaRuleSchema.obscuredJson(0.75)).isSuccess)
        assertTrue(MediaRuleSchema.parse("""{"predicate":{"type":"obscured","threshold":1.1},"actions":[{"type":"move_to_bin"}]}""").isFailure)
    }

    @Test
    fun obscuredEvaluatorSeparatesSyntheticDetailedAndCoveredImages() {
        val detailed = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val covered = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) for (x in 0 until 64) detailed.setPixel(x, y, if ((x + y) % 2 == 0) Color.WHITE else Color.BLACK)
        covered.eraseColor(Color.BLACK)

        val evaluator = ObscuredEvaluator()
        assertEquals(false, evaluator.evaluate(detailed, 0.75).matched)
        assertEquals(true, evaluator.evaluate(covered, 0.75).matched)
        assertEquals("edge-flat-v1", evaluator.evaluate(covered, 0.75).evaluator)
        detailed.recycle()
        covered.recycle()
    }

    @Test
    fun obscuredEvaluatorRejectsVideosPendingTrashedAndUnavailableMedia() {
        val evaluator = ObscuredEvaluator()
        assertEquals(false, evaluator.supports(media(1, 1, 1).copy(mimeType = "video/mp4")))
        assertEquals(false, evaluator.supports(media(1, 1, 1).copy(isPending = true)))
        assertEquals(false, evaluator.supports(media(1, 1, 1).copy(isTrashed = true)))
        assertEquals(false, evaluator.supports(media(1, 1, 1).copy(isCurrentlyAvailable = false)))
        assertEquals(true, evaluator.supports(media(1, 1, 1)))
    }

    @Test
    fun duplicateEvaluationHandles11761RowsDeterministically() {
        val rows = (1L..11_761L).map { id ->
            media(id, id, id, name = "${id / 2}.jpg", size = id / 2)
        }
        val first = DuplicateEvaluator.groups(rows)
        val second = DuplicateEvaluator.groups(rows.shuffled(java.util.Random(11761)))
        assertEquals(first, second)
        assertEquals(5_880, first.sumOf { it.duplicateIds.size })
    }

    private fun media(
        id: Long,
        mediaStoreId: Long,
        dateAdded: Long?,
        name: String = "same.jpg",
        size: Long = 1234
    ) = VideoItem(
        id = id,
        mediaStoreId = mediaStoreId,
        uriString = "content://test/$mediaStoreId",
        displayName = name,
        relativePath = "Pictures/",
        mimeType = "image/jpeg",
        sizeBytes = size,
        durationMs = null,
        dateAdded = dateAdded,
        dateModified = dateAdded
    )
}
