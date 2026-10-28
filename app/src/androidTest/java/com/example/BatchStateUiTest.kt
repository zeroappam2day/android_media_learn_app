package com.example

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.example.ui.VideoViewModel
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Empty input exercises the production finished state without processing existing media. */
class BatchStateUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun emulatorOnly() = PreviewRuntimeGuard.requireApprovedPreviewRuntime()
    @Test fun finishedBatchDoesNotKeepSpinning() {
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close menu").performClick()
        compose.runOnUiThread { ViewModelProvider(compose.activity)[VideoViewModel::class.java].processor.startBatch(emptyList()) }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Batch: FINISHED").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Batch: FINISHED").assertIsDisplayed()
        compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
    }
}
