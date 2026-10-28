package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import com.example.ui.VideoViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.Call
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/** Production MainScreen on the debug host with isolated data and no real credential/network access. */
@RunWith(AndroidJUnit4::class)
class NavigationCompletionTest {
    @get:Rule val compose = createAndroidComposeRule<InventoryRecreationTestActivity>()
    private val context get() = compose.activity.applicationContext
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val token = "QANav" + UUID.randomUUID().toString().replace("-", "").take(10)
    private val files = mutableListOf<File>()
    private val ids = mutableSetOf<Long>()
    private val originalBytes = linkedMapOf<File, ByteArray>()
    private val store = ViewModelStore()
    private lateinit var repo: VideoRepository
    private lateinit var vm: VideoViewModel
    private var oldSettings: AppSettings? = null
    private var oldAuto = false
    private var oldProvider: String? = null
    private lateinit var credentialsName: String
    private val dark = mutableStateOf(false)
    private var themeToggles = 0
    private val source = Source()
    private val expectedLocations = CopyOnWriteArraySet<String>()
    private val observedLocations = CopyOnWriteArraySet<String>()

    @Before fun isolateEmptyQaDataAndRenderProductionScreen() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview"))
        check(InstrumentationRegistry.getArguments().getString("rea_runtime") == "emulator")
        val db = VideoDatabase.getDatabase(context)
        check(io { db.videoItemDao().getAllVideosDirect().isEmpty() }) { "Install empty isolated QA application before navigation suite" }
        check(io { db.customTagDao().observeTags().first().isEmpty() && db.mediaRuleDao().observeRules().first().isEmpty() && db.binDao().observeEntries().first().isEmpty() })
        credentialsName = "qa_navigation_$token"
        val blankCredentials = context.getSharedPreferences(credentialsName, 0)
        repo = VideoRepository(context, mediaInventorySource = source,
            analysisClient = Call.Factory { error("Navigation suite must never issue any provider/network request") },
            credentialPreferences = blankCredentials)
        oldSettings = io { repo.settingsDao.getSettingsDirect() }
        oldAuto = repo.prefs.getBoolean("auto_ingest_enabled", false)
        oldProvider = repo.prefs.getString("llm_provider", null)
        repo.prefs.edit().putBoolean("auto_ingest_enabled", false).putString("llm_provider", "local").commit()
        io { repo.settingsDao.insertSettings(AppSettings(ollamaEnabled = false, ollamaModelName = "qa-navigation-model")) }
        automation.setOnAccessibilityEventListener { event ->
            event.text.map { it.toString() }.filter { it in expectedLocations }.forEach { observedLocations += it }
        }
        compose.runOnUiThread {
            vm = VideoViewModel(context as Application, repo); store.put("qa-navigation", vm)
            compose.activity.setContent {
                MyApplicationTheme(darkTheme = dark.value, dynamicColor = false) {
                    MainScreen(vm, dark.value) { dark.value = !dark.value; themeToggles++ }
                }
            }
        }
        compose.waitForIdle(); closeMenu()
    }
    @After fun cleanupOnlyOwnedState() {
        automation.setOnAccessibilityEventListener(null)
        if (::vm.isInitialized) compose.runOnUiThread { store.clear() }
        if (::repo.isInitialized) {
            io {
                ids.forEach { id -> repo.analysisDao.deleteAnalysisForVideo(id); repo.frameDao.deleteFramesForVideo(id); repo.videoDao.getVideoByIdDirect(id)?.let { repo.videoDao.deleteVideo(it) } }
                if (oldSettings != null) repo.settingsDao.insertSettings(requireNotNull(oldSettings))
                else {
                    check(repo.settingsDao.getSettingsDirect()?.ollamaModelName == "qa-navigation-model")
                    VideoDatabase.getDatabase(context).openHelper.writableDatabase.execSQL("DELETE FROM app_settings WHERE id = 1")
                }
            }
            repo.prefs.edit().putBoolean("auto_ingest_enabled", oldAuto).apply {
                if (oldProvider == null) remove("llm_provider") else putString("llm_provider", oldProvider)
            }.commit()
        }
        files.forEach { it.delete() }
        if (::credentialsName.isInitialized) context.deleteSharedPreferences(credentialsName)
    }

    @Test fun t007_menuClosesWithoutChangingHomeOrSavedRows() {
        val fixture = image("unchanged"); val before = row(fixture.id)
        openMenu(); compose.onNodeWithContentDescription("Close menu").performClick()
        compose.onNodeWithText("Home", substring = false).assertIsDisplayed()
        compose.onNodeWithContentDescription("Open menu").assertIsDisplayed(); assertEquals(before, row(fixture.id))
    }
    @Test fun t010_t011_overviewAndGuideExpandAndCollapseWithoutMutation() {
        val fixture = image("untouched"); val before = row(fixture.id)
        compose.onNodeWithText("Overview").performClick(); count("New", "01")
        compose.onNodeWithText("Overview").performClick(); compose.onNodeWithText("New", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Guide").performClick()
        compose.onNodeWithText("Text is read on your device.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Guide").performClick(); compose.onNodeWithText("Text is read on your device.", substring = true).assertDoesNotExist()
        assertEquals(before, row(fixture.id))
    }
    @Test fun t012_overviewCountsAvailableStatesAndSidebarTotalsIndependently() {
        image("newA", status = "new"); image("newB", status = "new")
        image("queued", status = "queued"); image("working", status = "processing")
        image("done", status = "processed"); image("archived", status = "archived")
        image("remove", status = "ready_to_delete"); image("error", status = "error")
        image("unavailable", available = false); image("pending", pending = true); image("trashed", trashed = true)
        compose.onNodeWithText("Overview").performClick()
        listOf("New", "Queued", "Saved", "Review").forEach { count(it, "02") }
        compose.onNodeWithText("8 Media Found").assertIsDisplayed()
        compose.onNodeWithText("Media: Full access").assertIsDisplayed()
        compose.onNodeWithText("qa-navigation-model", substring = true).assertIsDisplayed()
        openMenu(); compose.onNodeWithText("• Available media: 8").assertIsDisplayed()
        compose.onNodeWithText("• Files in queue: 8").assertIsDisplayed()
        compose.onNodeWithText("• AI service: LOCAL").assertIsDisplayed()
        assertEquals(11, io { repo.videoDao.getAllVideosDirect().size })
    }
    @Test fun t018_dismissLimitedBannerDoesNotChangeAccessOrRows() {
        val item = image("limited"); source.access = "Limited access"; compose.runOnUiThread { vm.refreshMediaAccess() }
        compose.onNodeWithText("Limited Access Enabled").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss").performClick(); compose.onNodeWithText("Limited Access Enabled").assertDoesNotExist()
        assertEquals("Limited access", vm.mediaAccessState.value); assertEquals(item, row(item.id))
        // Injected access-state presentation only; real Android selected-media grant proof is separate.
    }
    @Test fun t021_t038_t050_t073_t155_t173_t178_emptyScreensAndToolBackAreHonest() {
        navigate("sources"); compose.onNodeWithTag("inventory_total").assertTextEquals("0 files • 0 B")
        compose.onNodeWithText("No photos or videos found").assertIsDisplayed()
        compose.onNodeWithTag("open_tags").performClick()
        compose.onNode(hasSetTextAction() and hasText("Tag")).assertIsDisplayed()
        button("Add").assertIsNotEnabled(); compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("open_bin").performClick(); compose.onNodeWithText("Bin empty").assertIsDisplayed()
        compose.onNodeWithTag("delete_bin").assertIsNotEnabled(); compose.onNodeWithContentDescription("Back").performClick()
        navigate("queue"); compose.onNodeWithText("Queue is empty. Find files or clear filters.").assertIsDisplayed()
        for (destination in listOf("videos", "images")) { navigate(destination); compose.onNodeWithText("No processed files found in Archive.").assertIsDisplayed() }
        navigate("folders"); openMenu(); compose.onNodeWithText("No folders scanned yet. Tap Scan below.").assertIsDisplayed()
    }
    @Test fun t024_t025_sourceCategoryAndSubcategoryFiltersShowOnlyEligiblePresetRows() {
        val fb = image("facebook", category = "Screenshots", subcategory = "Facebook")
        val ig = image("instagram", category = "Screenshots", subcategory = "Instagram")
        image("private", category = "Screenshots", available = false)
        val camera = image("camera", category = "Camera")
        navigate("sources"); compose.onNodeWithTag("inventory_total").assertTextEquals("3 files • 120 B")
        compose.onNodeWithTag("source_category_Screenshots").performClick()
        compose.onNodeWithTag("inventory_total").assertTextEquals("2 files • 80 B")
        compose.onNodeWithTag("source_media_${fb.id}").assertIsDisplayed(); compose.onNodeWithTag("source_media_${ig.id}").assertIsDisplayed()
        compose.onNodeWithTag("source_media_${camera.id}").assertDoesNotExist()
        compose.onNodeWithTag("source_evidence_${fb.id}").assertTextContains("Facebook • Inferred from filename")
        compose.onNodeWithTag("source_subcategory_Facebook").performClick(); compose.onNodeWithTag("source_media_${ig.id}").assertDoesNotExist()
        compose.onNodeWithTag("source_subcategory_Facebook").performClick(); compose.onNodeWithTag("source_media_${ig.id}").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show all sources").performClick(); compose.onNodeWithTag("source_category_Camera").performClick()
        compose.onNodeWithTag("source_media_${camera.id}").assertIsDisplayed(); assertBytes()
        // Source categories/evidence are deliberately seeded; no classifier accuracy claim.
    }
    @Test fun t026_t027_t028_t029_t031_reviewChoicesPersistAcrossNavigationAndKeepCountsDistinct() {
        val keep = image("keep", category = "Downloads", decision = "unreviewed")
        val later = image("later", category = "Downloads", decision = "unreviewed")
        val remove = image("remove", category = "Downloads", decision = "unreviewed")
        navigate("sources"); compose.onNodeWithTag("source_category_Downloads").performClick()
        decide(keep.id, "Keep", "keep"); decide(later.id, "Later", "review_later"); decide(remove.id, "Remove", "ready_to_delete")
        compose.onNodeWithText("0 unreviewed   •   1 kept   •   1 ready to delete", substring = true).assertIsDisplayed()
        navigate("home"); compose.onNodeWithText("Overview").performClick(); count("New", "03"); count("Review", "00")
        navigate("queue"); listOf(keep, later, remove).forEach { compose.onNodeWithText(it.displayName).assertIsDisplayed() }
        navigate("sources")
        // Sources retains its chosen category across navigation; tools belong to its overview.
        compose.onNodeWithContentDescription("Show all sources").performClick()
        compose.onNodeWithTag("open_bin").performClick(); compose.onNodeWithText("Bin empty").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick(); compose.onNodeWithTag("source_category_Downloads").performClick()
        listOf(keep.id to "Keep", later.id to "Later", remove.id to "Remove").forEach { (id, text) -> compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("review_decision_$id"))).assertIsDisplayed() }
        decide(remove.id, "New", "unreviewed"); assertBytes()
        // Persistence is navigation + Room, not force-stop/process-death proof.
    }
    @Test fun t030_t170_androidBackDismissesReviewMenuWithoutChangingNew() {
        val item = image("menu", category = "Downloads", decision = "unreviewed")
        navigate("sources"); compose.onNodeWithTag("source_category_Downloads").performClick(); openDecision(item.id)
        listOf("New", "Keep", "Later", "Remove").forEach { compose.onNode(hasText(it) and hasAnyAncestor(isPopup())).assertIsDisplayed() }
        keyBack()
        await { compose.onAllNodes(hasText("Keep") and hasAnyAncestor(isPopup())).fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasText("Keep") and hasAnyAncestor(isPopup())).assertDoesNotExist()
        assertEquals("unreviewed", row(item.id).reviewDecision)
    }
    @Test fun t037_folderSearchNoMatchAndClearRestoreExactGroups() {
        image("first", folder = "QA-$token/Alpha"); image("second", folder = "QA-$token/Beta")
        image("hidden", folder = "QA-$token/Hidden", available = false)
        navigate("folders"); openMenu()
        // Placeholder text disappears on focus/input. Identify the sole editable control
        // in this menu without tying its identity to its mutable displayed value.
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        val search = compose.onNode(hasSetTextAction())
        search.performTextReplacement("Alpha"); compose.onNodeWithText("QA-$token/Alpha").assertIsDisplayed(); compose.onNodeWithText("QA-$token/Beta").assertDoesNotExist()
        search.performTextReplacement("QA-no-match-987"); compose.onNodeWithText("No matching folders found.").assertIsDisplayed()
        button("Clear search").performClick(); compose.onNodeWithText("QA-$token/Alpha").assertIsDisplayed(); compose.onNodeWithText("QA-$token/Beta").assertIsDisplayed()
        compose.onNodeWithText("QA-$token/Hidden").assertDoesNotExist()
    }
    @Test fun t039_selectedFolderQueuesOnlyKeptAndNeverUnselectedRows() {
        val keep = image("keep", folder = "QA-$token/Chosen", decision = "keep")
        val later = image("later", folder = "QA-$token/Chosen", decision = "review_later")
        val unselected = image("other", folder = "QA-$token/Other", decision = "keep")
        navigate("folders"); openMenu(); compose.onNodeWithText("QA-$token/Chosen").performClick(); button("Add selected to queue").performClick()
        await { row(keep.id).status == "queued" }; assertEquals("new", row(later.id).status); assertEquals("new", row(unselected.id).status)
        closeMenu(); navigate("queue"); compose.onNodeWithText(keep.displayName).assertIsDisplayed(); assertBytes()
        // Toast count and analysis exclusion remain outside this queue-state oracle.
    }
    @Test fun t046_t048_queueSortAndSelectAllThenDeselectPreserveSavedRows() {
        val z = image("z", status = "new", bytes = 900); val a = image("a", status = "queued", bytes = 20); val m = image("m", status = "error", bytes = 100)
        val before = listOf(z, a, m).associateBy { it.id }; navigate("queue")
        sort("Name"); order(a, m, z); sort("Size"); order(a, m, z); sort("Status"); order(m, z, a)
        val header = compose.onNodeWithTag("queue_select_all")
        header.performClick(); compose.onNodeWithText("3 files selected").assertIsDisplayed()
        compose.onAllNodes(isToggleable()).assertCountEquals(4); compose.onAllNodes(isToggleable()).fetchSemanticsNodes().forEachIndexed { index, _ -> compose.onAllNodes(isToggleable())[index].assertIsOn() }
        header.performClick(); compose.onNodeWithText("3 files selected").assertDoesNotExist()
        compose.onAllNodes(isToggleable()).fetchSemanticsNodes().forEachIndexed { index, _ -> compose.onAllNodes(isToggleable())[index].assertIsOff() }
        assertEquals(before, listOf(z, a, m).associate { it.id to row(it.id) }); assertBytes()
    }
    @Test fun t051_t052_unfinishedOutputDisabledAndLocationIdentifiesExactOwnedFile() {
        val item = image("unfinished"); navigate("queue")
        aligned(item, "Output").assertIsNotEnabled(); aligned(item, "Folder").performClick()
        await { "Location: ${item.uriString}" in observedLocations }
        aligned(item, "Preview").performClick(); compose.onNodeWithText("Status: NEW", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick(); assertEquals("new", row(item.id).status)
        assertNull(io { repo.analysisDao.getAnalysisForVideoDirect(item.id) }); assertBytes()
    }
    @Test fun t070_archiveDeselectLeavesRestoreDisabledAndMediaUnchanged() {
        val item = image("archived", status = "archived")
        // Stored video MIME enables the Videos archive; the file is not processed/decoded here.
        io { repo.videoDao.updateVideo(item.copy(mimeType = "video/mp4", mediaCollection = "video")) }
        navigate("videos"); val checkbox = compose.onNode(isToggleable() and hasAnySibling(hasText(item.displayName)))
        checkbox.performClick(); button("Restore selected").assertIsEnabled(); checkbox.performClick(); button("Restore selected").assertIsNotEnabled()
        button("Restore selected").performClick(); assertEquals("archived", row(item.id).status); assertBytes()
    }
    @Test fun t169_rulesTagsAndBinAndroidBackReturnToSourcesWithoutMutation() {
        val fixture = image("untouched"); val before = row(fixture.id); navigate("sources")
        for (tool in listOf("rules", "tags", "bin")) {
            compose.onNodeWithTag("open_$tool").performClick(); compose.onNodeWithTag("${tool}_screen").assertIsDisplayed(); keyBack()
            await { compose.onAllNodesWithTag("open_$tool").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("media_sources_screen").assertIsDisplayed(); compose.onNodeWithTag("open_$tool").assertIsDisplayed()
        }
        assertEquals(before, row(fixture.id))
    }
    @Test fun t008_bothThemesKeepOrdinaryNavigationAndControlsOperableSemantically() {
        image("theme"); repeat(2) {
            button("Toggle Theme").performClick(); compose.waitForIdle()
            for (destination in listOf("home", "folders", "sources", "queue", "videos", "images")) {
                navigate(destination); compose.onNodeWithTag("offcanvas_content").assertIsDisplayed(); button("Toggle Theme").assertIsDisplayed()
            }
        }
        assertEquals(2, themeToggles); assertFalse(dark.value)
        // Semantics prove operability only. No screenshot contrast, visual legibility or theme persistence claim.
    }

    private fun image(suffix: String, status: String = "new", category: String = "Downloads", subcategory: String? = null,
        decision: String = "keep", folder: String = "QA-$token/Files", bytes: Long = 40, available: Boolean = true, pending: Boolean = false, trashed: Boolean = false): VideoItem {
        val file = File(context.cacheDir, "$token-$suffix.png").also { files += it }
        Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE); file.outputStream().use { check(compress(Bitmap.CompressFormat.PNG, 100, it)) }; recycle() }
        originalBytes[file] = file.readBytes()
        val id = io { repo.videoDao.insertVideo(VideoItem(mediaStoreId = -System.nanoTime(), uriString = Uri.fromFile(file).toString(), displayName = file.name,
            relativePath = "$folder/", mimeType = "image/png", sizeBytes = bytes, durationMs = null, dateAdded = null, dateModified = null,
            status = status, reviewDecision = decision, mediaCollection = "image", sourceCategory = category, sourceSubcategory = subcategory,
            subcategoryEvidenceKind = if (subcategory == null) null else "filename_token", isCurrentlyAvailable = available, isPending = pending, isTrashed = trashed)) }
        ids += id; expectedLocations += "Location: ${Uri.fromFile(file)}"; await { vm.allVideos.value.any { it.id == id } }
        return row(id)
    }
    private fun openDecision(id: Long) { compose.onNodeWithTag("review_decision_$id").onChildren().filter(hasClickAction()).onFirst().performClick() }
    private fun decide(id: Long, text: String, value: String) { openDecision(id); compose.onNode(hasText(text) and hasAnyAncestor(isPopup())).performClick(); await { row(id).reviewDecision == value } }
    private fun row(id: Long) = io { requireNotNull(repo.videoDao.getVideoByIdDirect(id)) }
    private fun assertBytes() { originalBytes.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) } }
    private fun count(label: String, value: String) { compose.onNode(hasText(label, substring = false) and hasAnySibling(hasText(value, substring = false))).assertIsDisplayed() }
    private fun button(label: String) = compose.onNode(hasClickAction() and hasContentDescription(label))
    private fun openMenu() { if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Open menu").performClick() }
    private fun closeMenu() { if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close menu").performClick() }
    private fun navigate(destination: String) { openMenu(); compose.onNodeWithTag("nav_$destination").performClick(); closeMenu() }
    private fun sort(label: String) { compose.onNodeWithContentDescription("Sort by ${label.lowercase()}").performClick(); compose.waitForIdle() }
    private fun order(vararg items: VideoItem) { val positions = items.map { compose.onNodeWithText(it.displayName).fetchSemanticsNode().boundsInRoot.top }; assertEquals(positions.sorted(), positions) }
    private fun aligned(item: VideoItem, label: String): SemanticsNodeInteraction {
        check(item.id in ids && row(item.id).uriString == item.uriString && row(item.id).displayName == item.displayName)
        compose.onNodeWithText(item.displayName).performQaQueueScrollTo(compose).assertIsDisplayed()
        val queueTag = "queue_${item.id}_${label.lowercase()}"
        if (compose.onAllNodesWithTag(queueTag).fetchSemanticsNodes().size == 1) {
            return compose.onNodeWithTag(queueTag).performQaQueueScrollTo(compose).assertIsDisplayed()
        }
        // Filename is in a two-line Column; actions align with the enclosing row, not
        // necessarily with the first filename line's vertical interval.
        val y = compose.onNodeWithText(item.displayName).performScrollTo().fetchSemanticsNode().boundsInRoot.center.y
        val actions = compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
        check(actions.isNotEmpty()) { "No observed $label action for the owned row" }
        val nearest = actions.indices.minBy { kotlin.math.abs(actions[it].boundsInRoot.center.y - y) }
        val distance = kotlin.math.abs(actions[nearest].boundsInRoot.center.y - y)
        check(distance < 70f) { "The $label action is not proven to belong to the owned row" }
        check(actions.indices.none { it != nearest && kotlin.math.abs(actions[it].boundsInRoot.center.y - y) <= distance }) {
            "Ambiguous $label action; refusing to target another row"
        }
        return compose.onAllNodesWithContentDescription(label)[nearest]
    }
    private fun keyBack() {
        automation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        // Callers wait for the exact observed popup/tool transition; shell acceptance alone
        // is not a navigation success oracle.
    }
    private fun await(predicate: () -> Boolean) = compose.waitUntil(10_000, predicate)
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private class Source : MediaInventorySource {
        var access = "Full access"
        override fun currentAccessState() = access
        override suspend fun readSnapshot() = MediaInventorySnapshot(access, emptyList(), true)
    }
}
