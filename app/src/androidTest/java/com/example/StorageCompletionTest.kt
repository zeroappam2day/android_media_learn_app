package com.example

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real Android picker/confirmation tests restricted to synthetic media in the isolated QA package. */
@RunWith(AndroidJUnit4::class)
class StorageCompletionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private val automation get() = instrumentation.uiAutomation
    private val db get() = VideoDatabase.getDatabase(context)
    private val token = "QAStorage" + UUID.randomUUID().toString().replace("-", "").take(12)
    private val parentPath = "Pictures/$token"
    private val parentTree get() = treeUri(parentPath)
    private val images = linkedMapOf<Uri, ByteArray>()
    private val mediaIds = mutableSetOf<Long>()
    private val createdDocuments = linkedSetOf<Uri>()
    private val evidence = JSONArray()
    private var originalFolders: Set<String>? = null
    private var permitted = false
    private val folderValidationEvent = AtomicBoolean(false)

    @Before fun isolatedPackageAndRuntimeOnly() {
        check(context.packageName.endsWith(".qa.preview")) { "Storage tests require the isolated .qa.preview package" }
        PreviewRuntimeGuard.requireApprovedPreviewRuntime()
        check(Build.VERSION.SDK_INT >= 30) { "System trash/delete tests require API30+" }
        check(query { db.videoItemDao().getAllVideosDirect().isEmpty() }) { "Storage suite requires an empty isolated QA index; host cleanup must handle pre-existing index rows" }
        check(!context.getSharedPreferences("app_prefs", 0).getBoolean("auto_ingest_enabled", false)) { "Disable automatic discovery in the isolated QA fixture before storage suite" }
        originalFolders = context.getSharedPreferences("app_prefs", 0).getStringSet("target_folders", emptySet())?.toSet().orEmpty()
        permitted = true
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        automation.setOnAccessibilityEventListener { event ->
            if (event.packageName?.toString() in documentPackages && event.text.any { text ->
                val value = text.toString().lowercase()
                value.contains("already exists") || value.contains("couldn't create") || value.contains("cannot create") || value.contains("invalid name")
            }) folderValidationEvent.set(true)
        }
    }

    @After fun cleanOnlyOwnedFixturesAndWriteSanitizedEvidence() {
        if (!permitted) return
        automation.setOnAccessibilityEventListener(null)
        // Return from system UI before cleanup; never click a positive confirmation during teardown.
        repeat(4) { if (activePackage() != context.packageName) pressBack() }
        query {
            mediaIds.forEach { id -> db.binDao().remove(id); db.videoItemDao().getVideoByIdDirect(id)?.let { db.videoItemDao().deleteVideo(it) } }
        }
        images.keys.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
        createdDocuments.toList().asReversed().forEach { uri ->
            check(DocumentsContract.getDocumentId(uri).startsWith("primary:$parentPath/"))
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }.onFailure { record("cleanup_document_permission", listOf("owned document cleanup requires retained write grant")) }
        }
        val ownedParentDocument = DocumentsContract.buildDocumentUriUsingTree(parentTree, "primary:$parentPath")
        check(DocumentsContract.getDocumentId(ownedParentDocument) == "primary:Pictures/$token")
        runCatching { DocumentsContract.deleteDocument(resolver, ownedParentDocument) }
            .onFailure { record("cleanup_owned_parent_permission", listOf("owned directory cleanup needs retained write grant")) }
        context.getSharedPreferences("app_prefs", 0).edit().putStringSet("target_folders", originalFolders.orEmpty()).commit()
        resolver.persistedUriPermissions.filter { it.uri == parentTree || it.uri.toString().contains(Uri.encode(token)) }.forEach { grant ->
            val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            runCatching { resolver.releasePersistableUriPermission(grant.uri, flags) }
        }
        File(context.getExternalFilesDir(null), "storage-$token.json").writeText(JSONObject().put("fixture", token).put("api", Build.VERSION.SDK_INT).put("events", evidence).toString(2))
    }

    @Test fun t089_cancelActualTrashConfirmationPreservesBytesAndRow() {
        val fixture = image("cancelTrash"); openPreview(fixture.second)
        cleanupAction("Move media to trash"); storageDialog("trash", 1); cancelSystemDialog()
        assertBytes(fixture.first); assertEquals(0, trashFlag(fixture.first)); assertTrue(query { db.videoItemDao().getVideoByIdDirect(fixture.second)?.isCurrentlyAvailable == true })
    }
    @Test fun t090_confirmTrashAffectsOnlyChosenOwnedImage() {
        val chosen = image("trash"); val untouched = image("untouched"); openPreview(chosen.second)
        cleanupAction("Move media to trash"); storageDialog("trash", 1); approveSystemDialog("trash")
        await("owned image trashed") { trashFlag(chosen.first) == 1 }
        assertBytes(chosen.first); assertBytes(untouched.first); assertEquals(0, trashFlag(untouched.first))
        await("trashed row reconciled") { query { db.videoItemDao().getVideoByIdDirect(chosen.second)?.isCurrentlyAvailable == false } }
    }
    @Test fun t091_cancelActualDeleteConfirmationPreservesBytesAndRow() {
        val fixture = image("cancelDelete"); openPreview(fixture.second)
        cleanupAction("Delete media forever"); storageDialog("delete", 1); cancelSystemDialog()
        assertBytes(fixture.first); assertTrue(query { db.videoItemDao().getVideoByIdDirect(fixture.second)?.isCurrentlyAvailable == true })
    }
    @Test fun t092_confirmDeleteAffectsOnlyChosenOwnedImage() {
        val chosen = image("delete"); val untouched = image("untouched"); openPreview(chosen.second)
        cleanupAction("Delete media forever"); storageDialog("delete", 1); approveSystemDialog("delete")
        await("owned image deleted") { !exists(chosen.first) }; assertBytes(untouched.first)
        await("deleted row reconciled") { query { db.videoItemDao().getVideoByIdDirect(chosen.second)?.isCurrentlyAvailable == false } }
    }
    @Test fun t157_cancelDeleteAllTwiceKeepsOwnedFilesAndBin() {
        val first = image("binA"); val second = image("binB"); binFixture(first.second, "keep"); binFixture(second.second, "review_later"); openBin()
        repeat(2) { assertBinContainsOnlyOwnedMedia(); compose.onNodeWithTag("delete_bin").performClick(); storageDialog("delete", 2); cancelSystemDialog() }
        assertBytes(first.first); assertBytes(second.first)
        assertNotNull(query { db.binDao().entry(first.second) }); assertNotNull(query { db.binDao().entry(second.second) })
    }
    @Test fun t158_t159_deleteAllIncludesOnlyAvailableOwnedBinFiles() {
        val first = image("binA"); val second = image("binB"); val untouched = image("outsideBin")
        binFixture(first.second, "keep"); binFixture(second.second, "review_later"); openBin(); assertBinContainsOnlyOwnedMedia()
        compose.onNodeWithTag("delete_bin").performClick(); storageDialog("delete", 2); approveSystemDialog("delete")
        await("two owned Bin files deleted") { !exists(first.first) && !exists(second.first) }; assertBytes(untouched.first)
        await("two Bin entries reconciled") { query { db.binDao().entry(first.second) == null && db.binDao().entry(second.second) == null } }
    }
    @Test fun t182_restoreOwnedAndroidTrashAndFindItAfterProductionRefresh() {
        val fixture = image("restore"); openPreview(fixture.second); cleanupAction("Move media to trash")
        storageDialog("trash", 1); approveSystemDialog("trash"); await("trashed") { trashFlag(fixture.first) == 1 }
        await("trashed row unavailable before repair") { query { db.videoItemDao().getVideoByIdDirect(fixture.second)?.isCurrentlyAvailable == false } }
        assertEquals(1, resolver.update(fixture.first, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, null, null))
        // Fixture-only scope prevents a global refresh from admitting unrelated emulator media.
        context.getSharedPreferences("app_prefs", 0).edit().putStringSet("target_folders", setOf(parentTree.toString())).commit()
        assertBytes(fixture.first); closePreviewIfPresent(); navigate("sources")
        compose.onNodeWithTag("inventory_scan_button").performClick()
        await("restored owned file found by production refresh") { query { db.videoItemDao().getVideoByIdDirect(fixture.second)?.isCurrentlyAvailable == true } }
        assertTrue("Scoped refresh must not add unrelated index records", query { db.videoItemDao().getAllVideosDirect().all { it.id in mediaIds } })
        val category = query { requireNotNull(db.videoItemDao().getVideoByIdDirect(fixture.second)).sourceCategory } ?: "Other"
        compose.onNodeWithTag("source_category_$category").performScrollTo().performClick()
        compose.onNodeWithText(fixtureName(fixture.first)).performScrollTo().assertIsDisplayed()
    }
    @Test fun t214_t215_recoverRestoredPhysicalFileAndRepeatedTapKeepsPriorDecision() {
        val fixture = image("recover"); binFixture(fixture.second, "review_later")
        resolver.update(fixture.first, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
        resolver.update(fixture.first, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, null, null)
        openBin(); val action = hasContentDescription("Recover") and hasAnyAncestor(hasTestTag("bin_${fixture.second}"))
        compose.onNode(action, useUnmergedTree = true).performClick()
        // A second observed click may legitimately disappear with the removed row; never target another item.
        if (compose.onAllNodes(action, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) compose.onNode(action, useUnmergedTree = true).performClick()
        await("prior decision recovered") { query { db.binDao().entry(fixture.second) == null && db.videoItemDao().getVideoByIdDirect(fixture.second)?.reviewDecision == "review_later" } }
        assertBytes(fixture.first); compose.onNodeWithTag("bin_${fixture.second}").assertDoesNotExist()
    }
    @Test fun t216_returnFromOtherAppAndCancelPendingDeleteSafely() {
        val fixture = image("backgroundCancel"); val before = query { db.videoItemDao().getVideoByIdDirect(fixture.second) }
        openPreview(fixture.second); cleanupAction("Delete media forever"); storageDialog("delete", 1)
        injectKey(KeyEvent.KEYCODE_HOME)
        await("actual Home excursion") { activePackage() != context.packageName && activePackage() !in confirmationPackages }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        await("return to QA or retained Android confirmation") { activePackage() == context.packageName || activePackage() in confirmationPackages }
        if (activePackage() in confirmationPackages) {
            storageDialog("delete", 1); cancelSystemDialog()
            await("retained confirmation canceled") { activePackage() == context.packageName }
        } else record("android_dismissed_confirmation_on_home", listOf("owned bytes and saved row checked before retry"))
        assertBytes(fixture.first); assertEquals(before, query { db.videoItemDao().getVideoByIdDirect(fixture.second) })
        // Android may dismiss its own question on Home. Deliberately reopen the same owned
        // request, observe its real dialog again, and prove an explicit Cancel is safe.
        closePreviewIfPresent(); openPreview(fixture.second); cleanupAction("Delete media forever")
        storageDialog("delete", 1); cancelSystemDialog(); await("retried confirmation canceled") { activePackage() == context.packageName }
        assertBytes(fixture.first); assertEquals(before, query { db.videoItemDao().getVideoByIdDirect(fixture.second) })
    }
    @Test fun t185_olderAndroidActionUnavailableBranchNotApplicableOnApi36() {
        Assume.assumeTrue("Not applicable: API${Build.VERSION.SDK_INT} supports Android11+ storage requests; older API needs its own runtime", Build.VERSION.SDK_INT < 30)
        fail("Run this scenario with a separate legacy-API-compatible test class")
    }

    @Test fun t033_cancelWatchFolderPickerDoesNotAddFolder() {
        image("folderParent"); val before = folders(); navigate("folders"); compose.onNodeWithContentDescription("Add watch folder").performClick()
        documentsUi(); pressBack(); await("picker canceled") { activePackage() == context.packageName }; assertEquals(before, folders())
    }
    @Test fun t032_t034_t035_chooseSameWatchFolderTwiceThenRemoveOnlyChoice() {
        val fixture = image("watch"); val before = folders(); chooseParentAsWatchFolder()
        await("owned watch folder saved") { parentTree.toString() in folders() }; assertBytes(fixture.first)
        openFolderPicker(); navigateOwnedParent(); clickObserved("USE THIS FOLDER"); acceptFolderGrantIfShown()
        await("duplicate picker returned") { activePackage() == context.packageName }
        assertEquals(1, folders().count { it == parentTree.toString() }); assertEquals(before + parentTree.toString(), folders())
        val bounds = compose.onNodeWithText(parentPath).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.onNode(hasContentDescription("Remove") and SemanticsMatcher("Remove only the owned watch folder") { it.boundsInRoot.center.y in bounds.top..bounds.bottom }, useUnmergedTree = true).performClick()
        await("owned watch choice removed") { folders() == before }; assertBytes(fixture.first)
    }
    @Test fun t036_reselectOwnedFolderAfterItsReadGrantIsRevoked() {
        val fixture = image("regrant"); chooseParentAsWatchFolder()
        val granted = resolver.persistedUriPermissions.first { it.uri == parentTree }
        resolver.releasePersistableUriPermission(parentTree, (if (granted.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or (if (granted.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))
        assertTrue(resolver.persistedUriPermissions.none { it.uri == parentTree && it.isReadPermission })
        openFolderPicker(); navigateOwnedParent(); clickObserved("USE THIS FOLDER"); acceptFolderGrantIfShown()
        await("owned folder read grant restored") { resolver.persistedUriPermissions.any { it.uri == parentTree && it.isReadPermission } }
        assertEquals(1, folders().count { it == parentTree.toString() }); assertBytes(fixture.first)
    }
    @Test fun t160_exportActualSaveWritesReadableJsonAndPreservesImage() {
        val fixture = image("export"); chooseParentAsWatchFolder(); val filename = "$token-metadata.json"
        exportPicker(filename); navigateOwnedParent(); saveDocument()
        val uri = ownedDocument(filename); createdDocuments += uri
        await("export document written") { runCatching { resolver.openInputStream(uri)?.bufferedReader()?.use { JSONObject(it.readText()).has("media") } == true }.getOrDefault(false) }
        val json = resolver.openInputStream(uri)!!.bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(1, json.getInt("schema")); val rows = json.getJSONArray("media")
        assertTrue((0 until rows.length()).any { rows.getJSONObject(it).getString("uri") == fixture.first.toString() })
        assertBytes(fixture.first)
    }
    @Test fun t161_cancelActualExportCreatesNoFile() {
        val fixture = image("exportCancel"); chooseParentAsWatchFolder(); val filename = "$token-canceled.json"
        exportPicker(filename); navigateOwnedParent(); cancelDocumentPicker()
        assertFalse(documentExists(ownedDocument(filename))); assertBytes(fixture.first)
    }
    @Test fun t162_emptyInventoryExportContainsEmptyMediaArray() {
        assertTrue("T162 requires an empty QA index", query { db.videoItemDao().getAllVideosDirect().isEmpty() })
        image("directoryAnchor", index = false); chooseParentAsWatchFolder(); val filename = "$token-empty.json"
        assertTrue(query { db.videoItemDao().getAllVideosDirect().isEmpty() })
        exportPicker(filename); navigateOwnedParent(); saveDocument(); val uri = ownedDocument(filename); createdDocuments += uri
        await("empty export written") { runCatching { resolver.openInputStream(uri)?.bufferedReader()?.use { JSONObject(it.readText()).getJSONArray("media").length() == 0 } == true }.getOrDefault(false) }
    }
    @Test fun t163_existingFilenameRequiresObservedReplacementDecision() {
        val fixture = image("existing"); chooseParentAsWatchFolder(); val filename = "$token-existing.json"
        exportPicker(filename); navigateOwnedParent(); saveDocument(); val uri = ownedDocument(filename); createdDocuments += uri
        await("first export complete and parseable") { runCatching { readExport(uri).getJSONArray("media").length() == 1 }.getOrDefault(false) }
        val beforeHash = resolver.openInputStream(uri)!!.use { digest(it.readBytes()) }
        val beforeChildren = ownedJsonChildren().keys
        exportPicker(filename); navigateOwnedParent(); clickObserved("SAVE")
        await("actual replace prompt or returned numbered-file save") { systemNodes().any { it.isVisibleToUser && it.text?.toString()?.contains("replace", true) == true } || activePackage() == context.packageName }
        if (activePackage() != context.packageName) {
            recordControls("existing_filename_prompt"); cancelSystemDialog()
            await("replacement canceled") { activePackage() == context.packageName || activePackage() in documentPackages }
            if (activePackage() in documentPackages) pressBack()
            await("replacement cancellation returned") { activePackage() == context.packageName }
            assertEquals(beforeChildren, ownedJsonChildren().keys)
        } else {
            var newDocuments = emptyMap<String, Uri>()
            await("exactly one new owned numbered export") {
                newDocuments = ownedJsonChildren().filterKeys { it !in beforeChildren }
                newDocuments.size == 1
            }
            val (newName, newUri) = newDocuments.entries.single().let { it.key to it.value }
            val numberedName = Regex(Regex.escape(token + "-existing") + " ?\\([1-9][0-9]*\\)\\.json")
            check(numberedName.matches(newName)) { "Observed new output is not the expected token-owned numbered filename" }
            createdDocuments += newUri
            await("numbered export complete and parseable") { runCatching { readExport(newUri).getJSONArray("media").length() == 1 }.getOrDefault(false) }
            val document = readExport(newUri); assertEquals(1, document.getInt("schema"))
            val row = document.getJSONArray("media").getJSONObject(0)
            assertEquals(fixture.first.toString(), row.getString("uri")); assertEquals(fixtureName(fixture.first), row.getString("name"))
            record("observed_numbered_export_verified", listOf(newName))
        }
        assertEquals(beforeHash, resolver.openInputStream(uri)!!.use { digest(it.readBytes()) }); assertBytes(fixture.first)
    }
    @Test fun t233_t234_t235_t236_createCancelValidateAndDuplicateFolderInActualPicker() {
        image("folderAnchor"); chooseParentAsWatchFolder(); val before = folders()
        openFolderPicker(); navigateOwnedParent(); newFolder(); setSystemEdit(""); recordControls("empty_folder_name")
        val positive = systemNodes().firstOrNull { it.isVisibleToUser && it.viewIdResourceName == "android:id/button1" }
        assertNotNull("Observe Android folder-name positive control", positive)
        if (positive!!.isEnabled) {
            folderValidationEvent.set(false); clickNode(positive)
            await("empty folder name visibly rejected") { folderValidationEvent.get() || systemNodes().any { it.isVisibleToUser && it.error != null } }
            assertTrue(systemNodes().any { it.isVisibleToUser && it.isEditable && (it.text.isNullOrEmpty() || it.isShowingHintText) })
        }
        val child = "$token-child"; setSystemEdit(child); acceptFolderName(); createdDocuments += ownedDocument(child)
        await("new folder appears") { findObserved(child) != null }
        assertEquals("Creating a folder must not automatically register it", before, folders())
        navigateOwnedParent()
        newFolder(); setSystemEdit("$token-canceled"); cancelSystemDialog()
        assertNull(findObserved("$token-canceled")); assertEquals(before, folders())
        val marker = requireNotNull(DocumentsContract.createDocument(resolver, ownedDocument(child), "text/plain", "$token-marker.txt"))
        createdDocuments += marker
        val markerBytes = "Owned existing folder must survive $token".toByteArray()
        resolver.openOutputStream(marker)!!.use { it.write(markerBytes) }
        newFolder(); setSystemEdit(child); recordControls("duplicate_folder_name_before_save")
        val button = systemNodes().first { it.isVisibleToUser && it.viewIdResourceName == "android:id/button1" }
        folderValidationEvent.set(false)
        if (button.isEnabled) clickNode(button)
        await("duplicate resolved without replacing existing folder") { findObserved("$child (1)") != null || !button.isEnabled || folderValidationEvent.get() || systemNodes().any { it.isVisibleToUser && (it.error != null || it.text?.toString()?.contains("exist", true) == true) } }
        if (findObserved("$child (1)") != null) {
            createdDocuments += ownedDocument("$child (1)")
            assertTrue(documentExists(ownedDocument("$child (1)")))
            record("duplicate_folder_auto_named", listOf("$child (1)"))
        }
        recordControls("duplicate_folder_name_after_save"); assertEquals(before, folders())
        if (systemNodes().any { it.isVisibleToUser && it.isEditable }) cancelSystemDialog()
        assertArrayEquals(markerBytes, resolver.openInputStream(marker)!!.use { it.readBytes() })
        cancelDocumentPicker()
        assertTrue(documentExists(ownedDocument(child))); assertFalse(documentExists(ownedDocument("$token-canceled")))
    }
    @Test fun t229_topLevelWatchFolderCannotBeSelected() {
        image("rootAnchor"); openFolderPicker(); navigatePrimaryRoot()
        val use = findObserved("USE THIS FOLDER"); recordControls("top_level_folder_restriction")
        assertTrue("Android must disable or omit root folder selection", use == null || !use.isEnabled)
        pressBack(); await("root picker canceled") { activePackage() == context.packageName }
    }
    @Test fun t230_t237_observeActualPickerSortAndViewWithoutChangingMedia() {
        val fixture = image("sortAnchor"); chooseParentAsWatchFolder(); exportPicker("$token-navigation.json"); navigateOwnedParent()
        recordControls("save_picker_controls")
        // Discover visible, named system controls before clicking; an absent affordance is a real coverage gap.
        var sort = systemNodes().firstOrNull { it.isVisibleToUser && (it.contentDescription?.toString()?.contains("sort", true) == true || it.text?.toString()?.contains("Sort by", true) == true) }
        if (sort == null) {
            clickObserved("More options")
            await("sort menu opened") { findObserved("Sort by...") != null }
            recordControls("save_picker_overflow_controls")
            sort = findObserved("Sort by...")
        }
        assertNotNull("No observed DocumentsUI sort control: T237 remains unexecuted", sort); clickNode(sort!!)
        await("observed filename sorting choice") { findObserved("File name (A to Z)") != null || findObserved("Name") != null }
        recordControls("sort_choices")
        val name = findObserved("File name (A to Z)") ?: findObserved("Name"); assertNotNull("Observe Name sorting choice", name); clickNode(name!!)
        await("sort choices dismissed") { findObserved("File name (A to Z)") == null && findObserved("Name") == null }
        val view = systemNodes().firstOrNull { it.isVisibleToUser && it.contentDescription?.toString()?.let { text -> text.contains("grid", true) || text.contains("list", true) } == true }
        assertNotNull("No observed DocumentsUI view control: T237 remains unexecuted", view); clickNode(view!!); recordControls("view_changed")
        cancelDocumentPicker(); assertBytes(fixture.first)
    }

    private fun image(suffix: String, index: Boolean = true): Pair<Uri, Long> {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(29, 110, 185)) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); bitmap.recycle()
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$token-$suffix.png"); put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$parentPath/"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        images[uri] = bytes; resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val id = if (!index) -1L else query { db.videoItemDao().insertVideo(VideoItem(mediaStoreId = android.content.ContentUris.parseId(uri), uriString = uri.toString(), displayName = "$token-$suffix.png",
            relativePath = "$parentPath/", mimeType = "image/png", sizeBytes = bytes.size.toLong(), durationMs = null, dateAdded = null, dateModified = null,
            reviewDecision = "keep", mediaCollection = "image", volumeName = "external_primary", sourceCategory = token)) }.also { mediaIds += it }
        return uri to id
    }
    private fun fixtureName(uri: Uri) = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use { check(it.moveToFirst()); it.getString(0) }
    private fun exists(uri: Uri): Boolean { check(uri in images); return resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }, null)?.use { it.moveToFirst() } == true }
    private fun trashFlag(uri: Uri): Int { check(uri in images); return resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_TRASHED), Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }, null)?.use { if (it.moveToFirst()) it.getInt(0) else -1 } ?: -1 }
    private fun assertBytes(uri: Uri) { assertEquals(digest(requireNotNull(images[uri])), resolver.openInputStream(uri)!!.use { digest(it.readBytes()) }) }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun binFixture(id: Long, prior: String) { check(id in mediaIds); query { db.binDao().add(BinEntry(id, prior, null)); db.videoItemDao().updateReviewDecision(id, "ready_to_delete") } }
    private fun assertBinContainsOnlyOwnedMedia() { assertTrue("Delete all may include only owned fixtures", query { db.binDao().observeEntries().first().all { it.mediaId in mediaIds } }) }
    private fun navigate(destination: String) {
        if (compose.onAllNodesWithContentDescription("Open menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("nav_$destination").performClick()
        if (compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close menu").performClick()
    }
    private fun openPreview(id: Long) {
        check(id in mediaIds); navigate("queue"); val value = query { requireNotNull(db.videoItemDao().getVideoByIdDirect(id)).displayName }
        compose.onNodeWithText(value).performQaQueueScrollTo(compose).assertIsDisplayed()
        if (compose.onAllNodesWithTag("queue_${id}_preview").fetchSemanticsNodes().size == 1) {
            compose.onNodeWithTag("queue_${id}_preview").performQaQueueScrollTo(compose).assertIsDisplayed().performClick()
            compose.onNodeWithText("Media Details Preview: $value").assertIsDisplayed()
            return
        }
        val y = compose.onNodeWithText(value).performScrollTo().fetchSemanticsNode().boundsInRoot.center.y
        val actions = compose.onAllNodes(hasContentDescription("Preview") and hasClickAction()).fetchSemanticsNodes()
        check(actions.isNotEmpty()) { "Missing Preview action for owned media" }
        val index = actions.indices.minBy { kotlin.math.abs(actions[it].boundsInRoot.center.y - y) }
        check(kotlin.math.abs(actions[index].boundsInRoot.center.y - y) < 70f) { "Preview ownership not proven" }
        compose.onAllNodes(hasContentDescription("Preview") and hasClickAction())[index].performClick()
        compose.onNodeWithText("Media Details Preview: $value").assertIsDisplayed()
    }
    private fun closePreviewIfPresent() { if (compose.onAllNodesWithContentDescription("Close").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Close").performClick() }
    private fun cleanupAction(label: String) { compose.onNode(hasContentDescription(label) and hasClickAction()).performScrollTo().assertIsDisplayed().performClick() }
    private fun openBin() { navigate("sources"); compose.onNodeWithTag("open_bin").performClick(); compose.onNodeWithTag("bin_screen").assertIsDisplayed() }
    private fun folders() = context.getSharedPreferences("app_prefs", 0).getStringSet("target_folders", emptySet())?.toSet().orEmpty()
    private fun openFolderPicker() { navigate("folders"); compose.onNodeWithContentDescription("Add watch folder").performClick(); documentsUi() }
    private fun chooseParentAsWatchFolder() {
        openFolderPicker(); navigateOwnedParent(); clickObserved("USE THIS FOLDER"); acceptFolderGrantIfShown()
        await("watch folder saved") { parentTree.toString() in folders() }
        // Write grant is retained only for deleting this test's own DocumentsUI-created fixtures.
        runCatching { resolver.takePersistableUriPermission(parentTree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }
    private fun treeUri(path: String) = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:$path")
    private fun ownedDocument(child: String): Uri {
        check(child.startsWith(token)); return DocumentsContract.buildDocumentUriUsingTree(parentTree, "primary:$parentPath/$child")
    }
    private fun documentExists(uri: Uri) = runCatching { resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true }.getOrDefault(false)
    private val documentPackages = setOf("com.google.android.documentsui", "com.android.documentsui")
    private val confirmationPackages = setOf("android", "com.google.android.providers.media.module", "com.android.providers.media.module", "com.android.providers.media")
    private fun activePackage() = automation.rootInActiveWindow?.packageName?.toString().orEmpty()
    private fun documentsUi() { await("actual DocumentsUI") { activePackage() in documentPackages }; recordControls("documents_ui") }
    private fun primaryRootVisible(): Boolean = systemNodes().filter {
        it.isVisibleToUser && it.viewIdResourceName?.endsWith("/breadcrumb_text") == true
    }.lastOrNull()?.text?.toString() in setOf(Build.MODEL, "Internal storage", "Internal shared storage")
    private fun navigatePrimaryRoot() {
        documentsUi()
        await("loaded observed primary-root controls") {
            (findObserved("Pictures") != null && findObserved("DCIM") != null) ||
                systemNodes().any { it.isVisibleToUser && (it.viewIdResourceName?.endsWith("/breadcrumb_text") == true || it.contentDescription?.toString()?.equals("Show roots", true) == true) }
        }
        if (primaryRootVisible() || (findObserved("Pictures") != null && findObserved("DCIM") != null)) return
        val drawer = systemNodes().firstOrNull { it.isVisibleToUser && it.contentDescription?.toString()?.equals("Show roots", true) == true }
        val breadcrumb = systemNodes().firstOrNull { it.isVisibleToUser && it.viewIdResourceName?.endsWith("/breadcrumb_text") == true }
        if (drawer == null && breadcrumb != null) {
            repeat(8) {
                if (primaryRootVisible() || (findObserved("Pictures") != null && findObserved("DCIM") != null)) return
                check(activePackage() in documentPackages) { "Picker closed before primary root was observed" }
                pressBack()
            }
            error("Primary root not observed after bounded parent navigation")
        }
        check(drawer != null) { "No observed primary-root breadcrumb or Show roots control after load" }; clickNode(drawer)
        await("observed internal-storage root") { systemNodes().any { it.isVisibleToUser && it.text?.toString()?.let { text -> text.equals("Internal storage", true) || text.equals("Internal shared storage", true) } == true } }
        val internal = systemNodes().first { it.isVisibleToUser && it.text?.toString()?.let { text -> text.equals("Internal storage", true) || text.equals("Internal shared storage", true) } == true }
        clickNode(internal); await("Pictures in primary root") { primaryRootVisible() || (findObserved("Pictures") != null && findObserved("DCIM") != null) }
    }
    private fun navigateOwnedParent() {
        documentsUi()
        await("loaded picker breadcrumb") { systemNodes().any { it.isVisibleToUser && it.viewIdResourceName?.endsWith("/breadcrumb_text") == true } || findObserved("Show roots") != null }
        if (ownedParentVisible()) return
        val parentBreadcrumb = systemNodes().firstOrNull { it.isVisibleToUser && it.viewIdResourceName?.endsWith("/breadcrumb_text") == true && it.text?.toString() == token }
        if (parentBreadcrumb != null) {
            pressBack()
            await("owned parent after child navigation") { ownedParentVisible() }
            return
        }
        navigatePrimaryRoot(); findOwnedDirectoryByBoundedScroll("Pictures"); clickObserved("Pictures")
        await("Pictures directory list loaded") {
            systemNodes().any { it.isVisibleToUser && (it.text?.toString() == "Pictures" || it.contentDescription?.toString() == "Pictures") } &&
                (findObserved(token) != null || (observedDirectoryRecycler() != null && directoryViewportSignature().isNotEmpty()))
        }
        findOwnedDirectoryByBoundedScroll(); clickObserved(token)
        await("owned parent title and child") { ownedParentVisible() }
        recordControls("owned_parent_open")
    }
    private fun ownedParentVisible(): Boolean {
        val nodes = systemNodes().filter { it.isVisibleToUser }
        val title = nodes.any { it.text?.toString() == token || it.contentDescription?.toString() == token }
        val ownedNames = images.keys.map { fixtureName(it) }.toSet()
        val child = nodes.any { !it.isEditable && it.text?.toString() in ownedNames }
        // Android's current-directory title has no resource ID on this API36 build.
        // A token title AND a known direct child proves location without trusting an absent ID.
        return title && child
    }

    private fun observedDirectoryRecycler(): AccessibilityNodeInfo? {
        val candidates = systemNodes().filter {
            it.isVisibleToUser && (it.className?.toString()?.endsWith("RecyclerView") == true || it.viewIdResourceName?.endsWith("/dir_list") == true)
        }
        // Breadcrumbs can also be a RecyclerView. The directory grid has its own ID
        // or the largest observed viewport; never scroll the horizontal breadcrumb list.
        return candidates.firstOrNull { it.viewIdResourceName?.endsWith("/dir_list") == true }
            ?: candidates.maxByOrNull { node -> Rect().also { node.getBoundsInScreen(it) }.height() }
    }
    private fun directoryViewportSignature(): List<String> = systemNodes().filter {
        it.isVisibleToUser && (it.viewIdResourceName?.endsWith("/title") == true || it.viewIdResourceName?.endsWith("/icon") == true)
    }.map { node ->
        // Transient hashes detect a changed page. Arbitrary filenames never enter retained evidence.
        val rect = Rect().also { node.getBoundsInScreen(it) }
        "${node.viewIdResourceName}:${node.text?.toString()?.hashCode()}:$rect"
    }
    private fun scrollObservedDirectory(action: Int): Boolean {
        check(activePackage() in documentPackages)
        val list = observedDirectoryRecycler() ?: error("No observed DocumentsUI RecyclerView for bounded scrolling")
        record("observed_directory_scroll_target", listOf("${list.viewIdResourceName}:${list.className}:${Rect().also { list.getBoundsInScreen(it) }}"))
        val before = directoryViewportSignature()
        if (!list.performAction(action)) return false
        val deadline = SystemClock.uptimeMillis() + 2000
        var last = before
        var stableSince = SystemClock.uptimeMillis()
        var changed = false
        while (SystemClock.uptimeMillis() < deadline) {
            val current = directoryViewportSignature()
            if (current != last) { changed = true; last = current; stableSince = SystemClock.uptimeMillis() }
            if (changed && SystemClock.uptimeMillis() - stableSince >= 200) return true
            SystemClock.sleep(100)
        }
        return changed
    }
    private fun findOwnedDirectoryByBoundedScroll(label: String = token) {
        if (findObserved(label) != null) return
        // Reset only the observed directory list to its top; never scroll another window.
        var steps = 0
        while (steps++ < 50 && findObserved(label) == null && scrollObservedDirectory(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) { }
        if (findObserved(label) != null) return
        steps = 0
        while (steps++ < 50) {
            if (!scrollObservedDirectory(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
            if (findObserved(label) != null) return
        }
        recordControls("owned_directory_not_observed_after_bounded_scroll")
        check(findObserved(label) != null) { "Exact directory $label not observed after bounded RecyclerView traversal" }
    }
    private fun ownedJsonChildren(): Map<String, Uri> {
        val parentId = "primary:$parentPath"
        check(parentId == "primary:Pictures/$token")
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parentTree, parentId)
        val result = linkedMapOf<String, Uri>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0); val name = cursor.getString(1)
                if (name.startsWith(token) && name.endsWith(".json")) {
                    check(id.startsWith("primary:$parentPath/")); result[name] = DocumentsContract.buildDocumentUriUsingTree(parentTree, id)
                }
            }
        } ?: error("Cannot enumerate token-owned export directory")
        return result
    }
    private fun readExport(uri: Uri): JSONObject {
        check(DocumentsContract.getDocumentId(uri).startsWith("primary:$parentPath/"))
        return resolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    }
    private fun acceptFolderGrantIfShown() {
        await("folder grant dialog or QA return") { activePackage() == context.packageName || systemNodes().any { it.isVisibleToUser && it.viewIdResourceName == "android:id/button1" } }
        if (activePackage() != context.packageName) {
            recordControls("watch_folder_grant"); val positive = systemNodes().first { it.isVisibleToUser && it.viewIdResourceName == "android:id/button1" }
            check(positive.text?.toString()?.equals("ALLOW", true) == true) { "Unobserved folder grant label; refusing positive action" }; clickNode(positive)
        }
        await("watch picker returned") { activePackage() == context.packageName }
    }
    private fun exportPicker(filename: String) {
        closePreviewIfPresent(); compose.onNodeWithContentDescription("Save file details").performClick(); documentsUi()
        setSystemEdit(filename); recordControls("export_filename_set")
    }
    private fun saveDocument() { clickObserved("SAVE"); await("save returned to QA") { activePackage() == context.packageName } }
    private fun newFolder() {
        val node = systemNodes().firstOrNull { it.isVisibleToUser && it.contentDescription?.toString()?.equals("New folder", true) == true }
        if (node != null) clickNode(node) else {
            val overflow = systemNodes().firstOrNull { it.isVisibleToUser && it.contentDescription?.toString()?.equals("More options", true) == true }
            check(overflow != null) { "No observed New folder or More options control" }; clickNode(overflow); clickObserved("New folder")
        }
        await("new-folder name field") { systemNodes().any { it.isVisibleToUser && it.isEditable } }; recordControls("new_folder_dialog")
    }
    private fun acceptFolderName() {
        val positive = systemNodes().firstOrNull { it.isVisibleToUser && it.viewIdResourceName == "android:id/button1" }
        check(positive != null && positive.isEnabled) { "No enabled observed folder creation button" }
        check(positive.text?.toString()?.let { it.equals("OK", true) || it.equals("CREATE", true) } == true) { "Unknown folder creation positive label" }
        clickNode(positive)
    }
    private fun setSystemEdit(value: String) {
        await("single visible system text field") { systemNodes().count { it.isVisibleToUser && it.isEditable } == 1 }
        val field = systemNodes().single { it.isVisibleToUser && it.isEditable }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
    }
    private fun storageDialog(kind: String, expectedCount: Int) {
        await("observed Android $kind confirmation") { activePackage() in confirmationPackages && systemNodes().any { it.isVisibleToUser && it.viewIdResourceName == "android:id/button1" } }
        recordControls("${kind}_confirmation_$expectedCount")
        val text = systemNodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }.joinToString(" ")
        check(text.contains(kind, true) || (kind == "trash" && text.contains("move", true))) { "Observed dialog does not identify requested storage operation" }
        // Scope is established by the production action selecting an owned ID and, for Bin, a full owned-ID gate.
        check(images.isNotEmpty() && expectedCount <= images.size)
        val countPattern = Regex("(?i)\\b$expectedCount\\s+(?:photos?|images?|items?|files?)\\b")
        check(countPattern.containsMatchIn(text) || (expectedCount == 1 && text.contains("this photo", true))) {
            "System dialog does not expose the exact requested item count; do not accept this scenario without review"
        }
    }
    private fun approveSystemDialog(kind: String) {
        check(activePackage() in confirmationPackages)
        val positive = systemNodes().single { it.isVisibleToUser && it.viewIdResourceName == "android:id/button1" }
        val allowed = if (kind == "delete") setOf("delete", "allow") else setOf("move to trash", "trash", "allow", "move")
        check(positive.text?.toString()?.lowercase() in allowed) { "Unknown destructive confirmation label; refusing action" }
        recordControls("approve_$kind"); clickNode(positive); await("storage confirmation closed") { activePackage() == context.packageName }
    }
    private fun cancelSystemDialog() {
        check(activePackage() in confirmationPackages || activePackage() in documentPackages)
        val negative = systemNodes().firstOrNull { it.isVisibleToUser && it.isEnabled && (it.viewIdResourceName == "android:id/button2" || it.text?.toString()?.equals("Cancel", true) == true) }
        check(negative != null) { "No observed Cancel control; refusing inferred action" }; clickNode(negative)
    }
    private fun findObserved(label: String) = systemNodes().firstOrNull { it.isVisibleToUser && (it.text?.toString()?.equals(label, true) == true || it.contentDescription?.toString()?.equals(label, true) == true) }
    private fun clickObserved(label: String) { await("observed $label") { findObserved(label) != null }; val node = requireNotNull(findObserved(label)); check(node.isEnabled); clickNode(node) }
    private fun systemNodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { result += node; repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
        automation.rootInActiveWindow?.let(::visit); return result
    }
    private fun clickNode(node: AccessibilityNodeInfo) {
        check(activePackage() in documentPackages || activePackage() in confirmationPackages)
        var current: AccessibilityNodeInfo? = node
        while (current != null) { if (current.isEnabled && current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return; current = current.parent }
        val rect = Rect().also { node.getBoundsInScreen(it) }; check(!rect.isEmpty)
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }
    private fun cancelDocumentPicker() {
        repeat(8) {
            if (activePackage() == context.packageName) return
            check(activePackage() in documentPackages) { "Refusing Back outside the observed document picker" }
            pressBack()
        }
        await("document picker canceled after parent navigation") { activePackage() == context.packageName }
    }

    private fun injectKey(code: Int) { check(code in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME)); automation.executeShellCommand("input keyevent $code").close(); SystemClock.sleep(250) }
    private fun pressBack() { automation.executeShellCommand("input keyevent KEYCODE_BACK").close(); SystemClock.sleep(250) }
    private fun recordControls(event: String) {
        val allowed = setOf("save", "cancel", "delete", "allow", "move", "move to trash", "trash", "ok", "create", "use this folder", "new folder", "name", "sort by", "pictures", "internal storage", "more options", "show roots", "navigate up")
        val labels = systemNodes().filter { it.isVisibleToUser }.mapNotNull { node ->
            val text = node.text?.toString() ?: node.contentDescription?.toString() ?: return@mapNotNull null
            if (text.lowercase() in allowed || text.startsWith(token) || text.contains("sort", true) || text.contains("view", true)) "${node.viewIdResourceName.orEmpty()}:$text:${node.isEnabled}" else if (node.isEditable) "${node.viewIdResourceName.orEmpty()}:<editable>:${node.isEnabled}" else null
        }.distinct().take(40)
        record(event, labels)
    }
    private fun record(event: String, labels: List<String>) { evidence.put(JSONObject().put("event", event).put("package", activePackage()).put("labels", JSONArray(labels))) }
    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) { if (predicate()) return; Thread.sleep(100) }
        recordControls("timeout_$description"); error("Timed out: $description; sanitized evidence storage-$token.json")
    }
    private fun <T> query(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}
