package com.example

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Draft, not executed. Uses only owned synthetic MediaStore fixtures and index rows.
 * Requires an EMPTY dedicated QA database: global rule actions and scans must never
 * modify preexisting records. Existing physical media is excluded by owned tree scopes.
 * Parent adds switch tags auto_add/auto_record/auto_shots and automatic channel filtering.
 * No inference, Settings, credentials, shell UI dumps, permanent deletion, or permission grants.
 * Video fixtures are opaque inventory-only bytes, never passed to processing.
 */
@RunWith(AndroidJUnit4::class)
class DiscoveryCleanupCompletionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = VideoDatabase.getDatabase(context)
    private val dao get() = db.videoItemDao()
    private val prefs get() = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private val token = "DiscoveryQA${System.nanoTime()}"
    private val imagesRoot = "Pictures/$token/"
    private val videoRoot = "Movies/$token/"
    private val owned = linkedMapOf<Uri, ByteArray>()
    private val rules = mutableListOf<Long>()
    private var oldFolders: Set<String>? = null
    private val oldFlags = linkedMapOf<String, Boolean?>()
    private var entered = false
    private var hadObscuredTag = true

    @Before fun isolateOwnedScope() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview")) { "Dedicated QA preview required" }
        check(InstrumentationRegistry.getArguments().getString("rea_runtime") == "emulator")
        check(io { dao.getAllVideosDirect().isEmpty() }) { "Global scans/rules require an empty dedicated QA database; existing records preserved" }
        oldFolders = if (prefs.contains("target_folders")) prefs.getStringSet("target_folders", emptySet())!!.toSet() else null
        listOf("auto_ingest_enabled", "auto_ingest_screenshots", "auto_ingest_recordings").forEach { key ->
            oldFlags[key] = if (prefs.contains(key)) prefs.getBoolean(key, false) else null
        }
        hadObscuredTag = io { db.customTagDao().byName("Obscured") != null }
        entered = true
        prefs.edit().putStringSet("target_folders", setOf(tree(imagesRoot), tree(videoRoot)))
            .putBoolean("auto_ingest_enabled", false).putBoolean("auto_ingest_screenshots", true).putBoolean("auto_ingest_recordings", true).commit()
        compose.activityRule.scenario.recreate()
        navigate("Folders")
    }

    @After fun restorePreferencesAndRemoveOnlyOwnedState() {
        if (!entered) return
        // Recreate to cancel the old observer's pending debounce before fixture cleanup.
        prefs.edit().putBoolean("auto_ingest_enabled", false).commit()
        compose.activityRule.scenario.recreate()
        io {
            owned.keys.forEach { uri ->
                dao.getVideoByUri(uri.toString())?.let { row ->
                    db.binDao().remove(row.id)
                    db.extractedFrameDao().deleteFramesForVideo(row.id)
                    db.analysisResultDao().deleteAnalysisForVideo(row.id)
                    dao.deleteVideo(row)
                }
            }
            // Every name begins with this invocation's token, including its run ledger.
            db.openHelper.writableDatabase.execSQL("DELETE FROM rule_runs WHERE ruleName LIKE ?", arrayOf("$token%"))
            rules.forEach { db.mediaRuleDao().deleteRule(it) }
            if (!hadObscuredTag) db.customTagDao().byName("Obscured")?.let { tag ->
                check(db.customTagDao().observeAssignments().first().none { it.tagId == tag.id })
                db.customTagDao().delete(tag)
            }
        }
        owned.keys.forEach { context.contentResolver.delete(it, null, null) }
        prefs.edit().apply {
            if (oldFolders == null) remove("target_folders") else putStringSet("target_folders", oldFolders)
            oldFlags.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) }
        }.commit()
        compose.activityRule.scenario.recreate()
    }

    @Test fun automaticChannelsAdmitOnlyEnabledNewCaptureKindsWithoutAnalysis() {
        // T042/T043/T045 repaired behavior: disabled channels do not admit NEW records.
        setAuto(enabled = true, shots = false, record = true)
        val blockedShot = image("Screenshots", "blocked.png")
        val recording = video("Screen recordings", "record.mp4")
        val camera = image("Camera", "camera.png")
        await { row(recording) != null }
        absentForDebounce(blockedShot, camera)
        assertDiscoveryOnly(recording)
        setAuto(enabled = true, shots = true, record = false)
        val shot = image("Screenshots", "enabled.png")
        val blockedRecording = video("Screen recordings", "blocked.mp4")
        await { row(shot) != null }
        absentForDebounce(blockedRecording, camera)
        assertDiscoveryOnly(shot)
        assertEquals("Screenshots", row(shot)?.sourceCategory)
        assertEquals("Screen recordings", row(recording)?.sourceCategory)
        assertBytes()
    }

    @Test fun automaticOffRequiresManualRefreshAndManualIgnoresChannelSwitches() {
        // T044, manual recovery portion of T189; T225 Camera classification only.
        setAuto(enabled = false)
        val shot = image("Screenshots", "manual.png")
        val camera = image("Camera", "camera.png")
        absentForDebounce(shot, camera)
        refreshThroughUi()
        await { row(shot) != null && row(camera) != null }
        listOf(shot, camera).forEach { assertDiscoveryOnly(it) }
        assertEquals("Camera", row(camera)?.sourceCategory)
        assertBytes()
    }

    @Test fun pendingScreenshotIsNotAdmittedUntilPublished() {
        // T221; held MediaStore IS_PENDING across a complete observer debounce.
        setAuto(enabled = true, shots = true, record = false)
        val pending = image("Screenshots", "pending.png", pending = true)
        absentForDebounce(pending)
        publish(pending)
        await { row(pending) != null }
        assertDiscoveryOnly(pending)
        assertFalse(row(pending)!!.isPending)
        assertEquals(1, io { dao.getAllVideosDirect().count { it.uriString == pending.toString() } })
        assertBytes()
    }

    @Test fun flagsPersistAcrossActivityReopenAndObserverFindsLaterCapture() {
        // T041 activity recreation, not process death; T189 active reopen portion.
        setAuto(enabled = true, shots = false, record = true)
        compose.activityRule.scenario.recreate()
        navigate("Folders")
        compose.onNodeWithTag("auto_add").assertIsOn()
        compose.onNodeWithTag("auto_record").assertIsOn()
        compose.onNodeWithTag("auto_shots").assertIsOff()
        val recording = video("Screen recordings", "reopened.mp4")
        val blocked = image("Screenshots", "reopened.png")
        await { row(recording) != null }
        absentForDebounce(blocked)
        assertDiscoveryOnly(recording)
        // This is a visible Home excursion, not force-stop/process death.
        compose.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val background = video("Screen recordings", "background.mp4")
        waitElapsed(4500)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        await { row(background) != null }
        assertDiscoveryOnly(background)
        assertBytes()
    }

    @Test fun copiesPreviewShowsHeuristicLimitsWithoutChangingAnyDecision() {
        // T135/T137/T196. Same name+size DIFFERENT bytes counts; different name SAME bytes does not.
        val grayPng = png(Color.GRAY)
        val redPng = png(Color.RED)
        // PNG readers ignore bytes after IEND; pad the two valid, visibly different
        // solid images to equal physical lengths without changing decoded pixels.
        val length = maxOf(grayPng.size, redPng.size)
        val gray = grayPng.copyOf(length)
        val different = redPng.copyOf(length)
        assertEquals(gray.size, different.size)
        assertFalse(gray.contentEquals(different))
        val oldest = seed(imageBytes("A", "equal.png", gray), "keep", 10)
        val later = seed(imageBytes("B", "equal.png", different), "later", 20)
        seed(imageBytes("C", "renamed.png", gray), "unreviewed", 30)
        val before = io { dao.getAllVideosDirect().associate { it.id to it.reviewDecision } }
        val rule = addRule(obscure = false)
        preview(rule, expected = 1)
        compose.onNodeWithText("Contents may differ; review before deleting", substring = true).assertExists()
        assertEquals(before, io { dao.getAllVideosDirect().associate { it.id to it.reviewDecision } })
        assertNull(io { db.binDao().entry(oldest.id) })
        assertNull(io { db.binDao().entry(later.id) })
        assertTrue(io { db.customTagDao().observeAssignments().first().isEmpty() })
        assertBytes()
    }

    @Test fun copiesRunTwiceAndRecoverRestoresPriorKeepExactlyOnce() {
        // T136/T140/T197/T215, using only owned candidates.
        val bytes = png(Color.MAGENTA)
        val survivor = seed(imageBytes("A", "copy.png", bytes), "later", 10)
        val duplicate = seed(imageBytes("B", "copy.png", bytes), "keep", 20)
        val rule = addRule(obscure = false)
        run(rule, expected = 1)
        run(rule, expected = 1)
        assertNull(io { db.binDao().entry(survivor.id) })
        assertEquals("keep", io { db.binDao().entry(duplicate.id)?.priorReviewDecision })
        assertEquals("ready_to_delete", row(Uri.parse(duplicate.uriString))?.reviewDecision)
        assertEquals(1, io { db.binDao().observeEntries().first().size })
        assertTrue(io { db.customTagDao().observeAssignments().first().isEmpty() })
        openTool("bin")
        recover(duplicate.id, doubleTap = true)
        await { io { db.binDao().entry(duplicate.id) == null } }
        assertEquals("keep", row(Uri.parse(duplicate.uriString))?.reviewDecision)
        compose.onNodeWithTag("bin_${duplicate.id}").assertDoesNotExist()
        compose.onNodeWithText("Bin empty").assertExists()
        assertBytes()
    }

    @Test fun copiesNoMatchLeavesDifferentNamesOutsideBin() {
        // T138/T196: independently known equal bytes with different filenames.
        val bytes = png(Color.CYAN)
        seed(imageBytes("A", "first.png", bytes), "keep", 10)
        seed(imageBytes("B", "second.png", bytes), "later", 20)
        val before = io { dao.getAllVideosDirect().associate { it.id to it.reviewDecision } }
        val rule = addRule(obscure = false)
        preview(rule, 0)
        run(rule, 0)
        assertEquals(before, io { dao.getAllVideosDirect().associate { it.id to it.reviewDecision } })
        openTool("bin")
        compose.onNodeWithText("Bin empty").assertExists()
        assertBytes()
    }

    @Test fun obscureBoundaryPreviewRunAndRecoverRetainsObscuredTag() {
        // T139/T198. Real 100x100 gray/checker images; no fake evaluator results.
        val gray = seed(imageBytes("A", "flat.png", png(Color.GRAY)), "later", 10)
        val checker = seed(imageBytes("B", "checker.png", png(checker = true)), "keep", 20)
        val damaged = seed(imageBytes("C", "damaged.png", "invalid png owned QA".toByteArray()), "keep", 30)
        val clip = seed(video("Screen recordings", "opaque.mp4"), "keep", 40)
        val zero = addRule(obscure = true, threshold = "0")
        preview(zero, 2)
        assertTrue(io { db.binDao().observeEntries().first().isEmpty() })
        val one = addRule(obscure = true, threshold = "1")
        preview(one, 1)
        assertEquals("later", row(Uri.parse(gray.uriString))?.reviewDecision)
        run(one, 1)
        await { io { db.binDao().entry(gray.id) != null } }
        listOf(checker, damaged, clip).forEach { assertNull(io { db.binDao().entry(it.id) }) }
        val tag = io { requireNotNull(db.customTagDao().byName("Obscured")) }
        assertTrue(io { db.customTagDao().observeAssignments().first().contains(MediaTagCrossRef(gray.id, tag.id)) })
        openTool("tags")
        compose.onNodeWithText("Obscured", substring = false).performScrollTo().performClick()
        compose.onNodeWithText(gray.displayName).performScrollTo().assertExists()
        openTool("bin")
        recover(gray.id)
        await { io { db.binDao().entry(gray.id) == null } }
        assertEquals("later", row(Uri.parse(gray.uriString))?.reviewDecision)
        assertTrue(io { db.customTagDao().observeAssignments().first().contains(MediaTagCrossRef(gray.id, tag.id)) })
        openTool("tags")
        compose.onNodeWithText("Obscured", substring = false).performScrollTo().performClick()
        compose.onNodeWithText(gray.displayName).performScrollTo().assertExists()
        assertBytes()
    }

    @Test fun unavailableBinItemDisablesDeleteAndRecoversAfterOwnedAvailabilityRepair() {
        // T214/T226 partial: DB availability + corrupt thumbnail, NOT actual revoked permission.
        val uri = imageBytes("A", "repair.png", "owned broken thumbnail".toByteArray())
        val item = seed(uri, "ready_to_delete", 10)
        io { db.binDao().add(BinEntry(item.id, "later")); dao.updateVideo(item.copy(isCurrentlyAvailable = false)) }
        openTool("bin")
        compose.onNodeWithTag("bin_${item.id}").assertExists()
        compose.onNodeWithTag("delete_bin").assertIsNotEnabled()
        val replacement = png(Color.GREEN)
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(replacement) }
        owned[uri] = replacement
        io { dao.updateVideo(item.copy(isCurrentlyAvailable = true, sizeBytes = replacement.size.toLong())) }
        openTool("bin")
        compose.onNodeWithTag("delete_bin").assertIsEnabled()
        recover(item.id)
        await { io { db.binDao().entry(item.id) == null } }
        assertEquals("later", row(uri)?.reviewDecision)
        assertBytes()
    }

    private fun setAuto(enabled: Boolean, shots: Boolean = true, record: Boolean = true) {
        navigate("Folders")
        toggle("auto_add", true)
        toggle("auto_shots", shots)
        toggle("auto_record", record)
        toggle("auto_add", enabled)
        if (!enabled) { compose.onNodeWithTag("auto_shots").assertDoesNotExist(); compose.onNodeWithTag("auto_record").assertDoesNotExist() }
    }
    private fun toggle(tag: String, value: Boolean) {
        val node = compose.onNodeWithTag(tag)
        val current = node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState] == androidx.compose.ui.state.ToggleableState.On
        if (current != value) node.performClick()
        if (value) node.assertIsOn() else node.assertIsOff()
    }
    private fun refreshThroughUi() {
        navigate("Sources")
        compose.onNodeWithContentDescription("Refresh inventory").performClick()
    }
    private fun tree(path: String) = "content://com.android.externalstorage.documents/tree/" + Uri.encode("primary:${path.trimEnd('/')}")
    private fun image(folder: String, name: String, pending: Boolean = false) = imageBytes(folder, name, png(Color.BLUE), pending)
    private fun imageBytes(folder: String, name: String, bytes: ByteArray, pending: Boolean = false) = create(false, "$imagesRoot$folder/", name, bytes, pending)
    private fun video(folder: String, name: String) = create(true, "$videoRoot$folder/", name, "opaque inventory-only $token".toByteArray(), false)
    private fun create(video: Boolean, path: String, name: String, bytes: ByteArray, pending: Boolean): Uri {
        val uri = requireNotNull(context.contentResolver.insert(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        owned[uri] = bytes
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        if (!pending) publish(uri)
        return uri
    }
    private fun publish(uri: Uri) { context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }
    private fun png(color: Int = Color.GRAY, checker: Boolean = false): ByteArray {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            if (checker) for (y in 0 until 100) for (x in 0 until 100) bitmap.setPixel(x, y, if ((x / 5 + y / 5) % 2 == 0) Color.BLACK else Color.WHITE)
            else bitmap.eraseColor(color)
            return ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray() }
        } finally { bitmap.recycle() }
    }
    private fun seed(uri: Uri, decision: String, age: Long): VideoItem {
        check(uri in owned)
        val video = uri.pathSegments.any { it == "video" }
        val name = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use { it.moveToFirst(); it.getString(0) }
        val item = VideoItem(mediaStoreId = android.content.ContentUris.parseId(uri), uriString = uri.toString(), displayName = name,
            relativePath = if (video) videoRoot else imagesRoot, mimeType = if (video) "video/mp4" else "image/png", sizeBytes = owned.getValue(uri).size.toLong(),
            durationMs = null, dateAdded = age, dateModified = age, reviewDecision = decision, mediaCollection = if (video) "video" else "image")
        return item.copy(id = io { dao.insertVideo(item) })
    }
    private fun row(uri: Uri) = io { dao.getVideoByUri(uri.toString()) }
    private fun assertDiscoveryOnly(uri: Uri) {
        val item = requireNotNull(row(uri))
        assertEquals("new", item.status)
        assertEquals("unreviewed", item.reviewDecision)
        assertNull(item.manualTranscript)
        assertNull(io { db.analysisResultDao().getAnalysisForVideoDirect(item.id) })
        assertTrue(io { db.extractedFrameDao().getFramesForVideo(item.id).first().isEmpty() })
        assertEquals(1, io { dao.getAllVideosDirect().count { it.uriString == uri.toString() } })
    }
    private fun absentForDebounce(vararg uris: Uri) {
        val end = SystemClock.elapsedRealtime() + 4700
        while (SystemClock.elapsedRealtime() < end) {
            uris.forEach { assertNull("Disabled/pending owned channel must remain undiscovered", row(it)) }
            SystemClock.sleep(100)
        }
        uris.forEach { assertNull(row(it)) }
    }
    private fun waitElapsed(ms: Long) { val end = SystemClock.elapsedRealtime() + ms; while (SystemClock.elapsedRealtime() < end) SystemClock.sleep(100) }
    private fun assertBytes() { owned.forEach { (uri, bytes) -> assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }) } }
    private fun addRule(obscure: Boolean, threshold: String = "1"): Long {
        openTool("rules")
        compose.onNodeWithTag(if (obscure) "add_obscured_rule" else "add_copy_rule").performClick()
        val name = "$token-${rules.size}"
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement(name)
        if (obscure) compose.onNode(hasSetTextAction() and hasText("Score")).performTextReplacement(threshold)
        compose.onNodeWithContentDescription("Save").performClick()
        await { io { db.mediaRuleDao().observeRules().first().any { it.name == name } } }
        return io { db.mediaRuleDao().observeRules().first().single { it.name == name }.id }.also { rules += it }
    }
    private fun ruleAction(id: Long, label: String) = compose.onNode(hasContentDescription(label) and hasAnyAncestor(hasTestTag("rule_$id")))
    private fun preview(id: Long, expected: Int) {
        ruleAction(id, "Preview").performScrollTo().performClick()
        await { compose.onAllNodesWithText("$expected • ", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun finishedRuns(id: Long): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM rule_runs WHERE ruleId = ? AND status = 'finished'", arrayOf(id)).use { it.moveToFirst(); it.getInt(0) }
    private fun run(id: Long, expected: Int) {
        // Global rule candidates must all be owned immediately before each actual UI Run.
        check(io { dao.getAllVideosDirect().all { Uri.parse(it.uriString) in owned } })
        val count = finishedRuns(id)
        ruleAction(id, "Run").performScrollTo().performClick()
        await { finishedRuns(id) > count }
        compose.onNodeWithText("$expected in Bin").assertExists()
    }
    private fun recover(id: Long, doubleTap: Boolean = false) {
        val button = compose.onNode(hasContentDescription("Recover") and hasAnyAncestor(hasTestTag("bin_$id"))).performScrollTo()
        if (doubleTap) button.performTouchInput { doubleClick() } else button.performClick()
    }
    private fun openTool(tool: String) {
        navigate("Sources")
        // Source tool may survive navigation. Its explicit Back returns to the source controls.
        if (compose.onAllNodesWithTag("${tool}_screen").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Back").performClick()
        } else if (listOf("rules", "tags", "bin").any { compose.onAllNodesWithTag("${it}_screen").fetchSemanticsNodes().isNotEmpty() }) {
            compose.onNodeWithContentDescription("Back").performClick()
        }
        compose.onNodeWithTag("open_$tool").performClick()
        await { compose.onAllNodesWithTag("${tool}_screen").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun navigate(label: String) {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("nav_${label.lowercase()}").performClick()
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close menu").performClick()
        compose.waitForIdle()
    }
    private fun await(predicate: () -> Boolean) = compose.waitUntil(15000, predicate)
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}
