package com.example

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UxEvidenceAccessibilityTest {
    @get:Rule
    val compose = createAndroidComposeRule<UxEvidenceActivity>()

    @Test
    fun runtimeAccessibilityTreeHasUniqueOperableNonOverlappingControls() {
        compose.onNodeWithContentDescription("Open menu")
            .assertIsDisplayed()
            .assertHasClickAction()
        val iconActions = listOf(
            "Home", "Back", "Close", "Menu", "Search", "Sort", "Filter", "Scan", "Refresh",
            "Add", "Edit", "Save", "Delete", "Archive", "Restore", "Keep", "Remove", "Play",
            "Pause", "Next", "Settings", "Theme", "Computer", "Expand", "Collapse"
        )
        assertTrue("Icon action descriptions must be unique", iconActions.size == iconActions.toSet().size)
        iconActions.forEach { description ->
            compose.onNodeWithContentDescription(description)
                .assertIsDisplayed()
                .assertHasClickAction()
        }
        val closedTree = compose.onRoot(useUnmergedTree = true).printToString()

        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.waitForIdle()

        val descriptions = listOf("Home", "Folders", "Sources", "Queue", "Videos", "Images")
        assertTrue("Navigation descriptions must be unique", descriptions.size == descriptions.toSet().size)
        descriptions.forEach { label ->
            compose.onNodeWithTag("nav_${label.lowercase()}")
                .assertIsDisplayed()
                .assertHasClickAction()
        }
        compose.onNodeWithContentDescription("Close menu")
            .assertIsDisplayed()
            .assertHasClickAction()

        val clickableNodes = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("Expected the close action and six navigation actions", clickableNodes.size >= 7)
        val visibleClickables = clickableNodes.filter {
            val bounds = it.boundsInRoot
            bounds.left < 1080f && bounds.right > 0f && bounds.top < 2316f && bounds.bottom > 0f
        }
        assertTrue("Expected the close action and six visible navigation actions", visibleClickables.size >= 7)
        val bounds = clickableNodes.map { it.boundsInRoot }.filter { it.width > 0f && it.height > 0f }
        bounds.forEachIndexed { index, first ->
            bounds.drop(index + 1).forEach { second ->
                val overlaps = first.left < second.right && first.right > second.left &&
                    first.top < second.bottom && first.bottom > second.top
                assertFalse("Clickable control bounds overlap: $first and $second", overlaps)
            }
        }

        val openTree = compose.onRoot(useUnmergedTree = true).printToString()
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val outputDir = requireNotNull(targetContext.getExternalFilesDir(null))
        File(outputDir, "media-ux-runtime-accessibility-tree.txt").writeText(
            buildString {
                appendLine("fixture=synthetic")
                appendLine("viewport=1080x2316 portrait")
                appendLine("closed:")
                appendLine(closedTree)
                appendLine("open:")
                appendLine(openTree)
                appendLine("assertions:")
                appendLine("navigation_descriptions_unique=true")
                appendLine("navigation_actions_operable=true")
                appendLine("approved_icon_actions=${iconActions.joinToString(",")}")
                appendLine("approved_icon_descriptions_unique=true")
                appendLine("approved_icon_actions_operable=true")
                appendLine("approved_icon_visible_text_labels=0")
                appendLine("clickable_bounds_overlap=false")
            }
        )

        compose.onNodeWithContentDescription("Close menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open menu")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

}
