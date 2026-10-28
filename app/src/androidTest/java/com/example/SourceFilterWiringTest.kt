package com.example

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.data.MediaInventoryTotals
import com.example.data.SubcategoryAggregate
import com.example.data.VideoItem
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Renders the production source panel with in-memory fixtures on the emulator. */
class SourceFilterWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun emulatorOnly() = PreviewRuntimeGuard.requireApprovedPreviewRuntime()

    @Test fun subcategoryFiltersRowsAndSecondTapShowsAll() {
        val items = listOf(item(1, "Facebook"), item(2, "Instagram"))
        compose.setContent {
            MaterialTheme {
                MediaSourcesScreen(totals = MediaInventoryTotals(2, 20), aggregates = emptyList(), selectedCategory = "Screenshots",
                    subcategories = listOf(SubcategoryAggregate("Facebook", 1, 10), SubcategoryAggregate("Instagram", 1, 10)),
                    allMedia = items, mediaAccess = "Full access", isScanning = false, onScan = {}, onSelectCategory = {}, onDecision = { _, _ -> })
            }
        }
        compose.onNodeWithTag("source_media_1").assertIsDisplayed()
        compose.onNodeWithTag("source_media_2").assertIsDisplayed()
        compose.onNodeWithTag("source_subcategory_Facebook").performClick()
        compose.onNodeWithTag("source_media_1").assertIsDisplayed()
        compose.onNodeWithTag("source_media_2").assertDoesNotExist()
        compose.onNodeWithTag("source_subcategory_Facebook").performClick()
        compose.onNodeWithTag("source_media_2").assertIsDisplayed()
    }

    private fun item(id: Long, subcategory: String) = VideoItem(id = id, mediaStoreId = id,
        uriString = "content://qa/$id", displayName = "$subcategory.png", relativePath = "Pictures/Screenshots/",
        mimeType = "image/png", sizeBytes = 10, durationMs = null, dateAdded = null, dateModified = null,
        sourceCategory = "Screenshots", sourceSubcategory = subcategory, subcategoryEvidenceKind = "filename_token")
}
