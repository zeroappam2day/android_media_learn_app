package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import com.example.data.VideoDatabase
import com.example.data.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Only synthetic index records are created; no existing record or physical media is modified. */
@RunWith(AndroidJUnit4::class)
class ArchiveWiringTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val dao get() = VideoDatabase.getDatabase(InstrumentationRegistry.getInstrumentation().targetContext).videoItemDao()
    private val ids = mutableListOf<Long>()
    private val token = "qa_${System.nanoTime()}"
    private val imageA = "a_${token}.png"
    private val imageZ = "z_${token}.png"
    private val video = "v_${token}.mp4"
    private val queuedName = "000_${token}.png"
    private val folderName = "Queue_$token"

    @Before fun seedOnlyOwnedFixtures() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        query {
            listOf(Triple(imageA, "image/png", 900L), Triple(imageZ, "image/png", 10L), Triple(video, "video/mp4", 100L)).forEach { (name, mime, size) ->
                ids += dao.insertVideo(VideoItem(mediaStoreId = -System.nanoTime(), uriString = "content://qa/$name", displayName = name,
                    relativePath = "QA/", mimeType = mime, sizeBytes = size, durationMs = null, dateAdded = null, dateModified = null,
                    status = "archived", reviewDecision = "keep"))
            }
            ids += dao.insertVideo(VideoItem(mediaStoreId = -System.nanoTime(), uriString = "content://qa/$folderName/$queuedName", displayName = queuedName,
                relativePath = "$folderName/", mimeType = "image/png", sizeBytes = 10, durationMs = null, dateAdded = null, dateModified = null,
                status = "new", reviewDecision = "keep"))
        }
    }
    @After fun removeOnlyOwnedFixtures() { query { ids.forEach { id -> dao.getVideoByIdDirect(id)?.let { dao.deleteVideo(it) } } } }

    @Test fun archiveSortChangesVisibleOrder() {
        navigate("Images")
        compose.waitUntil(10000) { compose.onAllNodesWithText(imageA).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Sort by name").performClick()
        assertTrue(y(imageA) < y(imageZ))
        compose.onNodeWithContentDescription("Sort by size").performClick()
        assertTrue("Size sort must put the 10-byte item before the 900-byte item", y(imageZ) < y(imageA))
    }

    @Test fun restoreOnlyUsesSelectionsInCurrentArchive() {
        navigate("Videos")
        compose.waitUntil(10000) { compose.onAllNodesWithText(video).fetchSemanticsNodes().isNotEmpty() }
        selectRow(video)
        navigate("Images")
        selectRow(imageA)
        compose.onNodeWithContentDescription("Restore selected").performClick()
        compose.waitUntil(10000) { query { dao.getVideoByIdDirect(ids[0])?.status != "archived" } }
        assertEquals("A video selected on another screen must remain archived", "archived", query { dao.getVideoByIdDirect(ids[2])?.status })
        assertEquals("The unselected image must remain archived", "archived", query { dao.getVideoByIdDirect(ids[1])?.status })
    }

    @Test fun backlogSelectionShowsActionsAndCancelClearsThem() {
        navigate("Queue")
        compose.waitUntil(10000) { compose.onAllNodesWithText(queuedName).fetchSemanticsNodes().isNotEmpty() }
        selectRow(queuedName)
        compose.onNodeWithText("1 file selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel").performClick()
        compose.onNodeWithText("1 file selected").assertDoesNotExist()
    }

    @Test fun addingFolderTwiceKeepsItsItemQueued() {
        navigate("Folders")
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText(folderName).fetchSemanticsNodes().isNotEmpty() }
        selectRow(folderName)
        compose.onNodeWithContentDescription("Add selected to queue").performClick()
        compose.waitUntil(10000) { query { dao.getVideoByIdDirect(ids[3])?.status == "queued" } }
        compose.onNodeWithContentDescription("Add selected to queue").performClick()
        compose.waitForIdle()
        Thread.sleep(500)
        assertEquals("Adding the same folder twice must not undo queueing", "queued", query { dao.getVideoByIdDirect(ids[3])?.status })
        assertTrue("Archive entries must not be requeued", query { ids.take(3).all { dao.getVideoByIdDirect(it)?.status == "archived" } })
    }

    @Test fun queueActionIconsOpenTheirNamedWindow() {
        query { dao.getVideoByIdDirect(ids[3])?.let { dao.updateVideo(it.copy(status = "processed", reviewDecision = "unreviewed")) } }
        navigate("Queue")
        compose.waitUntil(10000) { compose.onAllNodesWithText(queuedName).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("queue_${ids[3]}_output").performQaQueueScrollTo(compose).performClick()
        compose.onNodeWithText("Processed Result Summary: $queuedName").assertIsDisplayed()
        onView(isRoot()).inRoot(isDialog()).perform(pressBack())
        compose.onNodeWithTag("queue_${ids[3]}_preview").performQaQueueScrollTo(compose).performClick()
        compose.onNodeWithText("Media Details Preview: $queuedName").assertIsDisplayed()
        onView(isRoot()).inRoot(isDialog()).perform(pressBack())
        compose.onNodeWithText("Media Details Preview: $queuedName").assertDoesNotExist()
    }

    @Test fun previewDraftSurvivesMinimizeWithoutSaving() {
        navigate("Queue")
        compose.waitUntil(10000) { compose.onAllNodesWithText(queuedName).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("queue_${ids[3]}_preview").performQaQueueScrollTo(compose).performClick()
        val notes = hasSetTextAction()
        compose.onAllNodes(notes)[0].performScrollTo().performTextReplacement("Unsaved QA draft")
        compose.onNodeWithContentDescription("Minimize").performClick()
        compose.onNodeWithContentDescription("Restore preview").performClick()
        compose.onAllNodes(notes)[0].performScrollTo().assertTextContains("Unsaved QA draft")
        assertNull("Minimizing must not silently save the draft", query { dao.getVideoByIdDirect(ids[3])?.manualTranscript })
        compose.onNodeWithContentDescription("Close").performClick()
    }

    private fun selectRow(name: String) {
        val center = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.center.y
        val nodes = compose.onAllNodes(isToggleable()).fetchSemanticsNodes()
        val index = nodes.indices.minBy { kotlin.math.abs(nodes[it].boundsInRoot.center.y - center) }
        compose.onAllNodes(isToggleable())[index].performClick()
    }

    private fun y(name: String) = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.top
    private fun navigate(label: String) {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("nav_${label.lowercase()}").performClick()
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close menu").performClick()
        compose.waitForIdle()
    }
    private fun <T> query(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}
