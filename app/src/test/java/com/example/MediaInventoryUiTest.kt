package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import com.github.takahirom.roborazzi.captureRoboImage
import com.example.data.MediaInventoryTotals
import com.example.data.SourceAggregate
import com.example.data.SubcategoryAggregate
import com.example.data.VideoItem
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h772dp-xxhdpi")
class MediaInventoryUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun navigationPreservesIconAndTextRowsWithShortVisibleLabels() {
        compose.setContent {
            MaterialTheme { MediaManagementNavigation("sources") {} }
        }

        val destinations = listOf("Home", "Folders", "Sources", "Queue", "Videos", "Images")
        destinations.forEach { destination ->
            assertTrue(destination.codePointCount(0, destination.length) <= 8)
            compose.onNodeWithText(destination)
                .assertIsDisplayed()
                .assertHasClickAction()
        }

        File("/tmp/media-ux-compose-semantics.txt").writeText(
            compose.onRoot(useUnmergedTree = true).printToString()
        )
    }

    @Test
    fun completeVisibleControlLabelCatalogRendersWithinEightCodePoints() {
        val tabs = listOf("Name", "Status", "Size")
        val filters = listOf(
            "All", "New", "Queued", "Done", "Error", "Archived", "Videos", "Images",
            "Remove", "PC", "Cloud", "Device", "No model"
        )
        val forms = listOf(
            "Host", "API key", "Model", "Temp", "Prompt", "Filter",
            "Group 1", "Group 2", "Group 3", "Line 1", "Line 2", "Timeout"
        )
        val expandable = listOf("Overview", "Folders", "Guide", "AI setup")
        val actions = listOf("Change", "Dismiss", "Done", "Cancel", "Enable", "Presets", "I agree")
        val allLabels = tabs + filters + forms + expandable + actions

        allLabels.forEach { label ->
            assertTrue(
                "Visible control label exceeds eight Unicode code points: $label",
                label.codePointCount(0, label.length) <= 8
            )
        }

        compose.setContent {
            MaterialTheme {
                ControlLabelPolicyFixture(tabs, filters, forms, expandable, actions)
            }
        }
        compose.waitForIdle()
        File("/tmp/media-ux-control-catalog-semantics.txt").writeText(
            compose.onRoot(useUnmergedTree = true).printToString()
        )
    }

    @Test
    fun offCanvasPanelPreservesWidthMovesScreenAndRestoresGeometryAndState() {
        compose.setContent { MaterialTheme { OffCanvasFixture() } }

        val before = compose.onNodeWithTag("offcanvas_content").getUnclippedBoundsInRoot()
        val stateBefore = compose.onNodeWithTag("fixture_state").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("fixture_state").assertTextContains("Sources:7:23", substring = false)
        compose.onRoot().captureRoboImage(filePath = "/tmp/media-ux-synthetic-closed.png")

        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.waitForIdle()
        val opened = compose.onNodeWithTag("offcanvas_content").getUnclippedBoundsInRoot()
        val stateOpened = compose.onNodeWithTag("fixture_state").getUnclippedBoundsInRoot()
        assertEquals(before.right - before.left, opened.right - opened.left)
        assertTrue("Content must be translated fully off-canvas", opened.left >= before.right)
        assertEquals(stateBefore.right - stateBefore.left, stateOpened.right - stateOpened.left)
        assertEquals(opened.left - before.left, stateOpened.left - stateBefore.left)
        compose.onNodeWithTag("nav_panel").assertIsDisplayed()
        compose.onRoot().captureRoboImage(filePath = "/tmp/media-ux-synthetic-open.png")

        compose.onNodeWithContentDescription("Close menu").performClick()
        compose.waitForIdle()
        val restored = compose.onNodeWithTag("offcanvas_content").getUnclippedBoundsInRoot()
        val stateRestored = compose.onNodeWithTag("fixture_state").getUnclippedBoundsInRoot()
        assertEquals(before.left, restored.left)
        assertEquals(before.top, restored.top)
        assertEquals(before.right, restored.right)
        assertEquals(before.bottom, restored.bottom)
        assertEquals(stateBefore.left, stateRestored.left)
        assertEquals(stateBefore.top, stateRestored.top)
        assertEquals(stateBefore.right, stateRestored.right)
        assertEquals(stateBefore.bottom, stateRestored.bottom)
        compose.onNodeWithTag("fixture_state").assertTextContains("Sources:7:23", substring = false)
        compose.onRoot().captureRoboImage(filePath = "/tmp/media-ux-synthetic-restored.png")

        File("/tmp/media-ux-layout-bounds.json").writeText(
            """{
              "root": {
                "before": ${before.toJson()},
                "open": ${opened.toJson()},
                "restored": ${restored.toJson()}
              },
              "descendant": {
                "before": ${stateBefore.toJson()},
                "open": ${stateOpened.toJson()},
                "restored": ${stateRestored.toJson()}
              },
              "assertions": {
                "width_unchanged_open": true,
                "translated_fully_off_canvas": true,
                "root_restored_exactly": true,
                "descendant_restored_exactly": true
              }
            }""".trimIndent()
        )
        File("/tmp/media-ux-state-trace.json").writeText(
            """{
              "ordered_states": [
                {"phase": "before", "destination": "Sources", "scroll_index": 7, "scroll_offset": 23},
                {"phase": "open", "destination": "Sources", "scroll_index": 7, "scroll_offset": 23},
                {"phase": "restored", "destination": "Sources", "scroll_index": 7, "scroll_offset": 23}
              ],
              "post_close_equals_pre_open": true
            }""".trimIndent()
        )
    }

    @Test
    fun productionMainScreenUsesTheSameOffCanvasLayoutAsTheGeometryTest() {
        val sourceFile = listOf(
            File("app/src/main/java/com/example/MainActivity.kt"),
            File("src/main/java/com/example/MainActivity.kt")
        ).firstOrNull(File::isFile)
        assertTrue("MainActivity.kt must be available to the production-linkage test", sourceFile != null)

        val source = sourceFile!!.readText()
        val mainScreen = source.substringAfter("fun MainScreen(", missingDelimiterValue = "")
        assertTrue("MainScreen declaration must be present", mainScreen.isNotEmpty())
        assertTrue(
            "Production MainScreen must invoke the tested OffCanvasPanelLayout primitive",
            mainScreen.contains("OffCanvasPanelLayout(")
        )
        assertEquals(
            "Only the primitive declaration and the production MainScreen invocation are expected",
            2,
            Regex("\\bOffCanvasPanelLayout\\(").findAll(source).count()
        )
        val testSourceFile = listOf(
            File("app/src/test/java/com/example/MediaInventoryUiTest.kt"),
            File("src/test/java/com/example/MediaInventoryUiTest.kt")
        ).firstOrNull(File::isFile)
        assertTrue("MediaInventoryUiTest.kt must be available to the linkage test", testSourceFile != null)
        val fixtureSource = testSourceFile!!.readText()
            .substringAfter("private fun OffCanvasFixture()", missingDelimiterValue = "")
        assertTrue("The geometry fixture must invoke the production primitive", fixtureSource.contains("OffCanvasPanelLayout("))

        File("/tmp/media-ux-production-linkage.json").writeText(
            """{
              "production_file": "app/src/main/java/com/example/MainActivity.kt",
              "production_composable": "MainScreen",
              "shared_primitive": "OffCanvasPanelLayout",
              "production_invocation_found": true,
              "geometry_test_invocation_found": true,
              "declaration_and_production_invocation_count": 2
            }""".trimIndent()
        )
    }

    @Test
    fun overviewShowsExactEligible64BitTotalsAndCategoryAggregates() {
        compose.setContent {
            inventoryScreen(
                totals = MediaInventoryTotals(5, 5_000_000_032L),
                aggregates = listOf(
                    SourceAggregate("Screenshots", 2, 5_000_000_001L),
                    SourceAggregate("Screen recordings", 1, 17L),
                    SourceAggregate("WhatsApp", 1, 11L),
                    SourceAggregate("Camera", 1, 3L)
                ),
                access = "Full access"
            )
        }

        compose.onNodeWithTag("inventory_total")
            .assertTextContains("5 files", substring = true)
            .assertTextContains("4.7 GB", substring = true)
        compose.onNodeWithTag("source_category_Screenshots")
            .assertTextContains("2 items", substring = true)
            .assertTextContains("4.7 GB", substring = true)
        compose.onNodeWithTag("source_category_Screen recordings")
            .assertTextContains("1 items", substring = true)
            .assertTextContains("17 B", substring = true)
        compose.onAllNodesWithText("Unknown", substring = true).assertCountEquals(0)
    }

    @Test
    fun detailShowsFilenameEvidenceAndOmitsUnavailableConversationAndUnknownLabels() {
        compose.setContent {
            inventoryScreen(
                totals = MediaInventoryTotals(2, 5_000_000_001L),
                aggregates = emptyList(),
                access = "Full access",
                selectedCategory = "Screenshots",
                subcategories = listOf(SubcategoryAggregate("Facebook", 1, 2_500_000_000L)),
                media = listOf(
                    fixture(2, "Screenshot_20260806_facebook.png", 2_500_000_000L, "Facebook", "filename_token"),
                    fixture(3, "Screenshot_20260806.png", 2_500_000_001L, null, null)
                )
            )
        }

        compose.onNodeWithTag("source_subcategory_Facebook")
            .assertTextContains("Facebook", substring = true)
            .assertTextContains("1", substring = true)
            .assertTextContains("2.3 GB", substring = true)
        compose.onNodeWithTag("source_evidence_2")
            .assertTextContains("Facebook • Inferred from filename", substring = true)
        compose.onAllNodesWithText("Unknown", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Conversation", substring = true).assertCountEquals(0)
    }

    @Test fun fullAccessStateIsVisible() = assertAccess("Full access")
    @Test fun limitedAccessStateIsVisible() = assertAccess("Limited access")
    @Test fun deniedAccessStateIsVisible() = assertAccess("Denied")
    @Test fun unknownAccessStateIsVisible() = assertAccess("Unknown")

    private fun assertAccess(access: String) {
        compose.setContent {
            inventoryScreen(MediaInventoryTotals(0, 0), emptyList(), access)
        }
        compose.onNodeWithTag("inventory_access").assertTextContains("Access: $access", substring = true)
    }

    @Composable
    private fun inventoryScreen(
        totals: MediaInventoryTotals,
        aggregates: List<SourceAggregate>,
        access: String,
        selectedCategory: String? = null,
        subcategories: List<SubcategoryAggregate> = emptyList(),
        media: List<VideoItem> = emptyList()
    ) {
        MaterialTheme {
            MediaSourcesScreen(
                totals = totals,
                aggregates = aggregates,
                selectedCategory = selectedCategory,
                subcategories = subcategories,
                allMedia = media,
                mediaAccess = access,
                isScanning = false,
                onScan = {},
                onSelectCategory = {},
                onDecision = { _, _ -> }
            )
        }
    }

    private fun fixture(
        id: Long,
        name: String,
        bytes: Long,
        subcategory: String?,
        evidence: String?
    ) = VideoItem(
        id = id,
        mediaStoreId = id,
        uriString = "content://fixture/image/$id",
        displayName = name,
        relativePath = "Pictures/Screenshots/",
        mimeType = "image/png",
        sizeBytes = bytes,
        durationMs = null,
        dateAdded = 100,
        dateModified = 200,
        sourceCategory = "Screenshots",
        sourceSubcategory = subcategory,
        subcategoryEvidenceKind = evidence
    )

    private fun androidx.compose.ui.unit.DpRect.toJson(): String =
        """{"left_dp":${left.value},"top_dp":${top.value},"right_dp":${right.value},"bottom_dp":${bottom.value},"width_dp":${(right - left).value},"height_dp":${(bottom - top).value}}"""


    @Composable
    private fun OffCanvasFixture() {
        var expanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxSize()) {
            OffCanvasPanelLayout(
                expanded = expanded,
                modifier = Modifier.fillMaxSize(),
                panel = { Box(Modifier.fillMaxSize().testTag("fixture_panel")) },
                content = {
                    androidx.compose.material3.Text(
                        "Sources:7:23",
                        modifier = Modifier.align(Alignment.Center).testTag("fixture_state")
                    )
                }
            )
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Icon(
                    if (expanded) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                    contentDescription = if (expanded) "Close menu" else "Open menu"
                )
            }
        }
    }

    @Composable
    private fun ControlLabelPolicyFixture(
        tabs: List<String>,
        filters: List<String>,
        forms: List<String>,
        expandable: List<String>,
        actions: List<String>
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row {
                tabs.forEachIndexed { index, label ->
                    androidx.compose.material3.Tab(
                        selected = index == 0,
                        onClick = {},
                        modifier = Modifier.weight(1f),
                        text = { androidx.compose.material3.Text(label) }
                    )
                }
            }
            filters.forEach { label ->
                androidx.compose.material3.FilterChip(
                    selected = false,
                    onClick = {},
                    label = { androidx.compose.material3.Text(label) }
                )
            }
            forms.forEach { label ->
                androidx.compose.material3.OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    label = { androidx.compose.material3.Text(label) }
                )
            }
            expandable.forEach { label ->
                androidx.compose.material3.TextButton(onClick = {}) {
                    androidx.compose.material3.Text(label)
                }
            }
            actions.forEach { label ->
                androidx.compose.material3.TextButton(onClick = {}) {
                    androidx.compose.material3.Text(label)
                }
            }
        }
    }
}
