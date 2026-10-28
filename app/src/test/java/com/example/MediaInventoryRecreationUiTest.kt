package com.example

import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class MediaInventoryRecreationUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<InventoryRecreationTestActivity>()

    @After
    fun cleanUp() {
        compose.activityRule.scenario.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(InventoryRecreationTestActivity.DATABASE_NAME)
        InventoryRecreationTestActivity.resetDatabaseOnNextCreate = true
    }

    @Test
    fun persistedDecisionAndExactScreenTotalsSurviveActivityRecreation() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag("inventory_total")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("inventory_total")
            .assertTextContains("2 files", substring = true)
            .assertTextContains("21 B", substring = true)

        compose.activityRule.scenario.recreate()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag("inventory_total")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("inventory_total")
            .assertTextContains("2 files", substring = true)
            .assertTextContains("21 B", substring = true)
        runBlocking {
            val rows = compose.activity.databaseRowsForTest()
            assertEquals("keep", rows.single { it.id == 1L }.reviewDecision)
        }
    }
}
