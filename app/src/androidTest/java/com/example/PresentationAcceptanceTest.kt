package com.example

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
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
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import com.example.ui.VideoViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** End-user acceptance for the requested presentation, with owned files and no provider access. */
@RunWith(AndroidJUnit4::class)
class PresentationAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<InventoryRecreationTestActivity>()
    private val context get() = compose.activity.applicationContext
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val dark = mutableStateOf(false)
    private val scale = mutableStateOf(1f)
    private val width = mutableStateOf(400)
    private val store = ViewModelStore()
    private lateinit var repo: VideoRepository
    private lateinit var vm: VideoViewModel
    private var settingsBefore: AppSettings? = null
    private var autoBefore = false
    private var providerBefore: String? = null
    private val token = "presentation-${System.nanoTime()}"
    private val owned = linkedMapOf<Long, Pair<File, ByteArray>>()
    private var contrasts = emptyList<Pair<Int, Int>>()
    private val longName = "Holiday photos and family memories from the weekend trip with a deliberately long readable file name.png"

    @Before fun renderProductionUiWithoutNetwork() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview"))
        repo = VideoRepository(context,
            mediaInventorySource = object : MediaInventorySource {
                override fun currentAccessState() = "Full access"
                override suspend fun readSnapshot() = MediaInventorySnapshot("Full access", emptyList(), true)
            },
            analysisClient = Call.Factory { error("Presentation acceptance must never contact an AI provider") },
            credentialPreferences = context.getSharedPreferences(token, 0))
        check(io { repo.videoDao.getAllVideosDirect().isEmpty() }) { "Empty isolated QA database required" }
        settingsBefore = io { repo.settingsDao.getSettingsDirect() }
        autoBefore = repo.prefs.getBoolean("auto_ingest_enabled", false)
        providerBefore = repo.prefs.getString("llm_provider", null)
        repo.prefs.edit().putBoolean("auto_ingest_enabled", false).putString("llm_provider", "local").commit()
        io { repo.settingsDao.insertSettings(AppSettings(ollamaEnabled = false, ollamaModelName = "presentation-test")) }
        compose.runOnUiThread {
            vm = VideoViewModel(context as Application, repo)
            store.put(token, vm)
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                    MyApplicationTheme(darkTheme = dark.value, dynamicColor = false) {
                        val c = MaterialTheme.colorScheme
                        SideEffect {
                            contrasts = listOf(c.onSurface.toArgb() to c.surface.toArgb(),
                                c.onBackground.toArgb() to c.background.toArgb(),
                                c.onSurfaceVariant.toArgb() to c.surfaceVariant.toArgb(),
                                c.onPrimary.toArgb() to c.primary.toArgb(),
                                c.onPrimaryContainer.toArgb() to c.primaryContainer.toArgb(),
                                c.onTertiary.toArgb() to c.tertiary.toArgb(),
                                c.onTertiaryContainer.toArgb() to c.tertiaryContainer.toArgb(),
                                c.onErrorContainer.toArgb() to c.errorContainer.toArgb())
                        }
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            Box(Modifier.width(minOf(maxWidth, width.value.dp)).fillMaxHeight().testTag("presentation_viewport")) {
                                MainScreen(vm, dark.value) { dark.value = !dark.value }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        closeMenu()
    }

    @After fun preserveEverythingExceptOwnedFixtures() {
        if (::vm.isInitialized) compose.runOnUiThread { store.clear() }
        if (::repo.isInitialized) {
            io {
                owned.keys.forEach { id ->
                    repo.analysisDao.deleteAnalysisForVideo(id)
                    repo.frameDao.deleteFramesForVideo(id)
                    repo.videoDao.getVideoByIdDirect(id)?.let { repo.videoDao.deleteVideo(it) }
                }
                if (settingsBefore != null) repo.settingsDao.insertSettings(requireNotNull(settingsBefore))
                else if (repo.settingsDao.getSettingsDirect()?.ollamaModelName == "presentation-test") {
                    VideoDatabase.getDatabase(context).openHelper.writableDatabase.execSQL("DELETE FROM app_settings WHERE id = 1")
                }
            }
            repo.prefs.edit().putBoolean("auto_ingest_enabled", autoBefore).apply {
                if (providerBefore == null) remove("llm_provider") else putString("llm_provider", providerBefore)
            }.commit()
        }
        owned.values.forEach { it.first.delete() }
        context.deleteSharedPreferences(token)
    }

    @Test fun queueFitsNarrowScreensAndLargeFontsWithoutLosingFieldsOrActions() {
        val item = seed(longName)
        navigate("queue")
        for (theme in listOf(false, true)) for (font in listOf(1f, 1.5f, 2f)) {
            compose.runOnUiThread { dark.value = theme; scale.value = font; width.value = 320 }
            compose.waitForIdle()
            val viewport = compose.onNodeWithTag("queue_table_viewport").fetchSemanticsNode().boundsInRoot
            val content = compose.onNodeWithTag("queue_table_content").fetchSemanticsNode().boundsInRoot
            assertEquals("Queue must not exceed its viewport", viewport.width, content.width, 1f)
            compose.onNodeWithText(longName).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(longName).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("A long file name must wrap", layouts.single().lineCount > 1)
            assertFalse("A long file name must remain readable", layouts.single().hasVisualOverflow)
            capture("queue-before-actions-${if (theme) "dark" else "light"}-$font")
            for (action in listOf("rename", "folder", "output", "preview", "process")) {
                val node = compose.onNodeWithTag("queue_${item.id}_$action")
                node.performScrollTo().assertIsDisplayed()
                val bounds = node.fetchSemanticsNode().boundsInRoot
                assertTrue("$action escaped the left edge", bounds.left >= viewport.left - 1f)
                assertTrue("$action escaped the right edge", bounds.right <= viewport.right + 1f)
            }
            compose.onNodeWithTag("queue_${item.id}_output").assertIsNotEnabled()
            capture("queue-${if (theme) "dark" else "light"}-$font")
        }
        assertOwnedFilesUnchanged()
    }

    @Test fun revisedCopyAndOverviewCountsAreExplicitInBothThemes() {
        seed("one.png"); seed("two.png", "queued"); seed("three.png", "processed")
        for (theme in listOf(false, true)) {
            compose.runOnUiThread { dark.value = theme }
            compose.onNodeWithText("Overview").performClick()
            for (label in listOf("New", "Queued", "Saved", "Review")) compose.onNodeWithText(label).assertIsDisplayed()
            compose.onNodeWithText("Overview").performClick()
            compose.onNodeWithText("Guide").performClick()
            compose.onNodeWithText("Text is read on your device.", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Guide").performClick()
            openMenu()
            compose.onNodeWithText("• Files in queue: 3").assertIsDisplayed()
            compose.onNodeWithText("• AI service: LOCAL").assertIsDisplayed()
            closeMenu()
            assertContrast()
        }
        assertOwnedFilesUnchanged()
    }

    @Test fun longPressShowsHelpWithoutActivatingExport() {
        val action = compose.onNodeWithContentDescription("Save file details")
        compose.mainClock.autoAdvance = false
        try {
            action.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(800)
            compose.onNodeWithText("Save file details").assertIsDisplayed()
            capture("long-press-help")
        } finally {
            action.performTouchInput { up() }
            compose.mainClock.autoAdvance = true
        }
        assertEquals(context.packageName, instrumentation.uiAutomation.rootInActiveWindow.packageName.toString())
    }

    @Test fun emptyQueueAndEveryDestinationRemainReachable() {
        for (theme in listOf(false, true)) {
            compose.runOnUiThread { dark.value = theme }
            for (destination in listOf("home", "folders", "sources", "queue", "videos", "images")) {
                navigate(destination)
                compose.onNodeWithTag("offcanvas_content").assertIsDisplayed()
                compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
                if (destination == "queue") compose.onNodeWithText("Queue is empty. Find files or clear filters.").assertIsDisplayed()
                capture("${if (theme) "dark" else "light"}-$destination")
            }
            assertContrast()
        }
    }

    @Test fun queuePreviewPreservesFileAndSavedReviewChoice() {
        val item = seed("preview.png")
        navigate("queue")
        compose.onNodeWithTag("queue_${item.id}_preview").performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        assertEquals("keep", io { repo.videoDao.getVideoByIdDirect(item.id)!!.reviewDecision })
        assertOwnedFilesUnchanged()
    }

    @Test fun bulkSelectionAndCancelLeaveFilesAndRowsUnchanged() {
        val first = seed("first.png")
        val second = seed("second.png")
        navigate("queue")
        compose.onNodeWithTag("queue_select_all").performClick()
        compose.onNodeWithText("2 files selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel").performClick()
        compose.onNodeWithText("2 files selected").assertDoesNotExist()
        for (item in listOf(first, second)) assertEquals(item, io { repo.videoDao.getVideoByIdDirect(item.id) })
        assertOwnedFilesUnchanged()
    }

    @Test fun iconSortControlsKeepTheExistingSortBehavior() {
        seed("z-last.png", "new")
        seed("a-first.png", "error")
        navigate("queue")
        compose.onNodeWithContentDescription("Sort by name").performClick()
        assertEquals("Name", vm.selectedSort.value)
        val first = compose.onNodeWithText("a-first.png").fetchSemanticsNode().boundsInRoot.top
        val last = compose.onNodeWithText("z-last.png").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Name sorting must still order file rows", first < last)
        compose.onNodeWithContentDescription("Sort by status").performClick()
        assertEquals("Status", vm.selectedSort.value)
        compose.onNodeWithContentDescription("Sort by size").performClick()
        assertEquals("Size", vm.selectedSort.value)
        assertOwnedFilesUnchanged()
    }

    @Test fun pressFeedbackAnimatesAndReturnsWithoutTriggeringTheAction() {
        val action = compose.onNodeWithContentDescription("Save file details")
        val before = action.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            action.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(160)
            val pressed = action.fetchSemanticsNode().boundsInRoot
            assertTrue("A held action should give visible press feedback", pressed.width < before.width * 0.98f)
            action.performTouchInput { cancel() }
            compose.mainClock.advanceTimeBy(700)
            val released = action.fetchSemanticsNode().boundsInRoot
            assertEquals("Releasing must restore the visual size", before.width, released.width, 1f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        assertEquals(context.packageName, instrumentation.uiAutomation.rootInActiveWindow.packageName.toString())
    }

    @Test fun doubleSizeTextKeepsEveryDestinationAndEmptyMessageReadable() {
        compose.runOnUiThread { width.value = 320; scale.value = 2f }
        for (theme in listOf(false, true)) {
            compose.runOnUiThread { dark.value = theme }
            for (destination in listOf("home", "folders", "sources", "queue", "videos", "images")) {
                navigate(destination)
                compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
                capture("large-text-${if (theme) "dark" else "light"}-$destination")
                when (destination) {
                    "home" -> compose.onNodeWithText("Guide").assertIsDisplayed()
                    "folders" -> compose.onNodeWithText("Folders to scan").assertIsDisplayed()
                    "sources" -> {
                        compose.onNodeWithText("No photos or videos found").performScrollTo().assertIsDisplayed()
                        compose.onNodeWithText("Find files, then allow photo and video access.").performScrollTo().assertIsDisplayed()
                        val layouts = mutableListOf<TextLayoutResult>()
                        compose.onNodeWithText("Find files, then allow photo and video access.")
                            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                        assertFalse("The empty-state instruction must not be clipped", layouts.single().hasVisualOverflow)
                    }
                    "queue" -> compose.onNodeWithText("Queue is empty. Find files or clear filters.").assertIsDisplayed()
                    else -> compose.onNodeWithText("No processed files found in Archive.").assertIsDisplayed()
                }
            }
        }
    }

    private fun assertContrast() = contrasts.forEach { (foreground, background) ->
        assertTrue("Text contrast must meet 4.5:1", ColorUtils.calculateContrast(foreground, background) >= 4.5)
    }
    private fun assertOwnedFilesUnchanged() = owned.values.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
    private fun seed(name: String, status: String = "new"): VideoItem {
        val file = File(context.cacheDir, "$token-${owned.size}.png")
        Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(245, 111, 29))
            file.outputStream().use { check(compress(Bitmap.CompressFormat.PNG, 100, it)) }
            recycle()
        }
        val id = io { repo.videoDao.insertVideo(VideoItem(mediaStoreId = -System.nanoTime(), uriString = Uri.fromFile(file).toString(),
            displayName = name, relativePath = "Pictures/Family/", mimeType = "image/png", sizeBytes = file.length(), durationMs = null,
            dateAdded = null, dateModified = null, status = status, reviewDecision = "keep", mediaCollection = "image",
            sourceCategory = "Downloads", isCurrentlyAvailable = true)) }
        owned[id] = file to file.readBytes()
        compose.waitUntil(10_000) { vm.allVideos.value.any { it.id == id } }
        return io { repo.videoDao.getVideoByIdDirect(id)!! }
    }
    private fun openMenu() {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Open menu").performClick()
    }
    private fun closeMenu() {
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close menu").performClick()
    }
    private fun navigate(destination: String) { openMenu(); compose.onNodeWithTag("nav_$destination").performClick(); closeMenu() }
    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(context.getExternalFilesDir(null), "presentation-acceptance").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}
