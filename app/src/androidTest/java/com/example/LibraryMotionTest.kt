package com.example

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.VideoItem
import com.example.ui.theme.LibraryPresentation
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Consumer tests use the production component without accessing credentials or the media database. */
@RunWith(AndroidJUnit4::class)
class LibraryMotionTest {
    @get:Rule val compose = createAndroidComposeRule<InventoryRecreationTestActivity>()
    private val dark = mutableStateOf(false)
    private val largeText = mutableStateOf(false)
    private var destination = ""
    private fun render(motion: Boolean) {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        compose.activity.setContent {
            MyApplicationTheme(darkTheme = dark.value) {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, if (largeText.value) 2f else 1f)) {
                    Surface {
                        Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                            LibraryPresentation(listOf(item(1, "image/png", "processed"), item(2, "video/mp4", "new"),
                                item(3, "image/png", "new").copy(isCurrentlyAvailable = false)),
                                onSources = { destination = "sources" }, onVideos = { destination = "videos" },
                                onImages = { destination = "images" }, motionEnabled = motion)
                        }
                    }
                }
            }
        }
    }
    @Test fun realCountsAndAllShortcutsWorkInBothThemes() {
        render(false)
        for (theme in listOf(false, true)) {
            compose.runOnIdle { dark.value = theme }
            compose.onNodeWithTag("library_total").assertTextEquals("2")
            compose.onNodeWithContentDescription("1 of 2 available files saved").assertExists()
            compose.onNodeWithTag("library_images").performScrollTo().performClick()
            assertEquals("images", destination)
            compose.onNodeWithTag("library_videos").performScrollTo().performClick()
            assertEquals("videos", destination)
            compose.onNodeWithTag("library_review").performScrollTo().performClick()
            assertEquals("sources", destination)
        }
    }
    @Test fun entranceActuallyMovesThenSettlesWithoutAnIdleLoop() {
        compose.mainClock.autoAdvance = false
        try {
            render(true)
            compose.mainClock.advanceTimeBy(80)
            val early = compose.onNodeWithTag("media_sculpture").captureToImage().asAndroidBitmap()
            compose.mainClock.advanceTimeBy(400)
            val middle = compose.onNodeWithTag("media_sculpture").captureToImage().asAndroidBitmap()
            assertFalse("Media layers must visibly move", early.sameAs(middle))
            compose.mainClock.advanceTimeBy(1000)
            val settled = compose.onNodeWithTag("media_sculpture").captureToImage().asAndroidBitmap()
            compose.mainClock.advanceTimeBy(1200)
            val later = compose.onNodeWithTag("media_sculpture").captureToImage().asAndroidBitmap()
            assertTrue("Idle artwork must stop moving", settled.sameAs(later))
        } finally { compose.mainClock.autoAdvance = true }
    }
    @Test fun reducedMotionIsImmediatelyStableAndDoubleTextRemainsReachable() {
        compose.mainClock.autoAdvance = false
        try {
            render(false)
            compose.mainClock.advanceTimeByFrame()
            val first = compose.onNodeWithTag("media_sculpture").captureToImage().asAndroidBitmap()
            compose.mainClock.advanceTimeBy(600)
            assertTrue(first.sameAs(compose.onNodeWithTag("media_sculpture").captureToImage().asAndroidBitmap()))
        } finally { compose.mainClock.autoAdvance = true }
        for (theme in listOf(false, true)) {
            compose.runOnIdle { dark.value = theme; largeText.value = true }
            compose.onNodeWithTag("library_total").performScrollTo().assertIsDisplayed()
            val total = compose.onNodeWithTag("library_total").getUnclippedBoundsInRoot()
            val art = compose.onNodeWithTag("media_sculpture").getUnclippedBoundsInRoot()
            assertTrue("Large text and artwork must not overlap", art.top >= total.bottom)
            compose.onNodeWithTag("library_review").performScrollTo().assertIsDisplayed().performClick()
            assertEquals("sources", destination)
        }
    }
    private fun item(id: Long, type: String, status: String) = VideoItem(id = id, mediaStoreId = id,
        uriString = "content://presentation-fixture/$id", displayName = "fixture-$id", relativePath = "Pictures/",
        mimeType = type, sizeBytes = 100, durationMs = null, dateAdded = null, dateModified = null,
        status = status, reviewDecision = "keep", mediaCollection = if (type.startsWith("image")) "image" else "video")
}
