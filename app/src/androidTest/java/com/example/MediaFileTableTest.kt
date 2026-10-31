package com.example

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.*
import com.example.ui.FileTableHeader
import com.example.ui.FileTableRow
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production table consumers with isolated immutable records; no media, database or provider access. */
@RunWith(AndroidJUnit4::class)
class MediaFileTableTest {
    @get:Rule val compose = createAndroidComposeRule<InventoryRecreationTestActivity>()
    private val dark = mutableStateOf(false)
    private val large = mutableStateOf(false)
    private val longName = "Family photographs and saved knowledge with a deliberately long readable filename.png"
    private var actionCount = 0
    private val checked = mutableStateOf(false)
    private val records = mutableStateOf(listOf(item(1, longName), item(2, "second.png")))
    private fun host(content: @Composable () -> Unit) {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        compose.activity.setContent {
            MyApplicationTheme(darkTheme = dark.value) {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, if (large.value) 2f else 1f)) {
                    Surface { Box(Modifier.width(320.dp).fillMaxHeight()) { content() } }
                }
            }
        }
    }
    private fun renderRows() = host {
        val scroll = rememberScrollState()
        Column(Modifier.verticalScroll(rememberScrollState())) {
            FileTableHeader(180.dp, scroll, "test")
            FileTableRow(180.dp, scroll, longName, "Pictures/Family/", "100 B", "New",
                leading = { Checkbox(checked.value, { checked.value = it }, Modifier.testTag("check")) }) {
                Button({ actionCount++ }, Modifier.testTag("action")) { Text("Open") }
            }
            FileTableRow(180.dp, scroll, "second.png", "Pictures/Family/", "200 B", "Saved") {
                Text("No action")
            }
        }
    }
    @Test fun sharedColumnsScrollTogetherAndFileIdentityStaysPinned() {
        renderRows()
        val before = compose.onNodeWithText(longName).getUnclippedBoundsInRoot()
        compose.onNodeWithTag("action").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, actionCount)
        val after = compose.onNodeWithText(longName).getUnclippedBoundsInRoot()
        assertEquals(before, after)
        compose.onNodeWithText(longName).assertIsDisplayed()
        compose.onNodeWithTag("test_header_actions").assertIsDisplayed()
        compose.onNodeWithTag("check").performClick()
        assertTrue(checked.value)
        var pages = 0
        while (compose.onAllNodesWithContentDescription("First columns").fetchSemanticsNodes().isEmpty()) {
            assertTrue("Column paging must reach the end", pages++ < 4)
            compose.onNodeWithContentDescription("Next columns").performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithContentDescription("First columns").performClick()
        val header = compose.onNodeWithText("Location").getUnclippedBoundsInRoot()
        val locations = compose.onAllNodesWithText("Pictures/Family/")
        locations.assertCountEquals(2)
        for (index in 0..1) assertEquals(header.left.value, locations[index].getUnclippedBoundsInRoot().left.value, 1f)
    }
    @Test fun longFilenamesRemainReadableInBothThemesAndDoubleText() {
        renderRows()
        for (theme in listOf(false, true)) for (font in listOf(false, true)) {
            compose.runOnIdle { dark.value = theme; large.value = font }
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(longName).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.single().lineCount > 1)
            assertFalse("Filename must wrap without ellipsis or overflow", layouts.single().hasVisualOverflow)
            compose.onNodeWithTag("action").performScrollTo().performClick()
        }
        assertEquals(4, actionCount)
    }
    @Test fun discoveredFilesRenderAsRowsAndReviewChangesOnlyChosenRecord() {
        host {
            MediaSourcesScreen(MediaInventoryTotals(2, 200), listOf(SourceAggregate("Camera", 2, 200)),
                selectedCategory = null, subcategories = emptyList(), allMedia = records.value,
                mediaAccess = "Full access", isScanning = false, onScan = {}, onSelectCategory = {},
                onDecision = { id, choice -> records.value = records.value.map { if (it.id == id) it.copy(reviewDecision = choice) else it } })
        }
        compose.onNodeWithTag("source_columns").performScrollTo().assertExists()
        compose.onNodeWithText(longName).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("review_decision_1").performScrollTo().performClick()
        compose.onNode(hasText("Keep") and hasClickAction()).performClick()
        assertEquals("keep", records.value.first().reviewDecision)
        assertEquals("unreviewed", records.value.last().reviewDecision)
        compose.onNodeWithTag("source_header_file").assertExists()
        compose.onNodeWithTag("source_header_size").assertExists()
    }
    private fun item(id: Long, name: String) = VideoItem(id = id, mediaStoreId = id,
        uriString = "content://table-fixture/$id", displayName = name, relativePath = "Pictures/Family/",
        mimeType = "image/png", sizeBytes = 100, durationMs = null, dateAdded = null, dateModified = null,
        sourceCategory = "Camera", reviewDecision = "unreviewed", mediaCollection = "image")
}
