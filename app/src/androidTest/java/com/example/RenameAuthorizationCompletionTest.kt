package com.example

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Draft T056 + T205 parity portions. Actual MainActivity, Queue Rename callback and real
 * Android MediaProvider write consent; no VM rename shortcut. Shell UID2000 owns the
 * newly created synthetic image so app-owned write bypass cannot satisfy the test.
 * Export uses actual button/callback with intercepted CreateDocument destination. It
 * proves bytes/parity, not DocumentsUI navigation. No credential preferences inspected.
 * Provisioner confirms the dedicated QA package has empty credentials; read access must
 * already be granted. No self-revoke or new permission grant is performed here.
 * T057 revoked-access/regrant recovery and process restart are explicitly outside scope.
 */
class RenameAuthorizationCompletionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private val db get() = VideoDatabase.getDatabase(context)
    private val dao get() = db.videoItemDao()
    private val prefs get() = context.getSharedPreferences("app_prefs", 0)
    private val token = "RenameAuthQA${System.nanoTime()}"
    private val originalName = "${token}_original.png"
    private val physicalPath = "Pictures/$token/Camera/"
    private val expectedName = "alpha_bravo_charlie.png"
    private var shellUri: Uri? = null
    private var mediaId: Long? = null
    private var tagId: Long? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    private var settingsBefore: AppSettings? = null
    private val saved = linkedMapOf<String, Any?>()
    private val exports = mutableListOf<Uri>()
    private var expectedHash: ByteArray? = null
    private var configured = false
    private val providers = setOf("android", "com.google.android.providers.media.module", "com.android.providers.media.module", "com.android.providers.media")

    @Before fun guardConfigureAndCreateOnlyShellOwnedFixture() {
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(context.packageName.endsWith(".qa.preview"))
        check(InstrumentationRegistry.getArguments().getString("rea_runtime") == "emulator")
        check(InstrumentationRegistry.getArguments().getString("qa_empty_credentials_confirmed") == "true")
        check(Build.VERSION.SDK_INT >= 33)
        check(context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) { "Provision QA READ_MEDIA_IMAGES before execution" }
        check(io { dao.getAllVideosDirect().isEmpty() }) { "Empty dedicated QA index required" }
        settingsBefore = io { db.appSettingsDao().getSettingsDirect() }
        saved["llm_provider"] = if (prefs.contains("llm_provider")) prefs.getString("llm_provider", null) else null
        saved["auto_ingest_enabled"] = if (prefs.contains("auto_ingest_enabled")) prefs.getBoolean("auto_ingest_enabled", false) else null
        saved["target_folders"] = prefs.getStringSet("target_folders", null)?.toSet()
        configured = true
        prefs.edit().putString("llm_provider", "local").putBoolean("auto_ingest_enabled", false)
            .putStringSet("target_folders", setOf("content://com.android.externalstorage.documents/tree/" + Uri.encode("primary:Pictures/$token"))).commit()
        io { db.appSettingsDao().insertSettings((settingsBefore ?: AppSettings()).copy(ollamaEnabled = false)) }
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        createShellImage()
        val uri = requireNotNull(shellUri)
        val item = VideoItem(mediaStoreId = android.content.ContentUris.parseId(uri), uriString = uri.toString(), displayName = originalName,
            relativePath = physicalPath, mimeType = "image/png", sizeBytes = shellRead(uri).size.toLong(), durationMs = null, dateAdded = null, dateModified = null,
            reviewDecision = "keep", status = "processed", lastCompletedStep = "result_saved", sourceCategory = "Camera", mediaCollection = "image",
            manualTranscript = "QA retained rename notes")
        mediaId = io { dao.insertVideo(item) }
        val id = requireNotNull(mediaId)
        io {
            // Explicit deterministic setup: OCR drives the existing local suggestion algorithm.
            // Seeded analysis is never presented as provider or actual OCR extraction proof.
            db.extractedFrameDao().insertFrame(ExtractedFrame(videoId = id, timestampMs = 0, ocrText = "ALPHA BRAVO CHARLIE"))
            db.analysisResultDao().insertAnalysis(AnalysisResult(videoId = id, descriptionLine1 = "QA rename title", descriptionLine2 = "QA rename details",
                categoryLevel1 = "Technical", categoryLevel2 = "QA", categoryLevel3 = "Rename", formattedActionPlan = "QA retained rename roadmap",
                urls = emptyList(), codeOrCommands = emptyList(), evidenceJson = "{}", source = "edited"))
            val tag = db.customTagDao().insert(CustomTag(name = token))
            tagId = tag
            db.customTagDao().assign(MediaTagCrossRef(id, tag))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    @After fun cleanOnlyOwnedShellImageRowsTagAndExports() {
        if (!configured) return
        // Never approve an outstanding system question during teardown.
        if (activePackage() in providers) instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        scenario?.close()
        io {
            mediaId?.let { id ->
                db.binDao().remove(id); db.extractedFrameDao().deleteFramesForVideo(id); db.analysisResultDao().deleteAnalysisForVideo(id)
                dao.getVideoByIdDirect(id)?.let { dao.deleteVideo(it) }
            }
            tagId?.let { id -> db.customTagDao().observeTags().first().singleOrNull { it.id == id }?.let { db.customTagDao().delete(it) } }
            if (configured) {
                if (settingsBefore != null) db.appSettingsDao().insertSettings(requireNotNull(settingsBefore))
                else db.openHelper.writableDatabase.execSQL("DELETE FROM app_settings WHERE id = 1")
            }
        }
        exports.forEach { context.contentResolver.delete(it, null, null) }
        shellUri?.let { uri ->
            val current = shellMetadata(uri)
            check(current.contains("relative_path=$physicalPath")) { "Owned shell URI changed scope; refusing cleanup" }
            check(current.contains("_display_name=$originalName,") || current.contains("_display_name=$expectedName,"))
            assertEquals("2000", shell("id -u").trim())
            shell("content delete --uri $uri")
        }
        if (configured) prefs.edit().apply { saved.forEach { (key, value) -> when (value) {
            null -> remove(key)
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>) }
        } } }.commit()
    }

    @Test fun t056_t205_cancelThenAllowActualRenameMaintainsIdentityEveryViewAndExportCopy() {
        val id = requireNotNull(mediaId)
        val uri = requireNotNull(shellUri)
        val before = io { requireNotNull(dao.getVideoByIdDirect(id)) }
        val oldExport = export("before")
        val oldBytes = context.contentResolver.openInputStream(oldExport)!!.use { it.readBytes() }
        assertEquals(originalName, record(oldBytes).getString("name"))
        navigate("Queue")
        renameOwned(originalName)
        observedWriteQuestion(originalName)
        clickSystem(approve = false)
        await { activePackage() == context.packageName }
        assertEquals(before, io { dao.getVideoByIdDirect(id) })
        assertPhysical(originalName)
        renameOwned(originalName)
        observedWriteQuestion(originalName)
        clickSystem(approve = true)
        await { activePackage() == context.packageName && io { dao.getVideoByIdDirect(id)?.displayName == expectedName } }
        assertPhysical(expectedName)
        val after = io { requireNotNull(dao.getVideoByIdDirect(id)) }
        assertEquals(before.copy(displayName = expectedName), after)
        assertEquals(uri.toString(), after.uriString)
        assertEquals(1, io { dao.getAllVideosDirect().count { it.mediaStoreId == before.mediaStoreId } })
        compose.onNodeWithText(expectedName, substring = false).assertIsDisplayed()
        navigate("Sources")
        compose.onNodeWithTag("source_category_Camera").performClick()
        compose.onNodeWithTag("source_media_$id").assertExists()
        compose.onNodeWithText(expectedName, substring = false).assertExists()
        compose.onNode(hasContentDescription("Show all sources") and hasClickAction()).performClick()
        compose.onNodeWithTag("open_tags").performClick()
        compose.onNodeWithText(token, substring = false).performClick()
        compose.onNodeWithText(expectedName, substring = false).performScrollTo().assertIsDisplayed()
        assertTrue(io { db.customTagDao().observeAssignments().first().contains(MediaTagCrossRef(id, requireNotNull(tagId))) })
        val newExport = export("after")
        val freshBytes = context.contentResolver.openInputStream(newExport)!!.use { it.readBytes() }
        assertEquals(expectedName, record(freshBytes).getString("name"))
        assertEquals(uri.toString(), record(freshBytes).getString("uri"))
        assertEquals("QA retained rename notes", record(freshBytes).getString("notes"))
        assertEquals("QA rename title", record(freshBytes).getString("summary"))
        assertArrayEquals("Earlier export is an immutable saved copy", oldBytes, context.contentResolver.openInputStream(oldExport)!!.use { it.readBytes() })
        // Actual manual refresh is scoped to the one owned Pictures root.
        navigate("Sources")
        compose.onNodeWithTag("inventory_scan_button").performClick()
        await { io { dao.getVideoByIdDirect(id)?.lastSeenScanToken != null } }
        assertEquals(1, io { dao.getAllVideosDirect().size })
        val refreshed = io { requireNotNull(dao.getVideoByIdDirect(id)) }
        assertEquals(expectedName, refreshed.displayName)
        assertEquals(before.mediaStoreId, refreshed.mediaStoreId)
        assertEquals(uri.toString(), refreshed.uriString)
        assertEquals(before.manualTranscript, refreshed.manualTranscript)
        assertPhysical(expectedName)
    }

    private fun createShellImage() {
        assertEquals("2000", shell("id -u").trim())
        check(originalName.matches(Regex("[A-Za-z0-9_.-]+")))
        val base = "content://media/external/images/media"
        val where = "_display_name='$originalName' AND relative_path='$physicalPath'"
        check(!shell("content query --uri $base --projection _id --where \"$where\"").contains("_id="))
        val inserted = shell("content insert --uri $base --bind _display_name:s:$originalName --bind relative_path:s:$physicalPath --bind mime_type:s:image/png")
        val rows = shell("content query --uri $base --projection _id:_display_name:owner_package_name:relative_path --where \"$where\"")
        check(rows.lineSequence().count { it.contains("_id=") } == 1) { "Owned rename fixture insert/query failed: insert=$inserted query=$rows" }
        val value = requireNotNull(Regex("_id=(\\d+)").find(rows)?.groupValues?.get(1))
        val uri = Uri.parse("$base/$value")
        shellUri = uri
        check(!rows.contains("owner_package_name=${context.packageName}"))
        val bitmap = Bitmap.createBitmap(800, 240, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(Color.WHITE)
            Canvas(bitmap).drawText("ALPHA BRAVO CHARLIE", 25f, 130f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 44f })
            ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray() }
        } finally { bitmap.recycle() }
        expectedHash = hash(bytes)
        val pipes = automation.executeShellCommandRw("content write --uri $uri")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(bytes) }
        ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() }
        assertPhysical(originalName)
    }
    private fun renameOwned(expectedCurrent: String) {
        val rows = io { dao.getAllVideosDirect() }
        check(rows.size == 1 && rows.single().id == mediaId && rows.single().uriString == shellUri.toString())
        assertPhysical(expectedCurrent)
        compose.onNodeWithText(expectedCurrent, substring = false)
            .performQaQueueScrollTo(compose).assertIsDisplayed()
        compose.onNodeWithTag("queue_${mediaId}_rename")
            .performQaQueueScrollTo(compose).assertIsDisplayed().performClick()
    }
    private fun observedWriteQuestion(expectedCurrent: String) {
        await { activePackage() in providers && systemNodes().any { it.isVisibleToUser && it.viewIdResourceName == "android:id/button1" } }
        // Never accept a dialog based solely on Allow. Its owning package, operation,
        // app label and exact one-photo scope must all be observed. Filename ownership
        // is independently bound by the sole visible Queue action and immutable URI.
        assertPhysical(expectedCurrent)
        val visible = systemNodes().filter { it.isVisibleToUser }
        val text = visible.mapNotNull { it.text?.toString() }.joinToString(" ")
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        check(text.contains(label, ignoreCase = true)) { "Write question does not identify this QA application" }
        check(text.contains("modify", true) || text.contains("edit", true) || text.contains("change", true)) { "Not an observed write/modify request" }
        check(Regex("(?i)\\b1\\s+(?:photos?|images?|items?|files?)\\b").containsMatchIn(text) || text.contains("this photo", true)) { "Exact one-photo count is not visible" }
        check(io { dao.getAllVideosDirect().single().displayName } == expectedCurrent)
    }
    private fun clickSystem(approve: Boolean) {
        check(activePackage() in providers)
        val button = systemNodes().single { it.isVisibleToUser && it.viewIdResourceName == if (approve) "android:id/button1" else "android:id/button2" }
        val label = button.text?.toString()?.trim()?.lowercase()
        check(label in if (approve) setOf("allow") else setOf("cancel", "deny", "don't allow", "don’t allow")) { "Unexpected observed write-consent action: $label" }
        check(button.isEnabled && button.isClickable && button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun export(suffix: String): Uri {
        check(io { dao.getAllVideosDirect().let { it.size == 1 && it.single().id == mediaId } })
        val destination = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$token-$suffix.json"); put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$token/")
        }))
        exports += destination
        val filter = IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("application/json") }
        val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(destination)), true)
        try {
            compose.onNode(hasContentDescription("Save file details") and hasClickAction()).performClick()
            await { runCatching { record(context.contentResolver.openInputStream(destination)!!.use { it.readBytes() }).getString("uri") == shellUri.toString() }.getOrDefault(false) }
            assertEquals(1, monitor.hits)
        } finally { instrumentation.removeMonitor(monitor) }
        return destination
    }
    private fun record(bytes: ByteArray): JSONObject = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("media").let { check(it.length() == 1); it.getJSONObject(0) }
    private fun assertPhysical(name: String) {
        val uri = requireNotNull(shellUri)
        val metadata = shellMetadata(uri)
        check(metadata.contains("_display_name=$name,"))
        check(metadata.contains("relative_path=$physicalPath"))
        check(!metadata.contains("owner_package_name=${context.packageName}"))
        assertArrayEquals(expectedHash, hash(shellRead(uri)))
        assertArrayEquals(expectedHash, hash(context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }))
    }
    private fun shellMetadata(uri: Uri) = shell("content query --uri $uri --projection _display_name:owner_package_name:relative_path")
    private fun shellRead(uri: Uri) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("content read --uri $uri")).use { it.readBytes() }
    private fun shell(command: String): String {
        val pipes = automation.executeShellCommandRwe("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write((command + "\n").toByteArray()) }
        val output = ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() }
        val error = ParcelFileDescriptor.AutoCloseInputStream(pipes[2]).bufferedReader().use { it.readText() }
        return output + error
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun activePackage() = automation.rootInActiveWindow?.packageName?.toString()
    private fun systemNodes(): List<AccessibilityNodeInfo> {
        check(activePackage() in providers) { "Refusing to read another app's UI tree" }
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo) { nodes += node; repeat(node.childCount) { node.getChild(it)?.let(::collect) } }
        automation.rootInActiveWindow?.let(::collect)
        return nodes
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
