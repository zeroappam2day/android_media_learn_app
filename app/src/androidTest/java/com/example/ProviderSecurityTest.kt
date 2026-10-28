package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AnalysisProviderProtocol
import com.example.data.AppSettings
import com.example.ui.VideoViewModel
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Uses only a hardcoded fake credential; never reads the user's credential. */
class ProviderSecurityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun emulatorOnly() = PreviewRuntimeGuard.requireApprovedPreviewRuntime()

    @Test fun cloudCredentialCannotBeSentToAnUnsafeAddressOrRemovedProvider() {
        for (url in listOf("http://ollama.com", "https://example.test", "https://ollama.com.example.test", "https://user@ollama.com",
            "https://ollama.com:444", "https://ollama.com?key=anything", "https://ollama.com#anything")) {
            val rejected = runCatching { AnalysisProviderProtocol.buildRequest("ollama_cloud", AppSettings(ollamaEndpointUrl = url), "fixture-only", 0f, "", "QA text") }.isFailure
            assertTrue("Unsafe Cloud address must be rejected", rejected)
        }
        val retiredRejected = runCatching { AnalysisProviderProtocol.buildRequest("gemini", AppSettings(), "fixture-only", 0f, "", "QA text") }.isFailure
        assertTrue("Removed provider must not build a request", retiredRejected)
        val request = AnalysisProviderProtocol.buildRequest("ollama_cloud", AppSettings(ollamaEndpointUrl = "https://ollama.com"), "fixture-only", 0f, "", "QA text")
        assertEquals("https://ollama.com/api/generate", request.url.toString())
        assertEquals("Bearer fixture-only", request.header("Authorization"))
        assertFalse(request.url.toString().contains("fixture-only"))
    }

    @Test fun publicUiStateDoesNotExposeTheSavedKey() {
        assertFalse(VideoViewModel::class.java.methods.any { it.name == "getApiKey" })
        assertTrue(VideoViewModel::class.java.methods.any { it.name == "getHasApiKey" })
    }

    @Test fun providerMenuContainsOnlyPcCloudAndDevice() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("app_prefs", 0)
        val label = when (prefs.getString("llm_provider", "ollama_local")) { "ollama_cloud" -> "Cloud"; "local" -> "Device"; else -> "PC" }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText(label, substring = false).performClick()
        compose.onNodeWithText("Gemini").assertDoesNotExist()
        compose.onAllNodesWithText("PC").onLast().assertIsDisplayed()
        compose.onNodeWithText("Device").assertIsDisplayed()
        compose.onAllNodesWithText("Cloud").onLast().performClick()
        compose.onNode(hasSetTextAction() and hasText("Host")).assertTextContains("https://ollama.com")
        compose.onNodeWithContentDescription("Close").performClick()
    }
}
