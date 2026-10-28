package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the shared production modal without changing stored settings or media. */
@RunWith(AndroidJUnit4::class)
class ModalInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun emulatorOnly() = PreviewRuntimeGuard.requireApprovedPreviewRuntime()

    @Test fun androidBackClosesSettingsAndKeepsActivityVisible() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("AI setup").assertIsDisplayed()
        onView(isRoot()).inRoot(isDialog()).perform(pressBack())
        compose.waitForIdle()
        compose.onNodeWithText("AI setup").assertDoesNotExist()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed().performClick()
        compose.onNodeWithText("AI setup").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()
    }

    @Test fun maximizeMinimizeRestoreAndCloseRemainOperable() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithContentDescription("Full Window").performClick()
        compose.onNodeWithContentDescription("Standard Window").performClick()
        compose.onNodeWithContentDescription("Minimize").performClick()
        compose.onNodeWithText("AI setup").assertDoesNotExist()
        compose.onNodeWithContentDescription("Restore settings").performClick()
        compose.onNodeWithText("AI setup").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("AI setup").assertDoesNotExist()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test fun settingsDraftSurvivesMinimizeButCloseDiscardsIt() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNode(hasSetTextAction() and hasText("Host")).performTextReplacement("http://qa.invalid")
        compose.onNodeWithContentDescription("Minimize").performClick()
        compose.onNodeWithContentDescription("Restore settings").performClick()
        compose.onNode(hasSetTextAction() and hasText("Host")).assertTextContains("http://qa.invalid")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("http://qa.invalid").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close").performClick()
    }
}
